# Document Ingestion Pipeline

> Status: **implemented (Task 4.1, 2026-11-10).** 191 of 227 documents (1,010 chunks) were embedded on the first real run; the run
> stopped cleanly at the free-tier **daily** embedding quota and resumes the remaining 36 documents on the next run (§6).

## 1. Flow

```text
corpus/**/*.md ──► CorpusLoader ──► SectionChunker ──► EmbeddingService ──► DocumentStore (PostgreSQL + pgvector)
 (front matter     (LOAD / PARSE      (CHUNK)           (EMBED: Gemini,       (STORE: source_document,
  + Markdown)       failures)                            RETRIEVAL_DOCUMENT)    document_chunk, embedding_cache)
                                   one transaction per document; every failure → ingestion_failure
```

Command (exits when done, with a banner and a report in `target/ingestion-reports/`):
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hrisrag.ingest.run=true --spring.ai.retry.max-attempts=1"
```
`--spring.ai.retry.max-attempts=1` disables Spring AI's built-in retry (10 attempts, x5 backoff), so the service's own retry
and quota handling are in control.

All 227 documents are ingested, including superseded, draft, confidential, distractor and injection documents, each with its
status and access level. Retrieval (M5) filters on that metadata; the benchmark needs these documents present to prove the
filters work.

## 2. Chunking: structure-aware sections (decision D30)

| Option | Verdict |
|---|---|
| Fixed size | Cuts rules and sections apart; vague citations. Kept as a future baseline experiment (M10) |
| Recursive | Better boundaries, but ignores headings |
| **Section-aware (chosen)** | One `##` section = one policy rule = one precise citation; deterministic; no extra API calls |
| Semantic | One embedding call per sentence (~10× quota, and the quota is 1,000 texts/day, §6); little gain when headings already mark topics |
| Parent–child, propositions | More storage or LLM cost; revisit only if the benchmark shows a need |

Rules (`hrisrag.ingest.chunking.*`, in **estimated** tokens = words × 1.3 for EN and × 2.0 for AR; not a real tokenizer):
- Each section is one chunk if it is ≤ `max-tokens` (400).
- Longer sections are split at paragraph, then line, then sentence boundaries into pieces of about `target-tokens` (250). `overlap-tokens` (40)
  of trailing whole sentences are repeated at the start of the next piece.
- A split-off tail under `min-tokens` (40) is merged back. Whole small sections are kept (precise citations over uniform size).
- Stored `content` = `## heading` + text (shown to the LLM and in citations).
- `embedding_text` = `Title > Section` header + text, so a bare "4. Exceptions" is not ambiguous. Full-text search also indexes `embedding_text`.

Real corpus: **227 documents → 1,208 chunks**, estimated tokens min 10 / average 156 / max 394 (`SectionChunkerTest`).

## 3. Incremental re-ingestion

| Situation | Behaviour | Cost |
|---|---|---|
| File unchanged (same SHA-256) and same embedding model | Skipped | 0 embedding calls |
| File changed | Re-chunked; chunks whose `embedding_text` hash is in `embedding_cache` reuse their vector | Only changed chunks are embedded |
| New file | Chunked and embedded | Its chunks only |
| File removed from the corpus | Document and chunks deleted (cascade), counted as `removed` | 0 |
| Chunking settings changed | Everything re-chunked; identical chunk text still comes from the cache | Only text that actually changed |
| **Embedding model changed** | Full re-embed (vectors of different models are not comparable; `embedding_model` is stored per chunk) | All chunks |

**HRIS rows are never embedded.** Employees, leave and attendance are answered by live queries through tools (M8), so growing
HRIS data needs no ingestion at all.

Ordering safeguard: removals run first, then non-ACTIVE versions, then ACTIVE ones, so a version change never violates the
"one ACTIVE version per doc_key" unique index.

## 4. Tables (Flyway `V3__document_ingestion.sql`)

| Table | Purpose |
|---|---|
| `source_document` | One row per document version: metadata, `content_hash`, `source_path`. Unique (`doc_key`, `version`); at most one ACTIVE per key. `scope_department` is a department **code without a foreign key**, so reseeding HRIS data never cascades into the corpus |
| `document_chunk` | `section_path`, `content`, `embedding_text`, `content_hash`, `token_count`, `language`, `embedding vector(768)` (HNSW cosine index), `embedding_model`, generated `search_vector` (`arabic`/`english` config, GIN index) |
| `embedding_cache` | (model, text hash) → vector; identical text is never embedded twice |
| `ingestion_run` | Status RUNNING/SUCCEEDED/PARTIAL/FAILED, config (JSON), all counters |
| `ingestion_failure` | Per-document failures with stage (LOAD/PARSE/CHUNK/EMBED/STORE) and message |

## 5. Embedding gateway (`ai.EmbeddingService`)

- Spring AI `EmbeddingModel` plus an `EmbeddingOptionsFactory` bean (Gemini needs its own options class, like chat).
- Task type `RETRIEVAL_DOCUMENT` for chunks, `RETRIEVAL_QUERY` for questions (asymmetric retrieval).
- Batches of `hrisrag.embedding.batch-size` (25). Spring AI 2.0.1 sends one `embedContent` request per batch (verified in bytecode).
- Checks the dimension (768) against the schema; retries transient errors with exponential backoff.
- Quota exhaustion raises a distinct exception that **includes the provider's quota id**: ingestion stops cleanly and resumes next run.

## 6. First real run and the free-tier quota (evidence)

| Item | Value |
|---|---|
| Result | `STOPPED: EMBEDDING QUOTA EXHAUSTED`, status PARTIAL, 604 s |
| Documents | 191 ingested, 1 failed at EMBED (the quota hit), 35 not processed |
| Chunks / embeddings | 1,010 written, 1,010 computed, 191 provider requests |
| Per-minute limit | 8 transient 429s, `EmbedContentRequestsPerMinutePerUserPerProjectPerModel-FreeTier`, limit **100**; handled by backoff. Observed throughput ≈ 100 chunks/min |
| Daily limit | Confirmed with a direct probe: `EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier`, limit **1,000 per day** |

Conclusions:
1. The free tier counts **texts, not batches**: about 100 chunks/minute and **1,000 chunks/day**, whatever the batch size. A full embed of this corpus
   (1,208 chunks) therefore needs 2 days on the free tier. Incremental ingestion and the embedding cache are essential, not optional.
2. **Query embeddings share the same daily quota.** One benchmark run (100 questions × 3 strategies) needs ~300 query embeddings. Plan for
   M5/M9: cache query embeddings, keyed by model + task type + text hash.
   Note: the current `embedding_cache` key has no task type, because it holds documents only; add one before caching queries.
3. A first run recorded only "daily embedding quota exhausted". Fixed: failures now include the provider's `quotaId` as evidence.

## 7. Retrieval sanity checks (stored Gemini vectors used as queries, no API calls)

| Probe | Top results | Reading |
|---|---|---|
| Egyptian-Arabic lateness FAQ chunk | MSA attendance FAQ (0.913), Arabic attendance tips (0.852), **English attendance FAQ (0.828)** | Dialect → MSA works; cross-lingual neighbour in top 3 |
| Arabic annual-leave "notice" chunk | 6 Arabic chunks (0.81–0.88), incl. sick leave and the Customer Support leave rule | **Strong same-language bias**: EN→AR / AR→EN retrieval will be the hardest category; close topic neighbours stress precision |
| FTS `english`: "carry over unused days" | Annual Leave FAQ part 2, Annual Leave Policy | ✓ |
| FTS `arabic`: "الإجازة المرضية" | Arabic sick leave policy sections | ✓ |

## 8. Tests
- `SectionChunkerTest` (6): sections, context header, long-section split with overlap, tiny-tail merge, Arabic estimate, determinism
  over all 227 real documents.
- `EmbeddingServiceTest` (5): batching, task types, dimension check, transient retry, quota exhaustion with provider evidence.
- `IngestionIntegrationTest` (1 ordered scenario, real schema, deterministic fake embeddings):
  - first run;
  - unchanged re-run with **0 requests**;
  - one edit re-embeds **exactly 1 chunk**;
  - removal;
  - corrupt file recorded as PARSE failure (run PARTIAL);
  - vector and keyword search find the right document.

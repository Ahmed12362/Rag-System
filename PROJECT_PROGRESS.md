# Project Progress

## Completed tasks

### Task 0.1: Stack verification and decision record (Milestone 0), 2026-11-10
- Verified Boot 4.1.2 + Spring AI 2.0.1 + Gemini starters + JPA + Flyway 12.4.0 + Actuator + Testcontainers 2.0.5
  against `pgvector/pgvector:0.8.7-pg18-bookworm` (PG 18.6, pgvector 0.8.7). 3/3 compatibility tests passed.
- Probed PostgreSQL Arabic/English full-text search. Results and limitations are in `docs/architecture.md` §1.4.
- Gemini live checks: model list, 768-dim EN/AR embeddings and a chat call all **PASS** (3/3).
  `gemini-3.5-flash-lite` is available. Details are in `docs/architecture.md` §1.5.
- Added `.env` (git-ignored), `.env.example`, and the `.gitignore` entry.

### Task 0.2: Architecture document (Milestone 0), 2026-11-10
- Wrote `docs/architecture.md` §2–§9: component view, package structure and dependency rules, provider abstraction,
  vector access decision, `RagStrategy` contract, Naive/Hybrid/Agentic request flows, cross-cutting concerns, requirements traceability.
- All 4 Mermaid diagrams render without syntax errors.

### Task 0.3: Database design, API boundaries, risk register (Milestone 0), 2026-11-10
- `docs/database-design.md`: ERD, 24 tables, conventions, validation evidence. The validated draft DDL is saved as `docs/schema-draft.sql`.
- Draft DDL applied to pgvector 0.8.7 / PG 18.6: valid inserts OK, 11/11 constraint-violation tests rejected, access/version filtering verified.
- `docs/api-contracts.md` (draft endpoint groups); `docs/architecture.md` §10 risk register (R1–R15).
- Correction to §1.4: Arabic stemming fails for ال + ه spellings (e.g. `المرضيه`).

### Task 1.1: Repository and infrastructure setup (Milestone 1), 2026-11-10
- `docker-compose.yml`: `hris-rag-db`, DB `hris_rag`, user `hris_user`, port 5434, volume `hris_rag_pgdata`; credentials from `.env`
  (`DB_PASSWORD` required). The old `invoice-ai-db` container and volume are untouched.
- `.env` gained `DB_NAME/DB_USER/DB_PASSWORD/DB_PORT` (random password generated locally, never printed); `.env.example` updated.
- Deleted `application.properties` (old plaintext key + malformed YAML); new `application.yml` imports `.env`, `ddl-auto=validate`,
  Flyway on, Gemini models per D5, Actuator health/info.
- `pom.xml`: added JPA, Flyway (+ PostgreSQL module), Actuator, Gemini chat/embedding starters, Testcontainers; removed the
  pgvector-store starter (D6); Mockito loaded as a `-javaagent` (JDK 24 warning gone).
- Package renamed to `com.mimocodes.hrisrag`. Flyway `V1__enable_extensions.sql` (pgvector only).
- Tests: `HrisRagApplicationTests` (Flyway V1 applied, `vector` 0.8.7 installed, health UP incl. db) on Testcontainers.
- `README.md` with startup/test/reset commands. `git init` (branch `main`); no commits made (the user commits).

### Task 2.1: HRIS schema, entities, repositories, architecture rules (Milestone 2), 2026-11-10
- Flyway `V2__hris_core_schema.sql`: 12 HRIS tables with named constraints and indexes (from `docs/schema-draft.sql`).
- JPA entities and repositories in `data.employee`, `data.attendance`, `data.leave`, `data.meeting`, `data.announcement`
  (`entity` / `repository` subpackages). Enums are stored as strings that match the CHECK constraints. Remaining leave is derived.
- Tool-backing queries: employee by code / direct reports, balance by employee+type+year, request by code, upcoming meetings,
  visible announcements, attendance by date range.
- Fixed during implementation: `currency CHAR(3)` → `VARCHAR(3)` (Hibernate validation); announcement query uses a LEFT JOIN
  (an implicit inner join would have dropped company-wide announcements).
- ArchUnit 1.5.1 rules (D9) now active. Test deps: `spring-boot-starter-data-jpa-test`, `archunit-junit5`.
- Compose DB migrated to V2 (12 tables); app starts with Hibernate `validate`; health UP.

### Task 2.2: Deterministic synthetic HRIS data generator (Milestone 2), 2026-11-10
- `data.seed` package: `SyntheticDataGenerator` (pure spec → dataset, per-table random streams), `WorkCalendar` (Egyptian
  week/holidays, 09:00 start, 15-min grace), `AnchorScenario` (fixed benchmark anchors E-1000..E-1004, LR-1000..1006,
  M-1000..1004, A-1000..1002), `SeedWriter` (JDBC batches, explicit IDs, sequence advance), `SeedValidator` (DB-level rules +
  anchor answers), `DatasetFingerprint` (MD5 per table, UTC), `SeedService` (one transaction: truncate → write → validate),
  `SeedRunner` (`--hrisrag.seed.run=true`, then exit).
- Profiles TEST / STANDARD / LARGE, plus per-count overrides. STANDARD: 12,284 rows in ~0.7 s; LARGE: 1,221,509 rows
  (1.1M attendance) written in 32.6 s; both validated with 0 failures.
- `.env.example` is tracked in git again (Option A; it contains placeholders only).
- `docs/synthetic-dataset.md` created; README seeding section.

### Task 3.1: Policy corpus design + generation pilot (Milestone 3), 2026-11-10
- `docs/corpus-design.md`: generation approach, file format/metadata, inventory (~235 docs), precedence rules, validation rules,
  pilot results and full-corpus estimate. `corpus-spec/` with example `facts.yaml` (11 facts) and `documents.yaml` (5 docs).
- Pilot (Gemini, real calls): EN and AR Annual Leave Policy. All numeric facts present, no unplanned numbers, length in range after a
  prompt fix. `gemini-3.5-flash` hit a transient HTTP 503 → retry/backoff is required. Spec consistency check: 0 errors.

### Task 3.2: Fact registry, document plan, corpus tooling, pilot batch (Milestone 3), 2026-11-10
- `corpus-spec/facts.yaml` (125 facts) and `documents.yaml` (227 documents across 10 groups; 124 EN / 103 AR, 13 Egyptian FAQs).
- `document.corpus` package: CorpusSpec (load/expand/consistency rules), DocumentMetadata (front matter), CorpusDocumentValidator,
  PromptBuilder, CorpusGenerator (retry/backoff, feedback regeneration, resume, pacing), CorpusManifest (SHA-256), CorpusRunner.
- `config/ChatOptionsFactory` + `ModelProviderConfiguration` (Gemini options); fixes the ClassCastException found in the first run.
- Pilot: 21/21 documents generated and validated (32 API calls, ~30k tokens); validate-only: 0 problems.
- Spec checks caught 2 real issues before generation: the FAQ count was 52, not 48 (inventory corrected), and a department-only fact
  was used in a company-wide document (fixed).

### Task 3.3: Full corpus generation and freeze (Milestone 3), 2026-11-10
- Prompt: no version/date in normal documents; Arabic proofreading and number-agreement instruction.
- Generator: model fallback chain on quota exhaustion; clean stop (NOT_ATTEMPTED) when all models are exhausted;
  finish-reason logging and classification (non-STOP blocks a draft); optional per-document `temperature` / `voice` (unused).
- Runner: start/completion banners with counts and report path; reports in `target/corpus-reports/`.
- Corpus: **227/227 valid**, manifest consistent (227 files, 227 distinct ids, checksums OK), all `gemini-3.5-flash-lite`.
  4 first-run failures were regenerated with unchanged settings (all finishReason=STOP); no recitation evidence, so no overrides.
- Corpus runs must use `--spring.ai.retry.max-attempts=1` (Spring AI's default retry is 10 attempts with x5 backoff).

### Task 3.4: Benchmark dataset (Milestone 3), 2026-11-10
- `benchmark/hris-bench-v1.yaml`: **100 hand-written cases** (reduced from 200 at the user's request): FACTUAL 25, MULTI_HOP 15,
  CROSS_LINGUAL 20 (5 EN→AR, 5 AR→EN, 6 EG→MSA, 4 mixed), UNANSWERABLE 10, SECURITY 12, STRUCTURED 18. SHA-256 recorded.
- `benchmark` package: BenchmarkCase, BenchmarkDataset (loader + derived graded relevance), BenchmarkDatasetValidator.
- Validation: dataset rules PASS, structured answers recomputed from the seeded DB 18/18 PASS, leakage check PASS, absent terms
  verified against all 227 corpus files. The validator caught 2 real labelling errors, which were fixed.
- `docs/benchmark-methodology.md` (dataset part), `benchmark/README.md`.
- **Milestone 3 complete.**

### Task 4.1: Document ingestion pipeline (Milestone 4), 2026-11-10
- Flyway V3: source_document, document_chunk (vector(768) HNSW + language tsvector GIN), embedding_cache, ingestion_run,
  ingestion_failure. Department scope stored as a code without FK (HRIS reseed never touches the corpus).
- `ai` package: EmbeddingService (batching, RETRIEVAL_DOCUMENT/QUERY, dimension check, retry, quota exception with provider
  quotaId), ProviderErrors (shared by corpus generation). `config/EmbeddingOptionsFactory` (Gemini options).
- `document.ingestion`: CorpusLoader, SectionChunker (section-aware, configurable), DocumentStore (JDBC), IngestionService
  (incremental: hash skip, embedding cache, removals, per-document transaction, failure recording), IngestionRunner (banners, report).
- `common/RunReports` (shared banner + report files).
- Real run: **191/227 documents, 1,010 chunks embedded**; stopped cleanly at the free-tier daily embedding quota (1,000 texts/day,
  confirmed by probe). 36 documents (~198 chunks) resume on the next run after the daily reset.

### Task 5.1: Naive RAG baseline (Milestone 5), 2026-11-10
- Flyway V4: `rag_request_trace`; `embedding_cache` gains `purpose` (DOCUMENT/QUERY) in its key.
- `rag.core` contract (RagStrategy, RagQuery, UserContext, RagSettings, RagResult, …), `RagProperties` (incl. temporary
  server-side dev user).
- `ai`: GenerationService (options factory, timeout, transient retry, tokens incl. thinking, finish reason), EmbeddingCache (shared).
- `rag`: QueryEmbedder (cached), VectorRetriever (filters in SQL, `hnsw.iterative_scan`), ContextBuilder, GroundedPrompt
  `grounded-v1`, CitationValidator, NaiveRagStrategy, RagService, TraceStore, ChatController (`POST /api/v1/chat`,
  `GET /api/v1/strategies`, ProblemDetail errors).
- Tests with fake models: all pass.
- New Google AI Studio project key (fresh free-tier quota; same embedding model, so no re-embedding): ingestion completed **227/227
  documents, 1,208 chunks**. The new project returned transient 403 SERVICE_DISABLED while activating; resolved on retry.
- **Real verification: 7 questions** (EN, AR, Egyptian, AR→EN-only fact, unanswerable, confidential request, injected document). All
  correct after 2 fixes found by the real run: (1) provider error details leaked in the API response, now a safe message;
  (2) a multi-part Gemini response was read as "INSU", now all result parts are joined (also in CorpusGenerator). Results:
  `docs/rag-strategies.md` §5.
- Manual testing kit: `scripts/ask.ps1` + README §7 (startup order, 10 questions with expected answers).

### Task 6.1: Guardrails and simulated login (Milestone 6), 2026-11-10
- Flyway V5 `app_user`: 6 simulated users (employee E-1001, qa E-1002, manager E-1000, hr E-1003 HR_ADMIN/CONFIDENTIAL,
  finance E-1004, contractor CT-0001 PUBLIC). Password hash NULL in the migration; `DemoUserProvisioner` writes BCrypt hashes
  from `APP_DEMO_PASSWORD` (`.env` only) at startup.
- Spring Security (`security` package): HTTP Basic, stateless, `/api/**` authenticated, everything else denied except health/info;
  RFC 9457 401/403 bodies; health details only for HR_ADMIN; `GET /api/v1/me`. Identity only from the principal; the
  configured dev user was removed. `UserContext` now carries `maxAccessLevel` (visible levels derived from it).
- Guardrails (`rag.guardrail`): `InjectionDetector` (narrow EN/AR/Egyptian rules on normalized text; question = DIRECT rules,
  passages = DIRECT minus SECRET_REQUEST plus AI-addressed rules, sentence-level redaction), `AccessPolicy` (re-check of every
  chunk before context construction), `OutputGuard` (system-prompt fragments, credential patterns, configured secret values),
  `GuardrailProperties` (input BLOCK|FLAG|OFF, context REDACT|FLAG|OFF, output on/off).
- Contract: `RetrievedChunk` gains status and scope department; `RagResult` gains `passageChecks` (per retrieved chunk:
  CLEAN/FLAGGED/REDACTED/REMOVED/DENIED + rule ids + sentences removed) and `callCounts` (embedding calls, embedding cache
  hits, chat calls, tokens). Both are stored in the trace with the user's role/department/access level.
- `retrieved` stays the raw retrieval, so retrieval metrics and the planted SEC-09..11 documents are unaffected; corpus files are unchanged.
- Tests: 165/165 (61 new). Real Gemini check: 10 questions, all as expected, 4 new embeddings (details: `docs/rag-strategies.md` §6).

### Task 6.2: Minimal benchmark runner, metrics, experiment persistence (Milestone 6), 2026-11-10
- Flyway V6: `benchmark_dataset` (checksum guard), `experiment`, `experiment_run`, `question_result` (UK run+case, raw
  results), `metric_aggregate` (slices ALL / SCOPE / LANGUAGE / CATEGORY with sample sizes). Cases stay in the versioned YAML.
- `evaluation`: `RetrievalMetrics` (document-level Recall/Precision/MRR/nDCG/Hit), `CaseScorer` (behaviour, must-contain with
  alternatives and Arabic-Indic digits, leaks, language, citations, case/security pass, status mix, cost), `MetricAggregator`.
- `experiment`: `ExperimentService` (same `RagService` as the chat; asker from `app_user`; pause after chat calls; quota →
  PARTIAL + resume), `BenchmarkRunner` (CLI, banners, `reports/benchmark/latest-benchmark.txt`), `ExperimentStore`, read-only
  `GET /api/v1/experiments[/{id}]` (HR_ADMIN). Default `limit=10` as a quota guard.
- Naive strategy reports quota errors as guardrail rule `PROVIDER_QUOTA` (safe text) so the benchmark can stop and resume.
- Fix found by the real run: the security filter chain is now web-only, because command-line runs without a web server failed to start.
- Real smoke run (experiment 1, 10 cases, 2 new embeddings, 9 chat calls): case_pass 0.80 overall, 0.89 RAG_ONLY, security 3/3.
  Findings: English questions sometimes answered in Arabic (language_match 0.71); multi-hop misses the second fact; tool case
  fails as expected (`docs/benchmark-methodology.md` §8).

### Task 7.1: Hybrid RAG (Milestone 7), 2026-11-10
- Refactor: `GroundedRagStrategy` holds the shared pipeline (guardrails, prompt, citations, trace). Naive and Hybrid implement
  only `retrieve`. All Naive and guardrail tests still pass unchanged.
- `AccessSql`: one access-filter SQL fragment for every retriever. `KeywordRetriever`: PostgreSQL full-text search on the existing
  `search_vector` (no re-embedding); Latin and Arabic parts are queried with the `english` and `arabic` configurations; OR semantics;
  `ts_rank_cd`. `RankFusion.rrf` (k=60, 20 candidates per side), deterministic ties.
- `RetrievedChunk` gains `vectorRank` / `keywordRank` (API, trace, benchmark detail, `ask.ps1 -Strategy HYBRID`).
- Benchmark: `--hrisrag.benchmark.strategy=HYBRID`, `--hrisrag.benchmark.compare-with=<id>`. The comparison is recomputed on the
  common cases only.
- Tests 183/183 (+9). Real comparison on the same 10 cases (experiment 2 vs 1, 0 new embeddings, 9 chat calls): case_pass 0.80
  for both; MHOP-EN-01 fixed, XLING-AR2EN-02 regressed (RRF credits weak double-matches over a top single match when keyword
  search is blind cross-lingually); language_match 0.71 → 1.00; MRR 0.92 → 0.68 (`docs/rag-strategies.md` §7).

### Task 8.1: Agentic RAG with authorized HRIS tools (Milestone 8), 2026-11-10
- `data.query.HrisQueryService`: read-only records (balance, request, upcoming meetings, attendance summary, employee
  reference). No compensation or disciplinary access.
- `tools`: `HrisTools` (4 allowlisted tools, strict argument validation, company-time output), `ToolAuthorizer`
  (SELF / DIRECT_REPORT / HR_ADMIN, otherwise NOT_OWN_DATA; unknown targets look like foreign ones), `ToolsProperties`
  (reference date 2026-11-01).
- `AgenticRagStrategy`: manual bounded loop (4 rounds, 6 tool calls, 60 s); `search_policies` via the extracted
  `HybridRetriever` plus the shared screening; a denial with no data gives a fixed refusal (BLOCKED); prompt `agentic-v1`
  (its rules are also in the output leak check). `GenerationService.converse` uses declaration-only tool callbacks.
- Spike (risk R11): Spring AI 2.0.1's Gemini model does not execute tools, and multi-turn calls with thought signatures work.
- `RagResult.toolCalls` (API, trace without payloads, `ask.ps1`). `ask.ps1` now defaults to port 8081.
- Tests 195/195 (+12 agentic). Real check, 5 questions (12 chat calls, 0 embeddings): all as expected. Two defects found and
  fixed: meeting times in UTC, and false "uncited" flags on tool-only answers.

## Decisions
- D1: Keep Spring Boot 4.1.2 + Spring AI 2.0.1 (no blocker found).
- D2: Flyway owns the schema (`ddl-auto=validate`, no Spring AI auto schema).
- D3: Gemini is the first provider behind Spring AI abstractions; key only from `.env` / the environment.
- D4 (applied): Compose renamed to `hris-rag-db` / `hris_rag` / `hris_user` / port 5434 / separate volume.
- D5 (approved): chat `gemini-3.5-flash-lite`; embeddings `gemini-embedding-001` @ 768. `gemini-embedding-2` is a later comparison candidate.
- D6 (applied in pom): Own Flyway-managed `document_chunk` table + `JdbcTemplate`; no Spring AI `PgVectorStore`.
- D7: Agentic RAG uses a manual, bounded tool loop (internal tool execution disabled) so every call is authorized, limited and traced.
- D8: No custom LLM interface over Spring AI. Thin `EmbeddingService` / `GenerationService` own batching, task type, usage, timeouts.
- D9 (applied in Task 2.1): Module dependency rules enforced with ArchUnit 1.5.1. Rules for not-yet-existing modules use `allowEmptyShould(true)`.
- D10: Enumerations as VARCHAR + CHECK; business codes as separate unique keys; derived values not stored.
- D11: One row per document version; a partial unique index guarantees a single ACTIVE version per `doc_key`.
- D12: Access/status filtering inside the retrieval SQL (join), not in application code.
- D13: Trace/result detail in JSONB; filterable fields as columns; UK (`run_id`, `case_id`) makes benchmark runs resumable.
- D14: Restricted synthetic tables `employee_compensation` and `disciplinary_record` exist as security-test targets.
- D15: DB credentials and the API key live only in `.env`, shared by Docker Compose and Spring (`spring.config.import`).
- D16: Flyway V1 contains extensions only; tables are added by the milestone that needs them.
- D17: HRIS entities: `@Getter` + protected no-arg constructor + required-field constructor; no `@Data`; lazy `@ManyToOne`;
  `@Enumerated(STRING)`; `Instant` for TIMESTAMPTZ; composite key for invitations as an `@Embeddable` record.
- D19: Seeding uses JDBC batch inserts with explicit deterministic IDs (not JPA), in one transaction with validation (rollback on failure).
- D20: Dataset "today" is a fixed reference date (2026-11-01). Benchmark anchors are fixed records independent of seed/profile, and
  benchmark runs must freeze the application clock to that date (tools take an injectable `Clock`, M8).
- D21: Synthetic business rules (21/30-day annual leave, 15-min grace, Sun–Thu week, manager approval) are the contract the
  M3 policy documents must match.
- D22: Corpus = fact-controlled LLM generation (facts.yaml + documents.yaml), validated, then frozen in git with a checksum
  manifest. Reproducibility comes from committed files, not regeneration. `generation` metadata is never ingested.
- D23: Corpus precedence: ACTIVE only → newest effective date → department-scoped ACTIVE overrides company policy for that
  department → access level ≤ user. All intentional conflicts are declared in documents.yaml.
- D24: Corpus tooling in Java inside the app (`--hrisrag.corpus.generate|validate-only`), consistent with seeding.
- D25: Provider-specific chat options are created by a `ChatOptionsFactory` bean (Spring AI's Gemini model rejects portable options).
- D26: Corpus generation model = free flash-lite fallback chain (quota). The app's answering model stays `gemini-3.5-flash-lite` (D5)
  and is used again for RAG testing from Milestone 5.
- D27: A draft is accepted only if validation passes AND the provider did not report an explicit non-STOP finish reason.
- D28: Benchmark relevance is derived from facts (ACTIVE, non-distractor, visible, in scope, target language); grade 2 for
  CORE/DEPARTMENT, 1 for supporting pages. Cross-lingual cases restrict relevance to the other language.
- D29: Benchmark size 100 (demo scale, user decision); datasets are immutable per version (checksum).
- D30: Chunking = structure-aware sections with size guard (target 250 / max 400 / min 40 / overlap 40 estimated tokens) and a
  "Title > Section" context header in the embedding text; fixed-size kept as a future baseline experiment.
- D31: Embedding gateway lives in a neutral `ai` package (used by document ingestion and rag retrieval; avoids a module cycle).
- D32: Free-tier embedding quota is per TEXT (100/min, 1,000/day): incremental ingestion and caching are mandatory; query
  embeddings must be cached (model + task type + text hash) before benchmark runs.
- D33: `spring.ai.retry.max-attempts=1` globally; retry/quota handling is owned by our gateways (Spring AI's default can hang
  requests for minutes on quota errors).
- D34: Naive abstention = no evidence (no LLM call) or the model's INSUFFICIENT_CONTEXT marker; no score threshold until calibrated
  on the benchmark. Traces store the prompt version and ids/scores, not retrieved text or the system prompt.
- D35: Simulated users with HTTP Basic + BCrypt (stateless; no sessions, so CSRF off). One shared demo password from `.env`;
  hashes written at startup, never in migrations. `app_user.employee_code` is not an FK (accounts survive a reseed).
- D36: Access control order: SQL filters (primary) → `AccessPolicy` re-check before context construction (second) → never
  answer-side. Injection detection is defense in depth with narrow patterns; retrieved text is always wrapped as untrusted data.
- D37: Indirect injection is redacted per SENTENCE, not per document, so legitimate facts in a planted document survive
  (e.g. the lost-badge rule in the parking notice). Raw retrieval is kept for metrics; screening outcomes are recorded per
  passage. FLAG modes let the benchmark measure the model's own resistance separately from the screening layer.
- D38: Benchmark metrics are deterministic for now (no LLM judge, to save quota). Retrieval is scored on distinct
  documents. STRUCTURED cases are reported in a separate `TOOLS_REQUIRED` scope so tool-less strategies are not misread.
- D39: Benchmark cases stay in the versioned YAML, guarded by a registered checksum, instead of a `benchmark_case` table.
  Raw per-question results are stored, and aggregates are always recomputed from them (resume-safe).
- D40: Strategies share one grounded pipeline (`GroundedRagStrategy`); a strategy only defines retrieval (Naive, Hybrid) or,
  later, its own loop (Agentic). Benchmark differences are then attributable to retrieval.
- D41: Hybrid = vector + PostgreSQL full-text search fused by unweighted RRF (k=60, 20 + 20 candidates) as the textbook
  baseline. Weighting and depth are tuned only on the full benchmark, never on the 10-case smoke set.
- D42: Tool authorization is decided in code by `ToolAuthorizer` from the login identity: SELF, a manager's
  DIRECT_REPORT, or HR_ADMIN. The model's arguments are only the requested target; a leave request is authorized against its owner.
- D43: The Agentic answer after a denied call with no data returned is a fixed refusal (BLOCKED), not model text. The
  outcome must not depend on how the model words it.
- D44: Tool results use company local time and the dataset reference date; payloads are never persisted, only validated
  arguments, decisions and sizes.
- D18: The HRIS `data` module is internally one module. Sub-capabilities may reference each other's entities (e.g. `LeaveRequest → Employee`);
  other modules must go through services (ArchUnit rule 1).

## Open items
- The old Gemini key (from the deleted `application.properties`) must be revoked in Google AI Studio, if not done already.
- Token metrics must record thinking tokens (`thoughtsTokenCount`) separately.
- Maven prints harmless 401 warnings for two Flyway community repositories declared in Flyway's POM.
- HTTP Basic sends the password with every request and runs a BCrypt check (strength 10) on each one, which adds some latency (not measured separately). Fine for a local demo, but not for production. Use HTTPS
  and a token/OIDC flow there.
- Injection patterns are a narrow, English/Arabic list: paraphrased or other-language attacks can pass the input check (then
  only the grounded prompt and access control apply). Measured in the benchmark (M6.2/M9).
- The output check catches verbatim prompt fragments and secret values, not translated or paraphrased leaks.
- Naive sometimes answers English questions in Arabic (found by the benchmark, Task 6.2). Candidate: prompt `grounded-v2`
  that names the detected question language. Needs approval and a benchmark re-run.
- Hybrid's unweighted RRF hurts cross-lingual questions (keyword search cannot match across languages). Candidates: weighted
  RRF, language-aware fusion, shallower candidate lists. Measure them on the full benchmark.
- Only a 10-case real benchmark has been run (quota). The full 100-case run (`--hrisrag.benchmark.limit=0`) costs about
  85 embeddings and about 95 chat calls.

## Test status
- `mvnw clean verify` (Task 8.1 final): **195 tests, 195 passed, 0 failed** (adds AgenticIntegrationTest 12).
- Previously `mvnw clean verify` (Task 7.1 final): **183 tests, 183 passed, 0 failed** (adds HybridUnitTest 2, HybridIntegrationTest 6,
  BenchmarkIntegrationTest +1 comparison).
- Previously `mvnw clean verify` (Task 6.2 final): **174 tests, 174 passed, 0 failed** (adds EvaluationUnitTest 4,
  BenchmarkIntegrationTest 5).
- Previously `mvnw clean verify` (Task 6.1 final): **165 tests, 165 passed, 0 failed** (adds GuardrailUnitTest 43, GuardrailFlowIntegrationTest 12,
  SecurityIntegrationTest 6). False-positive checks on real data: of the 100 benchmark questions only SEC-07/SEC-08 are flagged;
  of the 227 corpus documents only the 5 planted sentences are redacted.
- Previously `mvnw test` (Task 5.1 final): **104 tests, 104 passed, 0 failed** (adds RagUnitTest 4, RagFlowIntegrationTest 5,
  GenerationServiceTest 1). Secret scan of 373 files: none.
- Previously (Task 4.1): 94 tests, 94 passed, 0 failed (adds SectionChunkerTest 6, EmbeddingServiceTest 5,
  IngestionIntegrationTest 1). Secret scan of 353 files: none.
- Previously (Task 3.4): 82 tests, 82 passed, 0 failed (adds BenchmarkDatasetTest 3, BenchmarkStructuredAnswersTest 1).
- Previously (Task 3.3): 78 tests, 78 passed, 0 failed (CorpusGeneratorTest now 15: quota fallback, clean stop,
  finish-reason classification and blocking, per-document overrides, no rewrite/no duplicates on re-run; PromptBuilderTest 4).
- Corpus: `validate-only --require-complete` → 227/227, 0 problems. Secret scan of 328 repo files: none found.
- Manual (compose DB): STANDARD and LARGE seeded and validated (0 failures); STANDARD fingerprint identical across two runs;
  representative SQL (remaining leave, request status, upcoming meetings, lateness by department) returns the expected values.
  **The compose DB currently holds the STANDARD dataset.**

## Next proposed task
- Options, each needing approval:
  - (a) Task 7.2: fusion tuning (weighted RRF / candidate depth) measured on a larger benchmark subset;
  - (b) Task 8.2: benchmark tool metrics (tool selection, arguments, authorization, forbidden tools) and a
    Naive / Hybrid / Agentic comparison on the same 10 cases;
  - (c) a `grounded-v2` prompt fix for language following.
- Regenerate the AI Studio key after submission (it was shared in chat).

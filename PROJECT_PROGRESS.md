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
- D18: The HRIS `data` module is internally one module. Sub-capabilities may reference each other's entities (e.g. `LeaveRequest → Employee`);
  other modules must go through services (ArchUnit rule 1).

## Open items
- The old Gemini key (from the deleted `application.properties`) must be revoked in Google AI Studio, if not done already.
- Token metrics must record thinking tokens (`thoughtsTokenCount`) separately.
- Maven prints harmless 401 warnings for two Flyway community repositories declared in Flyway's POM.
- Actuator health shows full details (local dev only). Restrict in Milestone 6.

## Test status
- `mvnw clean verify` (Task 3.2): **69 tests, 69 passed, 0 failed** (adds CorpusSpecTest 5, CorpusDocumentValidatorTest 9,
  CorpusGeneratorTest 7, PromptBuilderTest 3, ModelProviderConfigurationTest 1).
- Manual (compose DB): STANDARD and LARGE seeded and validated (0 failures); STANDARD fingerprint identical across two runs;
  representative SQL (remaining leave, request status, upcoming meetings, lateness by department) returns the expected values.
  **The compose DB currently holds the STANDARD dataset.**

## Next proposed task
- Milestone 3, Task 3.3: Prompt tweak (no version/date in normal documents), generate the remaining 206 documents, review a sample
  per group, `validate-only --require-complete`, freeze `corpus/` + manifest. Pending user go/no-go on the pilot quality.

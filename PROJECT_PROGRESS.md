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

## Decisions
- D1: Keep Spring Boot 4.1.2 + Spring AI 2.0.1 (no blocker found).
- D2: Flyway owns the schema (`ddl-auto=validate`, no Spring AI auto schema).
- D3: Gemini is the first provider behind Spring AI abstractions; key only from `.env` / the environment.
- D4 (applied): Compose renamed to `hris-rag-db` / `hris_rag` / `hris_user` / port 5434 / separate volume.
- D5 (approved): chat `gemini-3.5-flash-lite`; embeddings `gemini-embedding-001` @ 768. `gemini-embedding-2` is a later comparison candidate.
- D6 (applied in pom): Own Flyway-managed `document_chunk` table + `JdbcTemplate`; no Spring AI `PgVectorStore`.
- D7: Agentic RAG uses a manual, bounded tool loop (internal tool execution disabled) so every call is authorized, limited and traced.
- D8: No custom LLM interface over Spring AI. Thin `EmbeddingService` / `GenerationService` own batching, task type, usage, timeouts.
- D9: Module dependency rules enforced with ArchUnit, **deferred to Milestone 2** (no module classes exist yet).
- D10: Enumerations as VARCHAR + CHECK; business codes as separate unique keys; derived values not stored.
- D11: One row per document version; a partial unique index guarantees a single ACTIVE version per `doc_key`.
- D12: Access/status filtering inside the retrieval SQL (join), not in application code.
- D13: Trace/result detail in JSONB; filterable fields as columns; UK (`run_id`, `case_id`) makes benchmark runs resumable.
- D14: Restricted synthetic tables `employee_compensation` and `disciplinary_record` exist as security-test targets.
- D15: DB credentials and the API key live only in `.env`, shared by Docker Compose and Spring (`spring.config.import`).
- D16: Flyway V1 contains extensions only; tables are added by the milestone that needs them.

## Open items
- The old Gemini key (from the deleted `application.properties`) must be revoked in Google AI Studio, if not done already.
- Token metrics must record thinking tokens (`thoughtsTokenCount`) separately.
- Maven prints harmless 401 warnings for two Flyway community repositories declared in Flyway's POM.
- Actuator health shows full details (local dev only). Restrict in Milestone 6.

## Test status
- `mvnw clean verify`: 3 tests, 3 passed, 0 failed (Testcontainers pgvector). BUILD SUCCESS.
- Manual: compose DB healthy; app started and health returned `UP` (db `UP`); Flyway V1 recorded in `hris_rag`; restart idempotent.
- Secret scan of all committable files + `target/`: none found.

## Next proposed task
- Milestone 2, Task 2.1: HRIS schema migrations (`department`, `employee`, `employee_compensation`, `leave_type`, `leave_balance`,
  `leave_request`, attendance, meetings, rewards, disciplinary, announcements) from `docs/schema-draft.sql`, JPA entities + repositories,
  Testcontainers repository/constraint tests, and the first ArchUnit rules.

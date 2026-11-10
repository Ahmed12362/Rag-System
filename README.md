# HRIS Multilingual RAG Benchmark Platform

A standalone project that builds, evaluates and compares RAG strategies (Naive, Hybrid, Agentic) over a synthetic,
bilingual (English / Arabic) HRIS domain. All data is synthetic; nothing connects to a real HR system.

> Status: **Milestone 2: HRIS data.** PostgreSQL + pgvector, Flyway schema for the HRIS domain, and a deterministic synthetic
> data generator. RAG features are not implemented yet. See [`PROJECT_PROGRESS.md`](PROJECT_PROGRESS.md).

## Tech stack (verified, see `docs/architecture.md` §1)

| Area | Version |
|---|---|
| Java | 21 (compiled with `release 21`; runs on JDK 21+) |
| Spring Boot | 4.1.2 |
| Spring AI | 2.0.1 (Google Gemini chat + embeddings) |
| PostgreSQL / pgvector | 18 / 0.8.7 (`pgvector/pgvector:0.8.7-pg18-bookworm`) |
| Flyway | 12.4.0 |
| Testcontainers | 2.0.5 |

## Prerequisites

- JDK 21 or newer
- Docker Desktop (running)
- A Gemini API key from Google AI Studio

Maven is not required; use the included wrapper (`mvnw` / `mvnw.cmd`).

## 1. Configure secrets

```powershell
Copy-Item .env.example .env
```

Edit `.env` and set `GOOGLE_API_KEY` and a strong `DB_PASSWORD`. `.env` is git-ignored. **Never commit it.**
Docker Compose reads `.env` automatically, and Spring imports it via `spring.config.import` in `application.yml`.
Real environment variables override `.env`.

| Variable | Default | Used by |
|---|---|---|
| `GOOGLE_API_KEY` | none (required for model calls) | Spring AI |
| `DB_NAME` | `hris_rag` | Compose + Spring |
| `DB_USER` | `hris_user` | Compose + Spring |
| `DB_PASSWORD` | none (required) | Compose + Spring |
| `DB_PORT` | `5434` | Compose host port + Spring |

## 2. Start the database

```powershell
docker compose up -d
docker ps --filter name=hris-rag-db      # wait for "(healthy)"
```

The container `hris-rag-db` listens on `localhost:5434` and stores data in the named volume `hrisrag_hris_rag_pgdata`.

## 3. Run the application

```powershell
.\mvnw.cmd spring-boot:run           # Linux/macOS: ./mvnw spring-boot:run
```

Run it from the project root so `.env` is found. Flyway applies migrations from `src/main/resources/db/migration` on startup.

Health check:

```powershell
curl.exe -s http://localhost:8080/actuator/health
```

Expected: `"status":"UP"` with a `db` component `UP`.

## 4. Generate synthetic HRIS data

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hrisrag.seed.run=true --hrisrag.seed.profile=STANDARD"
```

This replaces all HRIS rows with a deterministic synthetic dataset, validates it, prints a summary with a fingerprint, and exits.
Profiles: `TEST` (30 employees), `STANDARD` (500 employees, the default), `LARGE` (5,000 employees, ~1.1M attendance rows,
~35 s). Details, business rules and the fixed benchmark anchors (e.g. `E-1001`, `LR-1005`) are in
[`docs/synthetic-dataset.md`](docs/synthetic-dataset.md).

## 5. Policy corpus (generated once, committed)

The bilingual policy corpus in `corpus/` is generated from `corpus-spec/` by an LLM, validated, and committed with checksums in
`corpus/manifest.json` (see [`docs/corpus-design.md`](docs/corpus-design.md)). You normally only **validate** it:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hrisrag.corpus.validate-only=true --hrisrag.corpus.require-complete=true"
```

To (re)generate missing or invalid documents (needs `GOOGLE_API_KEY`; resumable; falls back through the free flash-lite models
when a quota is exhausted):

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--hrisrag.corpus.generate=true --spring.ai.retry.max-attempts=1"
```

Select a subset with `--hrisrag.corpus.only=group:FAQ` or `--hrisrag.corpus.only=POL-ANNUAL-LEAVE-EN@2.0`.

## 6. Run the tests

```powershell
.\mvnw.cmd -B clean verify
```

The tests start their own throwaway pgvector database with Testcontainers (Docker must be running). They do not use the
Compose database and do not call Gemini.

## 7. Reset the database

```powershell
docker compose down -v               # removes the container AND the data volume
docker compose up -d
```

The next application start re-runs all Flyway migrations on the empty database. Then regenerate the HRIS data (step 4).

## Project layout

```text
docs/                         architecture, database design, API contracts (draft), risk register
src/main/java/com/mimocodes/hrisrag/
src/main/resources/
  application.yml             non-secret defaults
  db/migration/               Flyway migrations (V1 = pgvector extension)
docker-compose.yml            PostgreSQL 18 + pgvector
PROJECT_PROGRESS.md           completed tasks, decisions, test status, next task
```

## Security notes

- Secrets live only in `.env` or the environment, never in `application.yml` or committed files.
- Benchmark labels are kept apart from the retrieval corpus (see `docs/architecture.md` §3 rule 3).
- The Actuator health details are fully exposed for local development only; this will be restricted when security is added (Milestone 6).

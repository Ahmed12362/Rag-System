# API Contracts

> Status: **DRAFT boundaries only (Task 0.3).** Lists the endpoint groups and their responsibilities.
> Request/response schemas, examples and error catalogues are finalized and tested in Milestone 11.
> Nothing here is implemented yet.

## Conventions
- Base path `/api/v1`, JSON, and `application/problem+json` (RFC 9457) for errors.
- The caller's identity comes **only** from Spring Security (simulated users from M6). No endpoint accepts a "user" or "role" field.
- Long-running work (ingestion, experiments) is **asynchronous**: `202 Accepted` + resource ID, then poll the status. The dashboard never
  shows success until the backend reports a terminal status.
- Lists are paginated (`page`, `size`) with stable sort order.

## Implemented (Task 5.1)

### `POST /api/v1/chat`
Request:
```json
{ "question": "How early must I request annual leave?", "strategy": "NAIVE", "conversationId": "c-1" }
```
- `question`: required, 1–2000 characters.
- `strategy`: optional, `NAIVE` (default), `HYBRID` or `AGENTIC`. Unavailable strategies → 400.
- `conversationId`: optional, ≤ 64 characters.
- There is no user field: identity is server-side.

Response `200` (`RagResult`, abbreviated):
```json
{ "requestId": "…", "strategy": "NAIVE", "status": "ANSWERED",
  "answer": "Submit the request at least 14 calendar days before … [S1]",
  "citations": [{ "sourceId": "S1", "chunkId": 42, "docKey": "POL-ANNUAL-LEAVE-EN", "version": "2.0",
                  "sectionPath": "Annual Leave Policy > 3. Policy" }],
  "retrieved": [{ "rank": 1, "chunkId": 42, "docKey": "POL-ANNUAL-LEAVE-EN", "score": 0.81, "content": "…" }],
  "modelCalls": [{ "purpose": "EMBED_QUERY", "cached": false }, { "purpose": "GENERATE", "promptTokens": 950, … }],
  "guardrails": [{ "layer": "OUTPUT", "rule": "CITATIONS", "outcome": "PASS" }],
  "timings": { "totalMs": 2100, "embeddingMs": 300, "retrievalMs": 15, "generationMs": 1750 },
  "settings": { "topK": 5, "maxContextTokens": 2500, "chatModel": "gemini-3.5-flash-lite", "temperature": 0.0 },
  "promptVersion": "grounded-v1" }
```
`status` is one of `ANSWERED`, `ABSTAINED` (no evidence), `BLOCKED` (input rejected) and `ERROR` (safe message; details in logs).

Errors: `400 application/problem+json` (RFC 9457) for validation failures and unavailable strategies.

### `GET /api/v1/strategies`
`[{ "id": "NAIVE", "available": true, "defaultTopK": 5, "chatModel": "gemini-3.5-flash-lite" }, { "id": "HYBRID", "available": false, … }]`

## Endpoint groups

| Group | Endpoints (draft) | Purpose | Milestone |
|---|---|---|---|
| Health | `GET /actuator/health` | Liveness/readiness, DB status | M1 |
| Strategies | `GET /api/v1/strategies` | Available strategies and their default `RagSettings` | M5 |
| Chat / playground | `POST /api/v1/chat` → `RagResult` (answer, status, citations, retrieval trace, usage, tool calls, guardrails, timings) | Ask a question with a selected strategy | M5 (Naive), M7, M8 |
| Traces | `GET /api/v1/traces/{requestId}` | Re-open a stored chat trace | M5 |
| Documents | `GET /api/v1/documents`, `GET /api/v1/documents/{docKey}` (versions), `GET /api/v1/documents/{docKey}/chunks` | Inspect the corpus and chunks, filtered by the caller's access level | M4 |
| Ingestion | `POST /api/v1/ingestion/runs`, `GET /api/v1/ingestion/runs`, `GET /api/v1/ingestion/runs/{id}` (incl. failures) | Start and inspect ingestion; observe failures | M4 |
| Benchmark datasets | `GET /api/v1/benchmarks`, `GET /api/v1/benchmarks/{name}/{version}/cases` | Browse datasets and cases. Expected answers are shown here for inspection but never reach the answering path. | M6/M9 |
| Experiments | `POST /api/v1/experiments` (dataset + strategies + trials + settings), `GET /api/v1/experiments`, `GET /api/v1/experiments/{id}`, `POST /api/v1/experiments/{id}/cancel` | Launch, monitor, cancel | M6 (minimal), M9 |
| Results | `GET /api/v1/experiments/{id}/results?strategy=&language=&category=&status=`, `GET /api/v1/experiments/{id}/results/{caseKey}` | Per-question inspection | M9 |
| Metrics / comparison | `GET /api/v1/experiments/{id}/metrics?slice=`, `GET /api/v1/comparisons?experimentIds=` (rejects incompatible configs with an explanation) | Aggregates and cross-strategy comparison | M9, M11 |
| Dev data (dev profile only) | `POST /api/v1/dev/seed/reset?seed=` | Regenerate synthetic data deterministically. Disabled outside the `dev` profile. | M2 |

## Explicit non-goals
- No endpoint executes SQL or arbitrary tool names.
- No endpoint returns API keys, full system prompts, or restricted HRIS fields to unauthorized roles.

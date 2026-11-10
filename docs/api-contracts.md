# API Contracts

> Status: **DRAFT boundaries only (Task 0.3).** Lists the endpoint groups and their responsibilities.
> Request/response schemas, examples and error catalogues are finalized and tested in Milestone 11.
> Implemented so far: authentication, `/me`, chat and strategies (below). The other groups are still drafts.

## Conventions
- Base path `/api/v1`, JSON, and `application/problem+json` (RFC 9457) for errors.
- The caller's identity comes **only** from Spring Security (simulated users from M6). No endpoint accepts a "user" or "role" field.
- Long-running work (ingestion, experiments) is **asynchronous**: `202 Accepted` + resource ID, then poll the status. The dashboard never
  shows success until the backend reports a terminal status.
- Lists are paginated (`page`, `size`) with stable sort order.

## Implemented (Tasks 5.1, 6.1)

### Authentication (Task 6.1)
- Every `/api/**` request requires HTTP Basic credentials of a simulated user (`app_user`, see README §7). The server is
  stateless (no session cookie).
- Missing or wrong credentials → `401 application/problem+json` with `WWW-Authenticate: Basic realm="hris-rag"`. An unknown user
  and a wrong password get the same body, so the response does not reveal which accounts exist.
- Any path not listed here is denied. `GET /actuator/health` is public and shows only `{"status":"UP"}`. Details are shown to
  HR_ADMIN only.

### `GET /api/v1/me`
`{ "username": "hr", "employeeCode": "E-1003", "role": "HR_ADMIN", "department": "D-HR", "maxAccessLevel": "CONFIDENTIAL" }`.
Credentials are never included.

### `POST /api/v1/chat`
Request:
```json
{ "question": "How early must I request annual leave?", "strategy": "NAIVE", "conversationId": "c-1" }
```
- `question`: required, 1–2000 characters.
- `strategy`: optional, `NAIVE` (default), `HYBRID` (Task 7.1) or `AGENTIC` (Task 8.1). AGENTIC adds `toolCalls[]`
  (`round`, `name`, validated `arguments`, `outcome` OK/DENIED/INVALID/NOT_FOUND/LIMIT, `reason`, `resultChars`,
  `latencyMs`). The tool result data is never returned or stored. For HYBRID,
  `retrieved[].score` is the fused RRF score, and `vectorRank` / `keywordRank` show the chunk's rank in each list (null = not in
  that list).
- `conversationId`: optional, ≤ 64 characters.
- There is no user field: identity comes from the authenticated principal. Extra identity fields in the body are ignored.

Response `200` (`RagResult`, abbreviated):
```json
{ "requestId": "…", "strategy": "NAIVE", "status": "ANSWERED",
  "answer": "Submit the request at least 14 calendar days before … [S1]",
  "citations": [{ "sourceId": "S1", "chunkId": 42, "docKey": "POL-ANNUAL-LEAVE-EN", "version": "2.0",
                  "sectionPath": "Annual Leave Policy > 3. Policy" }],
  "retrieved": [{ "rank": 1, "chunkId": 42, "docKey": "POL-ANNUAL-LEAVE-EN", "score": 0.81, "content": "…" }],
  "passageChecks": [{ "chunkId": 42, "docKey": "POL-ANNUAL-LEAVE-EN", "rank": 1, "outcome": "CLEAN", "rules": [],
                      "removedSentences": 0 }],
  "modelCalls": [{ "purpose": "EMBED_QUERY", "cached": false }, { "purpose": "GENERATE", "promptTokens": 950, … }],
  "callCounts": { "embeddingCalls": 1, "embeddingCacheHits": 0, "chatCalls": 1, "promptTokens": 950,
                  "completionTokens": 40, "thinkingTokens": 0 },
  "guardrails": [{ "layer": "INPUT", "rule": "PROMPT_INJECTION", "outcome": "PASS" },
                 { "layer": "OUTPUT", "rule": "CITATIONS", "outcome": "PASS" }],
  "timings": { "totalMs": 2100, "embeddingMs": 300, "retrievalMs": 15, "generationMs": 1750 },
  "settings": { "topK": 5, "maxContextTokens": 2500, "chatModel": "gemini-3.5-flash-lite", "temperature": 0.0 },
  "promptVersion": "grounded-v1" }
```
`status` is one of:
- `ANSWERED`;
- `ABSTAINED`: no evidence;
- `BLOCKED`: the question was rejected (length or prompt injection, with no model calls), or the answer was withheld by the leak
  check;
- `ERROR`: a safe message; details are in the server logs.

`passageChecks[].outcome` is one of CLEAN, FLAGGED, REDACTED, REMOVED or DENIED (see `docs/rag-strategies.md` §2).

Errors (all `application/problem+json`, RFC 9457):
- `400` for validation failures and unavailable strategies;
- `401` without valid credentials.

### `GET /api/v1/experiments`, `GET /api/v1/experiments/{id}` (Task 6.2, HR_ADMIN only)
- The list endpoint returns the latest 50 experiments: id, dataset and version, status, filter, limit, start and finish times, stop reason and result count.
- The detail endpoint also returns each run's `aggregates` (metric, slice type and value, value, sample size) and a summary per question (case, status, latency, calls, `case_pass`, `recall_at_k`).
- Other users get `403`, an unknown id gets `404` (both `application/problem+json`).
- Starting a run via `POST` comes in M9; runs are started from the command line (`docs/benchmark-methodology.md` §6).

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

-- Minimal benchmark experiment persistence (Milestone 6, Task 6.2). Design: docs/database-design.md §3.3–3.4.
--
-- Deviation from the draft: cases are NOT copied into a benchmark_case table. They stay in the versioned dataset file
-- (benchmark/hris-bench-v*.yaml); benchmark_dataset stores its SHA-256, and the runner refuses a file whose checksum
-- differs from the registered one. question_result therefore keys on the case id string.
-- Raw per-question results are stored, so every aggregate can be recomputed.

CREATE TABLE benchmark_dataset (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(80)  NOT NULL,
    version     VARCHAR(20)  NOT NULL,
    checksum    VARCHAR(64)  NOT NULL CHECK (checksum ~ '^[0-9a-f]{64}$'),
    case_count  INT          NOT NULL CHECK (case_count > 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (name, version)
);

CREATE TABLE experiment (
    id            BIGSERIAL     PRIMARY KEY,
    dataset_id    BIGINT        NOT NULL REFERENCES benchmark_dataset(id),
    status        VARCHAR(20)   NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    case_filter   VARCHAR(400),
    case_limit    INT           NOT NULL DEFAULT 0 CHECK (case_limit >= 0),   -- 0 = all selected cases
    config        JSONB         NOT NULL,                                     -- models, settings, guardrails; no secrets
    started_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    stop_reason   VARCHAR(500)
);

CREATE TABLE experiment_run (
    id             BIGSERIAL    PRIMARY KEY,
    experiment_id  BIGINT       NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    strategy       VARCHAR(20)  NOT NULL CHECK (strategy IN ('NAIVE','HYBRID','AGENTIC')),
    trial          INT          NOT NULL DEFAULT 1 CHECK (trial >= 1),
    settings       JSONB        NOT NULL,                                     -- RagSettings as executed
    UNIQUE (experiment_id, strategy, trial)
);

CREATE TABLE question_result (
    id                    BIGSERIAL    PRIMARY KEY,
    run_id                BIGINT       NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
    case_key              VARCHAR(40)  NOT NULL,
    category              VARCHAR(20)  NOT NULL,
    language              VARCHAR(10)  NOT NULL,
    expected_behavior     VARCHAR(10)  NOT NULL CHECK (expected_behavior IN ('ANSWER','ABSTAIN','REFUSE')),
    answer_status         VARCHAR(20)  NOT NULL CHECK (answer_status IN ('ANSWERED','ABSTAINED','BLOCKED','ERROR')),
    answer                TEXT,
    request_id            UUID         NOT NULL,                              -- links to rag_request_trace
    total_ms              INT          NOT NULL,
    embedding_calls       INT          NOT NULL,
    embedding_cache_hits  INT          NOT NULL,
    chat_calls            INT          NOT NULL,
    prompt_tokens         INT          NOT NULL,
    completion_tokens     INT          NOT NULL,
    thinking_tokens       INT          NOT NULL,
    detail                JSONB        NOT NULL,     -- retrieved doc ranking, citations, passage checks, guardrails
    metrics               JSONB        NOT NULL,     -- per-question metric values (null = not applicable)
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (run_id, case_key)                        -- resumable and idempotent
);

CREATE TABLE metric_aggregate (
    id           BIGSERIAL         PRIMARY KEY,
    run_id       BIGINT            NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
    metric       VARCHAR(60)       NOT NULL,
    slice_type   VARCHAR(20)       NOT NULL CHECK (slice_type IN ('ALL','SCOPE','LANGUAGE','CATEGORY')),
    slice_value  VARCHAR(40)       NOT NULL,
    value        DOUBLE PRECISION  NOT NULL,
    sample_size  INT               NOT NULL CHECK (sample_size > 0),
    UNIQUE (run_id, metric, slice_type, slice_value)
);

-- =====================================================================
-- DRAFT - NOT A FLYWAY MIGRATION.
-- Validated against pgvector/pgvector:0.8.7-pg18-bookworm in Task 0.3 (see docs/database-design.md section 4).
-- Each table becomes a real migration in the milestone that needs it.
-- =====================================================================
-- HRIS RAG: DRAFT schema (Task 0.3). Not a Flyway migration.
-- Status/enumerations use VARCHAR + CHECK (simpler with JPA than PG enums; easy to extend via migration).
-- =====================================================================
CREATE EXTENSION IF NOT EXISTS vector;

-- ---------------------------------------------------------------------
-- 1. HRIS core
-- ---------------------------------------------------------------------
CREATE TABLE department (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,              -- e.g. D-ENG
    name_en     VARCHAR(120) NOT NULL,
    name_ar     VARCHAR(120) NOT NULL
);

CREATE TABLE employee (
    id              BIGSERIAL PRIMARY KEY,
    employee_code   VARCHAR(20)  NOT NULL UNIQUE,          -- e.g. E-1001
    full_name_en    VARCHAR(160) NOT NULL,
    full_name_ar    VARCHAR(160) NOT NULL,
    email           VARCHAR(200) NOT NULL UNIQUE,          -- synthetic, @example.test
    department_id   BIGINT       NOT NULL REFERENCES department(id),
    manager_id      BIGINT       REFERENCES employee(id),
    job_title       VARCHAR(120) NOT NULL,
    employment_type VARCHAR(20)  NOT NULL CHECK (employment_type IN ('FULL_TIME','PART_TIME','CONTRACTOR','INTERN')),
    hire_date       DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','ON_LEAVE','TERMINATED')),
    CHECK (manager_id IS NULL OR manager_id <> id)
);
CREATE INDEX employee_department_idx ON employee(department_id);
CREATE INDEX employee_manager_idx    ON employee(manager_id);

-- Restricted: exists so salary-disclosure security tests have a real target. HR_ADMIN only.
CREATE TABLE employee_compensation (
    employee_id     BIGINT PRIMARY KEY REFERENCES employee(id),
    monthly_salary  NUMERIC(12,2) NOT NULL CHECK (monthly_salary > 0),
    currency        CHAR(3)       NOT NULL DEFAULT 'EGP',
    effective_from  DATE          NOT NULL
);

CREATE TABLE attendance_record (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT  NOT NULL REFERENCES employee(id),
    work_date       DATE    NOT NULL,
    check_in        TIMESTAMPTZ,
    check_out       TIMESTAMPTZ,
    minutes_late    INT     NOT NULL DEFAULT 0 CHECK (minutes_late >= 0),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT','LATE','ABSENT','REMOTE','ON_LEAVE','HOLIDAY')),
    UNIQUE (employee_id, work_date),
    CHECK (check_out IS NULL OR check_in IS NULL OR check_out > check_in)
);

CREATE TABLE leave_type (
    id                 BIGSERIAL PRIMARY KEY,
    code               VARCHAR(20)  NOT NULL UNIQUE,       -- ANNUAL, SICK, UNPAID, ...
    name_en            VARCHAR(80)  NOT NULL,
    name_ar            VARCHAR(80)  NOT NULL,
    default_annual_days NUMERIC(5,1) NOT NULL CHECK (default_annual_days >= 0)
);

CREATE TABLE leave_balance (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT  NOT NULL REFERENCES employee(id),
    leave_type_id   BIGINT  NOT NULL REFERENCES leave_type(id),
    year            SMALLINT NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    entitled_days   NUMERIC(5,1) NOT NULL CHECK (entitled_days >= 0),
    used_days       NUMERIC(5,1) NOT NULL DEFAULT 0 CHECK (used_days >= 0),
    pending_days    NUMERIC(5,1) NOT NULL DEFAULT 0 CHECK (pending_days >= 0),
    UNIQUE (employee_id, leave_type_id, year)
    -- remaining = entitled - used - pending: computed in the service, not stored (single source of truth)
);

CREATE TABLE leave_request (
    id              BIGSERIAL PRIMARY KEY,
    request_code    VARCHAR(20) NOT NULL UNIQUE,           -- e.g. LR-1005
    employee_id     BIGINT      NOT NULL REFERENCES employee(id),
    leave_type_id   BIGINT      NOT NULL REFERENCES leave_type(id),
    start_date      DATE        NOT NULL,
    end_date        DATE        NOT NULL,
    days            NUMERIC(5,1) NOT NULL CHECK (days > 0),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    approver_id     BIGINT      REFERENCES employee(id),
    reason          VARCHAR(500),
    submitted_at    TIMESTAMPTZ NOT NULL,
    decided_at      TIMESTAMPTZ,
    CHECK (end_date >= start_date),
    CHECK ((status IN ('APPROVED','REJECTED')) = (decided_at IS NOT NULL))
);
CREATE INDEX leave_request_employee_idx ON leave_request(employee_id, start_date);

CREATE TABLE meeting (
    id              BIGSERIAL PRIMARY KEY,
    meeting_code    VARCHAR(20)  NOT NULL UNIQUE,          -- e.g. M-0042
    title           VARCHAR(200) NOT NULL,
    organizer_id    BIGINT       NOT NULL REFERENCES employee(id),
    starts_at       TIMESTAMPTZ  NOT NULL,
    ends_at         TIMESTAMPTZ  NOT NULL,
    location        VARCHAR(120),
    requires_approval BOOLEAN    NOT NULL DEFAULT FALSE,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('SCHEDULED','PENDING_APPROVAL','CANCELLED','COMPLETED')),
    CHECK (ends_at > starts_at)
);
CREATE INDEX meeting_starts_idx ON meeting(starts_at);

CREATE TABLE meeting_invitation (
    meeting_id      BIGINT NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    employee_id     BIGINT NOT NULL REFERENCES employee(id),
    response        VARCHAR(20) NOT NULL CHECK (response IN ('PENDING','ACCEPTED','DECLINED','TENTATIVE')),
    attended        BOOLEAN,
    PRIMARY KEY (meeting_id, employee_id)
);
CREATE INDEX meeting_invitation_employee_idx ON meeting_invitation(employee_id);

CREATE TABLE employee_reward (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES employee(id),
    reward_type     VARCHAR(30) NOT NULL CHECK (reward_type IN ('SPOT_AWARD','EMPLOYEE_OF_MONTH','BONUS','CERTIFICATE','PROMOTION_RECOGNITION')),
    awarded_on      DATE        NOT NULL,
    awarded_by_id   BIGINT      REFERENCES employee(id),
    description     VARCHAR(500) NOT NULL
);
CREATE INDEX employee_reward_employee_idx ON employee_reward(employee_id);

-- Restricted: HR_ADMIN, or the employee's direct manager.
CREATE TABLE disciplinary_record (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES employee(id),
    action_type     VARCHAR(30) NOT NULL CHECK (action_type IN ('VERBAL_WARNING','WRITTEN_WARNING','FINAL_WARNING','SUSPENSION','DEDUCTION')),
    issued_on       DATE        NOT NULL,
    issued_by_id    BIGINT      REFERENCES employee(id),
    reason          VARCHAR(500) NOT NULL
);
CREATE INDEX disciplinary_record_employee_idx ON disciplinary_record(employee_id);

CREATE TABLE announcement (
    id              BIGSERIAL PRIMARY KEY,
    announcement_code VARCHAR(20) NOT NULL UNIQUE,
    title           VARCHAR(200) NOT NULL,
    body            TEXT         NOT NULL,
    language        VARCHAR(5)   NOT NULL CHECK (language IN ('en','ar')),
    scope           VARCHAR(20)  NOT NULL CHECK (scope IN ('COMPANY','DEPARTMENT')),
    department_id   BIGINT       REFERENCES department(id),
    published_at    TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ,
    CHECK ((scope = 'DEPARTMENT') = (department_id IS NOT NULL)),
    CHECK (expires_at IS NULL OR expires_at > published_at)
);

-- Simulated application users (used from M6). Identity comes from Spring Security, mapped here.
CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(60) NOT NULL UNIQUE,
    employee_id     BIGINT      NOT NULL UNIQUE REFERENCES employee(id),
    role            VARCHAR(20) NOT NULL CHECK (role IN ('EMPLOYEE','MANAGER','HR_ADMIN')),
    max_access_level VARCHAR(20) NOT NULL CHECK (max_access_level IN ('PUBLIC','INTERNAL','CONFIDENTIAL'))
);

-- ---------------------------------------------------------------------
-- 2. Documents, chunks, ingestion
-- ---------------------------------------------------------------------
CREATE TABLE source_document (
    id              BIGSERIAL PRIMARY KEY,
    doc_key         VARCHAR(60)  NOT NULL,                 -- stable across versions, e.g. POL-ANNUAL-LEAVE-AR
    version         VARCHAR(20)  NOT NULL,                 -- e.g. 2.0
    title           VARCHAR(250) NOT NULL,
    category        VARCHAR(40)  NOT NULL,
    language        VARCHAR(5)   NOT NULL CHECK (language IN ('en','ar')),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','DRAFT')),
    effective_date  DATE         NOT NULL,
    expiry_date     DATE,
    scope_department_id BIGINT   REFERENCES department(id), -- NULL = company-wide
    access_level    VARCHAR(20)  NOT NULL CHECK (access_level IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
    source_path     VARCHAR(400) NOT NULL,
    content_hash    CHAR(64)     NOT NULL,                 -- SHA-256 of normalized text; drives re-ingestion
    ingested_at     TIMESTAMPTZ,
    UNIQUE (doc_key, version),
    CHECK (expiry_date IS NULL OR expiry_date > effective_date)
);
-- At most one ACTIVE version per document key.
CREATE UNIQUE INDEX source_document_one_active ON source_document(doc_key) WHERE status = 'ACTIVE';

CREATE TABLE document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT      NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
    chunk_index     INT         NOT NULL CHECK (chunk_index >= 0),
    section_path    VARCHAR(400),                          -- e.g. "3. Requests > 3.2 Notice period"
    page_number     INT,
    content         TEXT        NOT NULL,
    token_count     INT         NOT NULL CHECK (token_count > 0),
    language        VARCHAR(5)  NOT NULL CHECK (language IN ('en','ar')),
    embedding       vector(768) NOT NULL,
    embedding_model VARCHAR(80) NOT NULL,
    search_vector   tsvector GENERATED ALWAYS AS (
                        to_tsvector(CASE language WHEN 'ar' THEN 'arabic'::regconfig ELSE 'english'::regconfig END, content)
                    ) STORED,
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX document_chunk_embedding_hnsw ON document_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX document_chunk_search_gin     ON document_chunk USING gin (search_vector);

CREATE TABLE ingestion_run (
    id              BIGSERIAL PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    status          VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
    config          JSONB       NOT NULL,                  -- chunk size, overlap, embedding model, batch size
    documents_total INT NOT NULL DEFAULT 0,
    documents_ok    INT NOT NULL DEFAULT 0,
    documents_skipped_unchanged INT NOT NULL DEFAULT 0,
    chunks_written  INT NOT NULL DEFAULT 0
);

CREATE TABLE ingestion_failure (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT       NOT NULL REFERENCES ingestion_run(id) ON DELETE CASCADE,
    source_path     VARCHAR(400) NOT NULL,
    stage           VARCHAR(20)  NOT NULL CHECK (stage IN ('LOAD','EXTRACT','NORMALIZE','CHUNK','EMBED','STORE')),
    error_message   VARCHAR(2000) NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL
);

-- ---------------------------------------------------------------------
-- 3. Benchmark (labels live ONLY here, never in document tables)
-- ---------------------------------------------------------------------
CREATE TABLE benchmark_dataset (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(80) NOT NULL,
    version         VARCHAR(20) NOT NULL,
    checksum        CHAR(64)    NOT NULL,                  -- SHA-256 of the source file; detects silent edits
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (name, version)
);

CREATE TABLE benchmark_case (
    id              BIGSERIAL PRIMARY KEY,
    dataset_id      BIGINT      NOT NULL REFERENCES benchmark_dataset(id) ON DELETE CASCADE,
    case_key        VARCHAR(30) NOT NULL,                  -- e.g. FACT-EN-001, stable across dataset versions
    question        TEXT        NOT NULL,
    language        VARCHAR(10) NOT NULL CHECK (language IN ('en','ar','ar-eg','mixed')),
    category        VARCHAR(20) NOT NULL CHECK (category IN ('FACTUAL','MULTI_HOP','CROSS_LINGUAL','UNANSWERABLE','SECURITY','STRUCTURED')),
    answerable      BOOLEAN     NOT NULL,
    expected_answer TEXT,                                  -- reference answer or evaluation criteria
    expected_doc_keys TEXT[]    NOT NULL DEFAULT '{}',
    expected_tool   VARCHAR(60),
    expected_auth_outcome VARCHAR(20) CHECK (expected_auth_outcome IN ('ALLOWED','DENIED')),
    asking_username VARCHAR(60) NOT NULL,                  -- simulated identity the case runs as
    UNIQUE (dataset_id, case_key),
    CHECK (answerable OR cardinality(expected_doc_keys) = 0)
);

-- ---------------------------------------------------------------------
-- 4. Experiments
-- ---------------------------------------------------------------------
CREATE TABLE experiment (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    dataset_id      BIGINT       NOT NULL REFERENCES benchmark_dataset(id),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    config          JSONB        NOT NULL,                 -- full snapshot: settings, models, corpus ingestion_run id, app version. NO secrets.
    created_at      TIMESTAMPTZ  NOT NULL,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ
);

CREATE TABLE experiment_run (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT      NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    strategy        VARCHAR(20) NOT NULL CHECK (strategy IN ('NAIVE','HYBRID','AGENTIC')),
    trial           SMALLINT    NOT NULL CHECK (trial >= 1),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','PARTIAL','FAILED','CANCELLED')),
    settings        JSONB       NOT NULL,                  -- RagSettings as executed
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    UNIQUE (experiment_id, strategy, trial)
);

CREATE TABLE question_result (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT      NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
    case_id         BIGINT      NOT NULL REFERENCES benchmark_case(id),
    answer_status   VARCHAR(20) NOT NULL CHECK (answer_status IN ('ANSWERED','ABSTAINED','BLOCKED','ERROR')),
    answer          TEXT,
    citations       JSONB       NOT NULL DEFAULT '[]',
    retrieved       JSONB       NOT NULL DEFAULT '[]',     -- [{chunkId, docKey, version, stage, rank, score}]
    tool_calls      JSONB       NOT NULL DEFAULT '[]',
    guardrails      JSONB       NOT NULL DEFAULT '[]',
    total_ms        INT,
    retrieval_ms    INT,
    generation_ms   INT,
    llm_calls       SMALLINT    NOT NULL DEFAULT 0,
    embedding_calls SMALLINT    NOT NULL DEFAULT 0,
    prompt_tokens   INT,
    completion_tokens INT,
    thinking_tokens INT,
    estimated_cost_usd NUMERIC(12,6),
    metrics         JSONB       NOT NULL DEFAULT '{}',     -- per-question metric values (recall@k, mrr, faithfulness, ...)
    error_message   VARCHAR(2000),
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (run_id, case_id)                               -- makes runs resumable / idempotent
);

CREATE TABLE metric_aggregate (
    id              BIGSERIAL PRIMARY KEY,
    run_id          BIGINT      NOT NULL REFERENCES experiment_run(id) ON DELETE CASCADE,
    metric          VARCHAR(40) NOT NULL,                  -- recall@5, mrr, p95_latency_ms, ...
    slice_type      VARCHAR(20) NOT NULL CHECK (slice_type IN ('ALL','LANGUAGE','CATEGORY')),
    slice_value     VARCHAR(20) NOT NULL DEFAULT '*',
    value           DOUBLE PRECISION NOT NULL,
    sample_size     INT         NOT NULL CHECK (sample_size >= 0),
    UNIQUE (run_id, metric, slice_type, slice_value)
);

-- ---------------------------------------------------------------------
-- 5. Interactive request traces (chat playground)
-- ---------------------------------------------------------------------
CREATE TABLE rag_request_trace (
    id              BIGSERIAL PRIMARY KEY,
    request_id      UUID        NOT NULL UNIQUE,
    conversation_id VARCHAR(64),
    username        VARCHAR(60) NOT NULL,
    strategy        VARCHAR(20) NOT NULL CHECK (strategy IN ('NAIVE','HYBRID','AGENTIC')),
    question        VARCHAR(4000) NOT NULL,
    answer_status   VARCHAR(20) NOT NULL CHECK (answer_status IN ('ANSWERED','ABSTAINED','BLOCKED','ERROR')),
    answer          TEXT,
    prompt_template_version VARCHAR(40) NOT NULL,          -- not the full system prompt
    trace           JSONB       NOT NULL,                  -- retrieval, citations, tool calls (summarized), guardrails, usage, timings
    created_at      TIMESTAMPTZ NOT NULL
);
CREATE INDEX rag_request_trace_created_idx ON rag_request_trace(created_at);

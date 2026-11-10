-- Naive RAG baseline (Milestone 5, Task 5.1).

-- 1. The embedding cache also stores QUERY embeddings now. Gemini uses different task types for documents and queries,
--    so the same text has different vectors per purpose: purpose becomes part of the key. Existing rows are documents.
ALTER TABLE embedding_cache ADD COLUMN purpose VARCHAR(10) NOT NULL DEFAULT 'DOCUMENT'
    CHECK (purpose IN ('DOCUMENT','QUERY'));
ALTER TABLE embedding_cache DROP CONSTRAINT embedding_cache_pkey;
ALTER TABLE embedding_cache ADD PRIMARY KEY (embedding_model, purpose, text_hash);

-- 2. One row per answered request (chat playground and API). Detail is JSONB; filterable fields are columns.
--    Stored: question, answer, status, strategy, settings, citations, retrieved chunk ids/scores, timings, token usage,
--    guardrail decisions. NOT stored: API keys, the full system prompt (only its version), or the context text.
CREATE TABLE rag_request_trace (
    id                      BIGSERIAL PRIMARY KEY,
    request_id              UUID          NOT NULL UNIQUE,
    conversation_id         VARCHAR(64),
    user_code               VARCHAR(20)   NOT NULL,
    strategy                VARCHAR(20)   NOT NULL CHECK (strategy IN ('NAIVE','HYBRID','AGENTIC')),
    question                VARCHAR(4000) NOT NULL,
    answer_status           VARCHAR(20)   NOT NULL CHECK (answer_status IN ('ANSWERED','ABSTAINED','BLOCKED','ERROR')),
    answer                  TEXT,
    prompt_template_version VARCHAR(40)   NOT NULL,
    total_ms                INT           NOT NULL,
    trace                   JSONB         NOT NULL,
    created_at              TIMESTAMPTZ   NOT NULL
);
CREATE INDEX rag_request_trace_created_idx ON rag_request_trace(created_at);

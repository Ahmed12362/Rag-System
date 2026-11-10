-- Documents, chunks, embeddings and ingestion tracking (Milestone 4, Task 4.1). Design: docs/database-design.md §3.2
-- Accessed with JdbcTemplate (decision D6), not JPA.

CREATE TABLE source_document (
    id               BIGSERIAL PRIMARY KEY,
    doc_key          VARCHAR(80)  NOT NULL,
    version          VARCHAR(20)  NOT NULL,
    title            VARCHAR(300) NOT NULL,
    category         VARCHAR(40)  NOT NULL,
    doc_type         VARCHAR(30)  NOT NULL CHECK (doc_type IN ('POLICY','PROCEDURE','FAQ','HANDBOOK','DEPARTMENT_POLICY')),
    language         VARCHAR(5)   NOT NULL CHECK (language IN ('en','ar')),
    status           VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','DRAFT')),
    effective_date   DATE         NOT NULL,
    expiry_date      DATE,
    -- Department CODE (e.g. D-CS), deliberately without a foreign key: reseeding HRIS data must never touch the corpus.
    scope_department VARCHAR(20),
    access_level     VARCHAR(20)  NOT NULL CHECK (access_level IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
    source_path      VARCHAR(400) NOT NULL,
    content_hash     VARCHAR(64)  NOT NULL,          -- SHA-256 of the corpus file; unchanged hash = skip re-ingestion
    ingested_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT source_document_key_version UNIQUE (doc_key, version),
    CONSTRAINT source_document_expiry CHECK (expiry_date IS NULL OR expiry_date > effective_date)
);
CREATE UNIQUE INDEX source_document_one_active ON source_document(doc_key) WHERE status = 'ACTIVE';

CREATE TABLE document_chunk (
    id              BIGSERIAL PRIMARY KEY,
    document_id     BIGINT       NOT NULL REFERENCES source_document(id) ON DELETE CASCADE,
    chunk_index     INT          NOT NULL CHECK (chunk_index >= 0),
    section_path    VARCHAR(500) NOT NULL,           -- "Annual Leave Policy > 4. Requesting leave" (used in citations)
    content         TEXT         NOT NULL,           -- chunk text shown to the LLM and in citations
    embedding_text  TEXT         NOT NULL,           -- content prefixed with its document/section context header
    content_hash    VARCHAR(64)  NOT NULL,           -- SHA-256 of embedding_text
    token_count     INT          NOT NULL CHECK (token_count > 0),   -- estimated, see docs/ingestion-pipeline.md
    language        VARCHAR(5)   NOT NULL CHECK (language IN ('en','ar')),
    embedding       vector(768)  NOT NULL,
    embedding_model VARCHAR(80)  NOT NULL,
    search_vector   tsvector GENERATED ALWAYS AS (
                        to_tsvector(CASE language WHEN 'ar' THEN 'arabic'::regconfig ELSE 'english'::regconfig END,
                                    embedding_text)
                    ) STORED,
    CONSTRAINT document_chunk_order UNIQUE (document_id, chunk_index)
);
CREATE INDEX document_chunk_embedding_hnsw ON document_chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX document_chunk_search_gin     ON document_chunk USING gin (search_vector);

-- Embeddings keyed by (model, text hash): identical text is never embedded twice, even after re-chunking.
CREATE TABLE embedding_cache (
    embedding_model VARCHAR(80)  NOT NULL,
    text_hash       VARCHAR(64)  NOT NULL,
    embedding       vector(768)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (embedding_model, text_hash)
);

CREATE TABLE ingestion_run (
    id                 BIGSERIAL PRIMARY KEY,
    started_at         TIMESTAMPTZ NOT NULL,
    finished_at        TIMESTAMPTZ,
    status             VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','PARTIAL','FAILED')),
    config             JSONB       NOT NULL,         -- chunking settings, embedding model, batch size
    documents_total    INT NOT NULL DEFAULT 0,
    documents_ingested INT NOT NULL DEFAULT 0,       -- new or changed
    documents_unchanged INT NOT NULL DEFAULT 0,
    documents_removed  INT NOT NULL DEFAULT 0,
    documents_failed   INT NOT NULL DEFAULT 0,
    chunks_written     INT NOT NULL DEFAULT 0,
    embeddings_computed INT NOT NULL DEFAULT 0,
    embeddings_cached  INT NOT NULL DEFAULT 0,
    embedding_requests INT NOT NULL DEFAULT 0
);

CREATE TABLE ingestion_failure (
    id            BIGSERIAL PRIMARY KEY,
    run_id        BIGINT        NOT NULL REFERENCES ingestion_run(id) ON DELETE CASCADE,
    source_path   VARCHAR(400)  NOT NULL,
    stage         VARCHAR(20)   NOT NULL CHECK (stage IN ('LOAD','PARSE','CHUNK','EMBED','STORE')),
    error_message VARCHAR(2000) NOT NULL,
    occurred_at   TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ingestion_failure_run_idx ON ingestion_failure(run_id);

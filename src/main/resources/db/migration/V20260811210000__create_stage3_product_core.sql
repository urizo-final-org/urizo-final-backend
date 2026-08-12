CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE app.project (
    project_id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_project_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE app.connector (
    connector_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES app.project(project_id),
    name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    active_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_connector_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_connector_name CHECK (name ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT ck_connector_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'))
);

CREATE TABLE app.connector_version (
    connector_version_id UUID PRIMARY KEY,
    connector_id UUID NOT NULL REFERENCES app.connector(connector_id),
    version_number INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
    config_json JSONB NOT NULL,
    config_digest VARCHAR(71) NOT NULL,
    previewed_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_connector_version_number UNIQUE (connector_id, version_number),
    CONSTRAINT ck_connector_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_connector_version_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_connector_version_config CHECK (jsonb_typeof(config_json) = 'object'),
    CONSTRAINT ck_connector_version_digest CHECK (config_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_connector_version_activation CHECK (
        (status = 'ACTIVE' AND activated_at IS NOT NULL)
        OR (status <> 'ACTIVE'))
);

ALTER TABLE app.connector
    ADD CONSTRAINT fk_connector_active_version
    FOREIGN KEY (active_version_id) REFERENCES app.connector_version(connector_version_id);

CREATE UNIQUE INDEX uq_connector_single_active_version
    ON app.connector_version (connector_id)
    WHERE status = 'ACTIVE';

CREATE TABLE app.knowledge_base (
    knowledge_base_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES app.project(project_id),
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    active_version_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_knowledge_base_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_knowledge_base_name CHECK (btrim(name) <> '')
);

CREATE TABLE app.knowledge_version (
    knowledge_version_id UUID PRIMARY KEY,
    knowledge_base_id UUID NOT NULL REFERENCES app.knowledge_base(knowledge_base_id),
    connector_version_id UUID NOT NULL REFERENCES app.connector_version(connector_version_id),
    build_job_id UUID,
    version_number INTEGER NOT NULL,
    label VARCHAR(120),
    status VARCHAR(32) NOT NULL DEFAULT 'BUILD_REQUESTED',
    config_digest VARCHAR(71) NOT NULL,
    document_count INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    score NUMERIC(5,2),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ready_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    CONSTRAINT uq_knowledge_version_number UNIQUE (knowledge_base_id, version_number),
    CONSTRAINT ck_knowledge_version_number CHECK (version_number >= 1),
    CONSTRAINT ck_knowledge_version_status CHECK (status IN (
        'BUILD_REQUESTED', 'BUILDING', 'APPROVAL_PENDING', 'ACTIVE', 'ARCHIVED', 'FAILED')),
    CONSTRAINT ck_knowledge_version_digest CHECK (config_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_version_counts CHECK (document_count >= 0 AND chunk_count >= 0),
    CONSTRAINT ck_knowledge_version_score CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT ck_knowledge_version_ready CHECK (
        status IN ('BUILD_REQUESTED', 'BUILDING', 'FAILED') OR ready_at IS NOT NULL),
    CONSTRAINT ck_knowledge_version_active CHECK (
        status <> 'ACTIVE' OR activated_at IS NOT NULL)
);

ALTER TABLE app.knowledge_base
    ADD CONSTRAINT fk_knowledge_base_active_version
    FOREIGN KEY (active_version_id) REFERENCES app.knowledge_version(knowledge_version_id);

CREATE UNIQUE INDEX uq_knowledge_base_single_active_version
    ON app.knowledge_version (knowledge_base_id)
    WHERE status = 'ACTIVE';

CREATE TABLE app.source_document (
    source_document_id UUID PRIMARY KEY,
    knowledge_version_id UUID NOT NULL REFERENCES app.knowledge_version(knowledge_version_id) ON DELETE CASCADE,
    external_document_id VARCHAR(500) NOT NULL,
    title VARCHAR(1000) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(200),
    source_url VARCHAR(2000) NOT NULL,
    source_updated_at TIMESTAMPTZ,
    content_digest VARCHAR(71) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_source_document_external UNIQUE (knowledge_version_id, external_document_id),
    CONSTRAINT ck_source_document_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_source_document_content CHECK (btrim(content) <> ''),
    CONSTRAINT ck_source_document_url CHECK (source_url ~ '^https://'),
    CONSTRAINT ck_source_document_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE TABLE app.document_chunk (
    document_chunk_id UUID PRIMARY KEY,
    source_document_id UUID NOT NULL REFERENCES app.source_document(source_document_id) ON DELETE CASCADE,
    knowledge_version_id UUID NOT NULL REFERENCES app.knowledge_version(knowledge_version_id) ON DELETE CASCADE,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_digest VARCHAR(71) NOT NULL,
    embedding vector(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_document_chunk_index UNIQUE (source_document_id, chunk_index),
    CONSTRAINT ck_document_chunk_index CHECK (chunk_index >= 0),
    CONSTRAINT ck_document_chunk_content CHECK (btrim(content) <> ''),
    CONSTRAINT ck_document_chunk_digest CHECK (content_digest ~ '^sha256:[0-9a-f]{64}$')
);

CREATE INDEX idx_document_chunk_knowledge_version
    ON app.document_chunk (knowledge_version_id, source_document_id);
CREATE INDEX idx_document_chunk_embedding_hnsw
    ON app.document_chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE app.chatbot_config (
    chatbot_id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES app.project(project_id),
    knowledge_base_id UUID NOT NULL REFERENCES app.knowledge_base(knowledge_base_id),
    name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_chatbot_project_name UNIQUE (project_id, name),
    CONSTRAINT ck_chatbot_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_chatbot_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE app.product_idempotency_command (
    command_id UUID PRIMARY KEY,
    operation VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest BYTEA NOT NULL,
    response_status INTEGER NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_product_idempotency_operation_key UNIQUE (operation, idempotency_key),
    CONSTRAINT ck_product_idempotency_operation CHECK (operation ~ '^[A-Z][A-Z0-9_]{2,119}$'),
    CONSTRAINT ck_product_idempotency_key CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_product_idempotency_digest CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_product_idempotency_status CHECK (response_status BETWEEN 200 AND 299),
    CONSTRAINT ck_product_idempotency_response CHECK (jsonb_typeof(response_json) = 'object')
);

CREATE VIEW app.project_status AS
SELECT project_id, name, status, created_at, updated_at FROM app.project;
CREATE VIEW app.connector_status AS
SELECT connector_id, project_id, name, status, active_version_id, created_at, updated_at FROM app.connector;
CREATE VIEW app.knowledge_base_status AS
SELECT knowledge_base_id, project_id, name, active_version_id, created_at, updated_at FROM app.knowledge_base;
CREATE VIEW app.knowledge_version_status AS
SELECT knowledge_version_id, knowledge_base_id, connector_version_id, version_number,
       status, document_count, chunk_count, score, created_at, ready_at, activated_at, archived_at
FROM app.knowledge_version;

GRANT USAGE ON SCHEMA app TO cms_app, dbeaver_reader;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    app.project, app.connector, app.connector_version, app.knowledge_base,
    app.knowledge_version, app.source_document, app.document_chunk,
    app.chatbot_config, app.product_idempotency_command TO cms_app;
GRANT SELECT ON
    app.project_status, app.connector_status, app.knowledge_base_status,
    app.knowledge_version_status TO dbeaver_reader;

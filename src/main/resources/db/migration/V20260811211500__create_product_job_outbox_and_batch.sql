CREATE TABLE app.product_job (
    job_id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES app.project(project_id),
    job_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    state_version INTEGER NOT NULL DEFAULT 1,
    phase VARCHAR(32),
    progress_percent INTEGER NOT NULL DEFAULT 0,
    target_count INTEGER,
    success_count INTEGER,
    failed_count INTEGER,
    resource_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    worker_id VARCHAR(120),
    batch_job_execution_id BIGINT,
    failure_code VARCHAR(120),
    failure_message VARCHAR(1000),
    failure_retryable BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ck_product_job_type CHECK (job_type IN ('CONNECTOR_SYNC', 'KNOWLEDGE_BUILD')),
    CONSTRAINT ck_product_job_status CHECK (status IN (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_product_job_state_version CHECK (state_version >= 1),
    CONSTRAINT ck_product_job_phase CHECK (phase IS NULL OR phase IN (
        'COLLECT', 'NORMALIZE', 'CHUNK', 'EMBED', 'INDEX', 'EVALUATE', 'APPROVAL_PENDING')),
    CONSTRAINT ck_product_job_progress CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT ck_product_job_counts CHECK (
        (target_count IS NULL OR target_count >= 0)
        AND (success_count IS NULL OR success_count >= 0)
        AND (failed_count IS NULL OR failed_count >= 0)),
    CONSTRAINT ck_product_job_attempt CHECK (attempt >= 0 AND max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_product_job_resources CHECK (jsonb_typeof(resource_refs) = 'array'),
    CONSTRAINT ck_product_job_failure CHECK (
        (status = 'FAILED' AND failure_code IS NOT NULL AND failure_message IS NOT NULL
            AND failure_retryable IS NOT NULL)
        OR (status <> 'FAILED' AND failure_code IS NULL AND failure_message IS NULL
            AND failure_retryable IS NULL)),
    CONSTRAINT ck_product_job_terminal_time CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NOT NULL)
        OR (status NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at IS NULL)),
    CONSTRAINT ck_product_job_time_order CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR finished_at >= created_at)
        AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at))
);

CREATE INDEX idx_product_job_project_updated
    ON app.product_job (project_id, updated_at DESC);
CREATE INDEX idx_product_job_claim
    ON app.product_job (status, next_attempt_at, created_at)
    WHERE status = 'QUEUED';

ALTER TABLE app.knowledge_version
    ADD CONSTRAINT fk_knowledge_version_build_job
    FOREIGN KEY (build_job_id) REFERENCES app.product_job(job_id);

CREATE TABLE app.transactional_outbox (
    outbox_id UUID PRIMARY KEY,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_key VARCHAR(200) NOT NULL UNIQUE,
    destination VARCHAR(120) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_id UUID,
    lease_expires_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_outbox_aggregate_type CHECK (aggregate_type IN ('PRODUCT_JOB', 'CODING_JOB')),
    CONSTRAINT ck_outbox_event_type CHECK (event_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    CONSTRAINT ck_outbox_event_key CHECK (btrim(event_key) <> ''),
    CONSTRAINT ck_outbox_destination CHECK (destination ~ '^[a-z][a-z0-9:._-]{2,119}$'),
    CONSTRAINT ck_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_outbox_lease CHECK (
        (status = 'PUBLISHING' AND lease_id IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'PUBLISHING' AND lease_id IS NULL AND lease_expires_at IS NULL)),
    CONSTRAINT ck_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL))
);

CREATE INDEX idx_transactional_outbox_dispatch
    ON app.transactional_outbox (available_at, created_at)
    WHERE status IN ('PENDING', 'FAILED', 'PUBLISHING');

CREATE VIEW app.product_job_status AS
SELECT job_id, trace_id, project_id, job_type, status, state_version, phase,
       progress_percent, target_count, success_count, failed_count, attempt,
       max_attempts, next_attempt_at, worker_id, batch_job_execution_id,
       failure_code, failure_retryable, created_at, started_at, updated_at, finished_at
FROM app.product_job;

CREATE VIEW app.transactional_outbox_status AS
SELECT outbox_id, aggregate_type, aggregate_id, event_type, event_key, destination,
       status, publish_attempts, available_at, lease_expires_at, published_at,
       last_error_code, created_at, updated_at
FROM app.transactional_outbox;

CREATE SCHEMA batch AUTHORIZATION migration_owner;

CREATE TABLE batch.batch_job_instance (
    job_instance_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    job_name VARCHAR(100) NOT NULL,
    job_key VARCHAR(32) NOT NULL,
    CONSTRAINT uq_batch_job_instance_name_key UNIQUE (job_name, job_key)
);

CREATE TABLE batch.batch_job_execution (
    job_execution_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT,
    job_instance_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(10),
    exit_code VARCHAR(2500),
    exit_message VARCHAR(2500),
    last_updated TIMESTAMP,
    CONSTRAINT fk_batch_job_execution_instance FOREIGN KEY (job_instance_id)
        REFERENCES batch.batch_job_instance(job_instance_id)
);

CREATE TABLE batch.batch_job_execution_params (
    job_execution_id BIGINT NOT NULL,
    parameter_name VARCHAR(100) NOT NULL,
    parameter_type VARCHAR(100) NOT NULL,
    parameter_value VARCHAR(2500),
    identifying CHAR(1) NOT NULL,
    CONSTRAINT fk_batch_job_params_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.batch_job_execution(job_execution_id)
);

CREATE TABLE batch.batch_step_execution (
    step_execution_id BIGINT NOT NULL PRIMARY KEY,
    version BIGINT NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    job_execution_id BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    status VARCHAR(10),
    commit_count BIGINT,
    read_count BIGINT,
    filter_count BIGINT,
    write_count BIGINT,
    read_skip_count BIGINT,
    write_skip_count BIGINT,
    process_skip_count BIGINT,
    rollback_count BIGINT,
    exit_code VARCHAR(2500),
    exit_message VARCHAR(2500),
    last_updated TIMESTAMP,
    CONSTRAINT fk_batch_step_execution_job FOREIGN KEY (job_execution_id)
        REFERENCES batch.batch_job_execution(job_execution_id)
);

CREATE TABLE batch.batch_step_execution_context (
    step_execution_id BIGINT NOT NULL PRIMARY KEY,
    short_context VARCHAR(2500) NOT NULL,
    serialized_context TEXT,
    CONSTRAINT fk_batch_step_context_execution FOREIGN KEY (step_execution_id)
        REFERENCES batch.batch_step_execution(step_execution_id)
);

CREATE TABLE batch.batch_job_execution_context (
    job_execution_id BIGINT NOT NULL PRIMARY KEY,
    short_context VARCHAR(2500) NOT NULL,
    serialized_context TEXT,
    CONSTRAINT fk_batch_job_context_execution FOREIGN KEY (job_execution_id)
        REFERENCES batch.batch_job_execution(job_execution_id)
);

CREATE SEQUENCE batch.batch_step_execution_seq MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE batch.batch_job_execution_seq MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE batch.batch_job_seq MAXVALUE 9223372036854775807 NO CYCLE;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    app.product_job, app.transactional_outbox TO cms_app;
GRANT SELECT, INSERT, UPDATE ON app.transactional_outbox TO dev_operator;
GRANT USAGE ON SCHEMA batch TO cms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO cms_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA batch TO cms_app;
GRANT SELECT ON app.product_job_status, app.transactional_outbox_status TO dbeaver_reader;

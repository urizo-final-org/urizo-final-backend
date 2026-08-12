ALTER TABLE app.coding_job
    ADD COLUMN authority_source VARCHAR(32) NOT NULL DEFAULT 'LEGACY_FIXTURE',
    ADD COLUMN actor_id UUID,
    ADD COLUMN project_id UUID,
    ADD COLUMN repository_id UUID,
    ADD COLUMN graph_step VARCHAR(120),
    ADD COLUMN base_sha VARCHAR(71),
    ADD COLUMN policy_hash VARCHAR(71),
    ADD COLUMN started_at TIMESTAMPTZ,
    ADD COLUMN finished_at TIMESTAMPTZ,
    ADD COLUMN failure_code VARCHAR(120),
    ADD COLUMN failure_retryable BOOLEAN;

ALTER TABLE app.coding_job
    ALTER COLUMN authority_source DROP DEFAULT,
    ADD CONSTRAINT ck_coding_job_authority_source
        CHECK (authority_source IN ('LEGACY_FIXTURE', 'SPRING_CONTROL_PLANE')),
    ADD CONSTRAINT ck_coding_job_authoritative_scope
        CHECK (authority_source <> 'SPRING_CONTROL_PLANE' OR (
            actor_id IS NOT NULL
            AND project_id IS NOT NULL
            AND repository_id IS NOT NULL
            AND graph_step IS NOT NULL
            AND graph_step ~ '^[a-z][a-z0-9_-]{0,119}$'
            AND graph_step = ANY (allowed_nodes)
            AND base_sha IS NOT NULL
            AND base_sha ~ '^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$'
            AND policy_hash IS NOT NULL
            AND policy_hash ~ '^sha256:[0-9a-f]{64}$'
        )),
    ADD CONSTRAINT ck_coding_job_authoritative_timestamps
        CHECK (authority_source <> 'SPRING_CONTROL_PLANE' OR (
            (status = 'PENDING' AND started_at IS NULL AND finished_at IS NULL)
            OR (status IN ('RUNNING', 'WAITING_APPROVAL')
                AND started_at IS NOT NULL AND finished_at IS NULL)
            OR (status IN ('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED')
                AND finished_at IS NOT NULL)
        )),
    ADD CONSTRAINT ck_coding_job_authoritative_failure
        CHECK (authority_source <> 'SPRING_CONTROL_PLANE' OR (
            (status = 'FAILED' AND failure_code IS NOT NULL
                AND failure_code ~ '^[A-Z][A-Z0-9_]{2,119}$'
                AND failure_retryable IS NOT NULL)
            OR (status <> 'FAILED' AND failure_code IS NULL AND failure_retryable IS NULL)
        )),
    ADD CONSTRAINT ck_coding_job_authoritative_time_order
        CHECK (authority_source <> 'SPRING_CONTROL_PLANE' OR (
            (started_at IS NULL OR started_at >= created_at)
            AND (finished_at IS NULL OR finished_at >= created_at)
            AND (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
        ));

CREATE INDEX idx_coding_job_project_status
    ON app.coding_job (project_id, status, updated_at DESC)
    WHERE authority_source = 'SPRING_CONTROL_PLANE';

CREATE TABLE app.coding_job_lifecycle_command (
    command_id UUID PRIMARY KEY,
    command_type VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest BYTEA NOT NULL,
    job_id UUID NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    result_state_version INTEGER NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coding_job_lifecycle_command_idempotency
        UNIQUE (command_type, idempotency_key),
    CONSTRAINT uq_coding_job_lifecycle_command_job_version
        UNIQUE (job_id, result_state_version),
    CONSTRAINT fk_coding_job_lifecycle_command_job
        FOREIGN KEY (job_id) REFERENCES app.coding_job(job_id),
    CONSTRAINT ck_coding_job_lifecycle_command_type
        CHECK (command_type IN ('CREATE', 'TRANSITION')),
    CONSTRAINT ck_coding_job_lifecycle_command_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_coding_job_lifecycle_command_request_digest
        CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_job_lifecycle_command_status
        CHECK (to_status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED',
                            'FAILED', 'CANCELLED', 'EXPIRED')
            AND (from_status IS NULL OR from_status IN (
                'PENDING', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED',
                'FAILED', 'CANCELLED', 'EXPIRED'))),
    CONSTRAINT ck_coding_job_lifecycle_command_shape
        CHECK ((command_type = 'CREATE'
                    AND from_status IS NULL
                    AND to_status = 'PENDING'
                    AND result_state_version = 1)
            OR (command_type = 'TRANSITION'
                    AND from_status IS NOT NULL
                    AND from_status <> to_status
                    AND result_state_version >= 2)),
    CONSTRAINT ck_coding_job_lifecycle_command_response
        CHECK (jsonb_typeof(response_json) = 'object')
);

CREATE INDEX idx_coding_job_lifecycle_command_job_created
    ON app.coding_job_lifecycle_command (job_id, created_at);

CREATE OR REPLACE VIEW app.coding_job_status AS
SELECT job_id, trace_id, job_type, status, state_version, prompt_version,
       allowed_capabilities, allowed_nodes, expires_at, created_at, updated_at,
       authority_source, actor_id, project_id, repository_id, graph_step,
       started_at, finished_at, failure_code, failure_retryable
FROM app.coding_job;

CREATE VIEW app.coding_job_lifecycle_command_status AS
SELECT command_id, command_type, idempotency_key, job_id, from_status, to_status,
       result_state_version, created_at
FROM app.coding_job_lifecycle_command;

GRANT SELECT, INSERT, DELETE ON app.coding_job_lifecycle_command TO dev_operator;
GRANT SELECT ON app.coding_job_lifecycle_command_status TO dbeaver_reader;

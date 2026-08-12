ALTER TABLE app.coding_job
    ADD COLUMN worker_attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN worker_max_attempts INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN worker_lease_id UUID,
    ADD COLUMN worker_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_coding_job_worker_attempt
        CHECK (worker_attempt >= 0 AND worker_max_attempts BETWEEN 1 AND 20),
    ADD CONSTRAINT ck_coding_job_worker_lease
        CHECK ((worker_lease_id IS NULL) = (worker_lease_expires_at IS NULL));

CREATE INDEX idx_coding_job_worker_claim
    ON app.coding_job (status, next_attempt_at, created_at)
    WHERE authority_source = 'SPRING_CONTROL_PLANE'
      AND status IN ('PENDING', 'RUNNING');

CREATE TABLE app.coding_worker_command (
    command_id UUID PRIMARY KEY,
    command_type VARCHAR(16) NOT NULL,
    job_id UUID NOT NULL REFERENCES app.coding_job(job_id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest BYTEA NOT NULL,
    response_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coding_worker_command UNIQUE (command_type, job_id, idempotency_key),
    CONSTRAINT ck_coding_worker_command_type
        CHECK (command_type IN ('CLAIM', 'HEARTBEAT', 'OUTCOME')),
    CONSTRAINT ck_coding_worker_command_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_coding_worker_command_digest CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_worker_command_response CHECK (jsonb_typeof(response_json) = 'object')
);

CREATE TABLE app.coding_tool_execution (
    execution_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    tool_call_id UUID NOT NULL,
    job_id UUID NOT NULL REFERENCES app.coding_job(job_id),
    trace_id UUID NOT NULL,
    lease_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest BYTEA NOT NULL,
    expected_state_version INTEGER NOT NULL,
    tool_name VARCHAR(32) NOT NULL,
    requested_path VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    result_media_type VARCHAR(64),
    result_size_bytes INTEGER,
    result_digest VARCHAR(71),
    result_content TEXT,
    error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uq_coding_tool_execution_idempotency UNIQUE (job_id, idempotency_key),
    CONSTRAINT ck_coding_tool_execution_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_coding_tool_execution_digest CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_tool_execution_state_version CHECK (expected_state_version >= 1),
    CONSTRAINT ck_coding_tool_execution_name CHECK (tool_name = 'read_file'),
    CONSTRAINT ck_coding_tool_execution_path CHECK (
        requested_path = 'README.md'
        AND requested_path !~ '(^|/)\.\.(/|$)'
        AND requested_path !~ '[\\:]'),
    CONSTRAINT ck_coding_tool_execution_status CHECK (status IN ('SUCCEEDED', 'DENIED')),
    CONSTRAINT ck_coding_tool_execution_result CHECK (
        (status = 'SUCCEEDED'
            AND result_media_type = 'text/plain'
            AND result_size_bytes BETWEEN 0 AND 200000
            AND result_digest ~ '^sha256:[0-9a-f]{64}$'
            AND result_content IS NOT NULL
            AND error_code IS NULL
            AND completed_at IS NOT NULL)
        OR (status = 'DENIED'
            AND result_media_type IS NULL
            AND result_size_bytes IS NULL
            AND result_digest IS NULL
            AND result_content IS NULL
            AND error_code IS NOT NULL
            AND completed_at IS NOT NULL))
);

CREATE VIEW app.coding_worker_lease_status AS
SELECT job_id, status, state_version, worker_attempt, worker_max_attempts,
       next_attempt_at, worker_lease_expires_at, last_heartbeat_at, updated_at
FROM app.coding_job
WHERE authority_source = 'SPRING_CONTROL_PLANE';

CREATE VIEW app.coding_tool_execution_status AS
SELECT execution_id, request_id, tool_call_id, job_id, trace_id, idempotency_key,
       expected_state_version, tool_name, requested_path, status, result_media_type,
       result_size_bytes, result_digest, error_code, created_at, completed_at
FROM app.coding_tool_execution;

CREATE OR REPLACE FUNCTION app.enqueue_coding_job_requested()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    new_outbox_id UUID := gen_random_uuid();
    delivery_attempt INTEGER := GREATEST(1, NEW.worker_attempt + 1);
    delivery_key VARCHAR(200) := NEW.job_id::text || ':coding-requested:v' || NEW.state_version::text;
BEGIN
    IF NEW.authority_source <> 'SPRING_CONTROL_PLANE' THEN
        RETURN NEW;
    END IF;
    IF NOT (
        (TG_OP = 'INSERT' AND NEW.status = 'PENDING')
        OR (TG_OP = 'UPDATE' AND OLD.status = 'RUNNING' AND NEW.status = 'PENDING')
        OR (TG_OP = 'UPDATE' AND OLD.status = 'WAITING_APPROVAL' AND NEW.status = 'RUNNING')
    ) THEN
        RETURN NEW;
    END IF;

    INSERT INTO app.transactional_outbox (
        outbox_id, aggregate_type, aggregate_id, event_type, event_key, destination,
        payload, status, available_at, created_at, updated_at)
    VALUES (
        new_outbox_id,
        'CODING_JOB',
        NEW.job_id,
        'CODING_JOB_REQUESTED',
        delivery_key,
        'axms:coding:jobs:v1',
        jsonb_build_object(
            'schemaVersion', '1.0',
            'eventId', new_outbox_id,
            'eventType', 'CODING_JOB_REQUESTED',
            'jobId', NEW.job_id,
            'traceId', NEW.trace_id,
            'idempotencyKey', 'coding-job:' || NEW.job_id::text || ':v' || NEW.state_version::text,
            'attempt', delivery_attempt,
            'expectedStateVersion', NEW.state_version,
            'occurredAt', CURRENT_TIMESTAMP,
            'payload', jsonb_build_object(
                'actorId', NEW.actor_id,
                'projectId', NEW.project_id,
                'repositoryId', NEW.repository_id,
                'graphStep', NEW.graph_step,
                'baseSha', NEW.base_sha,
                'contextDigest', NEW.context_digest,
                'policyHash', NEW.policy_hash,
                'expiresAt', NEW.expires_at
            )
        ),
        'PENDING',
        GREATEST(CURRENT_TIMESTAMP, NEW.next_attempt_at),
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP)
    ON CONFLICT (event_key) DO NOTHING;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_coding_job_requested_outbox
AFTER INSERT OR UPDATE OF status ON app.coding_job
FOR EACH ROW EXECUTE FUNCTION app.enqueue_coding_job_requested();

GRANT SELECT, INSERT ON app.coding_worker_command TO ai_workspace;
GRANT SELECT, INSERT ON app.coding_tool_execution TO ai_workspace;
GRANT UPDATE (
    status, state_version, started_at, finished_at, failure_code, failure_retryable,
    worker_attempt, next_attempt_at, worker_lease_id, worker_lease_expires_at,
    last_heartbeat_at, updated_at) ON app.coding_job TO ai_workspace;
GRANT INSERT ON app.transactional_outbox TO ai_workspace;
GRANT SELECT ON app.coding_worker_lease_status, app.coding_tool_execution_status
    TO dbeaver_reader;

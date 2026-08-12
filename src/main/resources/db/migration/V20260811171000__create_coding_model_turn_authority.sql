CREATE TABLE app.coding_service_credential (
    credential_id UUID PRIMARY KEY,
    credential_digest BYTEA NOT NULL UNIQUE,
    label VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    valid_from TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_until TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_coding_service_credential_digest CHECK (octet_length(credential_digest) = 32),
    CONSTRAINT ck_coding_service_credential_label
        CHECK (label ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
    CONSTRAINT ck_coding_service_credential_status
        CHECK (status IN ('ACTIVE', 'RETIRING', 'REVOKED')),
    CONSTRAINT ck_coding_service_credential_validity
        CHECK (valid_until IS NULL OR valid_until > valid_from),
    CONSTRAINT ck_coding_service_credential_revocation
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);

CREATE INDEX idx_coding_service_credential_active
    ON app.coding_service_credential (status, valid_from, valid_until);

CREATE TABLE app.coding_job (
    job_id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    job_type VARCHAR(32) NOT NULL DEFAULT 'CODING_AGENT',
    status VARCHAR(32) NOT NULL,
    state_version INTEGER NOT NULL,
    context_digest VARCHAR(71) NOT NULL,
    prompt_version VARCHAR(120) NOT NULL,
    allowed_capabilities VARCHAR(32)[] NOT NULL,
    allowed_nodes VARCHAR(120)[] NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_coding_job_type CHECK (job_type = 'CODING_AGENT'),
    CONSTRAINT ck_coding_job_status
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED',
                          'FAILED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT ck_coding_job_state_version CHECK (state_version >= 1),
    CONSTRAINT ck_coding_job_context_digest
        CHECK (context_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_coding_job_prompt_version
        CHECK (prompt_version ~ '^[A-Za-z0-9._-]{1,120}$'),
    CONSTRAINT ck_coding_job_capabilities
        CHECK (cardinality(allowed_capabilities) BETWEEN 1 AND 3
            AND allowed_capabilities <@ ARRAY[
                'CHAT', 'STRUCTURED_OUTPUT', 'TOOL_CALLING'
            ]::VARCHAR(32)[]),
    CONSTRAINT ck_coding_job_nodes CHECK (cardinality(allowed_nodes) BETWEEN 1 AND 50),
    CONSTRAINT ck_coding_job_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_coding_job_status_expiry ON app.coding_job (status, expires_at);

CREATE TABLE app.coding_model_turn_idempotency (
    job_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    turn_id UUID NOT NULL UNIQUE,
    request_digest BYTEA NOT NULL,
    attempt INTEGER NOT NULL,
    expected_state_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    lease_id UUID NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    response_json JSONB,
    failure_code VARCHAR(120),
    retryable BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, idempotency_key),
    CONSTRAINT fk_coding_model_turn_idempotency_job
        FOREIGN KEY (job_id) REFERENCES app.coding_job(job_id),
    CONSTRAINT ck_coding_model_turn_idempotency_key
        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_coding_model_turn_request_digest CHECK (octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_model_turn_attempt CHECK (attempt >= 1),
    CONSTRAINT ck_coding_model_turn_expected_state_version CHECK (expected_state_version >= 1),
    CONSTRAINT ck_coding_model_turn_status
        CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_coding_model_turn_completion
        CHECK ((status = 'COMPLETED') = (response_json IS NOT NULL)
            AND (status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_coding_model_turn_failure
        CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)
            AND (status = 'FAILED') = (retryable IS NOT NULL))
);

CREATE INDEX idx_coding_model_turn_lease
    ON app.coding_model_turn_idempotency (status, lease_expires_at);

CREATE VIEW app.coding_service_credential_status AS
SELECT credential_id, label, status, valid_from, valid_until, last_used_at, revoked_at, created_at
FROM app.coding_service_credential;

CREATE VIEW app.coding_job_status AS
SELECT job_id, trace_id, job_type, status, state_version, prompt_version,
       allowed_capabilities, allowed_nodes, expires_at, created_at, updated_at
FROM app.coding_job;

CREATE VIEW app.coding_model_turn_status AS
SELECT job_id, idempotency_key, turn_id, attempt, expected_state_version,
       status, lease_expires_at, failure_code, retryable, created_at, updated_at, completed_at
FROM app.coding_model_turn_idempotency;

GRANT USAGE ON SCHEMA app TO ai_workspace, dev_operator;
GRANT SELECT ON app.coding_service_credential TO ai_workspace;
GRANT UPDATE (last_used_at) ON app.coding_service_credential TO ai_workspace;
GRANT SELECT ON app.coding_job TO ai_workspace;
GRANT SELECT, INSERT, UPDATE ON app.coding_model_turn_idempotency TO ai_workspace;
GRANT SELECT, INSERT, UPDATE ON app.coding_service_credential TO dev_operator;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.coding_job TO dev_operator;
GRANT SELECT, DELETE ON app.coding_model_turn_idempotency TO dev_operator;
GRANT SELECT ON app.coding_service_credential_status,
    app.coding_job_status,
    app.coding_model_turn_status TO dbeaver_reader;

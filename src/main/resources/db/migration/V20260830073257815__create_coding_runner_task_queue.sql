CREATE TABLE app.coding_runner_task (
    task_id UUID PRIMARY KEY,
    kind VARCHAR(32) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    lease_id UUID,
    lease_expires_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    result_json JSONB,
    error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_coding_runner_task_kind CHECK (kind IN (
        'CREATE_WORKTREE',
        'PREPARE_SCAN_WORKTREE',
        'BUILD',
        'TEST',
        'PREVIEW_UP',
        'PREVIEW_DOWN',
        'CREATE_PR')),
    CONSTRAINT ck_coding_runner_task_status CHECK (
        status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_coding_runner_task_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_coding_runner_task_result CHECK (
        result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    CONSTRAINT ck_coding_runner_task_attempt CHECK (
        attempt >= 0 AND max_attempts BETWEEN 1 AND 20),
    CONSTRAINT ck_coding_runner_task_lease CHECK (
        (lease_id IS NULL) = (lease_expires_at IS NULL)),
    CONSTRAINT ck_coding_runner_task_failure CHECK (
        status <> 'FAILED' OR error_code IS NOT NULL),
    CONSTRAINT ck_coding_runner_task_lifecycle CHECK (
        (status = 'PENDING'
            AND lease_id IS NULL
            AND finished_at IS NULL)
        OR (status = 'RUNNING'
            AND lease_id IS NOT NULL
            AND started_at IS NOT NULL
            AND finished_at IS NULL)
        OR (status IN ('SUCCEEDED', 'FAILED')
            AND lease_id IS NULL
            AND started_at IS NOT NULL
            AND finished_at IS NOT NULL))
);

CREATE INDEX idx_coding_runner_task_claim
    ON app.coding_runner_task (created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_coding_runner_task_lease_expiry
    ON app.coding_runner_task (lease_expires_at)
    WHERE status = 'RUNNING';

GRANT SELECT, INSERT, UPDATE ON app.coding_runner_task TO ai_workspace;

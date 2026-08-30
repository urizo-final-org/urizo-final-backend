CREATE TABLE app.coding_job_request (
    job_id UUID PRIMARY KEY REFERENCES app.coding_job(job_id),
    request_text TEXT NOT NULL,
    system_work_id VARCHAR(120) NOT NULL,
    work_slug VARCHAR(160) NOT NULL,
    request_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_coding_job_request_system_work_id UNIQUE (system_work_id),
    CONSTRAINT uq_coding_job_request_work_slug UNIQUE (work_slug),
    CONSTRAINT ck_coding_job_request_text CHECK (
        length(request_text) BETWEEN 1 AND 10000),
    CONSTRAINT ck_coding_job_request_system_work_id CHECK (
        system_work_id ~ '^SYSTEM-LLMOPS-[A-F0-9]{32}$'),
    CONSTRAINT ck_coding_job_request_work_slug CHECK (
        work_slug ~ '^system-llmops-[a-f0-9]{32}$'),
    CONSTRAINT ck_coding_job_request_digest CHECK (
        octet_length(request_digest) = 32)
);

CREATE TABLE app.coding_pipeline_attempt (
    job_id UUID NOT NULL REFERENCES app.coding_job(job_id),
    pipeline_attempt INTEGER NOT NULL,
    workspace_id UUID,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, pipeline_attempt),
    CONSTRAINT uq_coding_pipeline_attempt_workspace UNIQUE (workspace_id),
    CONSTRAINT ck_coding_pipeline_attempt_number CHECK (
        pipeline_attempt BETWEEN 1 AND 3),
    CONSTRAINT ck_coding_pipeline_attempt_status CHECK (
        status IN ('ACTIVE', 'REJECTED', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_coding_pipeline_attempt_lifecycle CHECK (
        (status = 'ACTIVE' AND finished_at IS NULL)
        OR (status <> 'ACTIVE' AND finished_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_coding_pipeline_attempt_active
    ON app.coding_pipeline_attempt (job_id)
    WHERE status = 'ACTIVE';

CREATE TABLE app.coding_handler_result (
    result_id UUID PRIMARY KEY,
    job_id UUID NOT NULL,
    pipeline_attempt INTEGER NOT NULL,
    trace_id UUID NOT NULL,
    handler_key VARCHAR(120) NOT NULL,
    result_type VARCHAR(24) NOT NULL,
    result_port VARCHAR(64) NOT NULL,
    workspace_id UUID,
    candidate_sha VARCHAR(71),
    diff_digest VARCHAR(71),
    validation_hash VARCHAR(71),
    payload JSONB NOT NULL,
    request_digest BYTEA NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_coding_handler_result_attempt
        FOREIGN KEY (job_id, pipeline_attempt)
        REFERENCES app.coding_pipeline_attempt(job_id, pipeline_attempt),
    CONSTRAINT ck_coding_handler_result_key CHECK (
        handler_key ~ '^[a-z][a-z0-9._-]{0,119}$'),
    CONSTRAINT ck_coding_handler_result_type CHECK (
        result_type IN ('ANALYSIS', 'CANDIDATE', 'DIFF', 'REVIEW',
                        'PULL_REQUEST', 'DEPLOY_REQUEST')),
    CONSTRAINT ck_coding_handler_result_port CHECK (
        result_port IN ('feasible', 'infeasible', 'completed', 'passed',
                        'changes_requested', 'ready', 'requested', 'recorded')),
    CONSTRAINT ck_coding_handler_result_candidate CHECK (
        candidate_sha IS NULL
        OR candidate_sha ~ '^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$'),
    CONSTRAINT ck_coding_handler_result_diff CHECK (
        diff_digest IS NULL OR diff_digest ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_coding_handler_result_validation CHECK (
        validation_hash IS NULL OR validation_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_coding_handler_result_payload CHECK (
        jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_coding_handler_result_digest CHECK (
        octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_handler_result_registry CHECK (
        (handler_key = 'coding.analyze'
            AND result_type = 'ANALYSIS'
            AND result_port IN ('feasible', 'infeasible'))
        OR (handler_key = 'coding.code'
            AND result_type = 'CANDIDATE'
            AND result_port = 'completed')
        OR (handler_key = 'coding.review'
            AND result_type = 'REVIEW'
            AND result_port IN ('passed', 'changes_requested'))
        OR (handler_key = 'coding.preview'
            AND result_type = 'DIFF'
            AND result_port = 'ready')
        OR (handler_key = 'coding.pr_request'
            AND result_type = 'PULL_REQUEST'
            AND result_port = 'requested')
        OR (handler_key = 'coding.deploy_request'
            AND result_type = 'DEPLOY_REQUEST'
            AND result_port = 'recorded')),
    CONSTRAINT ck_coding_handler_result_shape CHECK (
        (result_type = 'ANALYSIS')
        OR (result_type = 'CANDIDATE' AND candidate_sha IS NOT NULL)
        OR (result_type = 'DIFF' AND candidate_sha IS NOT NULL
            AND diff_digest IS NOT NULL AND validation_hash IS NOT NULL)
        OR (result_type = 'REVIEW' AND candidate_sha IS NOT NULL)
        OR (result_type = 'PULL_REQUEST'
            AND candidate_sha IS NOT NULL AND validation_hash IS NOT NULL)
        OR (result_type = 'DEPLOY_REQUEST'
            AND candidate_sha IS NOT NULL AND validation_hash IS NOT NULL))
);

CREATE INDEX idx_coding_handler_result_attempt
    ON app.coding_handler_result (job_id, pipeline_attempt, recorded_at, result_id);

CREATE TABLE app.coding_approval_decision (
    job_id UUID NOT NULL,
    pipeline_attempt INTEGER NOT NULL,
    approval_id UUID NOT NULL,
    trace_id UUID NOT NULL,
    node_id VARCHAR(120) NOT NULL,
    stage VARCHAR(16) NOT NULL,
    stage_round INTEGER NOT NULL,
    decision VARCHAR(16) NOT NULL,
    subject_candidate_sha VARCHAR(71),
    policy_hash VARCHAR(71) NOT NULL,
    validation_hash VARCHAR(71),
    feedback VARCHAR(2000),
    actor_id UUID NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest BYTEA NOT NULL,
    result_state_version INTEGER NOT NULL,
    next_pipeline_attempt INTEGER,
    response_json JSONB NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (job_id, pipeline_attempt, approval_id),
    CONSTRAINT fk_coding_approval_decision_attempt
        FOREIGN KEY (job_id, pipeline_attempt)
        REFERENCES app.coding_pipeline_attempt(job_id, pipeline_attempt),
    CONSTRAINT uq_coding_approval_decision_idempotency
        UNIQUE (job_id, idempotency_key),
    CONSTRAINT ck_coding_approval_decision_value CHECK (
        decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_coding_approval_decision_stage CHECK (
        stage IN ('SCOPE', 'CANDIDATE', 'GITHUB', 'CMS', 'DEPLOY')),
    CONSTRAINT ck_coding_approval_decision_node CHECK (
        (stage = 'SCOPE' AND node_id = 'scope_approval')
        OR (stage = 'CANDIDATE' AND node_id = 'preview_approval')
        OR (stage = 'GITHUB' AND node_id = 'github_approval')
        OR (stage = 'CMS' AND node_id = 'cms_approval')
        OR (stage = 'DEPLOY' AND node_id = 'deploy_approval')),
    CONSTRAINT ck_coding_approval_decision_round CHECK (
        stage_round BETWEEN 1 AND 3),
    CONSTRAINT ck_coding_approval_decision_candidate CHECK (
        subject_candidate_sha IS NULL
        OR subject_candidate_sha ~ '^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$'),
    CONSTRAINT ck_coding_approval_decision_policy CHECK (
        policy_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_coding_approval_decision_validation CHECK (
        validation_hash IS NULL OR validation_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_coding_approval_decision_subject CHECK (
        (stage = 'SCOPE'
            AND subject_candidate_sha IS NULL
            AND validation_hash IS NULL)
        OR (stage IN ('CANDIDATE', 'GITHUB', 'CMS', 'DEPLOY')
            AND subject_candidate_sha IS NOT NULL
            AND validation_hash IS NOT NULL)),
    CONSTRAINT ck_coding_approval_decision_actor_role CHECK (
        actor_role IN ('GENERAL_ADMIN', 'SUPER_ADMIN')),
    CONSTRAINT ck_coding_approval_decision_idempotency_key CHECK (
        idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'),
    CONSTRAINT ck_coding_approval_decision_digest CHECK (
        octet_length(request_digest) = 32),
    CONSTRAINT ck_coding_approval_decision_version CHECK (
        result_state_version >= 2),
    CONSTRAINT ck_coding_approval_decision_retry CHECK (
        (decision = 'APPROVED' AND next_pipeline_attempt IS NULL)
        OR (decision = 'REJECTED' AND stage = 'CANDIDATE'
            AND pipeline_attempt < 3
            AND next_pipeline_attempt = pipeline_attempt + 1
            AND next_pipeline_attempt BETWEEN 2 AND 3)
        OR (decision = 'REJECTED'
            AND (stage <> 'CANDIDATE' OR pipeline_attempt = 3)
            AND next_pipeline_attempt IS NULL)),
    CONSTRAINT ck_coding_approval_decision_response CHECK (
        jsonb_typeof(response_json) = 'object')
);

ALTER TABLE app.coding_tool_execution
    ADD COLUMN arguments_json JSONB,
    ADD COLUMN requested_paths JSONB,
    ADD COLUMN workspace_id UUID,
    ADD COLUMN candidate_sha VARCHAR(71);

UPDATE app.coding_tool_execution
SET arguments_json = jsonb_build_object('path', requested_path),
    requested_paths = jsonb_build_array(requested_path),
    candidate_sha = (
        SELECT base_sha FROM app.coding_job
        WHERE app.coding_job.job_id = app.coding_tool_execution.job_id)
WHERE arguments_json IS NULL;

ALTER TABLE app.coding_tool_execution
    ALTER COLUMN arguments_json SET NOT NULL,
    ALTER COLUMN requested_paths SET NOT NULL,
    DROP CONSTRAINT ck_coding_tool_execution_name,
    DROP CONSTRAINT ck_coding_tool_execution_path,
    DROP CONSTRAINT ck_coding_tool_execution_result,
    ADD CONSTRAINT ck_coding_tool_execution_name CHECK (tool_name IN (
        'read_file',
        'search_code',
        'read_diff',
        'apply_patch',
        'run_check',
        'check_package_allowlist',
        'scan_changed_files')),
    ADD CONSTRAINT ck_coding_tool_execution_arguments CHECK (
        jsonb_typeof(arguments_json) = 'object'),
    ADD CONSTRAINT ck_coding_tool_execution_paths CHECK (
        jsonb_typeof(requested_paths) = 'array'
        AND jsonb_array_length(requested_paths) BETWEEN 1 AND 100),
    ADD CONSTRAINT ck_coding_tool_execution_candidate CHECK (
        candidate_sha IS NULL
        OR candidate_sha ~ '^(sha1:[0-9a-f]{40}|sha256:[0-9a-f]{64})$'),
    ADD CONSTRAINT ck_coding_tool_execution_result CHECK (
        (status = 'SUCCEEDED'
            AND result_media_type IN ('application/json', 'text/plain', 'text/x-diff')
            AND result_size_bytes BETWEEN 0 AND 1048576
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
            AND completed_at IS NOT NULL));

CREATE VIEW app.coding_pipeline_attempt_status AS
SELECT job_id, pipeline_attempt, workspace_id, status, created_at, updated_at, finished_at
FROM app.coding_pipeline_attempt;

CREATE VIEW app.coding_handler_result_status AS
SELECT result_id, job_id, pipeline_attempt, trace_id, handler_key, result_type,
       result_port, workspace_id, candidate_sha, diff_digest, validation_hash, recorded_at
FROM app.coding_handler_result;

CREATE VIEW app.coding_approval_decision_status AS
SELECT job_id, pipeline_attempt, approval_id, trace_id, node_id, stage, stage_round, decision,
       subject_candidate_sha, policy_hash, validation_hash, actor_id, actor_role,
       result_state_version, next_pipeline_attempt, decided_at
FROM app.coding_approval_decision;

GRANT SELECT ON app.coding_job_request TO ai_workspace;
GRANT SELECT, INSERT ON app.coding_job_request TO dev_operator;
GRANT SELECT, INSERT, UPDATE ON app.coding_pipeline_attempt TO ai_workspace, dev_operator;
GRANT SELECT, INSERT ON app.coding_handler_result TO ai_workspace, dev_operator;
GRANT SELECT ON app.coding_approval_decision TO ai_workspace;
GRANT SELECT, INSERT ON app.coding_approval_decision TO dev_operator;
GRANT SELECT ON app.coding_pipeline_attempt_status,
    app.coding_handler_result_status,
    app.coding_approval_decision_status TO dbeaver_reader;

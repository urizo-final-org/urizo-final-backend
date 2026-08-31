CREATE TABLE app.natural_cms_job (
    job_id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    profile_version_id UUID NOT NULL
        REFERENCES app.ai_profile_version(profile_version_id),
    actor_id UUID NOT NULL,
    pipeline_attempt INTEGER NOT NULL DEFAULT 1,
    state_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE',
    request_text TEXT NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    structured_command JSONB,
    preview_id UUID,
    preview_hash VARCHAR(71),
    preview_payload JSONB,
    preview_valid BOOLEAN NOT NULL DEFAULT FALSE,
    approval_decision VARCHAR(16),
    approval_feedback VARCHAR(2000),
    approver_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_natural_cms_job_attempt CHECK (pipeline_attempt BETWEEN 1 AND 3),
    CONSTRAINT ck_natural_cms_job_state_version CHECK (state_version >= 1),
    CONSTRAINT ck_natural_cms_job_status CHECK (
        status IN ('ACTIVE', 'WAITING_APPROVAL', 'COMPLETED', 'REJECTED')),
    CONSTRAINT ck_natural_cms_job_request CHECK (
        length(request_text) BETWEEN 1 AND 10000),
    CONSTRAINT ck_natural_cms_job_resource CHECK (
        resource_type = 'CONTENT'
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_natural_cms_job_command CHECK (
        structured_command IS NULL OR jsonb_typeof(structured_command) = 'object'),
    CONSTRAINT ck_natural_cms_job_preview_payload CHECK (
        preview_payload IS NULL OR jsonb_typeof(preview_payload) = 'object'),
    CONSTRAINT ck_natural_cms_job_preview_hash CHECK (
        preview_hash IS NULL OR preview_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_natural_cms_job_preview_shape CHECK (
        (preview_id IS NULL AND preview_hash IS NULL AND preview_payload IS NULL
            AND preview_valid = FALSE)
        OR (preview_id IS NOT NULL AND preview_hash IS NOT NULL
            AND preview_payload IS NOT NULL)),
    CONSTRAINT ck_natural_cms_job_decision CHECK (
        approval_decision IS NULL OR approval_decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_natural_cms_job_decision_shape CHECK (
        (approval_decision IS NULL AND approver_id IS NULL)
        OR (approval_decision IS NOT NULL AND approver_id IS NOT NULL
            AND ((status = 'WAITING_APPROVAL' AND preview_valid = TRUE)
                OR (status IN ('ACTIVE', 'COMPLETED', 'REJECTED')
                    AND preview_valid = FALSE)))),
    CONSTRAINT ck_natural_cms_job_rejection_feedback CHECK (
        approval_decision <> 'REJECTED'
        OR (approval_feedback IS NOT NULL AND length(approval_feedback) BETWEEN 1 AND 2000))
);

CREATE TABLE app.natural_cms_handler_result (
    result_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES app.natural_cms_job(job_id),
    pipeline_attempt INTEGER NOT NULL,
    trace_id UUID NOT NULL,
    handler_key VARCHAR(120) NOT NULL,
    result_port VARCHAR(32) NOT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    structured_command JSONB,
    preview_id UUID,
    preview_hash VARCHAR(71),
    payload JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_natural_cms_result_attempt CHECK (pipeline_attempt BETWEEN 1 AND 3),
    CONSTRAINT ck_natural_cms_result_registry CHECK (
        (handler_key = 'cms.analyze' AND result_port IN ('feasible', 'infeasible'))
        OR (handler_key = 'cms.preview' AND result_port = 'ready')
        OR (handler_key = 'cms.discard' AND result_port IN ('retry', 'discarded'))
        OR (handler_key = 'cms.apply' AND result_port = 'applied')),
    CONSTRAINT ck_natural_cms_result_resource CHECK (
        resource_type = 'CONTENT'
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_natural_cms_result_command CHECK (
        structured_command IS NULL OR jsonb_typeof(structured_command) = 'object'),
    CONSTRAINT ck_natural_cms_result_preview_hash CHECK (
        preview_hash IS NULL OR preview_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_natural_cms_result_preview_shape CHECK (
        (handler_key = 'cms.analyze'
            AND structured_command IS NULL
            AND preview_id IS NULL AND preview_hash IS NULL)
        OR (handler_key IN ('cms.preview', 'cms.discard', 'cms.apply')
            AND structured_command IS NOT NULL
            AND preview_id IS NOT NULL AND preview_hash IS NOT NULL)),
    CONSTRAINT ck_natural_cms_result_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX idx_natural_cms_result_job
    ON app.natural_cms_handler_result (job_id, pipeline_attempt, recorded_at, result_id);

GRANT SELECT, INSERT, UPDATE ON app.natural_cms_job TO ai_workspace;
GRANT SELECT, INSERT ON app.natural_cms_handler_result TO ai_workspace;

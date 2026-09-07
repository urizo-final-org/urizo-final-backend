-- Spring-owned, payload-free read model for live Snapshot node monitoring.
-- Business Job status and state_version remain authoritative in their existing tables.

CREATE TABLE app.ai_job_monitoring_state (
    job_id UUID PRIMARY KEY,
    trace_id UUID NOT NULL,
    observation_trace_id VARCHAR(32),
    profile_version_id UUID NOT NULL
        REFERENCES app.ai_profile_version(profile_version_id),
    pipeline_attempt INTEGER NOT NULL,
    execution_attempt INTEGER NOT NULL,
    monitor_status VARCHAR(24) NOT NULL,
    monitor_revision BIGINT NOT NULL DEFAULT 1,
    current_node_id VARCHAR(64) NOT NULL,
    current_node_sequence BIGINT NOT NULL,
    current_node_type VARCHAR(64) NOT NULL,
    current_handler_key VARCHAR(128) NOT NULL,
    current_node_status VARCHAR(24) NOT NULL,
    last_reported_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_job_monitoring_state_pipeline_attempt
        CHECK (pipeline_attempt >= 1),
    CONSTRAINT ck_ai_job_monitoring_state_execution_attempt
        CHECK (execution_attempt >= 1),
    CONSTRAINT ck_ai_job_monitoring_state_node_sequence
        CHECK (current_node_sequence >= 1),
    CONSTRAINT ck_ai_job_monitoring_state_revision
        CHECK (monitor_revision >= 1),
    CONSTRAINT ck_ai_job_monitoring_state_observation_trace_id
        CHECK (observation_trace_id IS NULL OR observation_trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_ai_job_monitoring_state_status
        CHECK (monitor_status IN ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_job_monitoring_state_node_status
        CHECK (current_node_status IN ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED'))
);

CREATE TABLE app.ai_job_node_occurrence (
    job_id UUID NOT NULL,
    profile_version_id UUID NOT NULL
        REFERENCES app.ai_profile_version(profile_version_id),
    pipeline_attempt INTEGER NOT NULL,
    execution_attempt INTEGER NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_sequence BIGINT NOT NULL,
    trace_id UUID NOT NULL,
    observation_trace_id VARCHAR(32),
    node_type VARCHAR(64) NOT NULL,
    handler_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at TIMESTAMPTZ,
    waiting_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    error_code VARCHAR(120),
    last_reported_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (
        job_id, profile_version_id, pipeline_attempt,
        execution_attempt, node_id, node_sequence
    ),
    CONSTRAINT uq_ai_job_node_occurrence_sequence
        UNIQUE (job_id, pipeline_attempt, execution_attempt, node_sequence),
    CONSTRAINT ck_ai_job_node_occurrence_pipeline_attempt
        CHECK (pipeline_attempt >= 1),
    CONSTRAINT ck_ai_job_node_occurrence_execution_attempt
        CHECK (execution_attempt >= 1),
    CONSTRAINT ck_ai_job_node_occurrence_node_sequence
        CHECK (node_sequence >= 1),
    CONSTRAINT ck_ai_job_node_occurrence_observation_trace_id
        CHECK (observation_trace_id IS NULL OR observation_trace_id ~ '^[0-9a-f]{32}$'),
    CONSTRAINT ck_ai_job_node_occurrence_status
        CHECK (status IN ('RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_ai_job_node_occurrence_error CHECK (
        (status = 'FAILED' AND error_code IS NOT NULL)
        OR (status <> 'FAILED' AND error_code IS NULL)
    )
);

CREATE INDEX ix_ai_job_monitoring_state_active
    ON app.ai_job_monitoring_state (monitor_status, updated_at DESC);

CREATE INDEX ix_ai_job_node_occurrence_job_order
    ON app.ai_job_node_occurrence (
        job_id, pipeline_attempt DESC, execution_attempt DESC, node_sequence DESC
    );

GRANT SELECT, INSERT, UPDATE ON app.ai_job_monitoring_state TO ai_workspace;
GRANT SELECT, INSERT, UPDATE ON app.ai_job_node_occurrence TO ai_workspace;
GRANT SELECT, DELETE ON app.ai_job_monitoring_state TO dev_operator;
GRANT SELECT, DELETE ON app.ai_job_node_occurrence TO dev_operator;

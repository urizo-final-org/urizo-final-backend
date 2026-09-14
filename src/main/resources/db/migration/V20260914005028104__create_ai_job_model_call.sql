CREATE TABLE app.ai_job_model_call (
    call_id UUID PRIMARY KEY,
    call_order BIGINT GENERATED ALWAYS AS IDENTITY,
    job_id UUID NOT NULL,
    profile_version_id UUID NOT NULL,
    pipeline_attempt INTEGER NOT NULL,
    execution_attempt INTEGER NOT NULL,
    node_id VARCHAR(64) NOT NULL,
    node_sequence BIGINT NOT NULL,
    turn_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('OPENAI', 'ANTHROPIC', 'GOOGLE_GENAI')),
    model VARCHAR(200) NOT NULL,
    provider_attempt INTEGER NOT NULL CHECK (provider_attempt BETWEEN 1 AND 3),
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_code VARCHAR(120),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    FOREIGN KEY (job_id, profile_version_id, pipeline_attempt, execution_attempt, node_id, node_sequence)
        REFERENCES app.ai_job_node_occurrence
            (job_id, profile_version_id, pipeline_attempt, execution_attempt, node_id, node_sequence)
        ON DELETE CASCADE,
    CHECK ((status = 'RUNNING' AND finished_at IS NULL AND error_code IS NULL)
        OR (status = 'SUCCEEDED' AND finished_at IS NOT NULL AND error_code IS NULL)
        OR (status = 'FAILED' AND finished_at IS NOT NULL AND error_code IS NOT NULL
            AND error_code ~ '^[A-Z][A-Z0-9_]{2,119}$'))
);

CREATE INDEX ix_ai_job_model_call_job_order ON app.ai_job_model_call (job_id, call_order DESC);
GRANT SELECT, INSERT, UPDATE ON app.ai_job_model_call TO ai_workspace;
GRANT USAGE, SELECT ON SEQUENCE app.ai_job_model_call_call_order_seq TO ai_workspace;
GRANT SELECT, DELETE ON app.ai_job_model_call TO dev_operator;

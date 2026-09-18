-- Historical Langfuse export: no prompts, outputs, credentials or Job state.
CREATE TABLE app.ai_provider_observation_archive (
    observation_id VARCHAR(64) PRIMARY KEY,
    trace_id VARCHAR(64),
    linked_call_id UUID UNIQUE REFERENCES app.ai_job_model_call(call_id) ON DELETE SET NULL,
    job_id UUID,
    profile_version_id UUID,
    node_id VARCHAR(64),
    provider VARCHAR(32),
    model VARCHAR(200),
    level VARCHAR(16) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    input_tokens BIGINT CHECK (input_tokens >= 0),
    output_tokens BIGINT CHECK (output_tokens >= 0),
    cached_input_tokens BIGINT CHECK (cached_input_tokens >= 0),
    imported_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (cached_input_tokens IS NULL OR input_tokens IS NULL OR cached_input_tokens <= input_tokens)
);
CREATE INDEX ix_ai_provider_archive_time ON app.ai_provider_observation_archive (started_at DESC, observation_id DESC);
CREATE INDEX ix_ai_provider_archive_job ON app.ai_provider_observation_archive (job_id, started_at DESC);
GRANT SELECT, INSERT ON app.ai_provider_observation_archive TO ai_workspace;
GRANT SELECT ON app.ai_provider_observation_archive TO dev_operator;

-- Prefer local usage. The archive fills only absent values on reconciled calls.
CREATE VIEW app.ai_provider_observation_read AS
SELECT c.call_id::text AS call_id, c.job_id, c.profile_version_id,
       c.pipeline_attempt, c.execution_attempt, c.node_id, c.node_sequence, c.provider_attempt,
       c.provider, c.model, c.status, c.error_code, c.started_at, c.finished_at,
       COALESCE(c.input_tokens, a.input_tokens) AS input_tokens,
       COALESCE(c.output_tokens, a.output_tokens) AS output_tokens,
       COALESCE(c.cached_input_tokens, a.cached_input_tokens) AS cached_input_tokens,
       COALESCE(a.trace_id, n.observation_trace_id) AS observation_trace_id
FROM app.ai_job_model_call c
LEFT JOIN app.ai_provider_observation_archive a ON a.linked_call_id=c.call_id
LEFT JOIN app.ai_job_node_occurrence n ON n.job_id=c.job_id
    AND n.profile_version_id=c.profile_version_id AND n.pipeline_attempt=c.pipeline_attempt
    AND n.execution_attempt=c.execution_attempt AND n.node_id=c.node_id AND n.node_sequence=c.node_sequence
UNION ALL
SELECT 'lf:' || a.observation_id, a.job_id, a.profile_version_id,
       NULL::integer, NULL::integer, a.node_id, NULL::bigint, NULL::integer,
       a.provider, a.model, CASE WHEN a.level='ERROR' THEN 'FAILED' ELSE 'SUCCEEDED' END,
       NULL::varchar, a.started_at, a.finished_at, a.input_tokens, a.output_tokens, a.cached_input_tokens, a.trace_id
FROM app.ai_provider_observation_archive a WHERE a.linked_call_id IS NULL;
GRANT SELECT ON app.ai_provider_observation_read TO ai_workspace, dev_operator;

ALTER TABLE app.coding_job
    ADD COLUMN profile_version_id UUID,
    ADD CONSTRAINT fk_coding_job_profile_version
        FOREIGN KEY (profile_version_id)
        REFERENCES app.ai_profile_version(profile_version_id);

GRANT SELECT ON app.ai_profile_version TO dev_operator;

CREATE OR REPLACE FUNCTION app.enforce_coding_job_profile_binding()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, app
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
            AND NEW.profile_version_id IS DISTINCT FROM OLD.profile_version_id THEN
        RAISE EXCEPTION 'Coding Job Profile Version binding is immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.authority_source = 'SPRING_CONTROL_PLANE' THEN
        IF NEW.profile_version_id IS NULL THEN
            RAISE EXCEPTION 'Spring-owned Coding Jobs require a Profile Version binding.'
                USING ERRCODE = '23502';
        END IF;

        PERFORM 1
        FROM app.ai_profile_version
        WHERE profile_version_id = NEW.profile_version_id
          AND profile_key = 'LLM_OPS'
          AND status = 'ACTIVE'
        FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Coding Jobs require an ACTIVE LLM_OPS Profile Version.'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION app.enforce_coding_job_profile_binding() FROM PUBLIC;

CREATE TRIGGER trg_coding_job_profile_binding
BEFORE INSERT OR UPDATE OF profile_version_id, authority_source ON app.coding_job
FOR EACH ROW EXECUTE FUNCTION app.enforce_coding_job_profile_binding();

CREATE OR REPLACE VIEW app.coding_job_status AS
SELECT job_id, trace_id, job_type, status, state_version, prompt_version,
       allowed_capabilities, allowed_nodes, expires_at, created_at, updated_at,
       authority_source, actor_id, project_id, repository_id, graph_step,
       started_at, finished_at, failure_code, failure_retryable,
       profile_version_id
FROM app.coding_job;

CREATE OR REPLACE VIEW app.coding_worker_lease_status AS
SELECT job_id, status, state_version, worker_attempt, worker_max_attempts,
       next_attempt_at, worker_lease_expires_at, last_heartbeat_at, updated_at,
       profile_version_id
FROM app.coding_job
WHERE authority_source = 'SPRING_CONTROL_PLANE';

CREATE OR REPLACE FUNCTION app.enqueue_coding_job_requested()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    new_outbox_id UUID := gen_random_uuid();
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
        jsonb_build_object('jobId', NEW.job_id),
        'PENDING',
        GREATEST(CURRENT_TIMESTAMP, NEW.next_attempt_at),
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP)
    ON CONFLICT (event_key) DO NOTHING;
    RETURN NEW;
END;
$$;

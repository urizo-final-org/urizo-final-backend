-- Spring owns the immutable Versioned Profile Snapshot authority. This first
-- contract exposes only authenticated reads; authoring, activation APIs, and
-- Job bindings are deliberately deferred to later work.

CREATE TABLE app.ai_profile_version (
    profile_version_id UUID PRIMARY KEY,
    profile_key VARCHAR(32) NOT NULL,
    profile_version INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_profile_version_key_version
        UNIQUE (profile_key, profile_version),
    CONSTRAINT ck_ai_profile_version_key
        CHECK (profile_key IN ('LLM_OPS', 'NATURAL_CMS')),
    CONSTRAINT ck_ai_profile_version_number
        CHECK (profile_version >= 1),
    CONSTRAINT ck_ai_profile_version_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_ai_profile_version_snapshot_object
        CHECK (jsonb_typeof(snapshot_json) = 'object'),
    CONSTRAINT ck_ai_profile_version_snapshot_identity CHECK ((
        snapshot_json ?& ARRAY[
            'contractVersion', 'profileVersionId', 'profileKey', 'profileVersion'
        ]
        AND jsonb_typeof(snapshot_json -> 'contractVersion') = 'string'
        AND jsonb_typeof(snapshot_json -> 'profileVersionId') = 'string'
        AND jsonb_typeof(snapshot_json -> 'profileKey') = 'string'
        AND jsonb_typeof(snapshot_json -> 'profileVersion') = 'number'
        AND snapshot_json ->> 'contractVersion' = '1.0'
        AND snapshot_json ->> 'profileVersionId' = profile_version_id::text
        AND snapshot_json ->> 'profileKey' = profile_key
        AND snapshot_json ->> 'profileVersion' = profile_version::text
    ) IS TRUE)
);

CREATE OR REPLACE FUNCTION app.enforce_ai_profile_version_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'AI Profile Versions cannot be deleted.'
            USING ERRCODE = '55000';
    END IF;

    IF NEW.profile_version_id IS DISTINCT FROM OLD.profile_version_id
            OR NEW.profile_key IS DISTINCT FROM OLD.profile_key
            OR NEW.profile_version IS DISTINCT FROM OLD.profile_version
            OR NEW.snapshot_json IS DISTINCT FROM OLD.snapshot_json
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'AI Profile Version identity and payload are immutable.'
            USING ERRCODE = '55000';
    END IF;

    IF NOT (
        NEW.status = OLD.status
        OR (OLD.status = 'DRAFT' AND NEW.status = 'ACTIVE')
        OR (OLD.status = 'ACTIVE' AND NEW.status = 'INACTIVE')
    ) THEN
        RAISE EXCEPTION 'AI Profile Version status transition is invalid.'
            USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ai_profile_version_immutable
BEFORE UPDATE OR DELETE ON app.ai_profile_version
FOR EACH ROW EXECUTE FUNCTION app.enforce_ai_profile_version_immutability();

GRANT SELECT ON app.ai_profile_version TO ai_workspace;

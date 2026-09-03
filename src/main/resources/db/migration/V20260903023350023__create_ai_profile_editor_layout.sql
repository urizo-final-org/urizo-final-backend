-- Canvas coordinates are authoring-only metadata. They are stored separately
-- from the immutable execution snapshot consumed by the Orchestrator.

CREATE TABLE app.ai_profile_editor_layout (
    profile_version_id UUID PRIMARY KEY
        REFERENCES app.ai_profile_version (profile_version_id),
    layout_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_ai_profile_editor_layout_shape CHECK (
        jsonb_typeof(layout_json) = 'object'
        AND layout_json ? 'nodes'
        AND (layout_json - 'nodes') = '{}'::jsonb
        AND jsonb_typeof(layout_json -> 'nodes') = 'array'
    )
);

CREATE FUNCTION app.enforce_ai_profile_editor_layout_immutability()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'AI Profile Editor Layouts cannot be updated or deleted.'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_ai_profile_editor_layout_immutable
BEFORE UPDATE OR DELETE ON app.ai_profile_editor_layout
FOR EACH ROW EXECUTE FUNCTION app.enforce_ai_profile_editor_layout_immutability();

GRANT SELECT, INSERT ON app.ai_profile_editor_layout TO ai_workspace;

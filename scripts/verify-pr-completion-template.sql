-- Run only in a disposable, empty PostgreSQL database with this exact name.
\set ON_ERROR_STOP on
DO $$ BEGIN
    IF current_database() <> 'ai04_019_verify' THEN
        RAISE EXCEPTION 'This verification requires the disposable ai04_019_verify database';
    END IF;
END $$;
CREATE SCHEMA app;
CREATE ROLE ai_workspace;
\ir ../src/main/resources/db/migration/V20260903145703043__create_ai_profile_default_template.sql
\ir ../src/main/resources/db/migration/V20260903234407711__update_default_template_model_bindings.sql

-- Preserve a customized node ID and all unrelated authoring settings.
UPDATE app.ai_profile_default_template
SET snapshot_json = replace(snapshot_json::text, '"pr_complete"', '"custom_pr_finish"')::jsonb
WHERE profile_key = 'LLM_OPS';
CREATE TEMP TABLE original_templates AS SELECT * FROM app.ai_profile_default_template;
\ir ../src/main/resources/db/migration/V20260909091111803__route_pr_completion_by_deployment_capability.sql

DO $$ DECLARE before_json jsonb; after_json jsonb; BEGIN
    SELECT snapshot_json INTO before_json FROM original_templates WHERE profile_key = 'LLM_OPS';
    SELECT snapshot_json INTO after_json FROM app.ai_profile_default_template WHERE profile_key = 'LLM_OPS';
    IF (after_json - 'nodes' - 'edges') IS DISTINCT FROM (before_json - 'nodes' - 'edges') THEN
        RAISE EXCEPTION 'Unrelated model, tool or profile settings changed';
    END IF;
    IF NOT after_json->'nodes' @> '[{"id":"custom_pr_finish","resultPorts":["completed","closed"],"config":{"completionMode":"deployment-capability"}}]'::jsonb THEN
        RAISE EXCEPTION 'PR completion node did not gain the capability route';
    END IF;
    IF (SELECT jsonb_agg(n ORDER BY ord) FROM jsonb_array_elements(after_json->'nodes') WITH ORDINALITY x(n,ord) WHERE n->>'id' <> 'custom_pr_finish')
       IS DISTINCT FROM
       (SELECT jsonb_agg(n ORDER BY ord) FROM jsonb_array_elements(before_json->'nodes') WITH ORDINALITY x(n,ord) WHERE n->>'id' <> 'custom_pr_finish') THEN
        RAISE EXCEPTION 'Unrelated nodes changed';
    END IF;
    IF after_json->'edges' IS DISTINCT FROM (before_json->'edges') || '[{"from":"custom_pr_finish","resultPort":"closed","to":"end"}]'::jsonb THEN
        RAISE EXCEPTION 'Original edges changed or the PR-only exit is missing';
    END IF;
    IF EXISTS (SELECT 1 FROM original_templates b JOIN app.ai_profile_default_template a USING (profile_key)
               WHERE a.profile_key = 'NATURAL_CMS' AND a.snapshot_json IS DISTINCT FROM b.snapshot_json) THEN
        RAISE EXCEPTION 'NATURAL_CMS changed';
    END IF;
END $$;

CREATE TEMP TABLE upgraded_templates AS SELECT * FROM app.ai_profile_default_template;
\ir ../src/main/resources/db/migration/V20260909091111803__route_pr_completion_by_deployment_capability.sql
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM upgraded_templates b JOIN app.ai_profile_default_template a USING (profile_key)
               WHERE a.snapshot_json IS DISTINCT FROM b.snapshot_json OR a.updated_at IS DISTINCT FROM b.updated_at) THEN
        RAISE EXCEPTION 'Second application was not a no-op';
    END IF;
END $$;
SELECT 'PASS: capability route, custom node ID, unrelated settings and idempotence' AS result;

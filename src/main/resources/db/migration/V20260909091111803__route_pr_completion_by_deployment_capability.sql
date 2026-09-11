-- Upgrade only the editable LLM_OPS default. Immutable profile versions and Jobs
-- keep their original graph; activate a new version through the Profile API.
WITH targets AS (
    SELECT t.profile_key, t.snapshot_json,
           p.node->>'id' AS pr_id, e.node->>'id' AS end_id
    FROM app.ai_profile_default_template t
    CROSS JOIN LATERAL jsonb_array_elements(t.snapshot_json->'nodes') p(node)
    CROSS JOIN LATERAL jsonb_array_elements(t.snapshot_json->'nodes') e(node)
    WHERE t.profile_key = 'LLM_OPS'
      AND p.node->>'handlerKey' = 'coding.pr_complete'
      AND p.node->'config' = '{}'::jsonb
      AND p.node->'resultPorts' = '["completed"]'::jsonb
      AND e.node->>'type' = 'end'
), upgraded AS (
    SELECT profile_key, jsonb_set(jsonb_set(snapshot_json, '{nodes}', (
        SELECT jsonb_agg(CASE WHEN node->>'id' = pr_id THEN
            node || '{"resultPorts":["completed","closed"],"config":{"completionMode":"deployment-capability"}}'::jsonb
            ELSE node END ORDER BY ordinal)
        FROM jsonb_array_elements(snapshot_json->'nodes') WITH ORDINALITY n(node, ordinal)
    )), '{edges}', (snapshot_json->'edges') || jsonb_build_array(
        jsonb_build_object('from', pr_id, 'resultPort', 'closed', 'to', end_id)
    )) AS snapshot_json
    FROM targets
)
UPDATE app.ai_profile_default_template t
SET snapshot_json = u.snapshot_json, updated_at = CURRENT_TIMESTAMP
FROM upgraded u
WHERE t.profile_key = u.profile_key;

-- AI05-022: extend the existing operation boundary; never rewrite applied revisions.
ALTER TABLE app.natural_cms_operation_selection
    DROP CONSTRAINT ck_natural_cms_operation_selection_resource_type;
ALTER TABLE app.natural_cms_operation_selection
    ADD CONSTRAINT ck_natural_cms_operation_selection_resource_type CHECK (
        resource_type IN ('MENU', 'BOARD', 'BOARD_POST', 'CONTENT', 'TEMPLATE')),
    ADD CONSTRAINT ck_natural_cms_operation_selection_template_update CHECK (
        resource_type <> 'TEMPLATE' OR operation = 'UPDATE');

-- Template UPDATE was previously outside guardrail control. Preserve that behavior
-- once for configured installations; future saves may explicitly disable it.
-- Unconfigured installations continue to use the handler defaults without seeding.
INSERT INTO app.natural_cms_operation_selection (
    natural_cms_operation_selection_id, resource_type, operation, enabled)
SELECT gen_random_uuid(), 'TEMPLATE', 'UPDATE', TRUE
FROM app.natural_cms_rule
WHERE configured
ON CONFLICT (resource_type, operation) DO NOTHING;

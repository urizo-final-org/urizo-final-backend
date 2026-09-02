-- The Natural CMS screen boundary covers MENU, BOARD, CONTENT and TEMPLATE, but the first
-- result boundary pinned both CHECK constraints to CONTENT. The Java contract
-- (NaturalCmsContract.RESOURCE_TYPES) and the MCP command range were already opened, so a MENU
-- Job failed at INSERT while the application layer accepted it. Widen the stored range to the
-- same four types. Existing rows are all CONTENT, so this only relaxes the constraint.

ALTER TABLE app.natural_cms_job
    DROP CONSTRAINT ck_natural_cms_job_resource;

ALTER TABLE app.natural_cms_job
    ADD CONSTRAINT ck_natural_cms_job_resource CHECK (
        resource_type IN ('MENU', 'BOARD', 'CONTENT', 'TEMPLATE')
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

ALTER TABLE app.natural_cms_handler_result
    DROP CONSTRAINT ck_natural_cms_result_resource;

ALTER TABLE app.natural_cms_handler_result
    ADD CONSTRAINT ck_natural_cms_result_resource CHECK (
        resource_type IN ('MENU', 'BOARD', 'CONTENT', 'TEMPLATE')
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

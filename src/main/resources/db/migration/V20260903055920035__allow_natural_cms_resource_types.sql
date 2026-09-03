ALTER TABLE app.natural_cms_job
    DROP CONSTRAINT ck_natural_cms_job_resource,
    ADD CONSTRAINT ck_natural_cms_job_resource CHECK (
        resource_type IN ('MENU', 'BOARD', 'CONTENT', 'TEMPLATE')
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

ALTER TABLE app.natural_cms_handler_result
    DROP CONSTRAINT ck_natural_cms_result_resource,
    ADD CONSTRAINT ck_natural_cms_result_resource CHECK (
        resource_type IN ('MENU', 'BOARD', 'CONTENT', 'TEMPLATE')
        AND resource_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

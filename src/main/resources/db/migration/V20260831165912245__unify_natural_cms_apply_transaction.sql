ALTER TABLE app.transactional_outbox
    DROP CONSTRAINT ck_outbox_aggregate_type,
    ADD CONSTRAINT ck_outbox_aggregate_type CHECK (
        aggregate_type IN ('PRODUCT_JOB', 'CODING_JOB', 'NATURAL_CMS_JOB'));

GRANT SELECT ON app.natural_cms_job TO cms_app;
GRANT UPDATE (status, preview_valid, updated_at)
    ON app.natural_cms_job TO cms_app;
GRANT SELECT, INSERT ON app.natural_cms_handler_result TO cms_app;

-- Direct administrator CMS changes only. AI Jobs keep their existing domain records.
-- No backfill: timestamps on current resources cannot reconstruct past changes.
CREATE TABLE app.cms_change_history (
    change_id UUID PRIMARY KEY,
    resource_type VARCHAR(16) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    title VARCHAR(240) NOT NULL,
    actor_id UUID NOT NULL,
    actor_name VARCHAR(200) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_cms_change_history_resource
        CHECK (resource_type IN ('MENU', 'CONTENT', 'BOARD', 'POST', 'TEMPLATE')),
    CONSTRAINT ck_cms_change_history_operation
        CHECK (operation IN ('CREATE', 'UPDATE', 'DELETE', 'SAVE')),
    CONSTRAINT ck_cms_change_history_actor_role
        CHECK (actor_role IN ('GENERAL_ADMIN', 'SUPER_ADMIN'))
);

CREATE INDEX ix_cms_change_history_order
    ON app.cms_change_history (occurred_at DESC, change_id DESC);

GRANT SELECT, INSERT ON app.cms_change_history TO cms_app;
GRANT SELECT ON app.cms_change_history TO dbeaver_reader;

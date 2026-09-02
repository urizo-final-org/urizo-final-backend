-- Natural CMS enqueues its Job with ON CONFLICT (event_key) DO NOTHING. PostgreSQL reads the
-- arbiter column to detect the conflict, so the insert needs read access to that column. The
-- runtime role held INSERT only, which surfaced as a permission error on Job creation once the
-- resource CHECK stopped rejecting non-CONTENT resources. Grant exactly the column the conflict
-- check reads; payload, destination and every other column stay unreadable for this role.

GRANT SELECT (event_key) ON app.transactional_outbox TO ai_workspace;

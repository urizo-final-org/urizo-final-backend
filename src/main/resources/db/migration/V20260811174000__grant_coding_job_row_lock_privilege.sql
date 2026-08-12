-- PostgreSQL row-locking SELECT clauses require UPDATE privilege on at least
-- one table column. The coding runtime receives only the audit timestamp
-- column so it can hold a FOR SHARE lock while validating state_version,
-- without gaining authority to mutate job state or scope.
GRANT UPDATE (updated_at) ON app.coding_job TO ai_workspace;

-- A recovered reliable-queue delivery reuses the original claim command and
-- renews only its authoritative lease expiry. Keep that narrowly scoped
-- response snapshot update under the Spring-owned ai_workspace role.
GRANT UPDATE (response_json) ON app.coding_worker_command TO ai_workspace;

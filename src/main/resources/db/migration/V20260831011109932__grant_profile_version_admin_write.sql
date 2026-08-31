-- The authenticated Spring admin API reuses the existing ai_workspace datasource.
-- Snapshot identity and payload remain immutable through the existing trigger;
-- DELETE stays ungranted and only forward status transitions are accepted.

GRANT INSERT, UPDATE ON app.ai_profile_version TO ai_workspace;

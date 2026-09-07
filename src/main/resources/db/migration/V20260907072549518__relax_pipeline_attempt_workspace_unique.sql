-- A rejected preview approval opens pipeline attempt 2, and its first stage result
-- then binds the job's workspace to the new attempt. The workspace id is the job id,
-- the same value attempt 1 already recorded, so UNIQUE (workspace_id) refused the
-- bind and every retry died as INTERNAL_TRANSIENT_ERROR with nothing recorded.
-- Measured three out of three: Jobs 7e600583 (09-03), 773aa27b (09-04),
-- 60401f37 (09-07 rehearsal) - the error surfaced only in the PostgreSQL log.
--
-- The guard's purpose is that two live attempts must not share a workspace. A
-- finished attempt is not live, so uniqueness is narrowed to ACTIVE rows: the
-- rejected attempt keeps its workspace record for audit, and the retry inherits
-- the same worktree - which is what a rejection asks for, since the reviewer's
-- feedback tells the model to amend the work sitting in that worktree.
-- No code looks an attempt up by workspace_id (verified: every query keys on
-- job_id + pipeline_attempt), so narrowing the index breaks no reader.
ALTER TABLE app.coding_pipeline_attempt
    DROP CONSTRAINT uq_coding_pipeline_attempt_workspace;

CREATE UNIQUE INDEX uq_coding_pipeline_attempt_workspace_active
    ON app.coding_pipeline_attempt (workspace_id)
    WHERE status = 'ACTIVE';

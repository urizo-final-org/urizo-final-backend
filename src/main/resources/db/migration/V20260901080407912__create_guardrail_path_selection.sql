-- The administrator's choice of which repository folders the Coding model may change.
--
-- Only the choice is stored. The folder list itself is scanned from the repository on every
-- request, so a folder added later appears on its own and a folder that disappears cannot be
-- offered from a stale copy.
--
-- A path that is absent here is OFF. Defaulting a new folder to ON would widen the model's reach
-- every time somebody creates a directory.
--
-- The fixed Denylist is not stored here at all. It lives in code so that no stored selection can
-- grant access to it.
CREATE TABLE app.guardrail_path_selection (
    guardrail_path_selection_id UUID PRIMARY KEY,
    repository VARCHAR(16) NOT NULL,
    path VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    label VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_guardrail_path_selection_path UNIQUE (repository, path),
    CONSTRAINT ck_guardrail_path_selection_repository CHECK (
        repository IN ('backend', 'frontend')),
    -- Repository-relative and /-separated, as the scan reports it. A leading slash, a backslash
    -- or a .. segment would be a different path than the one the scan offered.
    CONSTRAINT ck_guardrail_path_selection_path CHECK (
        path <> ''
        AND path NOT LIKE '/%'
        AND path NOT LIKE '%/'
        -- strpos, not LIKE: inside a LIKE pattern a backslash escapes the next character,
        -- so '%\%' asks whether the path ends in a literal per-cent sign rather than
        -- whether it contains a backslash.
        AND strpos(path, '\') = 0
        AND path NOT LIKE '%..%'),
    CONSTRAINT ck_guardrail_path_selection_label CHECK (
        label IS NULL OR label <> '')
);

CREATE INDEX idx_guardrail_path_selection_enabled
    ON app.guardrail_path_selection (repository, path)
    WHERE enabled;

-- ai_workspace serves the administrator API. dev_operator only reads, because the Job lifecycle
-- connection copies the choice into a job snapshot and never edits the choice itself.
GRANT SELECT, INSERT, UPDATE, DELETE ON app.guardrail_path_selection TO ai_workspace;
GRANT SELECT ON app.guardrail_path_selection TO dev_operator;

-- The guardrail a single job is judged by, copied when the job is created.
--
-- A job can run for a long time. If it were judged by whatever the selection happens to say when it
-- finishes, an administrator changing the setting mid-run would silently change the rules the job
-- already worked under. The copy is what that job is measured against, start to end.
--
-- No UPDATE or DELETE is granted. The copy cannot be edited afterwards even by the application,
-- which is the whole point of taking it.
CREATE TABLE app.guardrail_job_snapshot (
    job_id UUID PRIMARY KEY REFERENCES app.coding_job(job_id),
    snapshot_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_guardrail_job_snapshot_json CHECK (
        jsonb_typeof(snapshot_json) = 'object')
);

-- dev_operator writes the copy in the same transaction that creates the job. ai_workspace only
-- reads it, because the worker judges against the copy and must never be able to change it.
GRANT INSERT, SELECT ON app.guardrail_job_snapshot TO dev_operator;
GRANT SELECT ON app.guardrail_job_snapshot TO ai_workspace;

-- The guardrail rules that have nothing to do with paths: whether the model may add a library,
-- and how large a change it may produce.
--
-- These cannot be decided before the model runs. A request that sounds small can still produce a
-- thousand lines, so the only honest place to judge them is the finished candidate.
--
-- One row, always. The primary key is a constant, so a second row cannot be inserted, and neither
-- INSERT nor DELETE is granted to anybody, so the row can be edited but never removed or
-- duplicated. A settings table that can end up empty turns "no rule" and "table broken" into the
-- same state.
--
-- A limit left NULL means no limit. Refusing everything until somebody fills the screen in would
-- stop ordinary work the moment the setting was forgotten, and the paths that actually matter are
-- closed by the fixed Denylist either way. This matches how an empty path selection is treated.
CREATE TABLE app.guardrail_rule (
    guardrail_rule_id BOOLEAN PRIMARY KEY DEFAULT TRUE,
    allow_new_dependency BOOLEAN NOT NULL DEFAULT FALSE,
    max_changed_files INTEGER,
    max_changed_lines INTEGER,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_guardrail_rule_single_row CHECK (guardrail_rule_id),
    CONSTRAINT ck_guardrail_rule_max_changed_files CHECK (
        max_changed_files IS NULL OR max_changed_files > 0),
    CONSTRAINT ck_guardrail_rule_max_changed_lines CHECK (
        max_changed_lines IS NULL OR max_changed_lines > 0)
);

-- The row exists from the start, so reading the rules never has to handle an absent one.
INSERT INTO app.guardrail_rule (guardrail_rule_id) VALUES (TRUE);

-- ai_workspace serves the administrator screen and may change the values. dev_operator only reads,
-- because the Job lifecycle connection copies the rules into the job snapshot and never edits them.
GRANT SELECT, UPDATE ON app.guardrail_rule TO ai_workspace;
GRANT SELECT ON app.guardrail_rule TO dev_operator;

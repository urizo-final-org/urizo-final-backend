-- Production identity and Project authorization for AXMS-FND-03.
-- The MVP fixes exactly two administrator roles; custom roles and a permission
-- editor are out of scope. No account, password, or session token value is
-- created here: the first SUPER_ADMIN arrives through the one-shot bootstrap
-- registrar, and E2E fixtures stay outside the migration.

CREATE TABLE app.admin_account (
    account_id UUID PRIMARY KEY,
    login_id VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_admin_account_login_id UNIQUE (login_id),
    CONSTRAINT ck_admin_account_login_id CHECK (btrim(login_id) <> ''),
    CONSTRAINT ck_admin_account_role CHECK (role IN ('SUPER_ADMIN', 'GENERAL_ADMIN')),
    CONSTRAINT ck_admin_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    -- The stored form carries its algorithm and cost, so the work factor can be
    -- raised later without invalidating existing rows. A plaintext or foreign
    -- value is rejected by the database rather than only by the application.
    CONSTRAINT ck_admin_account_password_hash CHECK (
        password_hash ~ '^pbkdf2-sha256\$[1-9][0-9]*\$[A-Za-z0-9_-]+\$[A-Za-z0-9_-]+$')
);

-- Only the session digest is persisted. A leaked table cannot replay a session
-- because the presented token is never stored.
CREATE TABLE app.admin_session (
    session_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES app.admin_account(account_id),
    token_digest VARCHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT uq_admin_session_token_digest UNIQUE (token_digest),
    CONSTRAINT ck_admin_session_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_admin_session_revocation CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE INDEX ix_admin_session_account ON app.admin_session (account_id);

-- A GENERAL_ADMIN reaches only assigned Projects. SUPER_ADMIN is platform-global
-- and therefore holds no membership row. The primary key is the account/project
-- uniqueness constraint.
CREATE TABLE app.project_membership (
    account_id UUID NOT NULL REFERENCES app.admin_account(account_id),
    project_id UUID NOT NULL REFERENCES app.project(project_id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_project_membership PRIMARY KEY (account_id, project_id)
);

CREATE INDEX ix_project_membership_project ON app.project_membership (project_id);

-- The read-only reporting role never observes a password hash or session digest.
CREATE VIEW app.admin_account_status AS
SELECT account_id, login_id, role, status, created_at, updated_at FROM app.admin_account;

CREATE VIEW app.project_membership_status AS
SELECT account_id, project_id, assigned_at FROM app.project_membership;

GRANT SELECT, INSERT, UPDATE, DELETE ON
    app.admin_account, app.admin_session, app.project_membership TO cms_app;
GRANT SELECT ON
    app.admin_account_status, app.project_membership_status TO dbeaver_reader;

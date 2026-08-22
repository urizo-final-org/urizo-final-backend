-- AXMS-FND-05: evolve the FND-03 opaque session registry into a refresh-JWT
-- registry. Existing opaque sessions cannot be represented as signed JWTs, so
-- the forward migration revokes them and requires one fresh login.

ALTER TABLE app.admin_session
    ADD COLUMN jwt_id UUID,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN rotated_at TIMESTAMPTZ,
    ADD COLUMN replaced_by_jwt_id UUID;

UPDATE app.admin_session
SET jwt_id = session_id,
    status = 'REVOKED',
    revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
WHERE jwt_id IS NULL;

ALTER TABLE app.admin_session
    ALTER COLUMN jwt_id SET NOT NULL,
    ADD CONSTRAINT uq_admin_session_jwt_id UNIQUE (jwt_id),
    ADD CONSTRAINT ck_admin_session_status
        CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    ADD CONSTRAINT ck_admin_session_rotation
        CHECK (
            (status = 'ACTIVE' AND rotated_at IS NULL AND replaced_by_jwt_id IS NULL)
            OR (status = 'ROTATED' AND rotated_at IS NOT NULL AND replaced_by_jwt_id IS NOT NULL)
            OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
        );

CREATE INDEX ix_admin_session_active_account
    ON app.admin_session (account_id, expires_at)
    WHERE status = 'ACTIVE';

COMMENT ON COLUMN app.admin_session.token_digest IS
    'Base64url SHA-256 digest of the full refresh JWT; the raw JWT is never persisted.';
COMMENT ON COLUMN app.admin_session.jwt_id IS
    'Server-generated jti claim of the refresh JWT.';

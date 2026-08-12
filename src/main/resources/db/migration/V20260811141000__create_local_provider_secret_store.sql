CREATE SCHEMA IF NOT EXISTS app AUTHORIZATION migration_owner;

CREATE TABLE app.local_provider_secret (
    provider VARCHAR(32) PRIMARY KEY,
    encrypted_value BYTEA NOT NULL,
    nonce BYTEA NOT NULL,
    fingerprint VARCHAR(71) NOT NULL,
    key_version SMALLINT NOT NULL,
    connection_state VARCHAR(32) NOT NULL DEFAULT 'STORED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_tested_at TIMESTAMPTZ,
    CONSTRAINT ck_local_provider_secret_provider
        CHECK (provider IN ('OPENAI', 'ANTHROPIC', 'GOOGLE_GENAI')),
    CONSTRAINT ck_local_provider_secret_key_version
        CHECK (key_version > 0),
    CONSTRAINT ck_local_provider_secret_connection_state
        CHECK (connection_state IN (
            'STORED',
            'VERIFIED',
            'BILLING_BLOCKED',
            'INVALID_CREDENTIAL',
            'PROVIDER_UNAVAILABLE'
        )),
    CONSTRAINT ck_local_provider_secret_encrypted_value
        CHECK (octet_length(encrypted_value) BETWEEN 17 AND 4096),
    CONSTRAINT ck_local_provider_secret_nonce
        CHECK (octet_length(nonce) = 12),
    CONSTRAINT ck_local_provider_secret_fingerprint
        CHECK (fingerprint ~ '^hmac-sha256:[0-9a-f]{64}$')
);

CREATE TABLE app.local_provider_connection_audit (
    audit_id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    capability VARCHAR(32) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    safe_error_code VARCHAR(64),
    input_tokens INTEGER,
    output_tokens INTEGER,
    latency_ms INTEGER,
    tested_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_local_provider_connection_audit_provider
        FOREIGN KEY (provider) REFERENCES app.local_provider_secret(provider),
    CONSTRAINT ck_local_provider_connection_audit_capability
        CHECK (capability IN ('CHAT', 'STREAMING', 'TOOL_CALLING', 'STRUCTURED_OUTPUT')),
    CONSTRAINT ck_local_provider_connection_audit_outcome
        CHECK (outcome IN ('PASSED', 'FAILED', 'BILLING_BLOCKED')),
    CONSTRAINT ck_local_provider_connection_audit_tokens
        CHECK ((input_tokens IS NULL OR input_tokens >= 0)
            AND (output_tokens IS NULL OR output_tokens >= 0)),
    CONSTRAINT ck_local_provider_connection_audit_latency
        CHECK (latency_ms IS NULL OR latency_ms >= 0)
);

GRANT USAGE ON SCHEMA app TO cms_app, dbeaver_reader;
GRANT SELECT, INSERT, UPDATE, DELETE ON app.local_provider_secret TO cms_app;
GRANT SELECT, INSERT ON app.local_provider_connection_audit TO cms_app;
GRANT SELECT ON app.local_provider_secret, app.local_provider_connection_audit TO dbeaver_reader;

ALTER TABLE app.local_provider_connection_audit
    DROP CONSTRAINT fk_local_provider_connection_audit_provider;

ALTER TABLE app.local_provider_connection_audit
    ADD CONSTRAINT fk_local_provider_connection_audit_provider
        FOREIGN KEY (provider)
        REFERENCES app.local_provider_secret(provider)
        ON DELETE CASCADE;

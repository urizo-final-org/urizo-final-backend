-- The local-full runtime reports migration readiness without receiving the
-- migration-owner credential. Expose only the two immutable history fields it
-- needs; do not grant table-wide SELECT or any write/DDL privilege.
GRANT SELECT (version, success)
    ON TABLE public.flyway_schema_history
    TO cms_app;

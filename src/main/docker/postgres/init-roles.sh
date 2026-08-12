#!/usr/bin/env bash
set -Eeuo pipefail

migration_password="$(< /run/secrets/migration_owner_password)"
cms_password="$(< /run/secrets/cms_app_password)"
reader_password="$(< /run/secrets/dbeaver_reader_password)"
ai_workspace_password="$(< /run/secrets/ai_workspace_password)"
dev_operator_password="$(< /run/secrets/dev_operator_password)"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=migration_password="$migration_password" \
  --set=cms_password="$cms_password" \
  --set=reader_password="$reader_password" \
  --set=ai_workspace_password="$ai_workspace_password" \
  --set=dev_operator_password="$dev_operator_password" <<'SQL'
SELECT format('CREATE ROLE migration_owner LOGIN PASSWORD %L', :'migration_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'migration_owner') \gexec
SELECT format('ALTER ROLE migration_owner PASSWORD %L', :'migration_password') \gexec

SELECT format('CREATE ROLE cms_app LOGIN PASSWORD %L', :'cms_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'cms_app') \gexec
SELECT format('ALTER ROLE cms_app PASSWORD %L', :'cms_password') \gexec

SELECT format('CREATE ROLE dbeaver_reader LOGIN PASSWORD %L', :'reader_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dbeaver_reader') \gexec
SELECT format('ALTER ROLE dbeaver_reader PASSWORD %L', :'reader_password') \gexec

SELECT format('CREATE ROLE ai_workspace LOGIN PASSWORD %L', :'ai_workspace_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_workspace') \gexec
SELECT format('ALTER ROLE ai_workspace PASSWORD %L', :'ai_workspace_password') \gexec

SELECT format('CREATE ROLE dev_operator LOGIN PASSWORD %L', :'dev_operator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dev_operator') \gexec
SELECT format('ALTER ROLE dev_operator PASSWORD %L', :'dev_operator_password') \gexec

ALTER ROLE migration_owner NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE cms_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE dbeaver_reader NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE ai_workspace NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE dev_operator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE dbeaver_reader SET default_transaction_read_only = on;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT CONNECT ON DATABASE ax_module_studio TO migration_owner, cms_app, dbeaver_reader, ai_workspace, dev_operator;
GRANT CREATE ON DATABASE ax_module_studio TO migration_owner;
GRANT USAGE, CREATE ON SCHEMA public TO migration_owner;
SQL

unset migration_password
unset cms_password
unset reader_password
unset ai_workspace_password
unset dev_operator_password

#!/usr/bin/env bash
set -Eeuo pipefail

ai_workspace_password="$(< /run/secrets/ai_workspace_password)"
dev_operator_password="$(< /run/secrets/dev_operator_password)"

psql --set=ON_ERROR_STOP=1 \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ai_workspace_password="$ai_workspace_password" \
  --set=dev_operator_password="$dev_operator_password" <<'SQL'
SELECT format('CREATE ROLE ai_workspace LOGIN PASSWORD %L', :'ai_workspace_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_workspace') \gexec
SELECT format('ALTER ROLE ai_workspace PASSWORD %L', :'ai_workspace_password') \gexec

SELECT format('CREATE ROLE dev_operator LOGIN PASSWORD %L', :'dev_operator_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dev_operator') \gexec
SELECT format('ALTER ROLE dev_operator PASSWORD %L', :'dev_operator_password') \gexec

ALTER ROLE ai_workspace NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER ROLE dev_operator NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT CONNECT ON DATABASE ax_module_studio TO ai_workspace, dev_operator;
SQL

unset ai_workspace_password
unset dev_operator_password
echo 'Local coding runtime database roles are synchronized.'

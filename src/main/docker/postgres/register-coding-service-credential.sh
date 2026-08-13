#!/usr/bin/env bash
set -Eeuo pipefail

service_token="$(< /run/secrets/coding_model_bridge_service_token)"
dev_operator_password="$(< /run/secrets/dev_operator_password)"

if [[ ${#service_token} -lt 43 || ${#service_token} -gt 512 || "$service_token" =~ [^[:graph:]] ]]; then
  echo 'Local coding service credential has an invalid format.' >&2
  exit 1
fi

credential_digest_hex="$(printf '%s' "$service_token" | sha256sum | cut -d' ' -f1)"
credential_id="$(< /proc/sys/kernel/random/uuid)"

PGPASSWORD="$dev_operator_password" psql --quiet --no-psqlrc --set=ON_ERROR_STOP=1 \
  --host database \
  --username dev_operator \
  --dbname ax_module_studio \
  --set=credential_id="$credential_id" \
  --set=credential_digest_hex="$credential_digest_hex" <<'SQL'
BEGIN;

UPDATE app.coding_service_credential
SET status = 'RETIRING',
    valid_until = LEAST(COALESCE(valid_until, CURRENT_TIMESTAMP + INTERVAL '5 minutes'),
                        CURRENT_TIMESTAMP + INTERVAL '5 minutes')
WHERE status = 'ACTIVE'
  AND credential_digest <> decode(:'credential_digest_hex', 'hex');

INSERT INTO app.coding_service_credential (
    credential_id, credential_digest, label, status, valid_from, valid_until, revoked_at
)
VALUES (
    :'credential_id'::UUID,
    decode(:'credential_digest_hex', 'hex'),
    'local-orchestrator-v1',
    'ACTIVE',
    CURRENT_TIMESTAMP,
    NULL,
    NULL
)
ON CONFLICT (credential_digest) DO UPDATE
SET status = 'ACTIVE',
    valid_from = LEAST(app.coding_service_credential.valid_from, CURRENT_TIMESTAMP),
    valid_until = NULL,
    revoked_at = NULL;

UPDATE app.coding_service_credential
SET status = 'REVOKED', revoked_at = CURRENT_TIMESTAMP
WHERE status = 'RETIRING' AND valid_until <= CURRENT_TIMESTAMP;

COMMIT;
SQL

unset service_token
unset dev_operator_password
unset credential_digest_hex
echo 'Local coding service credential registration completed without displaying credential material.'

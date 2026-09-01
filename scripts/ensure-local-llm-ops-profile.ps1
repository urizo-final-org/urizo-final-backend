[CmdletBinding()]
param(
    [string]$DatabaseContainer,
    [string]$DatabaseName = 'ax_module_studio'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$dockerBinCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'),
    'C:\Program Files\Docker\Docker\resources\bin'
)
$dockerBin = $dockerBinCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ 'docker.exe') } |
    Select-Object -First 1
if (-not $dockerBin) {
    throw 'Docker CLI was not found in an approved Docker Desktop installation path.'
}
$docker = Join-Path $dockerBin 'docker.exe'
if (-not $DatabaseContainer) {
    $composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
    $DatabaseContainer = (& $docker compose -f $composeFile ps -q database).Trim()
}
if (-not $DatabaseContainer) {
    throw 'Local PostgreSQL container was not found.'
}

$snapshotPath = Join-Path $repositoryRoot `
    'contracts\fixtures\orchestration\llm-ops-coding-handler.snapshot.valid.json'
$snapshotJson = [System.IO.File]::ReadAllText($snapshotPath)
$snapshot = $snapshotJson | ConvertFrom-Json
$fixtureProfileVersionId = [string]$snapshot.profileVersionId
if ($snapshot.contractVersion -ne '1.0' -or $snapshot.profileKey -ne 'LLM_OPS' -or
        [string]::IsNullOrWhiteSpace($fixtureProfileVersionId)) {
    throw 'The version-managed local LLM_OPS Profile fixture is invalid.'
}
$snapshotLiteral = $snapshotJson.Replace("'", "''")
$newProfileVersionId = [Guid]::NewGuid().ToString()
$seedSql = @"
BEGIN;
LOCK TABLE app.ai_profile_version IN SHARE ROW EXCLUSIVE MODE;

CREATE TEMP TABLE axms_selected_profile_version (
    profile_version_id UUID PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO axms_selected_profile_version (profile_version_id)
SELECT profile_version_id
FROM app.ai_profile_version
WHERE profile_key = 'LLM_OPS'
  AND status = 'ACTIVE'
  AND (
      snapshot_json - ARRAY[
          'profileVersionId', 'profileVersion'
      ]::text[]
  ) = (
      '$snapshotLiteral'::jsonb - ARRAY[
          'profileVersionId', 'profileVersion'
      ]::text[]
  )
ORDER BY profile_version DESC
LIMIT 1;

UPDATE app.ai_profile_version
SET status = 'INACTIVE'
WHERE profile_key = 'LLM_OPS'
  AND status = 'ACTIVE'
  AND profile_version_id IS DISTINCT FROM (
      SELECT profile_version_id
      FROM axms_selected_profile_version
  );

WITH next_version AS (
    SELECT COALESCE(MAX(profile_version), 0) + 1 AS profile_version
    FROM app.ai_profile_version
    WHERE profile_key = 'LLM_OPS'
), inserted AS (
    INSERT INTO app.ai_profile_version (
        profile_version_id, profile_key, profile_version, snapshot_json
    )
    SELECT
        '$newProfileVersionId'::uuid,
        'LLM_OPS',
        next_version.profile_version,
        jsonb_set(
            jsonb_set(
                '$snapshotLiteral'::jsonb,
                '{profileVersionId}',
                to_jsonb('$newProfileVersionId'::text),
                false
            ),
            '{profileVersion}',
            to_jsonb(next_version.profile_version),
            false
        )
    FROM next_version
    WHERE NOT EXISTS (
        SELECT 1
        FROM axms_selected_profile_version
    )
    RETURNING profile_version_id
)
INSERT INTO axms_selected_profile_version (profile_version_id)
SELECT profile_version_id
FROM inserted;

UPDATE app.ai_profile_version
SET status = 'ACTIVE'
WHERE profile_version_id = (
    SELECT profile_version_id
    FROM axms_selected_profile_version
)
  AND status = 'DRAFT';

SELECT selected.profile_version_id
FROM axms_selected_profile_version selected
JOIN app.ai_profile_version stored
  ON stored.profile_version_id = selected.profile_version_id
WHERE stored.profile_key = 'LLM_OPS'
  AND stored.status = 'ACTIVE'
  AND (
      stored.snapshot_json - ARRAY[
          'profileVersionId', 'profileVersion'
      ]::text[]
  ) = (
      '$snapshotLiteral'::jsonb - ARRAY[
          'profileVersionId', 'profileVersion'
      ]::text[]
  );

COMMIT;
"@
$seedOutput = & $docker exec $DatabaseContainer psql `
    -U bootstrap_admin `
    -d $DatabaseName `
    -v ON_ERROR_STOP=1 `
    -qAtc $seedSql
$seedExitCode = $LASTEXITCODE
$activeProfileVersionId = ([string]$seedOutput).Trim()
$parsedProfileVersionId = [Guid]::Empty
if ($seedExitCode -ne 0 -or
        [string]::IsNullOrWhiteSpace($activeProfileVersionId) -or
        -not [Guid]::TryParse($activeProfileVersionId, [ref]$parsedProfileVersionId)) {
    throw 'A semantic local LLM_OPS Profile Version could not be ensured ACTIVE.'
}

Write-Output $activeProfileVersionId

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
    'contracts\fixtures\orchestration\profile-version.snapshot.valid.json'
$snapshotJson = [System.IO.File]::ReadAllText($snapshotPath)
$snapshot = $snapshotJson | ConvertFrom-Json
$profileVersionId = [string]$snapshot.profileVersionId
if ($snapshot.contractVersion -ne '1.0' -or $snapshot.profileKey -ne 'LLM_OPS' -or
        [string]::IsNullOrWhiteSpace($profileVersionId)) {
    throw 'The version-managed local LLM_OPS Profile fixture is invalid.'
}
$snapshotLiteral = $snapshotJson.Replace("'", "''")
$seedSql = @"
INSERT INTO app.ai_profile_version (
    profile_version_id, profile_key, profile_version, snapshot_json
) VALUES (
    '$profileVersionId',
    'LLM_OPS',
    $([int]$snapshot.profileVersion),
    '$snapshotLiteral'::jsonb
)
ON CONFLICT (profile_version_id) DO NOTHING;
UPDATE app.ai_profile_version
SET status = 'ACTIVE'
WHERE profile_version_id = '$profileVersionId'
  AND profile_key = 'LLM_OPS'
  AND profile_version = $([int]$snapshot.profileVersion)
  AND snapshot_json = '$snapshotLiteral'::jsonb
  AND status = 'DRAFT';
SELECT profile_version_id
FROM app.ai_profile_version
WHERE profile_version_id = '$profileVersionId'
  AND profile_key = 'LLM_OPS'
  AND status = 'ACTIVE'
  AND snapshot_json = '$snapshotLiteral'::jsonb;
"@
$activeProfileVersionId = (& $docker exec $DatabaseContainer psql `
    -U bootstrap_admin `
    -d $DatabaseName `
    -v ON_ERROR_STOP=1 `
    -qAtc $seedSql).Trim()
if ($LASTEXITCODE -ne 0 -or $activeProfileVersionId -ne $profileVersionId) {
    throw 'The fixed local LLM_OPS Profile Version could not be ensured ACTIVE.'
}

Write-Output $profileVersionId

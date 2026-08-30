[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'sync-dev-database-roles.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local database role synchronization failed.'
}

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
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$migrationDirectory = Join-Path $repositoryRoot 'src\main\resources\db\migration'
$migrationVersions = @(Get-ChildItem -LiteralPath $migrationDirectory -File -Filter 'V*__*.sql' |
    ForEach-Object {
        if ($_.Name -notmatch '^V([0-9]{14}|[0-9]{17})__.+\.sql$') {
            throw "Invalid versioned migration filename: $($_.Name)"
        }
        $Matches[1]
    } |
    Sort-Object)
if ($migrationVersions.Count -lt 2) {
    throw 'At least two versioned migrations are required for the previous-revision upgrade gate.'
}
$previousMigrationVersion = $migrationVersions[$migrationVersions.Count - 2]
$databaseContainer = (& $docker compose -f $composeFile ps -q database).Trim()
if (-not $databaseContainer) {
    throw 'Local PostgreSQL container was not found.'
}

$passwordPath = Join-Path $repositoryRoot '.local\secrets\migration_owner_password'
if (-not (Test-Path -LiteralPath $passwordPath)) {
    throw 'Migration credential file is missing.'
}
if (-not $env:JAVA_HOME) {
    $knownJdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
    if (-not (Test-Path -LiteralPath (Join-Path $knownJdk 'bin\java.exe'))) {
        throw 'JDK 21 was not found.'
    }
    $env:JAVA_HOME = $knownJdk
}

$suffix = [Guid]::NewGuid().ToString('N').ToLowerInvariant()
$emptyDatabase = "axms_verify_empty_$suffix"
$upgradeDatabase = "axms_verify_upgrade_$suffix"
$verificationDatabases = @($emptyDatabase, $upgradeDatabase)

function Assert-VerificationDatabaseName([string] $databaseName) {
    if ($databaseName -notmatch '^axms_verify_(empty|upgrade)_[0-9a-f]{32}$') {
        throw "Refusing to operate on an invalid verification database name: $databaseName"
    }
}

function Invoke-AdminSql([string] $databaseName, [string] $sql) {
    & $docker exec $databaseContainer psql `
        -U bootstrap_admin `
        -d $databaseName `
        -v ON_ERROR_STOP=1 `
        -P pager=off `
        -c $sql
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL verification command failed for $databaseName."
    }
}

function Assert-AdminSqlFails([string] $databaseName, [string] $description, [string] $sql) {
    & $docker exec $databaseContainer psql `
        -U bootstrap_admin `
        -d $databaseName `
        -v ON_ERROR_STOP=1 `
        -P pager=off `
        -c $sql
    if ($LASTEXITCODE -eq 0) {
        throw "$description unexpectedly succeeded for $databaseName."
    }
    Write-Host "$description rejection verified for $databaseName."
}

function Get-AdminScalar([string] $databaseName, [string] $sql) {
    $value = (& $docker exec $databaseContainer psql `
        -U bootstrap_admin `
        -d $databaseName `
        -v ON_ERROR_STOP=1 `
        -Atc $sql).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "PostgreSQL scalar verification failed for $databaseName."
    }
    return $value
}

function New-VerificationDatabase([string] $databaseName) {
    Assert-VerificationDatabaseName $databaseName
    Invoke-AdminSql 'postgres' "CREATE DATABASE `"$databaseName`" OWNER migration_owner;"
}

function Remove-VerificationDatabase([string] $databaseName) {
    Assert-VerificationDatabaseName $databaseName
    Invoke-AdminSql 'postgres' "DROP DATABASE IF EXISTS `"$databaseName`" WITH (FORCE);"
}

function Invoke-Flyway([string] $databaseName, [string[]] $arguments) {
    $env:AXMS_DB_URL = "jdbc:postgresql://127.0.0.1:15432/$databaseName"
    $env:AXMS_MIGRATION_PASSWORD = $script:migrationPassword
    Push-Location $repositoryRoot
    try {
        & '.\mvnw.cmd' -o '-Dflyway.validateMigrationNaming=true' @arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Flyway verification failed for $databaseName."
        }
    }
    finally {
        Pop-Location
        Remove-Item Env:AXMS_DB_URL -ErrorAction SilentlyContinue
        Remove-Item Env:AXMS_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    }
}

function Assert-HeadSchema([string] $databaseName) {
    $invalidHistoryCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM (
    SELECT version
    FROM public.flyway_schema_history
    GROUP BY version
    HAVING count(*) <> 1 OR NOT bool_and(success)
) AS invalid_history
'@
    if ($invalidHistoryCount -ne '0') {
        throw "Flyway history is not single-and-successful for $databaseName."
    }

    $authorityTableCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM information_schema.tables
WHERE table_schema = 'app'
  AND table_name IN (
      'coding_service_credential',
      'coding_job',
      'coding_model_turn_idempotency',
      'coding_job_lifecycle_command'
  )
'@
    if ($authorityTableCount -ne '4') {
        throw "Coding authority tables are incomplete for $databaseName."
    }

    $maskedViewCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM information_schema.views
WHERE table_schema = 'app'
  AND table_name IN (
      'coding_service_credential_status',
      'coding_job_status',
      'coding_model_turn_status',
      'coding_job_lifecycle_command_status'
  )
'@
    if ($maskedViewCount -ne '4') {
        throw "Masked coding status views are incomplete for $databaseName."
    }

    $criticalConstraintCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM pg_constraint AS constraint_record
JOIN pg_class AS relation ON relation.oid = constraint_record.conrelid
JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'app'
  AND constraint_record.conname IN (
      'coding_service_credential_credential_digest_key',
      'ck_coding_service_credential_digest',
      'ck_coding_service_credential_revocation',
      'ck_coding_job_state_version',
      'ck_coding_job_context_digest',
      'ck_coding_job_authority_source',
      'ck_coding_job_authoritative_scope',
      'ck_coding_job_authoritative_timestamps',
      'ck_coding_job_authoritative_failure',
      'ck_coding_job_authoritative_time_order',
      'coding_model_turn_idempotency_turn_id_key',
      'fk_coding_model_turn_idempotency_job',
      'ck_coding_model_turn_completion',
      'ck_coding_model_turn_failure',
      'uq_coding_job_lifecycle_command_idempotency',
      'uq_coding_job_lifecycle_command_job_version',
      'fk_coding_job_lifecycle_command_job',
      'ck_coding_job_lifecycle_command_request_digest',
      'ck_coding_job_lifecycle_command_shape',
      'ck_coding_job_lifecycle_command_response'
  )
'@
    if ($criticalConstraintCount -ne '20') {
        throw "Critical coding constraints are incomplete for $databaseName."
    }

    $lifecycleColumnCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM information_schema.columns
WHERE table_schema = 'app'
  AND table_name = 'coding_job'
  AND column_name IN (
      'authority_source',
      'actor_id',
      'project_id',
      'repository_id',
      'graph_step',
      'base_sha',
      'policy_hash',
      'started_at',
      'finished_at',
      'failure_code',
      'failure_retryable'
  )
'@
    if ($lifecycleColumnCount -ne '11') {
        throw "Authoritative coding lifecycle columns are incomplete for $databaseName."
    }

    $stageThreeAndFourTableCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM information_schema.tables
WHERE table_schema IN ('app', 'batch')
  AND table_name IN (
      'project',
      'connector',
      'connector_version',
      'knowledge_base',
      'knowledge_version',
      'source_document',
      'document_chunk',
      'chatbot_config',
      'product_idempotency_command',
      'product_job',
      'transactional_outbox',
      'coding_worker_command',
      'coding_tool_execution',
      'batch_job_instance',
      'batch_job_execution',
      'batch_step_execution'
  )
'@
    if ($stageThreeAndFourTableCount -ne '16') {
        throw "Stage 3/4 product, outbox, batch, and coding worker tables are incomplete for $databaseName."
    }

    $stageThreeAndFourViewCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM information_schema.views
WHERE table_schema = 'app'
  AND table_name IN (
      'project_status',
      'connector_status',
      'knowledge_base_status',
      'knowledge_version_status',
      'product_job_status',
      'transactional_outbox_status',
      'coding_worker_lease_status',
      'coding_tool_execution_status'
  )
'@
    if ($stageThreeAndFourViewCount -ne '8') {
        throw "Stage 3/4 masked status views are incomplete for $databaseName."
    }

    $stageThreeAndFourConstraintCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM pg_constraint AS constraint_record
JOIN pg_class AS relation ON relation.oid = constraint_record.conrelid
JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
WHERE namespace.nspname = 'app'
  AND constraint_record.conname IN (
      'ck_connector_version_digest',
      'fk_connector_active_version',
      'ck_knowledge_version_ready',
      'fk_knowledge_base_active_version',
      'ck_product_idempotency_digest',
      'ck_product_job_terminal_time',
      'ck_outbox_lease',
      'ck_outbox_published',
      'ck_coding_job_worker_lease',
      'uq_coding_worker_command',
      'uq_coding_tool_execution_idempotency',
      'ck_coding_tool_execution_result'
  )
'@
    if ($stageThreeAndFourConstraintCount -ne '12') {
        throw "Stage 3/4 critical constraints are incomplete for $databaseName."
    }

    $claimRenewalPrivilege = Get-AdminScalar $databaseName @'
SELECT has_column_privilege(
           'ai_workspace', 'app.coding_worker_command', 'response_json', 'UPDATE')
       AND NOT has_column_privilege(
           'ai_workspace', 'app.coding_worker_command', 'command_type', 'UPDATE')
'@
    if ($claimRenewalPrivilege -ne 't') {
        throw "Coding claim renewal does not have the required narrow column privilege for $databaseName."
    }

    $migrationReadinessPrivilege = Get-AdminScalar $databaseName @'
SELECT has_column_privilege(
           'cms_app', 'public.flyway_schema_history', 'version', 'SELECT')
       AND has_column_privilege(
           'cms_app', 'public.flyway_schema_history', 'success', 'SELECT')
       AND NOT has_table_privilege(
           'cms_app', 'public.flyway_schema_history', 'SELECT')
       AND NOT has_table_privilege(
           'cms_app', 'public.flyway_schema_history', 'INSERT')
       AND NOT has_table_privilege(
           'cms_app', 'public.flyway_schema_history', 'UPDATE')
       AND NOT has_table_privilege(
           'cms_app', 'public.flyway_schema_history', 'DELETE')
'@
    if ($migrationReadinessPrivilege -ne 't') {
        throw "Runtime migration readiness access is not limited to version/success for $databaseName."
    }

    $profileVersionContract = Get-AdminScalar $databaseName @'
SELECT EXISTS (
           SELECT 1
           FROM information_schema.tables
           WHERE table_schema = 'app'
             AND table_name = 'ai_profile_version')
       AND (
           SELECT count(*) = 6
           FROM information_schema.columns
           WHERE table_schema = 'app'
             AND table_name = 'ai_profile_version'
             AND column_name IN (
                 'profile_version_id', 'profile_key', 'profile_version',
                 'status', 'snapshot_json', 'created_at'))
       AND EXISTS (
           SELECT 1
           FROM pg_trigger AS trigger_record
           JOIN pg_class AS relation ON relation.oid = trigger_record.tgrelid
           JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
           WHERE namespace.nspname = 'app'
             AND relation.relname = 'ai_profile_version'
             AND trigger_record.tgname = 'trg_ai_profile_version_immutable'
             AND NOT trigger_record.tgisinternal)
'@
    if ($profileVersionContract -ne 't') {
        throw "AI Profile Version table or immutable trigger is incomplete for $databaseName."
    }

    $profileVersionPrivilege = Get-AdminScalar $databaseName @'
SELECT has_table_privilege('ai_workspace', 'app.ai_profile_version', 'SELECT')
       AND NOT has_table_privilege('ai_workspace', 'app.ai_profile_version', 'INSERT')
       AND NOT has_table_privilege('ai_workspace', 'app.ai_profile_version', 'UPDATE')
       AND NOT has_table_privilege('ai_workspace', 'app.ai_profile_version', 'DELETE')
'@
    if ($profileVersionPrivilege -ne 't') {
        throw "AI Profile Version access is not read-only for ai_workspace in $databaseName."
    }

    Invoke-AdminSql $databaseName @'
INSERT INTO app.ai_profile_version (
    profile_version_id, profile_key, profile_version, snapshot_json
) VALUES (
    '77777777-7777-4777-8777-777777777777',
    'LLM_OPS',
    1,
    '{"contractVersion":"1.0","profileVersionId":"77777777-7777-4777-8777-777777777777","profileKey":"LLM_OPS","profileVersion":1}'::jsonb
);
UPDATE app.ai_profile_version
SET status = 'ACTIVE'
WHERE profile_version_id = '77777777-7777-4777-8777-777777777777';
UPDATE app.ai_profile_version
SET status = 'INACTIVE'
WHERE profile_version_id = '77777777-7777-4777-8777-777777777777';
'@
    $profileVersionStatus = Get-AdminScalar $databaseName @'
SELECT status
FROM app.ai_profile_version
WHERE profile_version_id = '77777777-7777-4777-8777-777777777777'
'@
    if ($profileVersionStatus -ne 'INACTIVE') {
        throw "AI Profile Version forward status transitions failed for $databaseName."
    }

    Assert-AdminSqlFails $databaseName 'AI Profile Version payload mutation' @'
UPDATE app.ai_profile_version
SET snapshot_json = snapshot_json || '{"unexpected":true}'::jsonb
WHERE profile_version_id = '77777777-7777-4777-8777-777777777777'
'@
    Assert-AdminSqlFails $databaseName 'AI Profile Version deletion' @'
DELETE FROM app.ai_profile_version
WHERE profile_version_id = '77777777-7777-4777-8777-777777777777'
'@

    $criticalIndexCount = Get-AdminScalar $databaseName @'
SELECT count(*)
FROM pg_indexes
WHERE schemaname = 'app'
  AND indexname IN (
      'idx_coding_service_credential_active',
      'idx_coding_job_status_expiry',
      'idx_coding_model_turn_lease',
      'idx_coding_job_project_status',
      'idx_coding_job_lifecycle_command_job_created'
  )
'@
    if ($criticalIndexCount -ne '5') {
        throw "Critical coding indexes are incomplete for $databaseName."
    }

    & $docker exec $databaseContainer psql `
        -U bootstrap_admin `
        -d $databaseName `
        -v ON_ERROR_STOP=1 `
        -P pager=off `
        -c 'SET ROLE ai_workspace; CREATE TABLE app.axms_runtime_ddl_probe (probe_id integer);'
    if ($LASTEXITCODE -eq 0) {
        throw "Runtime DDL unexpectedly succeeded for $databaseName."
    }
    Write-Host "Runtime DDL rejection verified for $databaseName."
}

$migrationPassword = [System.IO.File]::ReadAllText($passwordPath).Trim()
try {
    foreach ($databaseName in $verificationDatabases) {
        New-VerificationDatabase $databaseName
    }

    Invoke-Flyway $emptyDatabase @('flyway:migrate', 'flyway:info')
    Invoke-Flyway $emptyDatabase @('flyway:migrate', 'flyway:info')
    Assert-HeadSchema $emptyDatabase

    Invoke-Flyway $upgradeDatabase @("-Dflyway.target=$previousMigrationVersion", 'flyway:migrate', 'flyway:info')
    Invoke-Flyway $upgradeDatabase @('flyway:migrate', 'flyway:info')
    Invoke-Flyway $upgradeDatabase @('flyway:migrate', 'flyway:info')
    Assert-HeadSchema $upgradeDatabase

    Write-Host 'Core DB empty-head, previous-revision upgrade, repeat, history, constraint, index, and DDL guard verification passed.'
}
finally {
    foreach ($databaseName in $verificationDatabases) {
        try {
            Remove-VerificationDatabase $databaseName
        }
        catch {
            Write-Warning "Verification database cleanup needs attention: $databaseName"
        }
    }
    $migrationPassword = $null
}

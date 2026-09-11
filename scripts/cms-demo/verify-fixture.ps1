[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PythonExecutable,
    [Parameter(Mandatory = $true)][string]$MavenExecutable,
    [Parameter(Mandatory = $true)][string]$MavenRepository,
    [switch]$ApproveIsolatedDatabaseMutation
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not $ApproveIsolatedDatabaseMutation) { throw 'Explicit isolated fixture approval is required.' }
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$docker = (Get-Command docker -ErrorAction Stop).Source
$fixture = 'axms-cms-demo-fixture-' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$fixtureId = $null
function Docker([string[]]$Arguments) {
    $result = @(& $docker @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "Fixture Docker command failed: $($Arguments[0])" }
    return $result
}
function Sql([string]$Database, [string]$Query) {
    $Query | & $docker exec -i $fixture psql -X -qAt -U bootstrap_admin -d $Database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw 'Fixture preparation SQL failed.' }
}
# Existing images only: no network pull, no canonical DB/Compose/Volume access.
$null = Docker @('image','inspect','axms/core-postgres:pg16-vector0.8.5-trusted-dev')
$null = Docker @('image','inspect','axms/spring-migration:dev')
$previousEnv = @{}
$envNames = @('AXMS_CMS_IMPORT_FIXTURE','AXMS_CMS_IMPORT_FIXTURE_JDBC','AXMS_CMS_IMPORT_FIXTURE_PYTHON','AXMS_CMS_IMPORT_FIXTURE_CONTAINER')
foreach ($name in $envNames) { $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $fixtureId = (Docker @('run','-d','--pull=never','--name',$fixture,'--label','axms.work-slug=axms-cms-demo-share-fixture',
        '--publish','127.0.0.1::5432','--tmpfs','/var/lib/postgresql/data:rw,size=768m',
        '-e','POSTGRES_HOST_AUTH_METHOD=trust','-e','POSTGRES_USER=bootstrap_admin','-e','POSTGRES_DB=postgres',
        'axms/core-postgres:pg16-vector0.8.5-trusted-dev')) -join ''
    $ready = $false
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        & $docker exec $fixture pg_isready -U bootstrap_admin -d postgres | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready=$true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $ready) { throw 'Fixture database did not become ready.' }
    Sql 'postgres' 'CREATE ROLE migration_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE ROLE cms_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE ROLE ai_workspace LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE ROLE dev_operator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE ROLE dbeaver_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE; CREATE DATABASE axms_cms_import_fixture OWNER migration_owner;'
    Sql 'axms_cms_import_fixture' 'REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE,CREATE ON SCHEMA public TO migration_owner;'
    $migrationDirectory = Join-Path $source 'src/main/resources/db/migration'
    Docker @('run','--rm','--pull=never','--network',"container:$fixture",'--mount',"type=bind,source=$migrationDirectory,target=/migrations,readonly",
        '--entrypoint','mvn','axms/spring-migration:dev','-o','-B','-ntp','-Dstyle.color=never',
        '-Dflyway.url=jdbc:postgresql://127.0.0.1:5432/axms_cms_import_fixture','-Dflyway.user=migration_owner','-Dflyway.password=',
        '-Dflyway.locations=filesystem:/migrations','-Dflyway.validateMigrationNaming=true','flyway:migrate','flyway:validate','flyway:info')
    $inspection = (Docker @('inspect',$fixture) | ConvertFrom-Json)[0]
    $binding = @($inspection.NetworkSettings.Ports.'5432/tcp')[0]
    if ($binding.HostIp -ne '127.0.0.1') { throw 'Fixture database was not bound to loopback.' }
    [Environment]::SetEnvironmentVariable('AXMS_CMS_IMPORT_FIXTURE','true','Process')
    [Environment]::SetEnvironmentVariable('AXMS_CMS_IMPORT_FIXTURE_JDBC',"jdbc:postgresql://127.0.0.1:$($binding.HostPort)/axms_cms_import_fixture",'Process')
    [Environment]::SetEnvironmentVariable('AXMS_CMS_IMPORT_FIXTURE_PYTHON',$PythonExecutable,'Process')
    [Environment]::SetEnvironmentVariable('AXMS_CMS_IMPORT_FIXTURE_CONTAINER',$fixture,'Process')
    Push-Location -LiteralPath $source
    try {
        & $MavenExecutable -o "-Dmaven.repo.local=$MavenRepository" '-Dtest=CmsDemoImportFixtureTest' test
        if ($LASTEXITCODE -ne 0) { throw 'Isolated CMS import verification failed.' }
    } finally { Pop-Location }
} finally {
    foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name,$previousEnv[$name],'Process') }
    if ($fixtureId) {
        $exact = (Docker @('inspect',$fixtureId) | ConvertFrom-Json)[0]
        if ($exact.Name -ne "/$fixture" -or $exact.Config.Labels.'axms.work-slug' -ne 'axms-cms-demo-share-fixture' -or
            $exact.HostConfig.Tmpfs.PSObject.Properties.Name -notcontains '/var/lib/postgresql/data' -or
            @($exact.Mounts | Where-Object Type -eq 'volume').Count -ne 0) {
            throw 'Fixture cleanup identity validation failed; preserving it.'
        }
        $null = Docker @('rm','-f',$fixtureId)
        Write-Output 'FIXTURE_REMOVED: only this tmpfs-backed test database; canonical containers/volumes preserved.'
    }
}

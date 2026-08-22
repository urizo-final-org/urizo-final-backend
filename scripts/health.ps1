[CmdletBinding()]
param(
    [ValidateSet('spring-core', 'full')]
    [string]$Profile = 'full',

    [switch]$Quick,

    [int]$WaitTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($WaitTimeoutSeconds -lt 5 -or $WaitTimeoutSeconds -gt 1800) {
    throw 'WaitTimeoutSeconds must be between 5 and 1800.'
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $docker = $dockerCommand.Source
}
else {
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
}
$compose = @('compose', '-f', $composeFile, '--profile', $Profile)

function Get-ComposeContainerId {
    param([Parameter(Mandatory = $true)][string]$Service)

    $containerId = (& $docker @compose ps -a -q $Service).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to inspect Compose service: $Service"
    }
    return $containerId
}

function Get-ContainerState {
    param([Parameter(Mandatory = $true)][string]$ContainerId)

    $state = (& $docker inspect --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.State.ExitCode}}' $ContainerId).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Failed to inspect a required local container.'
    }
    return $state
}

function Get-HttpStatus {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
        return [int]$response.StatusCode
    }
    catch {
        $exception = $_.Exception
        if ($exception -and $exception.PSObject.Properties.Match('Response').Count -gt 0 -and $exception.Response) {
            return [int]$exception.Response.StatusCode
        }
        return 0
    }
}

function Assert-HttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][int]$ExpectedStatus
    )

    $actualStatus = Get-HttpStatus -Uri $Uri
    if ($actualStatus -ne $ExpectedStatus) {
        throw "Unexpected HTTP status for $Uri. Expected $ExpectedStatus, received $actualStatus."
    }
}

$requiredCore = @(
    'nginx',
    'frontend',
    'spring-app',
    'database',
    'valkey'
)
$requiredProfile = if ($Profile -eq 'full') {
    @($requiredCore + 'coding-runtime' + 'checkpoint_database')
}
else {
    $requiredCore
}
$requiredLongRunning = @($requiredProfile + 'database_gateway')

$deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitTimeoutSeconds)
do {
    $allHealthy = $true
    foreach ($service in $requiredLongRunning) {
        $containerId = Get-ComposeContainerId -Service $service
        if (-not $containerId) {
            $allHealthy = $false
            continue
        }
        $state = Get-ContainerState -ContainerId $containerId
        if ($state -ne 'running|healthy|0') {
            $allHealthy = $false
        }
    }
    if ($allHealthy) {
        break
    }
    Start-Sleep -Seconds 2
}
while ([DateTimeOffset]::UtcNow -lt $deadline)

if (-not $allHealthy) {
    throw "Timed out waiting for the $Profile services and database gateway to become healthy."
}

$migrationId = Get-ComposeContainerId -Service 'flyway-migration'
if (-not $migrationId) {
    throw 'Flyway one-shot container is missing.'
}
$migrationState = Get-ContainerState -ContainerId $migrationId
if ($migrationState -ne 'exited|none|0') {
    throw "Flyway one-shot container is not Exited (0): $migrationState"
}

if ($Quick) {
    $httpPort = if ($env:AXMS_HTTP_PORT) { [int]$env:AXMS_HTTP_PORT } else { 18080 }
    $baseUri = "http://127.0.0.1:$httpPort"
    Assert-HttpStatus -Uri "$baseUri/nginx-health" -ExpectedStatus 200
    Assert-HttpStatus -Uri "$baseUri/" -ExpectedStatus 200
    Assert-HttpStatus -Uri "$baseUri/api/health" -ExpectedStatus 200
    Assert-HttpStatus -Uri "$baseUri/api/readiness" -ExpectedStatus 200
    Write-Output "$Profile quick health passed; required containers, Flyway and core HTTP endpoints are ready."
    return
}

foreach ($service in @($requiredLongRunning + 'flyway-migration')) {
    $containerId = Get-ComposeContainerId -Service $service
    $limits = (& $docker inspect --format '{{.HostConfig.Memory}}|{{.HostConfig.NanoCpus}}|{{.HostConfig.PidsLimit}}' $containerId).Trim()
    if ($LASTEXITCODE -ne 0 -or $limits -notmatch '^(?<memory>\d+)\|(?<cpu>\d+)\|(?<pids>\d+)$') {
        throw "Unable to verify resource limits for service: $service"
    }
    if ([int64]$Matches.memory -le 0 -or [int64]$Matches.cpu -le 0 -or [int64]$Matches.pids -le 0) {
        throw "Service has an unbounded memory, CPU, or PID setting: $service"
    }
}

$nginxBinding = (& $docker @compose port nginx 80).Trim()
if ($LASTEXITCODE -ne 0 -or $nginxBinding -notmatch '^127\.0\.0\.1:\d+$') {
    throw "Nginx is not published loopback-only: $nginxBinding"
}
$databaseBinding = (& $docker @compose port database_gateway 15432).Trim()
if ($LASTEXITCODE -ne 0 -or $databaseBinding -notmatch '^127\.0\.0\.1:\d+$') {
    throw "PostgreSQL gateway is not published loopback-only: $databaseBinding"
}

$internalOnlyServices = @('frontend', 'spring-app', 'database', 'valkey')
if ($Profile -eq 'full') {
    $internalOnlyServices += @('coding-runtime', 'checkpoint_database')
}
foreach ($service in $internalOnlyServices) {
    $containerId = Get-ComposeContainerId -Service $service
    $publishedPorts = @(& $docker port $containerId)
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to inspect published ports for service: $service"
    }
    if (@($publishedPorts | Where-Object { $_ -and $_.Trim() }).Count -ne 0) {
        throw "Internal-only service unexpectedly publishes a host port: $service"
    }
}

$httpPort = if ($env:AXMS_HTTP_PORT) { [int]$env:AXMS_HTTP_PORT } else { 18080 }
$baseUri = "http://127.0.0.1:$httpPort"
Assert-HttpStatus -Uri "$baseUri/nginx-health" -ExpectedStatus 200
Assert-HttpStatus -Uri "$baseUri/" -ExpectedStatus 200
Assert-HttpStatus -Uri "$baseUri/api/health" -ExpectedStatus 200
Assert-HttpStatus -Uri "$baseUri/api/readiness" -ExpectedStatus 200
Assert-HttpStatus -Uri "$baseUri/actuator/health" -ExpectedStatus 404
Assert-HttpStatus -Uri "$baseUri/internal/not-allowlisted" -ExpectedStatus 404

$databaseId = Get-ComposeContainerId -Service 'database'
$migrationFiles = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'src/main/resources/db/migration') -File -Filter 'V*__*.sql'
$expectedVersions = @(
    $migrationFiles |
        ForEach-Object {
            if ($_.Name -notmatch '^V(?<version>[0-9]+)__[a-z0-9_]+\.sql$') {
                throw "Invalid Flyway migration filename: $($_.Name)"
            }
            $Matches.version
        } |
        Sort-Object
)
if ($expectedVersions.Count -eq 0) {
    throw 'No Flyway migration files were found.'
}

$historySql = "SELECT version FROM public.flyway_schema_history WHERE type = 'SQL' AND success ORDER BY version;"
$appliedVersions = @(
    & $docker exec $databaseId psql --no-psqlrc -U bootstrap_admin -d ax_module_studio -tA -c $historySql |
        Where-Object { $_ -and $_.Trim() } |
        ForEach-Object { $_.Trim() }
)
if ($LASTEXITCODE -ne 0) {
    throw 'Failed to verify Flyway history in the preserved Core DB.'
}

$historyDiff = @(Compare-Object -ReferenceObject $expectedVersions -DifferenceObject $appliedVersions)
if ($historyDiff.Count -ne 0) {
    throw 'Flyway pending/history gate failed: source migrations and successful Core DB history differ.'
}

$invalidHistorySql = "SELECT count(*) FROM public.flyway_schema_history WHERE NOT success OR checksum IS NULL;"
$invalidHistoryCount = (& $docker exec $databaseId psql --no-psqlrc -U bootstrap_admin -d ax_module_studio -tA -c $invalidHistorySql).Trim()
if ($LASTEXITCODE -ne 0 -or $invalidHistoryCount -ne '0') {
    throw 'Flyway checksum/success gate failed.'
}

$duplicateHistorySql = "SELECT count(*) - count(DISTINCT version) FROM public.flyway_schema_history WHERE type = 'SQL';"
$duplicateHistoryCount = (& $docker exec $databaseId psql --no-psqlrc -U bootstrap_admin -d ax_module_studio -tA -c $duplicateHistorySql).Trim()
if ($LASTEXITCODE -ne 0 -or $duplicateHistoryCount -ne '0') {
    throw 'Flyway single-history gate failed.'
}

$latestVersion = $expectedVersions[-1]
Write-Output "$Profile services and the database gateway are healthy; Flyway is Exited (0) at $latestVersion with pending 0."

[CmdletBinding()]
param(
    [ValidateSet('full')]
    [string]$Profile = 'full',

    [switch]$SkipBuild,

    [int]$WaitTimeoutSeconds = 420
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($WaitTimeoutSeconds -lt 30 -or $WaitTimeoutSeconds -gt 1800) {
    throw 'WaitTimeoutSeconds must be between 30 and 1800.'
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $repositoryRoot
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'

if (Test-Path -LiteralPath (Join-Path $workspaceRoot '.git')) {
    throw 'The shared AX Module Studio workspace must not be a Git repository.'
}

foreach ($sibling in @('urizo-final-frontend', 'urizo-final-orchestrator', 'urizo-final-backend')) {
    $siblingPath = Join-Path $workspaceRoot $sibling
    if (-not (Test-Path -LiteralPath $siblingPath -PathType Container)) {
        throw "Required sibling repository is missing: $sibling"
    }
}

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
    # Docker Desktop's credential helper is resolved through PATH by the CLI.
    # Keep this process-scoped so bootstrap works without changing the host environment.
    $env:Path = $dockerBin + [System.IO.Path]::PathSeparator + $env:Path
}

& $docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available.'
}

& (Join-Path $PSScriptRoot 'initialize-dev-secrets.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local secret initialization failed.'
}
& (Join-Path $PSScriptRoot 'initialize-dev-build-trust.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local build trust initialization failed.'
}

$compose = @('compose', '-f', $composeFile, '--profile', $Profile)
$opsCompose = @('compose', '-f', $composeFile, '--profile', 'ops')
& $docker @compose config --quiet
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Compose configuration validation failed.'
}

if (-not $SkipBuild) {
    & $docker @compose build --pull
    if ($LASTEXITCODE -ne 0) {
        throw 'One or more local application images failed to build.'
    }
}

# Existing role files are synchronized without replacing the Core DB or its volume.
& (Join-Path $PSScriptRoot 'sync-dev-database-roles.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local Core DB role synchronization failed.'
}

function Wait-ComposeOneShot {
    param(
        [Parameter(Mandatory = $true)][string[]]$ComposeArguments,
        [Parameter(Mandatory = $true)][string]$Service,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitTimeoutSeconds)
    $containerId = ''
    $containerState = ''
    do {
        $containerId = (& $docker @ComposeArguments ps -a -q $Service).Trim()
        if ($containerId) {
            $containerState = (& $docker inspect --format '{{.State.Status}}|{{.State.ExitCode}}' $containerId).Trim()
            if ($containerState -match '^exited\|(?<exitCode>-?\d+)$') {
                if ([int]$Matches.exitCode -ne 0) {
                    throw "$Label failed with exit code $($Matches.exitCode)."
                }
                return
            }
            if ($containerState -match '^(dead|removing)\|') {
                throw "$Label entered an invalid state: $containerState"
            }
        }
        Start-Sleep -Seconds 2
    }
    while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "Timed out waiting for $Label to exit successfully."
}

# Keep the one-shot container so acceptance can verify Exited (0).
& $docker @compose up -d --force-recreate flyway-migration
if ($LASTEXITCODE -ne 0) {
    throw 'Flyway one-shot service could not be created.'
}
Wait-ComposeOneShot -ComposeArguments $compose -Service 'flyway-migration' -Label 'Flyway one-shot service'

# Register or idempotently reactivate the current rotating service credential
# only after its authoritative Core DB table exists.
& $docker @opsCompose up -d --no-deps --force-recreate coding_credential_registrar
if ($LASTEXITCODE -ne 0) {
    throw 'Coding service credential registrar could not be created.'
}
Wait-ComposeOneShot -ComposeArguments $opsCompose -Service 'coding_credential_registrar' `
    -Label 'Coding service credential registrar'

& $docker @compose up -d --wait --wait-timeout $WaitTimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw 'The full local Compose profile did not become healthy.'
}

& (Join-Path $PSScriptRoot 'health.ps1') -Profile $Profile -WaitTimeoutSeconds $WaitTimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw 'Full local health verification failed.'
}

$httpPort = if ($env:AXMS_HTTP_PORT) { $env:AXMS_HTTP_PORT } else { '18080' }
$postgresPort = if ($env:POSTGRES_HOST_PORT) { $env:POSTGRES_HOST_PORT } else { '15432' }
Write-Output "AX Module Studio full local profile is healthy at http://127.0.0.1:$httpPort/."
Write-Output "Read-only PostgreSQL gateway remains loopback-only at 127.0.0.1:$postgresPort."

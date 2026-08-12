[CmdletBinding()]
param(
    [ValidateSet('up', 'status', 'down')]
    [string]$Action = 'up'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
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
$env:PATH = $dockerBin + ';' + $env:PATH
$docker = Join-Path $dockerBin 'docker.exe'

if ($Action -eq 'up') {
    & (Join-Path $PSScriptRoot 'initialize-dev-secrets.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw 'Local secret initialization failed.'
    }
    & $docker compose -f $composeFile up -d database_gateway
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose failed to start the local database gateway.'
    }

    $containerId = (& $docker compose -f $composeFile ps -q database_gateway).Trim()
    if (-not $containerId) {
        throw 'The local database gateway container was not created.'
    }
    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(2)
    do {
        $health = (& $docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' $containerId).Trim()
        if ($health -eq 'healthy') {
            Write-Output 'Local PostgreSQL is healthy on 127.0.0.1:15432.'
            exit 0
        }
        if ($health -eq 'unhealthy' -or $health -eq 'exited') {
            throw "Local PostgreSQL gateway entered terminal state: $health"
        }
        Start-Sleep -Seconds 2
    }
    while ([DateTimeOffset]::UtcNow -lt $deadline)
    throw 'Timed out waiting for local PostgreSQL gateway health.'
}

if ($Action -eq 'status') {
    & $docker compose -f $composeFile ps
    exit $LASTEXITCODE
}

# Deliberately omit --volumes. Local business and secret metadata survive a normal stop.
& $docker compose -f $composeFile down
exit $LASTEXITCODE

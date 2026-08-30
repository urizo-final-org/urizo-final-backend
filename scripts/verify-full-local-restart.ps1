[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [switch]$ConfirmRestart,

    [int]$WaitTimeoutSeconds = 420
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $ConfirmRestart) {
    throw 'Restart verification requires -ConfirmRestart.'
}

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
$docker = Join-Path $dockerBin 'docker.exe'
$compose = @('compose', '-f', $composeFile, '--profile', 'full')

# Restart never removes containers or volumes. Existing Core DB identity is retained.
& $docker @compose restart database database_gateway valkey checkpoint_database spring-app coding-runtime mcp-server frontend nginx
if ($LASTEXITCODE -ne 0) {
    throw 'One or more required services failed to restart.'
}

& (Join-Path $PSScriptRoot 'bootstrap-dev.ps1') -Profile full -SkipBuild -WaitTimeoutSeconds $WaitTimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw 'Post-restart migration/idempotency bootstrap failed.'
}

& (Join-Path $PSScriptRoot 'verify-full-local-e2e.ps1') -Profile full -WaitTimeoutSeconds $WaitTimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw 'Post-restart full local E2E verification failed.'
}

Write-Output 'Full local restart, repeated Flyway, health, and routing gates passed without replacing volumes.'

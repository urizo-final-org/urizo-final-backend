[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'dev-database.ps1') -Action up
if ($LASTEXITCODE -ne 0) {
    throw 'Local database startup failed.'
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
}
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$databaseContainer = (& $docker compose -f $composeFile ps -q database).Trim()
if (-not $databaseContainer) {
    throw 'Local PostgreSQL container was not found.'
}

& $docker exec $databaseContainer bash /opt/axms/sync-runtime-roles.sh
if ($LASTEXITCODE -ne 0) {
    throw 'Local coding runtime database role synchronization failed.'
}

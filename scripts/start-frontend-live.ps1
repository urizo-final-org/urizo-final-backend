[CmdletBinding()]
param(
    [string]$FrontendSourceRoot,

    [switch]$ApproveLocalMutation,

    [switch]$RestoreImageOnly,

    [ValidateRange(30, 1800)]
    [int]$WaitTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $repositoryRoot
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$liveComposeFile = Join-Path $repositoryRoot 'compose.dev-live.yaml'
$healthScript = Join-Path $PSScriptRoot 'health.ps1'

if (-not $FrontendSourceRoot) {
    $FrontendSourceRoot = Join-Path $workspaceRoot 'urizo-final-frontend'
}

foreach ($requiredFile in @($composeFile, $liveComposeFile, $healthScript)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required Frontend live-development file is missing: $requiredFile"
    }
}
if (-not (Test-Path -LiteralPath $FrontendSourceRoot -PathType Container)) {
    throw "Frontend source root does not exist: $FrontendSourceRoot"
}
$FrontendSourceRoot = (Resolve-Path -LiteralPath $FrontendSourceRoot).Path
foreach ($requiredSource in @('src', 'public', 'index.html', 'package.json', 'pnpm-lock.yaml', 'Dockerfile')) {
    if (-not (Test-Path -LiteralPath (Join-Path $FrontendSourceRoot $requiredSource))) {
        throw "Frontend source root is incomplete; missing: $requiredSource"
    }
}

if (-not $ApproveLocalMutation) {
    throw 'Frontend live development recreates local Frontend and Nginx containers. Re-run after explicit approval with -ApproveLocalMutation.'
}

$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) {
    $docker = $dockerCommand.Source
}
else {
    $dockerBinCandidates = @()
    if ($env:LOCALAPPDATA) {
        $dockerBinCandidates += Join-Path $env:LOCALAPPDATA 'Programs\Docker\Docker\resources\bin'
    }
    $dockerBinCandidates += 'C:\Program Files\Docker\Docker\resources\bin'
    $dockerBin = $dockerBinCandidates |
        Where-Object { Test-Path -LiteralPath (Join-Path $_ 'docker.exe') } |
        Select-Object -First 1
    if (-not $dockerBin) {
        throw 'Docker CLI was not found. On macOS/Linux it must be available on PATH.'
    }
    $docker = Join-Path $dockerBin 'docker.exe'
    $env:Path = $dockerBin + [System.IO.Path]::PathSeparator + $env:Path
}

& $docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available.'
}

$composeVersionText = (& $docker compose version --short).Trim()
if ($LASTEXITCODE -ne 0 -or $composeVersionText -notmatch '(?<major>\d+)\.(?<minor>\d+)\.(?<patch>\d+)') {
    throw "Unable to read Docker Compose version: $composeVersionText"
}
$composeVersion = [Version]::new(
    [int]$Matches.major,
    [int]$Matches.minor,
    [int]$Matches.patch
)
if ($composeVersion -lt [Version]'2.22.0') {
    throw "Docker Compose 2.22.0 or newer is required for Watch; found $composeVersionText."
}

$baseCompose = @('compose', '-f', $composeFile, '--profile', 'spring-core')
$liveCompose = @('compose', '-f', $composeFile, '-f', $liveComposeFile, '--profile', 'spring-core')

function Invoke-QuickHealth {
    & $healthScript -Profile spring-core -Quick -WaitTimeoutSeconds $WaitTimeoutSeconds
    if ($LASTEXITCODE -ne 0) {
        throw 'spring-core quick health failed.'
    }
}

function Restore-ImageOnlyFrontend {
    $frontendContainerId = (& $docker @baseCompose ps -q frontend).Trim()
    if ($LASTEXITCODE -eq 0 -and $frontendContainerId) {
        $readOnlyState = (& $docker inspect --format '{{.HostConfig.ReadonlyRootfs}}' $frontendContainerId).Trim()
        if ($LASTEXITCODE -eq 0 -and $readOnlyState -eq 'true') {
            Invoke-QuickHealth
            Write-Output 'FRONTEND LIVE STOP PASS: the Frontend is already image-only and healthy.'
            return
        }
    }

    Write-Output 'Restoring the image-only Frontend container...'
    & $docker @baseCompose up -d --no-build --no-deps --force-recreate --wait --wait-timeout $WaitTimeoutSeconds frontend
    if ($LASTEXITCODE -ne 0) {
        throw 'Image-only Frontend container restoration failed.'
    }
    & $docker @baseCompose restart nginx
    if ($LASTEXITCODE -ne 0) {
        throw 'Nginx restart failed after Frontend restoration.'
    }
    Invoke-QuickHealth
    Write-Output 'FRONTEND LIVE STOP PASS: restored the image-only Frontend and healthy ingress.'
}

if ($RestoreImageOnly) {
    Restore-ImageOnlyFrontend
    return
}

try {
    Invoke-QuickHealth
}
catch {
    throw "Frontend live development requires an already healthy spring-core runtime. Start it through the Master CMS script first. $($_.Exception.Message)"
}

$previousLiveSource = $env:AXMS_FRONTEND_LIVE_SOURCE
$env:AXMS_FRONTEND_LIVE_SOURCE = $FrontendSourceRoot.Replace('\', '/')
try {
    & $docker @liveCompose config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'Frontend live Compose configuration validation failed.'
    }

    & $docker @liveCompose up -d --no-build --no-deps --force-recreate --wait --wait-timeout $WaitTimeoutSeconds frontend
    if ($LASTEXITCODE -ne 0) {
        throw 'Frontend live container recreation failed.'
    }
    & $docker @baseCompose restart nginx
    if ($LASTEXITCODE -ne 0) {
        throw 'Nginx restart failed after Frontend live activation.'
    }
    Invoke-QuickHealth

    Write-Output 'FRONTEND LIVE START PASS: Compose Watch is active for one Frontend source root.'
    Write-Output "Frontend source: $FrontendSourceRoot"
    Write-Output 'Press Ctrl+C to stop Watch, then run this script with -RestoreImageOnly.'
    Write-Output 'The runner also attempts restoration on a normal shell exit; -RestoreImageOnly is idempotent.'

    try {
        & $docker @liveCompose watch --no-up frontend
        if ($LASTEXITCODE -ne 0) {
            throw "Docker Compose Watch stopped with exit code $LASTEXITCODE."
        }
    }
    finally {
        Restore-ImageOnlyFrontend
    }
}
finally {
    $env:AXMS_FRONTEND_LIVE_SOURCE = $previousLiveSource
}

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('spring-app', 'frontend', 'coding-runtime', 'mcp-server')]
    [string]$Service,

    [ValidateSet('spring-core', 'full')]
    [string]$Profile = 'full',

    [string]$SourceRoot,

    [switch]$ApproveLocalMutation,

    [switch]$ApproveNetwork,

    [ValidateRange(30, 1800)]
    [int]$WaitTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$allowedProfiles = @{
    'spring-app' = @('spring-core', 'full')
    'frontend' = @('spring-core', 'full')
    'coding-runtime' = @('full')
    'mcp-server' = @('full')
}
if ($Profile -notin $allowedProfiles[$Service]) {
    throw "Service '$Service' is not part of the '$Profile' profile."
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $repositoryRoot
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$buildTrustComposeFile = Join-Path $repositoryRoot 'compose.dev-build-trust.yaml'
$healthScript = Join-Path $PSScriptRoot 'health.ps1'
$buildTrustScript = Join-Path $PSScriptRoot 'initialize-dev-build-trust.ps1'

foreach ($requiredFile in @($composeFile, $healthScript)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required partial-rebuild file is missing: $requiredFile"
    }
}

$probeTimeoutSeconds = [Math]::Min(10, $WaitTimeoutSeconds)
try {
    & $healthScript -Profile $Profile -Quick -WaitTimeoutSeconds $probeTimeoutSeconds
    if ($LASTEXITCODE -ne 0) {
        throw "$Profile quick health failed."
    }
}
catch {
    throw "Partial rebuild requires an already healthy $Profile runtime. Use the full local-start script instead. $($_.Exception.Message)"
}

if (-not $ApproveLocalMutation) {
    throw "Rebuilding '$Service' changes its local image and container. Re-run only after explicit approval with -ApproveLocalMutation."
}
if (-not $ApproveNetwork) {
    throw "Rebuilding '$Service' may download build dependencies or base images. Re-run only after network approval with -ApproveNetwork."
}

$sourceContracts = @{
    'spring-app' = [pscustomobject]@{
        Label = 'Backend'
        EnvironmentName = 'AXMS_BACKEND_SOURCE_ROOT'
        DefaultPath = $repositoryRoot
        RequiredEntries = @('Dockerfile', 'pom.xml', 'src')
    }
    'frontend' = [pscustomobject]@{
        Label = 'Frontend'
        EnvironmentName = 'AXMS_FRONTEND_SOURCE_ROOT'
        DefaultPath = Join-Path $workspaceRoot 'urizo-final-frontend'
        RequiredEntries = @('Dockerfile', 'package.json', 'pnpm-lock.yaml', 'src')
    }
    'coding-runtime' = [pscustomobject]@{
        Label = 'Orchestrator'
        EnvironmentName = 'AXMS_ORCHESTRATOR_SOURCE_ROOT'
        DefaultPath = Join-Path $workspaceRoot 'urizo-final-orchestrator'
        RequiredEntries = @('Dockerfile', 'pyproject.toml', 'uv.lock', 'src')
    }
    'mcp-server' = [pscustomobject]@{
        Label = 'MCP Server'
        EnvironmentName = 'AXMS_MCP_SOURCE_ROOT'
        DefaultPath = Join-Path $workspaceRoot 'urizo-final-mcp-server'
        RequiredEntries = @('Dockerfile', 'pyproject.toml', 'uv.lock', 'src')
    }
}
$sourceContract = $sourceContracts[$Service]
if (-not $SourceRoot) {
    $SourceRoot = $sourceContract.DefaultPath
}
if (-not (Test-Path -LiteralPath $SourceRoot -PathType Container)) {
    throw "$($sourceContract.Label) source root does not exist: $SourceRoot"
}
$SourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
foreach ($entry in $sourceContract.RequiredEntries) {
    if (-not (Test-Path -LiteralPath (Join-Path $SourceRoot $entry))) {
        throw "$($sourceContract.Label) source root is incomplete; missing '$entry': $SourceRoot"
    }
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

$composeFiles = @($composeFile)
if ($Service -in @('spring-app', 'frontend')) {
    foreach ($requiredFile in @($buildTrustComposeFile, $buildTrustScript)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
            throw "Required partial-rebuild trust file is missing: $requiredFile"
        }
    }
    & $buildTrustScript
    if ($LASTEXITCODE -ne 0) {
        throw 'Opt-in local build trust initialization failed.'
    }
    $composeFiles += $buildTrustComposeFile
}

$compose = @('compose')
foreach ($file in $composeFiles) {
    $compose += @('-f', $file)
}
$compose += @('--profile', $Profile)
$sourceEnvironmentName = $sourceContract.EnvironmentName
$previousSourceRoot = [Environment]::GetEnvironmentVariable($sourceEnvironmentName, 'Process')
try {
    $composeSourceRoot = $SourceRoot.Replace('\', '/')
    [Environment]::SetEnvironmentVariable($sourceEnvironmentName, $composeSourceRoot, 'Process')
    Write-Output "Build source: $sourceEnvironmentName=$composeSourceRoot"

    & $docker @compose config --quiet
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose configuration validation failed.'
    }

    & $docker @compose build --pull $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Image build failed for '$Service'."
    }

    & $docker @compose up -d --no-deps --force-recreate --wait --wait-timeout $WaitTimeoutSeconds $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Container recreation failed for '$Service'."
    }

    & $healthScript -Profile $Profile -WaitTimeoutSeconds $WaitTimeoutSeconds
    if ($LASTEXITCODE -ne 0) {
        throw "$Profile health verification failed after rebuilding '$Service'."
    }

    Write-Output "LOCAL SERVICE REBUILD PASS: rebuilt '$Service' from '$composeSourceRoot', recreated its container, and passed $Profile health."
}
finally {
    [Environment]::SetEnvironmentVariable($sourceEnvironmentName, $previousSourceRoot, 'Process')
}

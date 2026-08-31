[CmdletBinding()]
param(
    [ValidateSet('spring-core', 'full')]
    [string]$Profile = 'spring-core',

    [string]$BackendSourceRoot,

    [string]$FrontendSourceRoot,

    [string]$OrchestratorSourceRoot,

    [string]$McpSourceRoot,

    [switch]$ApproveLocalMutation,

    [switch]$ApproveNetwork,

    [switch]$Rebuild,

    [ValidateRange(30, 1800)]
    [int]$WaitTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspaceRoot = Split-Path -Parent $repositoryRoot
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$healthScript = Join-Path $PSScriptRoot 'health.ps1'
$bootstrapScript = Join-Path $PSScriptRoot 'bootstrap-dev.ps1'

foreach ($requiredFile in @($composeFile, $healthScript, $bootstrapScript)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required CMS local runtime file is missing: $requiredFile"
    }
}

if ($Profile -eq 'spring-core' -and ($OrchestratorSourceRoot -or $McpSourceRoot)) {
    throw 'OrchestratorSourceRoot and McpSourceRoot are valid only with -Profile full.'
}

$requestedSourceRoots = @(
    $BackendSourceRoot,
    $FrontendSourceRoot,
    $OrchestratorSourceRoot,
    $McpSourceRoot
)
$sourceBindingRequested = @($requestedSourceRoots | Where-Object { $_ }).Count -gt 0

$probeTimeoutSeconds = [Math]::Min(10, $WaitTimeoutSeconds)
$probeFailure = ''
try {
    $healthOutput = @(& $healthScript -Profile $Profile -Quick -WaitTimeoutSeconds $probeTimeoutSeconds)
    if ($LASTEXITCODE -eq 0 -and -not $Rebuild -and -not $sourceBindingRequested) {
        $healthOutput | Write-Output
        Write-Output "LOCAL START PASS: reused the already healthy $Profile containers."
        Write-Output 'CMS URL: http://127.0.0.1:18080/'
        return
    }
}
catch {
    $probeFailure = $_.Exception.Message
}

if (-not $ApproveLocalMutation) {
    $detail = if ($probeFailure) { " Last health error: $probeFailure" } else { '' }
    throw "$Profile is not healthy. Re-run after an explicit local-start request with -ApproveLocalMutation.$detail"
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
    $env:Path = $dockerBin + [System.IO.Path]::PathSeparator + $env:Path
}

& $docker info --format '{{.ServerVersion}}' | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Docker Engine is not available.'
}

$requiredImages = @(
    & $docker compose -f $composeFile --profile $Profile config --images |
        Where-Object { $_ -and $_.Trim() } |
        Sort-Object -Unique
)
if ($LASTEXITCODE -ne 0 -or $requiredImages.Count -eq 0) {
    throw "Could not resolve the $Profile image set from Docker Compose."
}

$missingImages = [System.Collections.Generic.List[string]]::new()
foreach ($image in $requiredImages) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $docker image inspect $image *> $null
        $imageInspectExitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($imageInspectExitCode -ne 0) {
        $missingImages.Add($image)
    }
}

$buildRequired = $Rebuild -or $sourceBindingRequested -or $missingImages.Count -gt 0
if ($buildRequired -and -not $ApproveNetwork) {
    $reason = if ($Rebuild) {
        'a rebuild was requested'
    }
    elseif ($sourceBindingRequested) {
        'active Source worktrees were supplied'
    }
    else {
        "$($missingImages.Count) required image(s) are missing"
    }
    throw "$Profile image build/download is required because $reason. Re-run with -ApproveNetwork after network use is approved."
}

function Resolve-BuildSourceRoot {
    param(
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string[]]$RequiredEntries
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$Label source root does not exist: $Path"
    }
    $resolvedPath = (Resolve-Path -LiteralPath $Path).Path
    foreach ($entry in $RequiredEntries) {
        if (-not (Test-Path -LiteralPath (Join-Path $resolvedPath $entry))) {
            throw "$Label source root is incomplete; missing '$entry': $resolvedPath"
        }
    }
    return $resolvedPath
}

$sourceEnvironment = @{}
if ($buildRequired) {
    if (-not $BackendSourceRoot) { $BackendSourceRoot = $repositoryRoot }
    if (-not $FrontendSourceRoot) { $FrontendSourceRoot = Join-Path $workspaceRoot 'urizo-final-frontend' }
    $BackendSourceRoot = Resolve-BuildSourceRoot -Label 'Backend' -Path $BackendSourceRoot `
        -RequiredEntries @('Dockerfile', 'pom.xml', 'src')
    $FrontendSourceRoot = Resolve-BuildSourceRoot -Label 'Frontend' -Path $FrontendSourceRoot `
        -RequiredEntries @('Dockerfile', 'package.json', 'pnpm-lock.yaml', 'src')
    $sourceEnvironment['AXMS_BACKEND_SOURCE_ROOT'] = $BackendSourceRoot.Replace('\', '/')
    $sourceEnvironment['AXMS_FRONTEND_SOURCE_ROOT'] = $FrontendSourceRoot.Replace('\', '/')

    if ($Profile -eq 'full') {
        if (-not $OrchestratorSourceRoot) { $OrchestratorSourceRoot = Join-Path $workspaceRoot 'urizo-final-orchestrator' }
        if (-not $McpSourceRoot) { $McpSourceRoot = Join-Path $workspaceRoot 'urizo-final-mcp-server' }
        $OrchestratorSourceRoot = Resolve-BuildSourceRoot -Label 'Orchestrator' -Path $OrchestratorSourceRoot `
            -RequiredEntries @('Dockerfile', 'pyproject.toml', 'uv.lock', 'src')
        $McpSourceRoot = Resolve-BuildSourceRoot -Label 'MCP Server' -Path $McpSourceRoot `
            -RequiredEntries @('Dockerfile', 'pyproject.toml', 'uv.lock', 'src')
        $sourceEnvironment['AXMS_ORCHESTRATOR_SOURCE_ROOT'] = $OrchestratorSourceRoot.Replace('\', '/')
        $sourceEnvironment['AXMS_MCP_SOURCE_ROOT'] = $McpSourceRoot.Replace('\', '/')
    }
}

$bootstrapArguments = @{
    Profile = $Profile
    WaitTimeoutSeconds = $WaitTimeoutSeconds
}
if ($buildRequired) {
    # Use the ignored host CA bundle so Maven and Node builds follow one reproducible path
    # on both normal networks and TLS-intercepted teammate environments.
    $bootstrapArguments.EnableHostBuildTrust = $true
}
else {
    $bootstrapArguments.SkipBuild = $true
}

$previousSourceEnvironment = @{}
try {
    foreach ($entry in $sourceEnvironment.GetEnumerator()) {
        $previousSourceEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
        Write-Output "Build source: $($entry.Key)=$($entry.Value)"
    }

    & $bootstrapScript @bootstrapArguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Profile bootstrap failed. Use the first reported script error; do not replace this flow with ad-hoc Docker commands."
    }
}
finally {
    foreach ($entry in $previousSourceEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
}

Write-Output "LOCAL START PASS: $Profile is ready."
Write-Output 'CMS URL: http://127.0.0.1:18080/'

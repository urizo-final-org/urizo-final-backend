[CmdletBinding()]
param(
    [switch]$ApproveLocalMutation,

    [switch]$ApproveNetwork,

    [switch]$Rebuild,

    [ValidateRange(30, 1800)]
    [int]$WaitTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$healthScript = Join-Path $PSScriptRoot 'health.ps1'
$bootstrapScript = Join-Path $PSScriptRoot 'bootstrap-dev.ps1'

foreach ($requiredFile in @($composeFile, $healthScript, $bootstrapScript)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required CMS local runtime file is missing: $requiredFile"
    }
}

$probeTimeoutSeconds = [Math]::Min(10, $WaitTimeoutSeconds)
$probeFailure = ''
try {
    $healthOutput = @(& $healthScript -Profile spring-core -Quick -WaitTimeoutSeconds $probeTimeoutSeconds)
    if ($LASTEXITCODE -eq 0 -and -not $Rebuild) {
        $healthOutput | Write-Output
        Write-Output 'CMS LOCAL START PASS: reused the already healthy spring-core containers.'
        Write-Output 'CMS URL: http://127.0.0.1:18080/'
        return
    }
}
catch {
    $probeFailure = $_.Exception.Message
}

if (-not $ApproveLocalMutation) {
    $detail = if ($probeFailure) { " Last health error: $probeFailure" } else { '' }
    throw "CMS spring-core is not healthy. Re-run after an explicit local-start request with -ApproveLocalMutation.$detail"
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
    & $docker compose -f $composeFile --profile spring-core config --images |
        Where-Object { $_ -and $_.Trim() } |
        Sort-Object -Unique
)
if ($LASTEXITCODE -ne 0 -or $requiredImages.Count -eq 0) {
    throw 'Could not resolve the spring-core image set from Docker Compose.'
}

$missingImages = [System.Collections.Generic.List[string]]::new()
foreach ($image in $requiredImages) {
    & $docker image inspect $image *> $null
    if ($LASTEXITCODE -ne 0) {
        $missingImages.Add($image)
    }
}

$buildRequired = $Rebuild -or $missingImages.Count -gt 0
if ($buildRequired -and -not $ApproveNetwork) {
    $reason = if ($Rebuild) { 'a rebuild was requested' } else { "$($missingImages.Count) required image(s) are missing" }
    throw "CMS image build/download is required because $reason. Re-run with -ApproveNetwork after network use is approved."
}

$bootstrapArguments = @{
    Profile = 'spring-core'
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

& $bootstrapScript @bootstrapArguments
if ($LASTEXITCODE -ne 0) {
    throw 'CMS spring-core bootstrap failed. Use the first reported script error; do not replace this flow with ad-hoc Docker commands.'
}

Write-Output 'CMS LOCAL START PASS: spring-core is ready.'
Write-Output 'CMS URL: http://127.0.0.1:18080/'

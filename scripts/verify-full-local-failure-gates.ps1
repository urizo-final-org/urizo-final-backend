[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [switch]$ConfirmFailureInjection,

    [int]$RecoveryTimeoutSeconds = 240
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $ConfirmFailureInjection) {
    throw 'Failure-gate verification requires -ConfirmFailureInjection.'
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
$httpPort = if ($env:AXMS_HTTP_PORT) { [int]$env:AXMS_HTTP_PORT } else { 18080 }
$baseUri = "http://127.0.0.1:$httpPort"
$stoppedServices = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)

function Get-HttpStatus {
    param([Parameter(Mandatory = $true)][string]$Uri)

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 5
        return [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

function Stop-TestService {
    param([Parameter(Mandatory = $true)][string]$Service)

    & $docker @compose stop --timeout 20 $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to stop service for a bounded failure test: $Service"
    }
    [void]$stoppedServices.Add($Service)
}

function Start-TestService {
    param([Parameter(Mandatory = $true)][string]$Service)

    & $docker @compose start $Service
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to restore service after a bounded failure test: $Service"
    }
    [void]$stoppedServices.Remove($Service)
}

try {
    & (Join-Path $PSScriptRoot 'health.ps1') -Profile full -WaitTimeoutSeconds $RecoveryTimeoutSeconds

    Stop-TestService -Service 'valkey'
    Start-Sleep -Seconds 5
    if ((Get-HttpStatus -Uri "$baseUri/api/readiness") -eq 200) {
        throw 'Spring readiness remained healthy while the required Valkey dependency was stopped.'
    }
    Start-TestService -Service 'valkey'
    & (Join-Path $PSScriptRoot 'health.ps1') -Profile full -WaitTimeoutSeconds $RecoveryTimeoutSeconds

    Stop-TestService -Service 'checkpoint_database'
    Start-Sleep -Seconds 5
    $codingRuntimeId = (& $docker @compose ps -a -q coding-runtime).Trim()
    & $docker exec $codingRuntimeId python -c `
        "import http.client; connection = http.client.HTTPConnection('127.0.0.1', 8090, timeout=3); connection.request('GET', '/health/ready'); response = connection.getresponse(); raise SystemExit(0 if response.status == 200 else 1)" `
        2>$null | Out-Null
    $codingReadinessExitCode = $LASTEXITCODE
    if ($codingReadinessExitCode -eq 0) {
        throw 'Coding Runtime remained ready while its Checkpoint DB was stopped.'
    }
    if ((Get-HttpStatus -Uri "$baseUri/api/health") -ne 200) {
        throw 'Checkpoint DB failure incorrectly changed Spring Core health.'
    }
    Start-TestService -Service 'checkpoint_database'
    & (Join-Path $PSScriptRoot 'health.ps1') -Profile full -WaitTimeoutSeconds $RecoveryTimeoutSeconds

    Stop-TestService -Service 'spring-app'
    Start-Sleep -Seconds 3
    if ((Get-HttpStatus -Uri "$baseUri/api/health") -eq 200) {
        throw 'Nginx continued to report Spring API success while Spring was stopped.'
    }
    Start-TestService -Service 'spring-app'
    & (Join-Path $PSScriptRoot 'health.ps1') -Profile full -WaitTimeoutSeconds $RecoveryTimeoutSeconds
}
finally {
    foreach ($service in @($stoppedServices)) {
        & $docker @compose start $service | Out-Null
    }
}

Write-Output 'Valkey, Checkpoint DB, and Spring bounded failure/recovery gates passed.'

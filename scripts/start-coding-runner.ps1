[CmdletBinding()]
param(
    [string]$BaseUri,
    [ValidateRange(5, 60)][int]$WaitTimeoutSeconds = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-RunnerWorkspace {
    param([string]$RepositoryRoot)
    $candidate = Split-Path -Parent $RepositoryRoot
    while ($candidate) {
        if ((Test-Path -LiteralPath (Join-Path $candidate 'urizo-final-backend') -PathType Container) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'urizo-final-frontend') -PathType Container)) {
            return $candidate
        }
        $candidate = Split-Path -Parent $candidate
    }
    throw 'Coding Runner requires the canonical Backend/Frontend workspace layout.'
}

function ConvertTo-RunnerHostPath {
    param([string]$Path)
    if ($Path -match '^/(?:run/desktop/mnt/host|host_mnt)/([a-zA-Z])/(.+)$') {
        return ($Matches[1] + ':/' + $Matches[2]).Replace('/', '\')
    }
    return $Path
}

function Get-CodingRunnerProcesses {
    # Fail closed if process inspection is unavailable; never risk a second claimant.
    return @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match '(?i)[\\/]runner\.ps1(?:"|\s|$)' })
}

function Start-CodingRunner {
    param([string]$RepositoryRoot, [string]$TargetUri, [int]$TimeoutSeconds)
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        Write-Output 'CODING RUNNER SKIPPED: automatic host startup is Windows-only.'
        return
    }
    $runnerPath = Join-Path $RepositoryRoot 'scripts/runner.ps1'
    $workRoot = Join-Path (Resolve-RunnerWorkspace -RepositoryRoot $RepositoryRoot) '.worktrees'
    $composeFile = Join-Path $RepositoryRoot 'compose.dev.yaml'
    $dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
    $docker = if ($dockerCommand) { $dockerCommand.Source } else {
        @((Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
            'C:/Program Files/Docker/Docker/resources/bin/docker.exe') |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    }
    if (-not $docker) { throw 'Docker CLI is required to resolve the active Runner credential path.' }
    $containerId = (& $docker compose -f $composeFile --profile full ps -q coding-runtime) -join ''
    if ($LASTEXITCODE -ne 0 -or -not $containerId) { throw 'The full Coding Runtime is not running.' }
    # Inspect paths only, never credential contents. A reused stack may belong to another worktree.
    $mountJson = (& $docker inspect --format '{{json .Mounts}}' $containerId) -join ''
    if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect the active Coding Runtime credential mount.' }
    $mounts = $mountJson | ConvertFrom-Json
    $tokenMounts = @($mounts | Where-Object {
        $_.Type -eq 'bind' -and $_.Destination -eq '/run/secrets/coding_model_bridge_service_token'
    })
    if ($tokenMounts.Count -ne 1) { throw 'Expected exactly one active Coding Runtime credential mount.' }
    $tokenPath = ConvertTo-RunnerHostPath -Path $tokenMounts[0].Source
    if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
        throw 'The active Coding Runtime credential is not accessible from this Windows host.'
    }
    $secretsRoot = Split-Path -Parent (Resolve-Path -LiteralPath $tokenPath).Path
    $arguments = @('-BaseUri', $TargetUri.TrimEnd('/'), '-WorkRoot', $workRoot,
        '-SecretsRoot', $secretsRoot)
    # Start-Process joins ArgumentList into a native command line. Quote every path/value.
    foreach ($value in @($runnerPath) + $arguments) {
        if ($value -match '["\r\n]') { throw 'Runner paths and arguments must not contain quotes or newlines.' }
    }
    $bindingArguments = ($arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $launchLock = [Threading.Mutex]::new($false, 'Local\AXMS-CodingRunner-Startup')
    $lockHeld = $false
    try {
        try { $lockHeld = $launchLock.WaitOne(30000) }
        catch [Threading.AbandonedMutexException] { $lockHeld = $true }
        if (-not $lockHeld) { throw 'Another Coding Runner startup is still in progress. Retry after it finishes.' }
        $existing = @(Get-CodingRunnerProcesses)
        if ($existing.Count -gt 0) {
            if ($existing.Count -eq 1 -and
                $existing[0].CommandLine.IndexOf('"' + $runnerPath + '"', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                $existing[0].CommandLine.IndexOf($bindingArguments, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                if ($existing[0].CommandLine -notmatch '-StartupSignalPath "([^"]+)"' -or
                    -not (Test-Path -LiteralPath $Matches[1] -PathType Leaf) -or
                    (Get-Content -LiteralPath $Matches[1] -Raw).Trim() -ne [string]$existing[0].ProcessId) {
                    throw 'The existing Coding Runner has not confirmed its first poll. Process preserved; inspect its startup logs.'
                }
                Write-Output "CODING RUNNER REUSED: PID=$($existing[0].ProcessId); existing process preserved."
                return
            }
            throw 'An existing Coding Runner has a different or unverified binding. It was preserved; no duplicate was started.'
        }
        $stateRoot = Join-Path $RepositoryRoot '.local/runner'
        New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
        $launchId = [guid]::NewGuid().ToString('N')
        $readyPath = Join-Path $stateRoot "$launchId.ready"
        $outputPath = Join-Path $stateRoot "$launchId.stdout.log"
        $errorPath = Join-Path $stateRoot "$launchId.stderr.log"
        $shellPath = (Get-Process -Id $PID).Path
        $commandLine = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $runnerPath +
            '" ' + $bindingArguments + ' -StartupSignalPath "' + $readyPath + '"'
        $previousPath = $env:Path
        try {
            $env:Path = (Split-Path -Parent $docker) + [IO.Path]::PathSeparator + $env:Path
            $child = Start-Process -FilePath $shellPath -ArgumentList $commandLine -WorkingDirectory $RepositoryRoot `
                -WindowStyle Hidden -RedirectStandardOutput $outputPath -RedirectStandardError $errorPath -PassThru
        }
        finally { $env:Path = $previousPath }
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            $child.Refresh()
            if ($child.HasExited) {
                throw "Coding Runner exited during startup (PID=$($child.Id)); inspect $errorPath"
            }
            if ((Test-Path -LiteralPath $readyPath -PathType Leaf) -and
                (Get-Content -LiteralPath $readyPath -Raw).Trim() -eq [string]$child.Id) {
                Write-Output "CODING RUNNER STARTED: PID=$($child.Id); first authenticated poll succeeded."
                Write-Output "Runner logs: $outputPath ; $errorPath"
                return
            }
            Start-Sleep -Milliseconds 250
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        # It may already be executing a task. Never terminate it just because the startup wait expired.
        throw "Coding Runner has not confirmed its first poll (PID=$($child.Id)); process preserved. Inspect $outputPath and $errorPath"
    }
    finally {
        if ($lockHeld) { $launchLock.ReleaseMutex() }
        $launchLock.Dispose()
    }
}

if (-not $BaseUri) {
    $httpPort = if ($env:AXMS_HTTP_PORT) { $env:AXMS_HTTP_PORT } else { '18080' }
    $BaseUri = "http://127.0.0.1:$httpPort"
}
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Start-CodingRunner -RepositoryRoot $repositoryRoot -TargetUri $BaseUri -TimeoutSeconds $WaitTimeoutSeconds

[CmdletBinding()]
param(
    [string]$BaseUri,
    [ValidateRange(5, 60)][int]$WaitTimeoutSeconds = 20,
    [ValidateRange(1, 7200)][int]$DrainTimeoutSeconds = 1800
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Resolve-RunnerWorkspace {
    param([string]$RepositoryRoot)
    $candidate = Split-Path -Parent $RepositoryRoot
    while ($candidate) {
        # Worktree roots may contain compatibility junctions named backend/frontend.
        # Only the parent with the Master manifest is the canonical workspace.
        if ((Test-Path -LiteralPath (Join-Path $candidate 'urizo-final-master/repository-manifest.json') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path $candidate 'urizo-final-backend') -PathType Container) -and
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

function Read-CodingRunnerArguments {
    param([string]$CommandLine)
    # Only a real -File entrypoint; a diagnostic -Command containing runner.ps1 is not a Runner.
    if ($CommandLine -notmatch '^(?:"[^"]+\.exe"|[^"\s]+\.exe)?\s*(?:(?:-NoProfile|-NonInteractive|-NoLogo|-STA|-MTA)\s+|-ExecutionPolicy\s+\w+\s+)*-File\s+(?:"(?<file>[^"]+)"|(?<file>\S+))(?<args>.*)$') { return $null }
    $file = $Matches['file']
    $tail = $Matches['args']
    if ($file -notmatch '(?i)[\\/]runner\.ps1$') { return $null }
    $result = @{ File = [IO.Path]::GetFullPath($file) }
    foreach ($match in [regex]::Matches($tail, '(?i)(?:^|\s)"?-(?<key>\w+)"?\s+(?:"(?<value>[^"]*)"|(?<value>[^\s"]+))')) {
        $key = $match.Groups['key'].Value
        if ($result.ContainsKey($key)) { return $null }
        $result[$key] = $match.Groups['value'].Value
    }
    foreach ($key in @('BaseUri', 'WorkRoot', 'SecretsRoot', 'StartupSignalPath', 'LifecycleId', 'SourceSha256')) {
        if (-not $result.ContainsKey($key)) { $result[$key] = $null }
    }
    $result['ManagedArguments'] = $tail -match '^(?:\s+"?-(?:BaseUri|WorkRoot|SecretsRoot|StartupSignalPath|LifecycleId|SourceSha256)"?\s+(?:"[^"]*"|[^\s"]+))*\s*$'
    return $result
}

function Get-CodingRunnerProcesses {
    # Fail closed if process inspection is unavailable; never risk a second claimant.
    return @(Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'" |
        Where-Object { $_.CommandLine -and (Read-CodingRunnerArguments -CommandLine $_.CommandLine) })
}

function Test-RunnerPathEqual {
    param([string]$Left, [string]$Right)
    return $Left -and $Right -and [IO.Path]::GetFullPath($Left).TrimEnd('\', '/').Equals(
        [IO.Path]::GetFullPath($Right).TrimEnd('\', '/'), [StringComparison]::OrdinalIgnoreCase)
}

function Test-CodingRunnerDrainPending {
    param($Binding)
    $signal = $null
    try {
        $signal = [Threading.EventWaitHandle]::OpenExisting("Local\AXMS-CodingRunner-Drain-$($Binding.LifecycleId)")
        return $signal.WaitOne(0)
    }
    catch { throw 'Coding Runner has no live drain acknowledgement channel. Process preserved; no duplicate was started.' }
    finally { if ($signal) { $signal.Dispose() } }
}

function Wait-CodingRunnerDrain {
    param($Existing, $Binding, [int]$TimeoutSeconds)
    if ($Binding.LifecycleId -notmatch '^[a-f0-9]{32}$') {
        throw "CODING RUNNER LEGACY: PID=$($Existing.ProcessId) cannot acknowledge a safe stop. Process preserved; a one-time controlled stop is required before automatic replacement is available."
    }
    # A deployment task can itself invoke full startup. Waiting for its owning Runner
    # would deadlock that task. Leave it untouched and report the boundary explicitly.
    $ancestor = $PID
    $seen = @{}
    while ($ancestor -gt 0 -and -not $seen.ContainsKey($ancestor)) {
        if ($ancestor -eq $Existing.ProcessId) {
            throw 'Coding Runner replacement must be invoked outside its own task process tree. Process preserved.'
        }
        $seen[$ancestor] = $true
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId = $ancestor"
        if (-not $parent) { break }
        $ancestor = [int]$parent.ParentProcessId
    }
    $drain = $null
    $drained = $null
    $process = $null
    try {
        try {
            $drain = [Threading.EventWaitHandle]::OpenExisting("Local\AXMS-CodingRunner-Drain-$($Binding.LifecycleId)")
            $drained = [Threading.EventWaitHandle]::OpenExisting("Local\AXMS-CodingRunner-Drained-$($Binding.LifecycleId)")
        }
        catch { throw 'Coding Runner has no live drain acknowledgement channel. Process preserved; no duplicate was started.' }
        $process = Get-Process -Id $Existing.ProcessId -ErrorAction Stop
        # Keep the process handle open so ExitCode remains available after it exits.
        $null = $process.Handle
        if ([Math]::Abs(($process.StartTime.ToUniversalTime() - $Existing.CreationDate.ToUniversalTime()).TotalMilliseconds) -ge 1) {
            throw 'Coding Runner process identity changed during inspection. No stop was requested.'
        }
        [void]$drain.Set()
        Write-Output "CODING RUNNER DRAINING: PID=$($Existing.ProcessId); waiting for the current task and outcome report before replacement."
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
        do {
            if ($process.WaitForExit(250)) {
                if ($process.ExitCode -ne 0 -or -not $drained.WaitOne(0)) {
                    throw "Coding Runner exited without a graceful drain acknowledgement (exit=$($process.ExitCode); acknowledged=$($drained.WaitOne(0))). Inspect its task and logs before retrying; no replacement was started."
                }
                Write-Output "CODING RUNNER STOPPED: PID=$($Existing.ProcessId); graceful drain completed."
                return
            }
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        throw 'Coding Runner drain is still pending. No task was interrupted and no replacement was started. Retry startup after the task finishes; the drain request remains active.'
    }
    finally {
        if ($drain) { $drain.Dispose() }
        if ($drained) { $drained.Dispose() }
        if ($process) { $process.Dispose() }
    }
}

function Start-CodingRunner {
    param([string]$RepositoryRoot, [string]$TargetUri, [int]$TimeoutSeconds,
        [int]$DrainSeconds = 1800)
    if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
        Write-Output 'CODING RUNNER SKIPPED: automatic host startup is Windows-only.'
        return
    }
    $runnerPath = Join-Path $RepositoryRoot 'scripts/runner.ps1'
    $sourceHash = (Get-FileHash -LiteralPath $runnerPath -Algorithm SHA256).Hash
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
            if ($existing.Count -ne 1) { throw 'Multiple Coding Runners were found. Processes preserved; no duplicate was started.' }
            $binding = Read-CodingRunnerArguments -CommandLine $existing[0].CommandLine
            $oldRepository = Split-Path -Parent (Split-Path -Parent $binding.File)
            if ($binding.ManagedArguments -and $binding.BaseUri -and $binding.BaseUri.TrimEnd('/') -ieq $TargetUri.TrimEnd('/') -and
                (Test-RunnerPathEqual $binding.WorkRoot $workRoot) -and
                (Test-RunnerPathEqual (Resolve-RunnerWorkspace $oldRepository) (Resolve-RunnerWorkspace $RepositoryRoot))) {
                if (-not $binding.StartupSignalPath -or
                    -not (Test-Path -LiteralPath $binding.StartupSignalPath -PathType Leaf) -or
                    (Get-Content -LiteralPath $binding.StartupSignalPath -Raw).Trim() -ne [string]$existing[0].ProcessId) {
                    throw 'The existing Coding Runner has not confirmed its first poll. Process preserved; inspect its startup logs.'
                }
                if ((Test-RunnerPathEqual $binding.File $runnerPath) -and
                    (Test-RunnerPathEqual $binding.SecretsRoot $secretsRoot) -and $binding.SourceSha256 -eq $sourceHash -and
                    -not (Test-CodingRunnerDrainPending -Binding $binding)) {
                    Write-Output "CODING RUNNER REUSED: PID=$($existing[0].ProcessId); source and binding match."
                    return
                }
                Wait-CodingRunnerDrain -Existing $existing[0] -Binding $binding -TimeoutSeconds $DrainSeconds
                if (@(Get-CodingRunnerProcesses).Count -ne 0) {
                    throw 'A Coding Runner appeared during handoff. No duplicate was started.'
                }
            }
            else { throw 'An existing Coding Runner has a different or unverified binding. It was preserved; no duplicate was started.' }
        }
        $stateRoot = Join-Path $RepositoryRoot '.local/runner'
        New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
        $launchId = [guid]::NewGuid().ToString('N')
        $readyPath = Join-Path $stateRoot "$launchId.ready"
        $outputPath = Join-Path $stateRoot "$launchId.stdout.log"
        $errorPath = Join-Path $stateRoot "$launchId.stderr.log"
        $shellPath = (Get-Process -Id $PID).Path
        $commandLine = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + $runnerPath +
            '" ' + $bindingArguments + ' -StartupSignalPath "' + $readyPath +
            '" -LifecycleId "' + $launchId + '" -SourceSha256 "' + $sourceHash + '"'
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
Start-CodingRunner -RepositoryRoot $repositoryRoot -TargetUri $BaseUri -TimeoutSeconds $WaitTimeoutSeconds -DrainSeconds $DrainTimeoutSeconds

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$checks = 0
function Assert-DrainCheck {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:checks++
    Write-Output "PASS: $Message"
}
function Wait-Fixture {
    param([scriptblock]$Condition)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds(20)
    while (-not (& $Condition)) {
        if ([DateTimeOffset]::UtcNow -gt $deadline) { throw 'Isolated drain fixture timed out.' }
        Start-Sleep -Milliseconds 50
    }
}

# Real hidden Windows processes and the production poll loop. Only the HTTP and
# task executor functions are replaced with local file barriers. No sockets,
# product tokens, Docker commands, DB access, Git operations, or real tasks.
$fixtureRoot = Join-Path (Split-Path -Parent $PSScriptRoot) ('.local/runner-drain-test-' + [guid]::NewGuid().ToString('N'))
$startupPath = Join-Path $PSScriptRoot 'start-coding-runner.ps1'
$repositoryA = Join-Path $fixtureRoot 'Runner A'
$repositoryB = Join-Path $fixtureRoot 'Runner B'
$handoff = $null
$drainSignal = $null
$fixtureUri = 'http://127.0.0.1:' + (Get-Random -Minimum 30000 -Maximum 60000)

$environmentSetup = {
    param($StartupPath, $FixtureRoot)
    $script:drainFixtureRoot = $FixtureRoot
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($StartupPath, [ref]$null, [ref]$errors)
    if (@($errors).Count) { throw 'Startup script parse failed.' }
    foreach ($definition in $ast.FindAll({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
        . ([scriptblock]::Create($definition.Extent.Text))
    }
    function Get-Command {
        param($Name, $ErrorAction)
        if ($Name -ne 'docker') { throw 'Unexpected command discovery.' }
        [pscustomobject]@{ Source = 'Invoke-DrainFixtureDocker' }
    }
    function Invoke-DrainFixtureDocker {
        $global:LASTEXITCODE = 0
        if ($args[0] -eq 'compose') { return 'fixture-container' }
        if ($args[0] -ne 'inspect') { throw 'Unexpected Docker operation.' }
        ConvertTo-Json -InputObject @(@{ Type='bind'; Source=(Join-Path $script:drainFixtureRoot 'coding_model_bridge_service_token'); Destination='/run/secrets/coding_model_bridge_service_token' }) -Compress
    }
    function Get-CimInstance {
        param($ClassName, $Filter)
        CimCmdlets\Get-CimInstance -ClassName $ClassName -Filter $Filter |
            Where-Object { $_.CommandLine -and $_.CommandLine.Contains($script:drainFixtureRoot) }
    }
}

try {
    foreach ($repository in @($repositoryA, $repositoryB)) {
        New-Item -ItemType Directory -Path (Join-Path $repository 'scripts') -Force | Out-Null
    }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'coding_model_bridge_service_token') -Value ('x' * 64) -Encoding ASCII
    $runnerPath = Join-Path $PSScriptRoot 'runner.ps1'
    $runnerText = [IO.File]::ReadAllText($runnerPath)
    $errors = $null
    $runnerAst = [Management.Automation.Language.Parser]::ParseFile($runnerPath, [ref]$null, [ref]$errors)
    if (@($errors).Count) { throw 'Runner script parse failed.' }
    $requestFixture = @'
function Invoke-RunnerRequest {
    param([string]$Uri, [hashtable]$Body)
    $fixture = '__FIXTURE__'
    if ($Uri.EndsWith('/claim')) {
        Add-Content -LiteralPath (Join-Path $fixture 'claims.log') -Value $PID
        $serve = Join-Path $fixture 'serve-task'
        if (Test-Path -LiteralPath $serve) {
            Remove-Item -LiteralPath $serve
            return [pscustomobject]@{ kind='FIXTURE_ONLY' }
        }
        return $null
    }
    Set-Content -LiteralPath (Join-Path $fixture 'outcome-started') -Value $PID
    while (-not (Test-Path -LiteralPath (Join-Path $fixture 'outcome-release'))) { Start-Sleep -Milliseconds 50 }
    Add-Content -LiteralPath (Join-Path $fixture 'outcomes.log') -Value $PID
    return $null
}
'@
    $taskFixture = @'
function Complete-RunnerTask {
    param($Task)
    $fixture = '__FIXTURE__'
    Set-Content -LiteralPath (Join-Path $fixture 'task-started') -Value $PID
    while (-not (Test-Path -LiteralPath (Join-Path $fixture 'task-release'))) { Start-Sleep -Milliseconds 50 }
    if (Test-Path -LiteralPath (Join-Path $fixture 'crash-task')) { [Environment]::Exit(17) }
    Invoke-RunnerRequest -Uri "$BaseUri/internal/coding/runner/outcomes" -Body @{}
}
'@
    foreach ($replacement in @(@{Name='Invoke-RunnerRequest'; Text=$requestFixture}, @{Name='Complete-RunnerTask'; Text=$taskFixture})) {
        $name = $replacement.Name
        $definition = $runnerAst.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq $name }, $true)
        $runnerText = $runnerText.Replace($definition.Extent.Text, $replacement.Text.Replace('__FIXTURE__', $fixtureRoot.Replace("'", "''")))
    }
    foreach ($repository in @($repositoryA, $repositoryB)) {
        [IO.File]::WriteAllText((Join-Path $repository 'scripts/runner.ps1'), $runnerText, [Text.UTF8Encoding]::new($true))
    }
    . $environmentSetup $startupPath $fixtureRoot
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'serve-task') -Value 'fixture'
    $started = @(Start-CodingRunner -RepositoryRoot $repositoryA -TargetUri $fixtureUri -TimeoutSeconds 20)
    Wait-Fixture { Test-Path -LiteralPath (Join-Path $fixtureRoot 'task-started') }
    $old = @(Get-CodingRunnerProcesses)[0]
    $oldProcess = Get-Process -Id $old.ProcessId
    Assert-DrainCheck (($started -join '') -match 'STARTED') 'startup acknowledgement does not wait for task completion'
    $handoff = Start-Job -ArgumentList $environmentSetup.ToString(), $startupPath, $fixtureRoot, $repositoryB, $fixtureUri -ScriptBlock {
        param($Setup, $Startup, $Fixture, $Repository, $Uri)
        $ErrorActionPreference = 'Stop'
        Set-StrictMode -Version Latest
        . ([scriptblock]::Create($Setup)) $Startup $Fixture
        Start-CodingRunner -RepositoryRoot $Repository -TargetUri $Uri -TimeoutSeconds 20 -DrainSeconds 30
    }
    $binding = Read-CodingRunnerArguments $old.CommandLine
    $drainSignal = [Threading.EventWaitHandle]::OpenExisting("Local\AXMS-CodingRunner-Drain-$($binding.LifecycleId)")
    Wait-Fixture { $drainSignal.WaitOne(0) }
    Assert-DrainCheck (-not $oldProcess.HasExited -and @(Get-CodingRunnerProcesses).Count -eq 1) 'busy Runner remains the only process after the stop request'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'task-release') -Value 'release'
    Wait-Fixture { Test-Path -LiteralPath (Join-Path $fixtureRoot 'outcome-started') }
    Assert-DrainCheck (-not $oldProcess.HasExited -and @(Get-Content -LiteralPath (Join-Path $fixtureRoot 'claims.log')).Count -eq 1) 'replacement waits for outcome acknowledgement without another claim'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'outcome-release') -Value 'release'
    Wait-Fixture { $handoff.State -in @('Completed', 'Failed') }
    $result = @(Receive-Job -Job $handoff -ErrorAction Stop)
    Assert-DrainCheck ($handoff.State -eq 'Completed' -and ($result -join '') -match 'STOPPED' -and ($result -join '') -match 'STARTED') 'task and outcome complete before automatic handoff succeeds'
    $current = @(Get-CodingRunnerProcesses)
    Assert-DrainCheck ($oldProcess.HasExited -and $current.Count -eq 1 -and $current[0].ProcessId -ne $old.ProcessId -and
        (Test-RunnerPathEqual (Read-CodingRunnerArguments $current[0].CommandLine).File (Join-Path $repositoryB 'scripts/runner.ps1'))) 'only the new source-bound Runner remains'
    Assert-DrainCheck (@(Get-Content -LiteralPath (Join-Path $fixtureRoot 'outcomes.log')).Count -eq 1) 'exactly one outcome was reported for the fixture task'
    Remove-Job -Job $handoff
    $handoff = $null
    $drainSignal.Dispose()
    $drainSignal = $null

    # Timeout must preserve the active task and must not report REUSED on a retry
    # while that same process already has a pending drain request.
    foreach ($name in @('task-release', 'task-started', 'outcome-release', 'outcome-started')) {
        Remove-Item -LiteralPath (Join-Path $fixtureRoot $name)
    }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'serve-task') -Value 'fixture'
    Wait-Fixture { Test-Path -LiteralPath (Join-Path $fixtureRoot 'task-started') }
    $blocked = $false
    try { Start-CodingRunner -RepositoryRoot $repositoryA -TargetUri $fixtureUri -TimeoutSeconds 20 -DrainSeconds 0 }
    catch { $blocked = $_.Exception.Message -match 'drain is still pending' }
    Assert-DrainCheck ($blocked -and @(Get-CodingRunnerProcesses).Count -eq 1) 'drain timeout preserves the task and does not launch a replacement'
    $blocked = $false
    try { Start-CodingRunner -RepositoryRoot $repositoryB -TargetUri $fixtureUri -TimeoutSeconds 20 -DrainSeconds 0 }
    catch { $blocked = $_.Exception.Message -match 'drain is still pending' }
    Assert-DrainCheck $blocked 'same-binding retry does not falsely reuse a process that is draining'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'task-release') -Value 'release'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'outcome-release') -Value 'release'
    Wait-Fixture { @(Get-CodingRunnerProcesses).Count -eq 0 }
    $restarted = @(Start-CodingRunner -RepositoryRoot $repositoryA -TargetUri $fixtureUri -TimeoutSeconds 20)
    Assert-DrainCheck (($restarted -join '') -match 'STARTED' -and @(Get-CodingRunnerProcesses).Count -eq 1) 'retry after a timed-out drain starts exactly one replacement'
    foreach ($name in @('task-release', 'task-started', 'outcome-release', 'outcome-started')) {
        Remove-Item -LiteralPath (Join-Path $fixtureRoot $name)
    }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'serve-task') -Value 'fixture'
    Wait-Fixture { Test-Path -LiteralPath (Join-Path $fixtureRoot 'task-started') }
    $old = @(Get-CodingRunnerProcesses)[0]
    $binding = Read-CodingRunnerArguments $old.CommandLine
    $drainSignal = [Threading.EventWaitHandle]::OpenExisting("Local\AXMS-CodingRunner-Drain-$($binding.LifecycleId)")
    $handoff = Start-Job -ArgumentList $environmentSetup.ToString(), $startupPath, $fixtureRoot, $repositoryB, $fixtureUri -ScriptBlock {
        param($Setup, $Startup, $Fixture, $Repository, $Uri)
        $ErrorActionPreference = 'Stop'
        . ([scriptblock]::Create($Setup)) $Startup $Fixture
        Start-CodingRunner -RepositoryRoot $Repository -TargetUri $Uri -TimeoutSeconds 20 -DrainSeconds 30
    }
    Wait-Fixture { $drainSignal.WaitOne(0) }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'crash-task') -Value 'fixture'
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'task-release') -Value 'release'
    Wait-Fixture { $handoff.State -in @('Completed', 'Failed') }
    Assert-DrainCheck ($handoff.State -eq 'Failed' -and
        $handoff.ChildJobs[0].JobStateInfo.Reason.Message -match 'without a graceful drain acknowledgement' -and
        @(Get-CodingRunnerProcesses).Count -eq 0) 'unexpected process exit is not reported as graceful and does not start another claimant'
    Write-Output "Runner drain: $checks checks passed on PowerShell $($PSVersionTable.PSVersion). No product operations."
}
finally {
    if ($handoff) { Stop-Job -Job $handoff; Remove-Job -Job $handoff }
    if ($drainSignal) { $drainSignal.Dispose() }
    foreach ($process in @(CimCmdlets\Get-CimInstance Win32_Process -Filter "Name = 'powershell.exe' OR Name = 'pwsh.exe'")) {
        if ($process.CommandLine -and $process.CommandLine.Contains($fixtureRoot) -and
            $process.CommandLine -match '(?i)-File\s+"[^"]+[\\/]runner\.ps1"') {
            Stop-Process -Id $process.ProcessId -Force
        }
    }
    $cleanup = [IO.Path]::GetFullPath($fixtureRoot)
    $allowed = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) '.local')) + [IO.Path]::DirectorySeparatorChar
    if (-not $cleanup.StartsWith($allowed, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $cleanup) -notmatch '^runner-drain-test-[a-f0-9]{32}$') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $cleanup -Recurse -Force
}

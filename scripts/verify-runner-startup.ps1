[CmdletBinding()]
param([switch]$ProcessSmoke)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Assert-StartupCheck {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
    $script:checks++
    Write-Output "PASS: $Message"
}

function Read-TestAst {
    param([string]$Path)
    $errors = $null
    $ast = [Management.Automation.Language.Parser]::ParseFile($Path, [ref]$null, [ref]$errors)
    if (@($errors).Count -gt 0) { throw "PowerShell parse failed: $Path" }
    return $ast
}

$script:checks = 0
$startupAst = Read-TestAst -Path (Join-Path $PSScriptRoot 'start-coding-runner.ps1')
foreach ($definition in $startupAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst]
    }, $true)) {
    . ([scriptblock]::Create($definition.Extent.Text))
}

# Only temporary, non-secret fixtures and in-memory stand-ins. No real process, HTTP,
# Docker, Git, credential read, claim, or outcome call is allowed by this verification.
$script:testRoot = Join-Path (Split-Path -Parent $PSScriptRoot) ('.local/runner-test-' + [guid]::NewGuid().ToString('N'))
$fixtureRoot = Join-Path $script:testRoot 'Backend With Spaces'
New-Item -ItemType Directory -Path (Join-Path $fixtureRoot 'scripts') -Force | Out-Null
$script:fixtureToken = Join-Path $fixtureRoot 'coding_model_bridge_service_token'
Set-Content -LiteralPath $script:fixtureToken -Value 'NOT-A-CREDENTIAL' -Encoding ASCII
$script:mode = 'ready'
$script:existing = @()
$script:launches = 0
$script:lastCommandLine = ''
$script:lastReadyPath = ''
$script:badMount = $false
$fixtureServer = $null
$script:fixtureRunner = $null

function Get-Command {
    param([string]$Name, $ErrorAction)
    if ($Name -ne 'docker') { throw 'Unexpected command discovery.' }
    return [pscustomobject]@{ Source = 'Invoke-FixtureDocker' }
}
function Invoke-FixtureDocker {
    $global:LASTEXITCODE = 0
    if ($args[0] -eq 'compose') { return 'fixture-container' }
    if ($args[0] -ne 'inspect') { throw 'Unexpected Docker operation.' }
    $mounts = @(
        @{ Type = 'bind'; Source = 'not-the-token'; Destination = '/run/secrets/other' },
        @{ Type = 'bind'; Source = $script:fixtureToken; Destination = '/run/secrets/coding_model_bridge_service_token' }
    )
    if ($script:badMount) { $mounts = @() }
    return ConvertTo-Json -InputObject $mounts -Compress
}
function Get-CimInstance {
    param($ClassName, $Filter)
    if ($script:mode -eq 'inspection-failure') { throw 'Fixture process inspection denied.' }
    return $script:existing
}
function Start-Sleep { param($Milliseconds) }
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, $WindowStyle,
        $RedirectStandardOutput, $RedirectStandardError, [switch]$PassThru)
    if ($WindowStyle -ne 'Hidden' -or $ArgumentList -notmatch '-NoProfile -NonInteractive -ExecutionPolicy Bypass') {
        throw 'Expected a hidden process with process-scoped execution policy.'
    }
    $script:launches++
    $script:lastCommandLine = $ArgumentList
    if ($ArgumentList -notmatch '-StartupSignalPath "([^"]+)"') { throw 'Missing startup signal path.' }
    $script:lastReadyPath = $Matches[1]
    if ($script:mode -eq 'ready') {
        Set-Content -LiteralPath $script:lastReadyPath -Value '4242' -Encoding ASCII
    }
    return [pscustomobject]@{ Id = 4242; HasExited = ($script:mode -eq 'exited') } |
        Add-Member -MemberType ScriptMethod -Name Refresh -Value {} -PassThru
}
function Stop-Process { throw 'A startup failure must never terminate a Runner.' }

try {
    $canonicalWorkspace = Resolve-RunnerWorkspace -RepositoryRoot (Split-Path -Parent $PSScriptRoot)
    Assert-StartupCheck ((Resolve-RunnerWorkspace -RepositoryRoot $fixtureRoot) -eq $canonicalWorkspace) 'nested worktree resolves canonical workspace'
    Assert-StartupCheck ((ConvertTo-RunnerHostPath '/run/desktop/mnt/host/c/Workspace With Spaces/token') -eq 'c:\Workspace With Spaces\token') 'Docker Desktop Linux mount path converts to Windows'
    $savedPath = $env:Path
    $started = @(Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1/' -TimeoutSeconds 0)
    Assert-StartupCheck ($script:launches -eq 1 -and ($started -join '') -match 'first authenticated poll succeeded') 'new Runner waits for the startup acknowledgement'
    Assert-StartupCheck ($script:lastCommandLine.Contains('"-WorkRoot" "' + (Join-Path $canonicalWorkspace '.worktrees') + '"') -and
        $script:lastCommandLine.Contains('"-SecretsRoot" "' + $fixtureRoot + '"') -and
        $script:lastCommandLine.Contains('"-BaseUri" "http://127.0.0.1:1"') -and
        -not $script:lastCommandLine.Contains('NOT-A-CREDENTIAL') -and $env:Path -eq $savedPath) 'quoted paths, mounted secret directory, normalized URL, and scoped PATH'

    $script:existing = @([pscustomobject]@{ ProcessId = 4242; CommandLine = $script:lastCommandLine })
    $reused = @(Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1' -TimeoutSeconds 0)
    Assert-StartupCheck ($script:launches -eq 1 -and ($reused -join '') -match 'REUSED') 'repeated startup reuses the same process without duplication'
    Set-Content -LiteralPath $script:lastReadyPath -Value 'wrong-pid' -Encoding ASCII
    $blocked = $false
    try { Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1' -TimeoutSeconds 0 }
    catch { $blocked = $_.Exception.Message -match 'has not confirmed its first poll' }
    Assert-StartupCheck ($blocked -and $script:launches -eq 1) 'unconfirmed existing process cannot become a successful retry'
    $script:existing[0].CommandLine = 'powershell.exe -File "C:\other\runner.ps1"'
    $blocked = $false
    try { Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1' -TimeoutSeconds 0 }
    catch { $blocked = $_.Exception.Message -match 'different or unverified binding' }
    Assert-StartupCheck ($blocked -and $script:launches -eq 1) 'different existing Runner is preserved and blocks a duplicate'
    $script:existing = @()
    foreach ($case in @(
        @{ Mode = 'exited'; Error = 'exited during startup'; Starts = 2 },
        @{ Mode = 'silent'; Error = 'has not confirmed'; Starts = 3 },
        @{ Mode = 'inspection-failure'; Error = 'inspection denied'; Starts = 3 }
    )) {
        $script:mode = $case.Mode
        $blocked = $false
        try { Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1' -TimeoutSeconds 0 }
        catch { $blocked = $_.Exception.Message -match $case.Error }
        Assert-StartupCheck ($blocked -and $script:launches -eq $case.Starts) "startup failure is visible without termination: $($case.Mode)"
    }
    $script:mode = 'ready'
    $script:badMount = $true
    $blocked = $false
    try { Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri 'http://127.0.0.1:1' -TimeoutSeconds 0 }
    catch { $blocked = $_.Exception.Message -match 'exactly one active' }
    Assert-StartupCheck ($blocked -and $script:launches -eq 3) 'missing active credential mount fails before process launch'

    # Run the actual healthy-reuse entrypoint with harmless sibling scripts, including
    # a failed Runner start: that failure must not fall through into a full DB bootstrap.
    $fixtureScripts = Join-Path $fixtureRoot 'scripts'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'start-cms-local.ps1') -Destination $fixtureScripts
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'compose.dev.yaml') -Value '# fixture' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureScripts 'health.ps1') -Value '$global:LASTEXITCODE = 0' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureScripts 'bootstrap-dev.ps1') -Value 'throw "UNEXPECTED BOOTSTRAP"' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureScripts 'start-coding-runner.ps1') -Value 'Write-Output "FIXTURE RUNNER"' -Encoding ASCII
    $entrypoint = Join-Path $fixtureScripts 'start-cms-local.ps1'
    $full = @(& $entrypoint -Profile full -ApproveLocalMutation)
    Assert-StartupCheck (($full -join '') -match 'FIXTURE RUNNER') 'healthy full startup still starts the Runner'
    $core = @(& $entrypoint -Profile spring-core)
    Assert-StartupCheck (($core -join '') -notmatch 'FIXTURE RUNNER') 'spring-core leaves the Coding Runner untouched'
    $blocked = $false
    try { & $entrypoint -Profile full }
    catch { $blocked = $_.Exception.Message -match 'requires -ApproveLocalMutation' }
    Assert-StartupCheck $blocked 'healthy full startup still requires local mutation approval'
    Set-Content -LiteralPath (Join-Path $fixtureScripts 'start-coding-runner.ps1') -Value 'throw "FIXTURE START FAILURE"' -Encoding ASCII
    $blocked = $false
    try { & $entrypoint -Profile full -ApproveLocalMutation }
    catch { $blocked = $_.Exception.Message -eq 'FIXTURE START FAILURE' }
    Assert-StartupCheck $blocked 'Runner failure never falls through into a DB bootstrap'

    $bootstrapAst = Read-TestAst -Path (Join-Path $PSScriptRoot 'bootstrap-dev.ps1')
    $bootstrapText = $bootstrapAst.Extent.Text
    Assert-StartupCheck ($bootstrapText.Contains("if (`$Profile -eq 'full') {`n    & (Join-Path `$PSScriptRoot 'start-coding-runner.ps1')".Replace("`n", "`r`n")) -or
        $bootstrapText.Contains("if (`$Profile -eq 'full') {`n    & (Join-Path `$PSScriptRoot 'start-coding-runner.ps1')")) 'bootstrap wires Runner startup only to full profile'
    Assert-StartupCheck ($bootstrapText.IndexOf("'start-coding-runner.ps1'") -gt $bootstrapText.IndexOf('local health verification failed')) 'bootstrap launches Runner only after container health verification'
    if ($ProcessSmoke) {
        # Real Windows child-process mechanics, against an isolated loopback stub that
        # returns only 204. It cannot access a product queue or execute a Coding task.
        Remove-Item Function:Start-Process, Function:Stop-Process, Function:Start-Sleep
        $script:badMount = $false
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'runner.ps1') -Destination $fixtureScripts
        Set-Content -LiteralPath $script:fixtureToken -Value ('x' * 64) -Encoding ASCII
        function Get-CimInstance {
            param($ClassName, $Filter)
            CimCmdlets\Get-CimInstance -ClassName $ClassName -Filter $Filter |
                Where-Object { $_.CommandLine -and $_.CommandLine.Contains($fixtureRoot) }
        }
        $portPath = Join-Path $fixtureRoot 'stub.port'
        $fixtureServer = Start-Job -ArgumentList $portPath -ScriptBlock {
            param($PortPath)
            $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
            $listener.Start()
            Set-Content -LiteralPath $PortPath -Value ([string]$listener.LocalEndpoint.Port) -Encoding ASCII
            try {
                while ($true) {
                    if (-not $listener.Pending()) { Start-Sleep -Milliseconds 50; continue }
                    $client = $listener.AcceptTcpClient()
                    $client.ReceiveTimeout = 2000
                    try {
                        $stream = $client.GetStream()
                        $reader = [IO.StreamReader]::new($stream)
                        $contentLength = 0
                        while ($line = $reader.ReadLine()) {
                            if ($line -match '^Content-Length: (\d+)$') { $contentLength = [int]$Matches[1] }
                        }
                        for ($index = 0; $index -lt $contentLength; $index++) { [void]$reader.Read() }
                        $response = [Text.Encoding]::ASCII.GetBytes("HTTP/1.1 204 No Content`r`nContent-Length: 0`r`nConnection: close`r`n`r`n")
                        $stream.Write($response, 0, $response.Length)
                    }
                    finally { $client.Dispose() }
                }
            }
            finally { $listener.Stop() }
        }
        $serverDeadline = [DateTimeOffset]::UtcNow.AddSeconds(30)
        while (-not (Test-Path -LiteralPath $portPath)) {
            if ([DateTimeOffset]::UtcNow -gt $serverDeadline -or $fixtureServer.State -eq 'Failed') {
                throw 'Isolated fixture server failed to start.'
            }
            Start-Sleep -Milliseconds 100
        }
        $fixtureUri = 'http://127.0.0.1:' + (Get-Content -LiteralPath $portPath -Raw).Trim()
        $processOutput = @(Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri $fixtureUri -TimeoutSeconds 20)
        $fixtureProcesses = @(Get-CodingRunnerProcesses)
        Assert-StartupCheck ($fixtureProcesses.Count -eq 1 -and ($processOutput -join '') -match 'STARTED') 'real hidden Runner authenticates only against isolated empty-response stub'
        $script:fixtureRunner = Get-Process -Id $fixtureProcesses[0].ProcessId
        $reusedOutput = @(Start-CodingRunner -RepositoryRoot $fixtureRoot -TargetUri $fixtureUri -TimeoutSeconds 20)
        $afterReuse = @(Get-CodingRunnerProcesses)
        Assert-StartupCheck ($afterReuse.Count -eq 1 -and $afterReuse[0].ProcessId -eq $script:fixtureRunner.Id -and
            ($reusedOutput -join '') -match 'REUSED') 'real repeated startup retains exactly the same Windows PID'
        $duplicateOutput = Join-Path $fixtureRoot 'duplicate.stdout.log'
        $duplicateArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
            (Join-Path $fixtureScripts 'runner.ps1') + '" -BaseUri "' + $fixtureUri +
            '" -SecretsRoot "' + $fixtureRoot + '" -WorkRoot "' + $fixtureRoot + '" -RunOnce'
        $duplicate = Start-Process -FilePath (Get-Process -Id $PID).Path -ArgumentList $duplicateArguments `
            -WindowStyle Hidden -RedirectStandardOutput $duplicateOutput -PassThru
        Assert-StartupCheck ($duplicate.WaitForExit(5000) -and
            (Get-Content -LiteralPath $duplicateOutput -Raw) -match 'no task was claimed') 'manual duplicate exits before claiming while automatic Runner is active'
    }
    Write-Output "Runner startup: $script:checks checks passed on PowerShell $($PSVersionTable.PSVersion). No live Runner operations."
}
finally {
    if ($ProcessSmoke) {
        # Stop only fixture processes bound to this run's unique temporary root.
        foreach ($fixtureProcess in @(Get-CodingRunnerProcesses)) {
            if ($fixtureProcess.CommandLine.Contains($fixtureRoot)) {
                Microsoft.PowerShell.Management\Stop-Process -Id $fixtureProcess.ProcessId -Force
            }
        }
        if ($fixtureServer) { Stop-Job -Job $fixtureServer; Remove-Job -Job $fixtureServer }
    }
    # Delete only this run's generated fixture tree, under the repository's ignored .local directory.
    $cleanupRoot = [IO.Path]::GetFullPath($script:testRoot)
    $allowedParent = [IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $PSScriptRoot) '.local')) + [IO.Path]::DirectorySeparatorChar
    if (-not $cleanupRoot.StartsWith($allowedParent, [StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path -Leaf $cleanupRoot) -notmatch '^runner-test-[a-f0-9]{32}$') { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $cleanupRoot -Recurse -Force
}

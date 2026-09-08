[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Never dot-source runner.ps1: its top-level code reads a token and claims work.
$runnerPath = Join-Path $PSScriptRoot 'runner.ps1'
$parseErrors = $null
$runnerAst = [Management.Automation.Language.Parser]::ParseFile(
    $runnerPath, [ref]$null, [ref]$parseErrors)
if (@($parseErrors).Count -gt 0) {
    throw 'runner.ps1 has PowerShell parse errors.'
}

foreach ($functionName in @('Get-PayloadValue', 'Get-FailureStatus',
        'Get-RepositorySourcePath', 'Complete-RunnerTask')) {
    $definition = @($runnerAst.FindAll({
        param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq $functionName
    }, $true))
    if ($definition.Count -ne 1) {
        throw "Expected exactly one $functionName definition."
    }
    . ([scriptblock]::Create($definition[0].Extent.Text))
}

function Assert-RunnerCheck {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Only in-memory replacements are available to the CREATE_WORKTREE test path.
function Invoke-CreateWorktree {
    param($Payload)
    return $script:testResult
}

function Invoke-RunnerRequest {
    param([string]$Uri, [hashtable]$Body)
    $script:reportCount++
    $script:reportedUri = $Uri
    $script:reportedBody = $Body
    if ($script:failReport) { throw 'Simulated report failure.' }
}

$BaseUri = 'http://runner.invalid'
$runnerId = 'offline-runner'
$testTask = [pscustomobject]@{
    kind = 'CREATE_WORKTREE'
    taskId = 'offline-task'
    leaseId = 'offline-lease'
    payload = [pscustomobject]@{ repo = 'backend' }
}
$script:failReport = $false

foreach ($testCase in @(
        @{ Name = 'new MCP workspace'; Path = '/workspaces/offline-new';
            Result = @{ repo = 'backend'; workspaceId = 'offline-new';
                workspacePath = '/workspaces/offline-new'; volume = 'offline'; reused = $false } },
        @{ Name = 'reused MCP workspace'; Path = '/workspaces/offline-reused';
            Result = @{ repo = 'backend'; workspaceId = 'offline-reused';
                workspacePath = '/workspaces/offline-reused'; volume = 'offline'; reused = $true } },
        @{ Name = 'existing host worktree'; Path = 'offline-host-worktree';
            Result = @{ worktreePath = 'offline-host-worktree'; reused = $true } }
    )) {
    $script:testResult = $testCase.Result
    $script:reportCount = 0
    $script:reportedBody = $null
    $script:reportedUri = ''
    $output = @(Complete-RunnerTask -Task $testTask)
    Assert-RunnerCheck ($script:reportCount -eq 1) 'Expected one outcome report.'
    Assert-RunnerCheck ($script:reportedUri -eq "$BaseUri/internal/coding/runner/tasks/offline-task/outcomes") 'Unexpected report URI.'
    Assert-RunnerCheck ($script:reportedBody.outcome -eq 'SUCCEEDED' -and
        $script:reportedBody.result -eq $script:testResult -and
        $script:reportedBody.taskId -eq $testTask.taskId -and
        $script:reportedBody.leaseId -eq $testTask.leaseId) 'Outcome report changed.'
    Assert-RunnerCheck ($output.Count -eq 1 -and
        $output[0].Contains($testCase.Path) -and
        -not $output[0].Contains('HTTP')) "Success display failed: $($testCase.Name)."
    Write-Output "PASS: $($testCase.Name)"
}

$script:failReport = $true
$script:reportCount = 0
$failedOutput = @(Complete-RunnerTask -Task $testTask)
Assert-RunnerCheck ($script:reportCount -eq 1 -and $failedOutput.Count -eq 1 -and
    $failedOutput[0].Contains('HTTP 0') -and
    -not $failedOutput[0].Contains($script:testResult.worktreePath)) 'Report failure must not display success.'
Write-Output 'PASS: outcome report failure remains visible'

# Exercise the actual explicit WorkRoot mapping with spaces, without making directories.
$expectedWorkspace = Join-Path ([IO.Path]::GetTempPath()) 'AXMS Runner Path Check'
$WorkRoot = Join-Path $expectedWorkspace '.worktrees'
$workspaceAssignment = @($runnerAst.FindAll({
    param($node)
    $node -is [Management.Automation.Language.AssignmentStatementAst] -and
        $node.Left.Extent.Text -eq '$workspaceRoot'
}, $true))
if ($workspaceAssignment.Count -ne 1) { throw 'Expected one workspaceRoot assignment.' }
. ([scriptblock]::Create($workspaceAssignment[0].Extent.Text))
foreach ($repository in @('frontend', 'backend')) {
    $actualPath = Get-RepositorySourcePath -Repository $repository
    Assert-RunnerCheck ($actualPath -eq (Join-Path $expectedWorkspace "urizo-final-$repository")) 'Explicit WorkRoot mapping failed.'
    Write-Output "PASS: explicit WorkRoot resolves canonical $repository"
}
$unknownRejected = $false
try { Get-RepositorySourcePath -Repository 'unknown' | Out-Null }
catch { $unknownRejected = $_.Exception.Message.StartsWith('RUNNER_PAYLOAD_INVALID|') }
Assert-RunnerCheck $unknownRejected 'Unknown repository must remain rejected.'
Write-Output 'PASS: unknown repository remains rejected'
Write-Output "Runner compatibility: 7 checks passed on PowerShell $($PSVersionTable.PSVersion). No live Runner operations."

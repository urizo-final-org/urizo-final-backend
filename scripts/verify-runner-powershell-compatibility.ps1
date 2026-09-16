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
        'Get-RepositorySourcePath', 'Complete-RunnerTask',
        'Get-ScanPhraseMatches', 'Add-ScanPhraseMatches')) {
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

# Phrase search against a throwaway repository in the temp folder, tracked through the index
# only. The phrase is built from code points so this file stays ASCII: Windows PowerShell 5.1
# reads a script without a byte order mark in the system code page.
$searchRoot = Join-Path ([IO.Path]::GetTempPath()) ('axms-phrase-check-' + [Guid]::NewGuid().ToString('N'))
$korean = -join [char[]](0xC9C0, 0xAE08, 0x0020, 0xC5F4, 0xB9AC, 0xB294)
$utf8 = [Text.UTF8Encoding]::new($false)
try {
    foreach ($folder in @('src/site', 'src/other', 'src/many')) {
        New-Item -ItemType Directory -Path (Join-Path $searchRoot $folder) -Force | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $searchRoot 'src/site/Portal.tsx'),
        "const a = 1;`n<h2>$korean</h2>`n// note`nconst b = 2;`n<p>$korean</p>`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $searchRoot 'src/other/Other.tsx'), "<h2>$korean</h2>`n", $utf8)
    [IO.File]::WriteAllText((Join-Path $searchRoot 'src/many/Many.tsx'),
        ((1..25 | ForEach-Object { "<li>$korean $_</li>" }) -join "`n") + "`n", $utf8)
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & git -C $searchRoot init --quiet 2>&1 | Out-Null
        & git -C $searchRoot add --all 2>&1 | Out-Null
        $added = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previousPreference }
    Assert-RunnerCheck ($added -eq 0) 'Could not prepare the throwaway search repository.'

    $search = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) -Paths @('src/site')
    Assert-RunnerCheck ($null -ne $search -and $search.Matches.Count -eq 2 -and
        $search.Matches[0].path -eq 'src/site/Portal.tsx' -and $search.Matches[0].line -eq 2 -and
        $search.Matches[0].preview -eq "<h2>$korean</h2>" -and $search.Matches[1].line -eq 5 -and
        $search.Matches[0].phrase -eq $korean -and
        -not $search.TimedOut -and -not $search.Truncated) 'Korean phrase search inside the sent folder failed.'
    Write-Output 'PASS: Korean phrase found only inside the sent folder'

    $badPhrases = Get-ScanPhraseMatches -Root $searchRoot -Paths @('src/site') `
        -Phrases @(('x' * 81), "two`nlines", 'a')
    $badPaths = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) `
        -Paths @('../src', 'src\site', 'C:/src', '/src', '-src', 'src"site')
    $noPaths = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) -Paths $null
    Assert-RunnerCheck ($null -eq $badPhrases -and $null -eq $badPaths -and $null -eq $noPaths) 'Unusable phrases or paths must not search.'
    Write-Output 'PASS: unusable phrases and paths are dropped before any search'

    $plain = Add-ScanPhraseMatches -Result @{ repo = 'frontend' } -Root $searchRoot `
        -Payload ('{"repo":"frontend"}' | ConvertFrom-Json)
    $searched = Add-ScanPhraseMatches -Result @{ repo = 'frontend' } -Root $searchRoot `
        -Payload (('{"repo":"frontend","phrases":["' + $korean + '"],"paths":["src/site"]}') | ConvertFrom-Json)
    $json = @{ result = $searched } | ConvertTo-Json -Depth 6 -Compress
    Assert-RunnerCheck (-not $plain.ContainsKey('phraseMatches') -and
        @($searched.phraseMatches).Count -eq 2 -and
        -not $searched.ContainsKey('phraseSearchTimedOut') -and
        $json.Contains('"line":2') -and $json.Contains('"path":"src/site/Portal.tsx"')) 'Scan result shape changed.'
    Write-Output 'PASS: a scan without phrases keeps its old result shape'

    $capped = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) -Paths @('src/many')
    Assert-RunnerCheck ($capped.Matches.Count -eq 20 -and $capped.Truncated) 'Line limit failed.'
    Write-Output 'PASS: matches stop at the line limit'

    $cut = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) -Paths @('src/site') -MaxBytesPerPhrase 40
    Assert-RunnerCheck ($cut.Truncated -and $cut.Matches.Count -eq 0) 'Byte limit failed.'
    Write-Output 'PASS: output stops at the byte limit without a cut line'

    $late = Get-ScanPhraseMatches -Root $searchRoot -Phrases @($korean) -Paths @('src/site') -TotalMilliseconds 0
    Assert-RunnerCheck ($late.TimedOut -and $late.Matches.Count -eq 0) 'Time budget failed.'
    Write-Output 'PASS: an exhausted time budget searches nothing and says so'
}
finally {
    if (Test-Path -LiteralPath $searchRoot) {
        Remove-Item -LiteralPath $searchRoot -Recurse -Force
    }
}
Write-Output "Runner compatibility: 13 checks passed on PowerShell $($PSVersionTable.PSVersion). No live Runner operations."

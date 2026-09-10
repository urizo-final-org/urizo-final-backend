[CmdletBinding()]
param(
    [string]$BaseUri = 'http://127.0.0.1:18080',

    [int]$PollIntervalSeconds = 2,

    # Secrets live outside the worktree: .local/ is git-ignored, so a worktree
    # never holds a copy. Point this at the checked-out repository instead.
    [string]$SecretsRoot,

    # Where the AI work folders are created. Defaults to the directory that
    # already holds this checkout, so no absolute path is baked into the script.
    [string]$WorkRoot,

    # The preview overlay is not on dev yet, so an AI worktree does not contain
    # it. Default to this checkout, which is where the file currently lives.
    [string]$PreviewOverlay,

    # Which stack PREVIEW_UP and PREVIEW_DOWN act on. It is deliberately not the
    # stack this runner asks for work: taking down its own broker would leave
    # nowhere to report the outcome to. Once the runner endpoints reach dev the
    # broker moves to the original stack and these return to the single preview.
    [string]$PreviewProject = 'axms-preview',

    [int]$PreviewHttpPort = 18081,

    [int]$PreviewDbPort = 15433,

    # Read-only source of the CMS content the preview is filled with.
    [string]$SourceDatabaseContainer = 'axms-spring-dev-database-1',

    # Where the MCP Coding Tools look for their workspaces. It has to be a named
    # volume rather than a host folder: a Docker Desktop bind mount always shows
    # as root:root inside the container, the MCP service runs as uid 10001, and
    # Git then refuses the repository with "dubious ownership". That cannot be
    # waived, because Git reads safe.directory only from system/global config
    # and the service pins both to nothing.
    [string]$McpWorkspaceVolume = 'axms-spring-dev-mcp-workspaces',

    # Used only to create and inspect workspaces. It is the same image the MCP
    # service runs, so this adds no new dependency and keeps Git versions equal.
    [string]$McpWorkspaceImage = 'axms/mcp-server:dev',

    # Fixed by compose.preview.yaml. TEST runs the frontend checks inside the image BUILD
    # just produced rather than building one of its own.
    [string]$PreviewFrontendImage = 'axms/preview-frontend:latest',

    [switch]$RunOnce,

    # Optional local startup acknowledgement. Contains only this process ID, never the token.
    [string]$StartupSignalPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($PollIntervalSeconds -lt 1 -or $PollIntervalSeconds -gt 60) {
    throw 'PollIntervalSeconds must be between 1 and 60.'
}

[Console]::OutputEncoding = [Text.Encoding]::UTF8

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$claimUri = "$BaseUri/internal/coding/runner/tasks/claim"
$runnerId = "$($env:COMPUTERNAME)-$PID"

if (-not $SecretsRoot) {
    $SecretsRoot = Join-Path $repositoryRoot '.local\secrets'
}
$tokenPath = Join-Path $SecretsRoot 'coding_model_bridge_service_token'
if (-not (Test-Path -LiteralPath $tokenPath -PathType Leaf)) {
    throw "자격증명 파일이 없습니다: $tokenPath`n-SecretsRoot 로 원본 저장소의 .local\secrets 를 지정하세요."
}
# Trimmed because the registrar hashes the value with bash "$(< file)",
# which drops the trailing newline. An untrimmed token would never match.
$script:runnerToken = (Get-Content -LiteralPath $tokenPath -Raw).Trim()
if ($script:runnerToken.Length -lt 43) {
    throw "자격증명 형식이 올바르지 않습니다: $tokenPath"
}

if (-not $WorkRoot) {
    $WorkRoot = Split-Path -Parent $repositoryRoot
}
$workspaceRoot = Split-Path -Parent $WorkRoot
if (-not $PreviewOverlay) {
    $PreviewOverlay = Join-Path $repositoryRoot 'compose.preview.yaml'
}

function Invoke-RunnerRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][hashtable]$Body
    )

    $json = $Body | ConvertTo-Json -Depth 6 -Compress
    $payload = [Text.Encoding]::UTF8.GetBytes($json)
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Uri `
        -Headers @{ Authorization = "Bearer $script:runnerToken" } `
        -ContentType 'application/json; charset=utf-8' -Body $payload -TimeoutSec 10
    if (-not $response.RawContentStream) {
        return $null
    }
    $content = [Text.Encoding]::UTF8.GetString($response.RawContentStream.ToArray())
    if (-not $content.Trim()) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Get-FailureStatus {
    param([Parameter(Mandatory = $true)]$Failure)

    $exception = $Failure.Exception
    if ($exception -and $exception.PSObject.Properties.Match('Response').Count -gt 0 -and $exception.Response) {
        return [int]$exception.Response.StatusCode
    }
    return 0
}

function Get-PayloadValue {
    param($Payload, [Parameter(Mandatory = $true)][string]$Name)

    if ($null -eq $Payload) {
        return $null
    }
    if ($Payload.PSObject.Properties.Match($Name).Count -eq 0) {
        return $null
    }
    return $Payload.$Name
}

function Get-RepositorySourcePath {
    param([Parameter(Mandatory = $true)][string]$Repository)

    # The runner does not decide which repository to use. It only translates a
    # name it already knows into a path; anything else is refused.
    $known = @{ frontend = 'urizo-final-frontend'; backend = 'urizo-final-backend' }
    if (-not $known.ContainsKey($Repository)) {
        throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $Repository"
    }
    return (Join-Path $workspaceRoot $known[$Repository])
}

function Set-PublishRemote {
    # The Coding workspace is cloned inside the container from a read-only mount, so its origin
    # is that mount path and it cannot reach GitHub at all. That is deliberate and stays that
    # way: the container the model works in has no network and no secrets. Publishing happens
    # here on the host instead, so the copy exported for the pull request is pointed at the
    # canonical repository's own origin - the same URL and the same stored credential a person
    # would push with. Nothing is granted to the model's room.
    #
    # The URL is read from the canonical checkout rather than taken from the payload: a queued
    # command must never be able to name where a pull request is published.
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Worktree
    )

    $source = Get-RepositorySourcePath -Repository $Repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_PR_BLOCKED|canonical 저장소를 찾을 수 없습니다: $source"
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $url = "$(@(& git -C $source remote get-url origin 2>&1)[0])".Trim()
        $readExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($readExit -ne 0 -or $url -notmatch '^https://github\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?(\.git)?$') {
        throw 'RUNNER_PR_BLOCKED|canonical origin 주소를 해석하지 못했습니다.'
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $applied = & git -C $Worktree remote set-url origin $url 2>&1
        $applyExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($applyExit -ne 0) {
        throw "RUNNER_PR_FAILED|작업 폴더 origin 설정 실패: $(($applied | Select-Object -Last 2) -join ' ')"
    }
}

function Invoke-CreateMcpWorkspace {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$BaseSha,
        [Parameter(Mandatory = $true)][string]$WorkspaceId
    )

    # Both values are interpolated into a shell command below, so they are
    # checked against fixed patterns first. WORKSPACE_KEY is the same pattern the
    # MCP server enforces, so a name it would reject fails here with a clear
    # message instead of surfacing later as WORKSPACE_NOT_FOUND.
    if ($WorkspaceId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        throw "RUNNER_PAYLOAD_INVALID|workspaceId 형식이 올바르지 않습니다: $WorkspaceId"
    }
    if ($BaseSha -notmatch '^([0-9a-f]{40}|[0-9a-f]{64})$') {
        throw "RUNNER_PAYLOAD_INVALID|baseSha 형식이 올바르지 않습니다: $BaseSha"
    }

    $source = Get-RepositorySourcePath -Repository $Repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_REPOSITORY_MISSING|저장소 폴더가 없습니다: $source"
    }

    # A linked worktree cannot be used here. Its .git is a file holding a Windows
    # absolute path, which does not resolve inside the container, so the MCP
    # server would reject it with REPOSITORY_SCOPE_DENIED. Only a self-contained
    # clone works.
    #
    # --user 0:0 is required and deliberate. The image runs as 10001, which can
    # neither create a directory in the volume root nor read the root-owned
    # source mount. Ownership is handed to the service user in the same command,
    # so the MCP service never sees a directory it does not own. The container is
    # short-lived and has no network and no secrets.
    # The shell commands below deliberately carry no double quotes. Windows
    # PowerShell rewrites quoting when it hands an argument to a native command,
    # and an embedded quote reaches sh mangled, which silently turned an earlier
    # version of this check into a no-op.
    #
    # The probe runs as the service user because the workspace belongs to it;
    # root does not own the directory and Git then refuses to read its config.
    $probe = "if [ -d /workspaces/$WorkspaceId ]; then " `
        + "git -C /workspaces/$WorkspaceId config --local --get axms.repository || echo AXMS_NO_MARKER; " `
        + 'else echo AXMS_MISSING; fi'

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $state = & docker run --rm `
            -v "${McpWorkspaceVolume}:/workspaces" `
            --entrypoint sh $McpWorkspaceImage -c $probe 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKSPACE_FAILED|작업 폴더를 확인하지 못했습니다: $(($state | Select-Object -Last 3) -join ' ')"
    }
    $held = "$(@($state) | Select-Object -Last 1)".Trim()

    if ($held -ne 'AXMS_MISSING') {
        # One workspace holds one repository. Spring carries a single workspace_id
        # per pipeline attempt, so a second repository under the same id cannot be
        # expressed today. Serving the first clone would hand the tools the wrong
        # repository, so the mismatch is refused instead.
        if ($held -ne $Repository) {
            throw "RUNNER_WORKSPACE_CONFLICT|이 workspaceId 는 다른 저장소에 묶여 있습니다: $WorkspaceId ($held)"
        }
        return @{
            repo = $Repository
            workspaceId = $WorkspaceId
            workspacePath = "/workspaces/$WorkspaceId"
            volume = $McpWorkspaceVolume
            reused = $true
        }
    }

    # The clone runs as root and records the repository before handing ownership
    # over, so the marker is written while root still owns the new directory.
    $create = @(
        'set -e',
        "git clone --quiet /src /workspaces/$WorkspaceId",
        "git -C /workspaces/$WorkspaceId checkout --quiet --detach $BaseSha",
        "git -C /workspaces/$WorkspaceId config --local axms.repository $Repository",
        "chown -R 10001:10001 /workspaces/$WorkspaceId"
    ) -join '; '

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker run --rm --user 0:0 `
            -v "${McpWorkspaceVolume}:/workspaces" `
            -v "${source}:/src:ro" `
            --entrypoint sh $McpWorkspaceImage -c $create 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKSPACE_FAILED|작업 폴더를 만들지 못했습니다: $(($output | Select-Object -Last 3) -join ' ')"
    }
    $reused = $false

    return @{
        repo = $Repository
        workspaceId = $WorkspaceId
        workspacePath = "/workspaces/$WorkspaceId"
        volume = $McpWorkspaceVolume
        reused = $reused
    }
}

function Invoke-CreateWorktree {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    $baseSha = Get-PayloadValue -Payload $Payload -Name 'baseSha'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }
    if (-not $baseSha) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 baseSha 가 없습니다.' }

    # A workspaceId means the Coding Job wants a room the MCP tools can reach.
    # Without one this stays the host worktree the BUILD, TEST, PREVIEW and
    # CREATE_PR commands already use, so their paths are untouched.
    $workspaceId = Get-PayloadValue -Payload $Payload -Name 'workspaceId'
    if ($workspaceId) {
        return Invoke-CreateMcpWorkspace -Repository $repository -BaseSha $baseSha -WorkspaceId $workspaceId
    }

    $source = Get-RepositorySourcePath -Repository $repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_REPOSITORY_MISSING|저장소 폴더가 없습니다: $source"
    }

    $target = Join-Path $WorkRoot "ai-$repository"
    if (Test-Path -LiteralPath $target -PathType Container) {
        # Folder lifetime is still undecided (see the feature document). Until it is,
        # the runner reuses the folder and carries no delete command at all.
        return @{ worktreePath = $target; reused = $true }
    }

    # --detach is required: Git refuses to check out one branch in two folders,
    # and the original checkout already holds dev. Pinning the commit avoids it.
    # ErrorActionPreference is relaxed only here because Windows PowerShell turns
    # native stderr into a terminating NativeCommandError even on exit code 0.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git -C $source worktree add --detach $target $baseSha 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKTREE_FAILED|$($output -join ' ')"
    }
    return @{ worktreePath = $target; reused = $false }
}

function Get-WorkspaceHostPath {
    param([Parameter(Mandatory = $true)][string]$WorkspaceId)

    if ($WorkspaceId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        throw "RUNNER_PAYLOAD_INVALID|workspaceId 형식이 올바르지 않습니다: $WorkspaceId"
    }
    return Join-Path $WorkRoot "ws-$WorkspaceId"
}

function Export-McpWorkspaceToHost {
    # The Coding tools write into a named volume because a Windows bind mount is
    # always root-owned inside the container and Git then refuses the repository.
    # Docker is therefore the only reader, and BUILD and PREVIEW_UP need the files
    # on the host. This copies the volume clone out to a path of its own so the
    # existing ai-<repo> worktrees the other commands use are never touched.
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$WorkspaceId
    )

    $target = Get-WorkspaceHostPath -WorkspaceId $WorkspaceId

    # The marker is read as the service user: the workspace belongs to 10001 and
    # root cannot read the Git config of a directory it does not own.
    $probe = "if [ -d /workspaces/$WorkspaceId ]; then " `
        + "git -C /workspaces/$WorkspaceId config --local --get axms.repository || echo AXMS_NO_MARKER; " `
        + 'else echo AXMS_MISSING; fi'

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $state = & docker run --rm `
            -v "${McpWorkspaceVolume}:/workspaces" `
            --entrypoint sh $McpWorkspaceImage -c $probe 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKSPACE_FAILED|작업 폴더를 확인하지 못했습니다: $(($state | Select-Object -Last 3) -join ' ')"
    }
    $held = "$(@($state) | Select-Object -Last 1)".Trim()
    if ($held -eq 'AXMS_MISSING') {
        throw "RUNNER_WORKSPACE_MISSING|작업 폴더가 없습니다. CREATE_WORKTREE 를 먼저 실행하세요: $WorkspaceId"
    }
    if ($held -ne $Repository) {
        throw "RUNNER_WORKSPACE_CONFLICT|이 workspaceId 는 다른 저장소에 묶여 있습니다: $WorkspaceId ($held)"
    }

    # A stale export would hand the build files the model already deleted, so the
    # target is emptied first. Only this runner writes here and the path is
    # derived from the validated workspaceId, never from the payload directly.
    if (Test-Path -LiteralPath $target -PathType Container) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target -Force | Out-Null

    # --user 0:0 reads the 10001-owned workspace and writes the bind mount, which
    # Docker Desktop maps back to the host user. The container is short-lived and
    # has no network and no secrets.
    $copy = "cp -a /workspaces/$WorkspaceId/. /out/"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker run --rm --user 0:0 `
            -v "${McpWorkspaceVolume}:/workspaces" `
            -v "${target}:/out" `
            --entrypoint sh $McpWorkspaceImage -c $copy 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKSPACE_EXPORT_FAILED|작업 폴더를 꺼내지 못했습니다: $(($output | Select-Object -Last 3) -join ' ')"
    }
    # A marker the repository is known to carry, so a half-copied export is caught here
    # rather than as an unreadable Compose error later. It is per repository: the Compose
    # files are Backend files, and a frontend checkout has never held one.
    $marker = switch ($Repository) {
        'backend' { 'compose.dev.yaml' }
        'frontend' { 'package.json' }
        default { throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $Repository" }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $target $marker) -PathType Leaf)) {
        throw "RUNNER_WORKSPACE_EXPORT_FAILED|꺼낸 폴더에 $marker 이 없습니다: $target"
    }

    # The clone was made on Linux, so its config keeps core.filemode true. Windows
    # cannot carry the executable bit, so every shell script would read as a mode
    # change and a later commit from this directory would carry that noise.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $config = & git -C $target config --local core.filemode false 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_WORKSPACE_EXPORT_FAILED|꺼낸 폴더 설정에 실패했습니다: $(($config | Select-Object -Last 2) -join ' ')"
    }
    return $target
}

function Get-AiWorktreePath {
    param([Parameter(Mandatory = $true)][string]$Repository)

    $path = Join-Path $WorkRoot "ai-$Repository"
    if (-not (Test-Path -LiteralPath $path -PathType Container)) {
        throw "RUNNER_WORKTREE_MISSING|작업 폴더가 없습니다. CREATE_WORKTREE 를 먼저 실행하세요: $path"
    }
    return $path
}

function Get-ScanFiles {
    param([Parameter(Mandatory = $true)][string]$Root)

    # The Coding agents cannot list files themselves: their only ways to find one are a
    # search that has to guess the word and a read that has to guess the path. Three live
    # runs burned every turn that way and never reached apply_patch. The tracked file list
    # is small enough (a few hundred paths) to hand over whole, and the Backend filters it
    # to the guardrail rather than the runner - the same reason Get-ScanFolders returns raw
    # folders: one copy of the fence, on the Backend.
    $listed = @(& git -C $Root ls-files 2>&1)
    if ($LASTEXITCODE -ne 0) {
        # A missing file list must not fail a scan whose real product is the sha. The agents
        # then work the old way instead of the Job being refused.
        return @()
    }
    return @($listed | ForEach-Object { "$_".Trim() } | Where-Object { $_ })
}

function Get-ScanFolders {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$Root
    )

    # Folder depth per repository. Shallower and the choice is useless, deeper and
    # the screen turns into a file browser. This yields roughly 7 frontend and
    # 8 backend entries.
    $specs = switch ($Repository) {
        'frontend' { @('src/features/*', 'src/app', 'src/shared/*', 'src/styles') }
        'backend' { @('src/main/java/org/urizo/axmodulestudio/backend/*') }
        default { throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $Repository" }
    }

    # The fixed Denylist is not applied here. The Backend holds the single copy of
    # that list and filters this raw result, so the two cannot drift apart.
    $folders = [System.Collections.Generic.List[string]]::new()
    foreach ($spec in $specs) {
        $relative = $spec -replace '/\*$', ''
        $absolute = Join-Path $Root ($relative -replace '/', [IO.Path]::DirectorySeparatorChar)
        if (-not (Test-Path -LiteralPath $absolute -PathType Container)) {
            continue
        }
        if ($spec.EndsWith('/*')) {
            foreach ($child in (Get-ChildItem -LiteralPath $absolute -Directory | Sort-Object Name)) {
                $folders.Add("$relative/$($child.Name)")
            }
        }
        else {
            $folders.Add($relative)
        }
    }
    return $folders.ToArray()
}

function Invoke-PrepareScanWorktree {
    param($Payload)

    # The guardrail screen lists folders the administrator may allow or forbid.
    # That list must reflect dev, not one job's work in progress, so this folder
    # is job-independent and is never written to.
    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }

    $source = Get-RepositorySourcePath -Repository $repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_REPOSITORY_MISSING|저장소 폴더가 없습니다: $source"
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Resolved from what was already fetched. The runner never reaches the
        # network on its own; refreshing dev stays a human action.
        # Not piped into Select-Object: that stops the pipeline early and can kill
        # the native command, which then reports a non-zero exit code at random.
        $revision = @(& git -C $source rev-parse origin/dev 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_SCAN_FAILED|origin/dev 를 찾을 수 없습니다: $($revision -join ' ')"
        }
        $baseSha = "$($revision[0])".Trim()

        $target = Join-Path $WorkRoot "scan-$repository"
        if (Test-Path -LiteralPath $target -PathType Container) {
            $dirty = & git -C $target status --porcelain 2>&1
            if ($LASTEXITCODE -eq 0 -and $dirty) {
                # Nothing should ever edit this folder. If something did, keep it
                # and let a person look rather than overwriting the evidence.
                return @{
                    repo = $repository; scanPath = $target; sha = 'unchanged'
                    note = '로컬 변경이 있어 갱신하지 않았습니다.'
                    folders = (Get-ScanFolders -Repository $repository -Root $target)
                    files = (Get-ScanFiles -Root $target)
                }
            }
            $current = "$(& git -C $target rev-parse HEAD 2>&1)".Trim()
            if ($current -ne $baseSha) {
                $moved = & git -C $target checkout --detach $baseSha 2>&1
                if ($LASTEXITCODE -ne 0) {
                    throw "RUNNER_SCAN_FAILED|스캔 폴더 갱신 실패: $(($moved | Select-Object -Last 2) -join ' ')"
                }
            }
            return @{
                repo = $repository; scanPath = $target; sha = $baseSha; reused = $true
                folders = (Get-ScanFolders -Repository $repository -Root $target)
                files = (Get-ScanFiles -Root $target)
            }
        }

        $created = & git -C $source worktree add --detach $target $baseSha 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_SCAN_FAILED|$(($created | Select-Object -Last 2) -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return @{
        repo = $repository; scanPath = $target; sha = $baseSha; reused = $false
        folders = (Get-ScanFolders -Repository $repository -Root $target)
        files = (Get-ScanFiles -Root $target)
    }
}

function Get-PreviewArguments {
    param([Parameter(Mandatory = $true)][string]$BackendWorktree)

    return @(
        'compose', '-p', $PreviewProject, '--profile', 'spring-core',
        '--project-directory', $BackendWorktree,
        '-f', (Join-Path $BackendWorktree 'compose.dev.yaml'),
        '-f', $PreviewOverlay
    )
}

function Set-PreviewEnvironment {
    # Which screen the preview shows. A frontend Job passes its own exported workspace: the
    # whole point of the preview is the screen the model just changed, and the shared
    # checkout is not that. A backend Job did not change the screen, so it uses the canonical
    # frontend checkout next to the Backend repository.
    #
    # BackendSource is the same idea for the Backend half. compose.dev.yaml builds spring-app,
    # flyway and database from ${AXMS_BACKEND_SOURCE_ROOT:-.}, and the default is the project
    # directory, which is already right. But this runner is started by bootstrap-dev.ps1 from
    # inside start-cms-local.ps1's rebuild, which has that variable set to whichever checkout
    # was rebuilt - and Start-Process hands the whole environment down. Left inherited, a
    # backend Job's preview would build the main stack's code and offer it as the candidate
    # nobody wrote. Set here so the preview builds what this command was given, not what the
    # shell that happened to start the runner was doing.
    param([string]$FrontendSource, [string]$BackendSource)

    $env:AXMS_PREVIEW_NAME = $PreviewProject
    $env:AXMS_PREVIEW_HTTP_PORT = "$PreviewHttpPort"
    $env:AXMS_PREVIEW_DB_PORT = "$PreviewDbPort"
    $env:AXMS_PREVIEW_SECRETS_ROOT = Join-Path (Join-Path $workspaceRoot 'urizo-final-backend') '.local\secrets'
    # Set even when empty. Leaving an inherited value in place for a caller that passed nothing
    # is the exact failure this guards against; empty makes Compose fall back to the project
    # directory, which is the right answer for a caller that named no source.
    $env:AXMS_BACKEND_SOURCE_ROOT = $BackendSource
    $frontend = if ($FrontendSource) { $FrontendSource } else { Get-RepositorySourcePath -Repository 'frontend' }
    if (-not (Test-Path -LiteralPath $frontend -PathType Container)) {
        throw "RUNNER_REPOSITORY_MISSING|Frontend 미리보기 Source가 없습니다: $frontend"
    }
    $env:AXMS_PREVIEW_FRONTEND_SOURCE = $frontend
}

function Clear-PreviewEnvironment {
    foreach ($name in 'AXMS_PREVIEW_NAME', 'AXMS_PREVIEW_HTTP_PORT', 'AXMS_PREVIEW_DB_PORT',
        'AXMS_PREVIEW_SECRETS_ROOT', 'AXMS_PREVIEW_FRONTEND_SOURCE',
        'AXMS_BACKEND_SOURCE_ROOT') {
        Remove-Item "Env:$name" -ErrorAction SilentlyContinue
    }
}

function Invoke-PreviewDown {
    # No -v. The volume stays and the next PREVIEW_UP overwrites its contents, so
    # this script carries no command that can delete stored data at all.
    # -p alone is enough: Compose finds the stack from the container labels.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker compose -p $PreviewProject down 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($LASTEXITCODE -ne 0) {
        throw "RUNNER_PREVIEW_DOWN_FAILED|$(($output | Select-Object -Last 3) -join ' ')"
    }
    return @{ project = $PreviewProject }
}

function Copy-SourceDatabase {
    param([Parameter(Mandatory = $true)][string]$TargetContainer)

    # The dump holds administrator password hashes, so it lives in the temp
    # directory and is removed in finally even when a step throws.
    $dump = Join-Path $env:TEMP "axms-preview-$([guid]::NewGuid().ToString('N')).sql"
    try {
        # Start-Process, not ">": Windows PowerShell redirection adds a BOM and
        # rewrites line endings, so the dump would not be byte-identical.
        Start-Process -FilePath 'docker' -NoNewWindow -Wait -RedirectStandardOutput $dump -ArgumentList @(
            'exec', $SourceDatabaseContainer, 'pg_dump', '--clean', '--if-exists',
            '-U', 'bootstrap_admin', '-d', 'ax_module_studio')
        if (-not (Test-Path -LiteralPath $dump -PathType Leaf) -or (Get-Item -LiteralPath $dump).Length -lt 1024) {
            throw "RUNNER_PREVIEW_COPY_FAILED|원본 DB 를 읽지 못했습니다: $SourceDatabaseContainer"
        }

        # The dump only drops what the source knows about. Anything this preview
        # has and the source does not would survive, and the dump's own
        # "DROP SCHEMA app;" has no CASCADE, so it would fail on the leftovers.
        $wipe = & docker exec $TargetContainer psql -U bootstrap_admin -d ax_module_studio -v ON_ERROR_STOP=1 -c 'DROP SCHEMA IF EXISTS app CASCADE; DROP SCHEMA IF EXISTS batch CASCADE;' 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PREVIEW_COPY_FAILED|미리보기 DB 비우기 실패: $(($wipe | Select-Object -Last 2) -join ' ')"
        }

        $copy = & docker cp $dump "${TargetContainer}:/tmp/restore.sql" 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PREVIEW_COPY_FAILED|dump 전달 실패: $(($copy | Select-Object -Last 2) -join ' ')"
        }
        $restore = & docker exec $TargetContainer psql -U bootstrap_admin -d ax_module_studio -v ON_ERROR_STOP=1 -f /tmp/restore.sql 2>&1
        $restoreExit = $LASTEXITCODE
        & docker exec $TargetContainer rm -f /tmp/restore.sql 2>&1 | Out-Null
        if ($restoreExit -ne 0) {
            throw "RUNNER_PREVIEW_COPY_FAILED|복원 실패: $(($restore | Select-Object -Last 3) -join ' ')"
        }
        return (Get-Item -LiteralPath $dump).Length
    }
    finally {
        Remove-Item -LiteralPath $dump -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-PreviewUp {
    param($Payload)

    # BUILD already exported this workspace and produced the images from it, so the same
    # export is reused here rather than taken again.
    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    if (-not $repository) { $repository = 'backend' }
    $workspaceId = Get-PayloadValue -Payload $Payload -Name 'workspaceId'
    $exported = ''
    if ($workspaceId) {
        $exported = Get-WorkspaceHostPath -WorkspaceId $workspaceId
        if (-not (Test-Path -LiteralPath $exported -PathType Container)) {
            throw "RUNNER_WORKSPACE_MISSING|꺼낸 작업 폴더가 없습니다. BUILD 를 먼저 실행하세요: $exported"
        }
    }
    # Same split as BUILD: the Compose files are Backend files, so the project directory is a
    # Backend checkout even when the Job worked in the frontend.
    if ($repository -eq 'frontend') {
        # The canonical checkout rather than an AI work folder. Nothing changed in the Backend
        # for a frontend Job, so any Backend checkout would do - but <WorkRoot>\ai-backend is
        # only ever made by the old CREATE_WORKTREE command, which no product flow runs any
        # more. It survives here as a stale August copy and is absent under any other WorkRoot,
        # so a runner started with the team lead's default WorkRoot fails the frontend Job at
        # BUILD. The canonical path is derived from the same WorkRoot and is always there.
        $backendWorktree = Get-RepositorySourcePath -Repository 'backend'
        $frontendSource = $exported
    }
    else {
        $backendWorktree = if ($exported) { $exported } else { Get-AiWorktreePath -Repository 'backend' }
        $frontendSource = ''
    }
    if (-not (Test-Path -LiteralPath $PreviewOverlay -PathType Leaf)) {
        throw "RUNNER_OVERLAY_MISSING|미리보기 설정 파일이 없습니다: $PreviewOverlay"
    }

    $dumpBytes = 0
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Set-PreviewEnvironment -FrontendSource $frontendSource -BackendSource $backendWorktree
        # 1. Clear anything left behind, including from an abnormal exit.
        & docker compose -p $PreviewProject down 2>&1 | Out-Null

        # 2. Database only. The copy in step 3 drops and recreates tables, which
        #    PostgreSQL refuses while the application holds them open.
        $arguments = Get-PreviewArguments -BackendWorktree $backendWorktree
        $dbUp = & docker @arguments up -d --no-build --wait database 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PREVIEW_UP_FAILED|DB 기동 실패: $(($dbUp | Select-Object -Last 3) -join ' ')"
        }

        # 3. Without the real content the administrator sees an empty site and
        #    reads it as "the AI deleted my pages", so approval cannot happen.
        $dumpBytes = Copy-SourceDatabase -TargetContainer "$PreviewProject-database-1"

        # 4. Flyway now sees the copied history and applies only what is new.
        $up = & docker @arguments up -d --no-build --wait 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PREVIEW_UP_FAILED|기동 실패: $(($up | Select-Object -Last 3) -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previous
        Clear-PreviewEnvironment
    }
    return @{
        project = $PreviewProject
        url     = "http://127.0.0.1:$PreviewHttpPort"
        copied  = "$([math]::Round($dumpBytes / 1KB)) KB"
    }
}

function Invoke-ComposeBuild {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }

    # One repository maps to a fixed set of services. A backend preview still serves the
    # frontend, so BUILD prepares that fixed image from the canonical frontend checkout.
    # The runner never accepts a service name from the payload: that would let the queue
    # pick build targets.
    $services = switch ($repository) {
        'backend' { @('spring-app', 'flyway-migration', 'frontend') }
        'frontend' { @('frontend') }
        default { throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $repository" }
    }

    # Compose files live in the backend worktree, so it is always the project
    # directory. The frontend worktree is passed through the same variable the
    # preview overlay uses for both its build context and its source mount.
    # A workspaceId means the model worked in the MCP volume, so its files are
    # exported here first. Without one this stays the host worktree the command
    # already used, so the existing path is untouched.
    $workspaceId = Get-PayloadValue -Payload $Payload -Name 'workspaceId'
    $exported = if ($workspaceId) {
        Export-McpWorkspaceToHost -Repository $repository -WorkspaceId $workspaceId
    }
    else { '' }
    if ($repository -eq 'frontend') {
        # The Compose files live in the Backend repository, so the project directory stays a
        # Backend checkout no matter which repository is being built. A frontend workspace holds
        # no compose.dev.yaml and naming it here fails before the build starts. The canonical
        # checkout is used rather than <WorkRoot>\ai-backend: see PREVIEW_UP above for why that
        # folder cannot be relied on. Nothing in the Backend changed for a frontend Job, so the
        # canonical copy is also the correct one to compose from.
        $backendWorktree = Get-RepositorySourcePath -Repository 'backend'
        # The image is built from the model's own work when there is any. Falling back to the
        # shared checkout would build a screen nobody changed and call it the candidate.
        $frontendWorktree = if ($exported) { $exported } else { Get-AiWorktreePath -Repository 'frontend' }
    }
    else {
        $backendWorktree = if ($exported) { $exported } else { Get-AiWorktreePath -Repository 'backend' }
        $frontendWorktree = Get-RepositorySourcePath -Repository 'frontend'
    }
    if (-not (Test-Path -LiteralPath $PreviewOverlay -PathType Leaf)) {
        throw "RUNNER_OVERLAY_MISSING|미리보기 설정 파일이 없습니다: $PreviewOverlay"
    }

    $arguments = @(
        'compose', '-p', 'axms-preview', '--profile', 'spring-core',
        '--project-directory', $backendWorktree,
        '-f', (Join-Path $backendWorktree 'compose.dev.yaml'),
        '-f', $PreviewOverlay,
        'build'
    ) + $services

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Set-PreviewEnvironment -FrontendSource $frontendWorktree -BackendSource $backendWorktree
        $output = & docker @arguments 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
        Clear-PreviewEnvironment
    }
    if ($LASTEXITCODE -ne 0) {
        $tail = ($output | Select-Object -Last 5) -join ' '
        throw "RUNNER_BUILD_FAILED|$tail"
    }
    return @{ repo = $repository; services = $services; projectDirectory = $backendWorktree }
}

function Invoke-FrontendChecks {
    # The frontend runtime image installs dependencies and serves the sources; nothing in it
    # compiles or tests them. A backend candidate at least has to compile to become an image,
    # so a broken one dies at BUILD. A broken screen does not - it reaches the person who is
    # asked to approve it, and the guardrail screen promises the opposite in so many words:
    # "빌드 통과 필수 · 테스트 통과 필수 - 항상 켜져 있으며 끌 수 없습니다".
    #
    # BUILD has already produced this image from the same workspace and the dev dependencies
    # are in it, so the checks run in what is there. Building a second image would double the
    # wait and could check something other than what the preview is about to serve.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker run --rm $PreviewFrontendImage pnpm run verify 2>&1
        $exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }

    # vitest colours its summary, so the escape codes are stripped before anything is matched
    # or reported. Left in, they reach the approval screen as unreadable noise.
    # PowerShell 5.1 has no `e escape, and a raw control character in the source would not
    # survive an editor round trip, so the byte is named rather than typed.
    $escape = [char]27
    $plain = @($output | ForEach-Object { "$_" -replace "$escape\[[0-9;]*[A-Za-z]", '' })
    if ($exit -ne 0) {
        # What failed has to survive into one line on the approval screen. Taking the tail
        # does not work: pnpm echoes "Command failed" once per nested script and vitest signs
        # off with timings, so the compiler error ends up buried in its own noise. The lines
        # that name a failure are picked out instead, and the tail is only the fallback for a
        # failure shaped like nothing here.
        $named = @($plain | Select-String -Pattern 'error TS[0-9]|Tests .*failed|error during build|Error:' |
            ForEach-Object { $_.ToString().Trim() } | Select-Object -First 2)
        if ($named) {
            $tail = $named
        }
        else {
            $tail = @($plain | Where-Object { $_.Trim() -and $_ -notmatch 'ELIFECYCLE' }) |
                Select-Object -Last 3
        }
        throw "RUNNER_TEST_FAILED|$($tail -join ' / ')"
    }
    $tests = ($plain | Select-String -Pattern '^\s*Tests\s+\S' | Select-Object -Last 1)
    $summary = if ($tests) { $tests.ToString().Trim() } else { '검사 통과' }
    return @{ repo = 'frontend'; summary = "$summary · 타입 검사와 빌드 통과" }
}

function Invoke-BackendTests {
    # The runtime image has no Maven and no test dependencies: the Dockerfile
    # builds with -DskipTests on purpose. Tests therefore run in the build stage,
    # and the dependency cache is a named volume so only the first run downloads.
    $repository = 'backend'
    $worktree = Get-AiWorktreePath -Repository $repository
    $stageImage = "axms/preview-$repository-test:latest"

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $build = & docker build --target build -t $stageImage $worktree 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_TEST_FAILED|테스트 이미지 준비 실패: $(($build | Select-Object -Last 3) -join ' ')"
        }
        $output = & docker run --rm -v axms-maven-cache:/root/.m2 $stageImage mvn -B -ntp test 2>&1
        $exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }

    # Surefire prints one summary line. Report the counts either way: a failing
    # test run must say how many failed, not only that something failed.
    $summary = ($output | Select-String -Pattern 'Tests run: .*Skipped:' | Select-Object -Last 1).ToString().Trim()
    if ($exit -ne 0) {
        throw "RUNNER_TEST_FAILED|$summary"
    }
    return @{ repo = $repository; summary = $summary }
}

function Invoke-CheckAttempt {
    param([string]$Repository)

    if ($Repository -eq 'frontend') { return Invoke-FrontendChecks }
    return Invoke-BackendTests
}

<#
    The check's own words when it failed, or nothing when it failed for another reason.

    Only RUNNER_TEST_FAILED is worth a second run. A missing workspace or an unreadable
    payload will fail the same way however many times it is asked.
#>
function Get-CheckFailureDetail {
    param($Failure)

    $message = "$($Failure.Exception.Message)"
    $prefix = 'RUNNER_TEST_FAILED|'
    if (-not $message.StartsWith($prefix)) { return $null }
    return $message.Substring($prefix.Length)
}

<#
    Runs the checks, and runs them a second time if the first attempt failed.

    A check that fails because it ran out of patience is not a finding. Job 9b55bb27 was
    told "AI 가 만든 화면이 검사를 통과하지 못했습니다" about AppShell.session-race.test.tsx,
    which the change never touched: the suite runs 27 files at once, and under that
    contention one assertion crossed the one second findBy waits. The same commit passed on
    another day. The wait itself is now four seconds (frontend src/test/setup.ts), which
    covers that run twice over - but no fixed number can promise it never happens again, and
    a machine under more load than usual will cross any line eventually.

    A second run answers that without guessing. Genuinely broken code fails both times. A
    single bad moment has to happen twice in a row to be believed, which is far rarer than
    once.

    The first failure is not thrown away. It is carried into the summary a super
    administrator reads, so a test that keeps needing its second chance is visible rather
    than quietly absorbed - "실패는 조용하지 않게". A general administrator is not shown the
    retry: there is nothing for them to do about it, and the only difference they can see is
    that a failing check takes about twice as long.

    Twice, not three times. Each extra attempt costs another full check - about a minute for
    the frontend, several for the backend - and buys less than the one before it.
#>
function Invoke-Tests {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }
    if ($repository -ne 'frontend' -and $repository -ne 'backend') {
        throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $repository"
    }

    $firstDetail = $null
    try {
        return Invoke-CheckAttempt -Repository $repository
    }
    catch {
        $firstDetail = Get-CheckFailureDetail -Failure $_
        if ($null -eq $firstDetail) { throw }
    }

    # Nothing is written to the pipeline here. This function's value is its result
    # hashtable, and a stray Write-Output would be returned alongside it - the caller
    # then holds an array and $result.ContainsKey(...) fails on it. The retry is not
    # hidden by leaving it out: it is carried in the summary the caller prints and
    # stores.
    try {
        $result = Invoke-CheckAttempt -Repository $repository
    }
    catch {
        $secondDetail = Get-CheckFailureDetail -Failure $_
        if ($null -eq $secondDetail) { throw }
        throw "RUNNER_TEST_FAILED|두 번 다 실패 · 1차 $firstDetail · 2차 $secondDetail"
    }

    $result.summary = "1차 실패 후 재검사 통과 · 1차 $firstDetail · 2차 $($result.summary)"
    return $result
}

function Invoke-CreatePullRequest {
    param($Payload)

    . (Join-Path $PSScriptRoot 'github-app-pr.ps1')

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    $branch = Get-PayloadValue -Payload $Payload -Name 'branch'
    $title = Get-PayloadValue -Payload $Payload -Name 'title'
    $candidateSha = Get-PayloadValue -Payload $Payload -Name 'candidateSha'
    $expectedDiffDigest = Get-PayloadValue -Payload $Payload -Name 'diffDigest'
    $validationHash = Get-PayloadValue -Payload $Payload -Name 'validationHash'
    $workspaceId = Get-PayloadValue -Payload $Payload -Name 'workspaceId'
    if (-not $title) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 title 이 없습니다.' }
    Assert-AxmsGitHubPrInput -Repository $repository -Branch $branch `
        -WorkspaceId $workspaceId -CandidateSha $candidateSha `
        -DiffDigest $expectedDiffDigest -ValidationHash $validationHash

    $worktree = Export-McpWorkspaceToHost `
        -Repository $repository -WorkspaceId $workspaceId
    $workspaceMarker = "$(@(& git -C $worktree config --local --get axms.repository 2>&1) |
        Select-Object -Last 1)".Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|꺼낸 Workspace marker를 확인하지 못했습니다.'
    }
    $canonicalSource = Get-RepositorySourcePath -Repository $repository
    if (-not (Test-Path -LiteralPath $canonicalSource -PathType Container)) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|선택 저장소의 canonical Source가 없습니다.'
    }
    $originUrl = "$(@(& git -C $canonicalSource remote get-url origin 2>&1) |
        Select-Object -First 1)".Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|선택 저장소의 canonical origin을 확인하지 못했습니다.'
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $appSession = $null
    $previousAppEnvironment = $null
    try {
        $baseHead = "$(@(& git -C $worktree rev-parse HEAD 2>&1)[0])".Trim()
        & git -C $worktree diff --cached --quiet --exit-code 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            throw 'RUNNER_PR_SUBJECT_BLOCKED|승인된 staged 변경이 없어 PR 을 만들 수 없습니다.'
        }
        if ($LASTEXITCODE -ne 1) {
            throw 'RUNNER_PR_FAILED|승인된 staged 변경을 확인하지 못했습니다.'
        }
        $actualDiffDigest = Get-StagedDiffDigest -Worktree $worktree
        $slug = Assert-AxmsGitHubPrWorkspaceBinding -Repository $repository `
            -WorkspaceMarker $workspaceMarker -OriginUrl $originUrl `
            -CandidateSha $candidateSha -ActualHeadSha "sha1:$baseHead" `
            -ExpectedDiffDigest $expectedDiffDigest -ActualDiffDigest $actualDiffDigest

        # This is the first GitHub network boundary. Every repository, workspace,
        # origin and approved-subject check above has already passed.
        $appSession = New-AxmsGitHubAppSession -SecretsRoot $SecretsRoot `
            -Repository $repository -RepositorySlug $slug
        $previousAppEnvironment = Enter-AxmsGitHubAppEnvironment `
            -Token $appSession.Token

        $current = "$(@(& git -C $worktree rev-parse --abbrev-ref HEAD 2>&1)[0])".Trim()
        if ($current -ne $branch) {
            $switched = & git -C $worktree switch -c $branch 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "RUNNER_PR_FAILED|브랜치 생성 실패: $(($switched | Select-Object -Last 2) -join ' ')"
            }
        }
        $env:GIT_AUTHOR_DATE = '2000-01-01T00:00:00Z'
        $env:GIT_COMMITTER_DATE = '2000-01-01T00:00:00Z'
        try {
            $committed = & git -C $worktree `
                -c "user.name=$($appSession.BotLogin)" `
                -c "user.email=$($appSession.BotId)+$($appSession.BotLogin)@users.noreply.github.com" `
                commit --no-gpg-sign --no-verify -m $title 2>&1
            $commitExit = $LASTEXITCODE
        }
        finally {
            Remove-Item Env:GIT_AUTHOR_DATE -ErrorAction SilentlyContinue
            Remove-Item Env:GIT_COMMITTER_DATE -ErrorAction SilentlyContinue
        }
        if ($commitExit -ne 0) {
            throw "RUNNER_PR_FAILED|Candidate commit 생성 실패: $(($committed | Select-Object -Last 2) -join ' ')"
        }
        $headSha = "$(@(& git -C $worktree rev-parse HEAD 2>&1)[0])".Trim()
        $parentSha = "$(@(& git -C $worktree rev-parse HEAD^ 2>&1)[0])".Trim()
        if ($parentSha -ne $baseHead -or $headSha -notmatch '^[0-9a-f]{40}$') {
            throw 'RUNNER_PR_SUBJECT_BLOCKED|생성된 PR head가 승인 Candidate에 직접 연결되지 않았습니다.'
        }

        $body = Get-PayloadValue -Payload $Payload -Name 'body'
        if (-not $body) { $body = $title }
        $boundBody = New-AxmsPullRequestBody -Body $body `
            -CandidateSha $candidateSha -HeadSha "sha1:$headSha" `
            -ValidationHash $validationHash

        $existing = Get-ExactPullRequest -Slug $slug -Branch $branch `
            -CandidateSha $candidateSha -ExpectedHeadSha "sha1:$headSha" `
            -ValidationHash $validationHash -Repository $repository `
            -BotLogin $appSession.BotLogin -Title $title -Body $boundBody
        if ($null -ne $existing) { return $existing }

        $pushUrl = "https://github.com/$slug.git"
        $pushed = & git -C $worktree `
            -c credential.helper= `
            -c "credential.helper=!gh auth git-credential" `
            -c http.extraHeader= `
            -c "http.https://github.com/.extraHeader=" `
            -c "http.$pushUrl.extraHeader=" `
            push $pushUrl "HEAD`:refs/heads/$branch" 2>&1
        if ($LASTEXITCODE -ne 0) {
            $detail = "$(($pushed | Select-Object -Last 3) -join ' ')"
            if (Test-NetworkFailure -Detail $detail) {
                throw "RUNNER_GITHUB_TRANSIENT|push 일시 실패: $detail"
            }
            throw "RUNNER_PR_BLOCKED|push 차단: $detail"
        }

        # The body is the multi-line Korean summary the control plane builds from the recorded
        # request, plan, diff and approvals. Windows PowerShell rewrites quoting and encoding when
        # it hands an argument to a native command - the same trap that mangled Korean elsewhere
        # in this workspace - so it is written as a BOM-less UTF-8 file and handed over by path.
        $bodyFile = Join-Path $env:TEMP "axms-pr-body-$([IO.Path]::GetRandomFileName()).md"
        [IO.File]::WriteAllText($bodyFile, $boundBody, [Text.UTF8Encoding]::new($false))
        try {
            $created = & gh pr create --repo "github.com/$slug" --base dev --head $branch `
                --title $title --body-file $bodyFile 2>&1
        }
        finally {
            Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        }
        if ($LASTEXITCODE -ne 0) {
            Throw-GitHubFailure -Output $created -Operation 'PR 생성'
        }
        $receipt = Get-ExactPullRequest -Slug $slug -Branch $branch `
            -CandidateSha $candidateSha -ExpectedHeadSha "sha1:$headSha" `
            -ValidationHash $validationHash -Repository $repository `
            -BotLogin $appSession.BotLogin -Title $title -Body $boundBody
        if ($null -eq $receipt) {
            throw 'RUNNER_PR_BLOCKED|생성된 PR 을 정확히 다시 조회하지 못했습니다.'
        }
        $receipt.reused = $false
    }
    finally {
        if ($appSession) { $appSession.Token = $null }
        if ($previousAppEnvironment) {
            Exit-AxmsGitHubAppEnvironment -Previous $previousAppEnvironment
        }
        $ErrorActionPreference = $previous
    }
    return $receipt
}

function Get-StagedDiffDigest {
    param([Parameter(Mandatory = $true)][string]$Worktree)

    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = 'git'
    $start.WorkingDirectory = $Worktree
    $start.Arguments = 'diff --cached --no-ext-diff --no-textconv --no-color --text HEAD --'
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    if (-not $process.Start()) {
        throw 'RUNNER_PR_FAILED|staged Diff 검증 프로세스를 시작하지 못했습니다.'
    }
    $bytes = [IO.MemoryStream]::new()
    try {
        $process.StandardOutput.BaseStream.CopyTo($bytes)
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) {
            throw "RUNNER_PR_FAILED|staged Diff 검증 실패: $errorText"
        }
        $hash = [Security.Cryptography.SHA256]::Create()
        try {
            $digest = $hash.ComputeHash($bytes.ToArray())
        }
        finally { $hash.Dispose() }
        return 'sha256:' + ([BitConverter]::ToString($digest) -replace '-', '').ToLowerInvariant()
    }
    finally {
        $bytes.Dispose()
        $process.Dispose()
    }
}

function Throw-GitHubFailure {
    param($Output, [Parameter(Mandatory = $true)][string]$Operation)

    $detail = "$(@($Output) -join ' ')".Trim()
    if (Test-NetworkFailure -Detail $detail) {
        throw "RUNNER_GITHUB_TRANSIENT|$Operation 일시 실패: $detail"
    }
    throw "RUNNER_GITHUB_BLOCKED|$Operation 차단: $detail"
}

function Test-NetworkFailure {
    param([string]$Detail)

    return $Detail -match '(?i)(rate limit|timed? out|timeout|temporar|HTTP 5\d\d|502|503|504|could not resolve host|connection (reset|refused|closed)|failed to connect|network is unreachable|TLS handshake)'
}

function Invoke-CheckDevMerge {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repository'
    $prNumber = Get-PayloadValue -Payload $Payload -Name 'prNumber'
    $head = Get-PayloadValue -Payload $Payload -Name 'head'
    $headSha = Get-PayloadValue -Payload $Payload -Name 'headSha'
    $candidateSha = Get-PayloadValue -Payload $Payload -Name 'candidateSha'
    # Either published repository may be checked for its dev merge; the Backend decided
    # which ones can deploy, the runner only refuses a name it does not know.
    if ($repository -notin @('backend', 'frontend') -or "$prNumber" -notmatch '^[1-9][0-9]*$' `
            -or $head -notmatch '^system/llmops-[a-z0-9][a-z0-9-]*$' `
            -or $headSha -notmatch '^sha1:[0-9a-f]{40}$' `
            -or $candidateSha -notmatch '^sha1:[0-9a-f]{40}$') {
        throw 'RUNNER_PAYLOAD_INVALID|dev merge 확인 payload 가 올바르지 않습니다.'
    }
    # The pull request lives on the canonical repository's origin. Reading the slug there
    # (as the deploy worktree does) means the check does not depend on a Job worktree that
    # may already have been cleaned up by the time the merge is confirmed.
    $source = Get-RepositorySourcePath -Repository $repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_DEPLOY_BLOCKED|$repository canonical 저장소를 찾을 수 없습니다."
    }
    $slug = Get-RemoteSlug -Worktree $source
    $raw = & gh pr view ([int]$prNumber) --repo $slug `
        --json number,url,state,baseRefName,headRefName,headRefOid,mergeCommit 2>&1
    if ($LASTEXITCODE -ne 0) {
        $detail = "$(@($raw) -join ' ')".Trim()
        if (Test-NetworkFailure -Detail $detail) {
            throw "RUNNER_GITHUB_TRANSIENT|dev merge 조회 일시 실패: $detail"
        }
        return @{
            status = 'BLOCKED'; reason = 'GitHub 조회 권한 또는 PR 상태 확인이 차단되었습니다.'
            candidateSha = $candidateSha; head = $head; headSha = $headSha
        }
    }
    $item = "$($raw -join '')" | ConvertFrom-Json
    if ($item.baseRefName -ne 'dev' -or $item.headRefName -ne $head `
            -or "sha1:$($item.headRefOid)" -ne $headSha) {
        return @{
            status = 'BLOCKED'; reason = 'PR base/head/candidate 불일치'
            candidateSha = $candidateSha; head = $head; headSha = $headSha
        }
    }
    if ($item.state -eq 'MERGED') {
        if ($null -eq $item.mergeCommit -or "$($item.mergeCommit.oid)" -notmatch '^[0-9a-f]{40}$') {
            return @{
                status = 'BLOCKED'; reason = 'merge commit 누락'
                candidateSha = $candidateSha; head = $head; headSha = $headSha
            }
        }
        return @{
            status = 'MERGED'; mergeSha = "sha1:$($item.mergeCommit.oid)"
            candidateSha = $candidateSha; head = $head; headSha = $headSha
        }
    }
    if ($item.state -eq 'OPEN') {
        return @{
            status = 'NOT_MERGED'; candidateSha = $candidateSha
            head = $head; headSha = $headSha
        }
    }
    return @{
        status = 'BLOCKED'; reason = 'PR 이 merge 없이 닫혔습니다.'
        candidateSha = $candidateSha; head = $head; headSha = $headSha
    }
}

function Get-MergedDeployWorktree {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$MergeSha
    )

    $rawMergeSha = $MergeSha.Substring('sha1:'.Length)
    $source = Get-RepositorySourcePath -Repository $Repository
    if (-not (Test-Path -LiteralPath $source -PathType Container)) {
        throw "RUNNER_DEPLOY_BLOCKED|$Repository canonical 저장소를 찾을 수 없습니다."
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $fetched = & git -C $source fetch origin dev:refs/remotes/origin/dev 2>&1
        $fetchExit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($fetchExit -ne 0) {
        $detail = "$(($fetched | Select-Object -Last 4) -join ' ')"
        if (Test-NetworkFailure -Detail $detail) {
            throw "RUNNER_DEPLOY_TRANSIENT|origin/dev fetch 일시 실패: $detail"
        }
        throw "RUNNER_DEPLOY_BLOCKED|origin/dev fetch 차단: $detail"
    }

    & git -C $source cat-file -e "$rawMergeSha^{commit}" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'RUNNER_DEPLOY_BLOCKED|승인된 mergeSha commit을 찾을 수 없습니다.'
    }
    & git -C $source merge-base --is-ancestor $rawMergeSha origin/dev 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 1) {
        throw 'RUNNER_DEPLOY_BLOCKED|승인된 mergeSha가 현재 origin/dev에 포함되지 않습니다.'
    }
    if ($LASTEXITCODE -ne 0) {
        throw 'RUNNER_DEPLOY_BLOCKED|mergeSha와 origin/dev 관계를 확인하지 못했습니다.'
    }

    $resolvedWorkRoot = [IO.Path]::GetFullPath($WorkRoot)
    $target = [IO.Path]::GetFullPath((Join-Path $resolvedWorkRoot "deploy-$Repository"))
    $prefix = $resolvedWorkRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) `
        + [IO.Path]::DirectorySeparatorChar
    if (-not $target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'RUNNER_DEPLOY_BLOCKED|고정 deploy Worktree 경로가 WorkRoot 밖입니다.'
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if (Test-Path -LiteralPath $target -PathType Container) {
            $inside = "$(@(& git -C $target rev-parse --is-inside-work-tree 2>&1)[0])".Trim()
            if ($LASTEXITCODE -ne 0 -or $inside -ne 'true') {
                throw 'RUNNER_DEPLOY_BLOCKED|고정 deploy 경로가 관리되는 Git Worktree가 아닙니다.'
            }
            $dirty = @(& git -C $target status --porcelain 2>&1)
            if ($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0) {
                throw 'RUNNER_DEPLOY_BLOCKED|deploy Worktree에 보존해야 할 로컬 변경이 있습니다.'
            }
            $current = "$(@(& git -C $target rev-parse HEAD 2>&1)[0])".Trim()
            if ($current -ne $rawMergeSha) {
                $checkedOut = & git -C $target checkout --detach $rawMergeSha 2>&1
                if ($LASTEXITCODE -ne 0) {
                    throw "RUNNER_DEPLOY_BLOCKED|deploy Worktree 갱신 실패: $(($checkedOut | Select-Object -Last 3) -join ' ')"
                }
            }
        }
        else {
            $created = & git -C $source worktree add --detach $target $rawMergeSha 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "RUNNER_DEPLOY_BLOCKED|deploy Worktree 생성 실패: $(($created | Select-Object -Last 3) -join ' ')"
            }
        }
        $verified = "$(@(& git -C $target rev-parse HEAD 2>&1)[0])".Trim()
    }
    finally { $ErrorActionPreference = $previous }
    if ($verified -ne $rawMergeSha) {
        throw 'RUNNER_DEPLOY_BLOCKED|deploy Worktree가 승인된 mergeSha를 가리키지 않습니다.'
    }
    return $target
}

function Invoke-LocalDockerComposeDeployment {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repository'
    $deploymentRequestId = Get-PayloadValue -Payload $Payload -Name 'deploymentRequestId'
    $prNumber = Get-PayloadValue -Payload $Payload -Name 'prNumber'
    $candidateSha = Get-PayloadValue -Payload $Payload -Name 'candidateSha'
    $mergeSha = Get-PayloadValue -Payload $Payload -Name 'mergeSha'
    $validationHash = Get-PayloadValue -Payload $Payload -Name 'validationHash'
    # One fixed Compose service per repository. The Backend adapter chose the target; the
    # runner only translates the repository it already knows into that service.
    $targets = @{ backend = 'full:backend:spring-app'; frontend = 'full:frontend:frontend' }
    if (-not $targets.ContainsKey("$repository") `
            -or $deploymentRequestId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' `
            -or "$prNumber" -notmatch '^[1-9][0-9]*$' `
            -or $candidateSha -notmatch '^sha1:[0-9a-f]{40}$' `
            -or $mergeSha -notmatch '^sha1:[0-9a-f]{40}$' `
            -or $validationHash -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'RUNNER_PAYLOAD_INVALID|고정 로컬 배포의 승인 증거가 올바르지 않습니다.'
    }
    $sourceRoot = Get-MergedDeployWorktree -Repository $repository -MergeSha $mergeSha
    # The Master wrapper sits beside the canonical repositories under the workspace root, which
    # is where WorkRoot already points; deriving it from this script's own repository would
    # break as soon as the runner is started from a worktree.
    $masterScript = Join-Path $workspaceRoot 'urizo-final-master\scripts\rebuild-local-service.ps1'
    if (-not (Test-Path -LiteralPath $masterScript -PathType Leaf)) {
        throw 'RUNNER_DEPLOY_BLOCKED|고정 배포 스크립트를 찾을 수 없습니다.'
    }
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = switch ($repository) {
            'backend' {
                & $masterScript -Service spring-app -Profile full `
                    -SourceRoot $sourceRoot -ApproveLocalMutation -ApproveNetwork 2>&1
            }
            'frontend' {
                & $masterScript -Service frontend -Profile full `
                    -SourceRoot $sourceRoot -ApproveLocalMutation -ApproveNetwork 2>&1
            }
        }
        $exit = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $previous }
    if ($exit -ne 0) {
        $detail = "$(($output | Select-Object -Last 4) -join ' ')"
        if (Test-NetworkFailure -Detail $detail) {
            throw "RUNNER_DEPLOY_TRANSIENT|로컬 Compose 배포 일시 실패: $detail"
        }
        throw "RUNNER_DEPLOY_BLOCKED|로컬 Compose 배포 실패: $detail"
    }
    return @{
        adapter = 'local-docker-compose'
        target = $targets["$repository"]
        sourceSha = $mergeSha
        status = 'COMPLETED'
    }
}

function Get-RemoteSlug {
    param([Parameter(Mandatory = $true)][string]$Worktree)

    $url = "$(@(& git -C $Worktree remote get-url origin 2>&1)[0])".Trim()
    if ($url -match 'github\.com[:/](.+?)(\.git)?$') {
        return $Matches[1]
    }
    throw "RUNNER_PR_FAILED|origin 주소를 해석하지 못했습니다: $url"
}

function Complete-RunnerTask {
    param([Parameter(Mandatory = $true)]$Task)

    $outcome = 'SUCCEEDED'
    $result = $null
    $errorCode = $null
    $detail = ''

    try {
        switch ($Task.kind) {
            'CREATE_WORKTREE' {
                $result = Invoke-CreateWorktree -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'BUILD' {
                $result = Invoke-ComposeBuild -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'TEST' {
                $result = Invoke-Tests -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'PREPARE_SCAN_WORKTREE' {
                $result = Invoke-PrepareScanWorktree -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'PREVIEW_UP' {
                $result = Invoke-PreviewUp -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'PREVIEW_DOWN' {
                $result = Invoke-PreviewDown
            }
            'CREATE_PR' {
                $result = Invoke-CreatePullRequest -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'CHECK_DEV_MERGE' {
                $result = Invoke-CheckDevMerge -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            'DEPLOY_LOCAL_COMPOSE' {
                $result = Invoke-LocalDockerComposeDeployment -Payload (Get-PayloadValue -Payload $Task -Name 'payload')
            }
            default {
                # Fixed allowlist: an unknown or not-yet-built command is refused
                # rather than interpreted. The runner holds the Docker privilege.
                throw "RUNNER_KIND_NOT_IMPLEMENTED|아직 구현하지 않은 명령입니다: $($Task.kind)"
            }
        }
    }
    catch {
        $parts = "$($_.Exception.Message)" -split '\|', 2
        $errorCode = if ($parts[0] -match '^[A-Z][A-Z0-9_]{2,119}$') { $parts[0] } else { 'RUNNER_COMMAND_FAILED' }
        $outcome = if ($errorCode -in @('RUNNER_GITHUB_TRANSIENT', 'RUNNER_DEPLOY_TRANSIENT')) {
            'RETRYABLE_FAILURE'
        }
        else { 'PERMANENT_FAILURE' }
        $detail = if ($parts.Count -gt 1) { $parts[1] } else { "$($_.Exception.Message)" }
    }

    $body = @{
        schemaVersion = '1.0'
        traceId       = [guid]::NewGuid().ToString()
        runnerId      = $runnerId
        taskId        = $Task.taskId
        leaseId       = $Task.leaseId
        outcome       = $outcome
    }
    if ($null -ne $result) { $body['result'] = $result }
    # A failure reported as a code alone tells the approval screen that something broke and
    # nothing about what. The reason was already computed for the console line; it travels in
    # the same result field, which the Job authority stores for any outcome. Bounded because
    # it is stored and shown, and a compiler can be arbitrarily talkative.
    elseif ($detail) {
        $reason = "$detail"
        if ($reason.Length -gt 500) { $reason = $reason.Substring(0, 500) }
        $body['result'] = @{ detail = $reason }
    }
    if ($null -ne $errorCode) { $body['errorCode'] = $errorCode }

    $stamp = (Get-Date).ToString('HH:mm:ss')
    try {
        Invoke-RunnerRequest -Uri "$BaseUri/internal/coding/runner/tasks/$($Task.taskId)/outcomes" -Body $body | Out-Null
        if ($outcome -eq 'SUCCEEDED') {
            $summary = if ($result.ContainsKey('worktreePath')) {
                $result.worktreePath + $(if ($result.reused) { ' (기존 폴더 재사용)' } else { '' })
            }
            elseif ($result.ContainsKey('workspacePath')) {
                $result.workspacePath + $(if ($result.reused) { ' (기존 폴더 재사용)' } else { '' })
            }
            elseif ($result.ContainsKey('created')) {
                if ($result.created) { "PR 생성 · $($result.url)" } else { "PR 불가 · $($result.reason)" }
            }
            elseif ($result.ContainsKey('url')) {
                "$($result.project) · $($result.url) · 복사 $($result.copied)"
            }
            elseif ($result.ContainsKey('project')) {
                "$($result.project) 내림"
            }
            elseif ($result.ContainsKey('scanPath')) {
                "$($result.scanPath) · $($result.sha)"
            }
            elseif ($result.ContainsKey('summary')) {
                "$($result.repo) · $($result.summary)"
            }
            else {
                "$($result.repo) · " + ($result.services -join ', ')
            }
            Write-Output "$stamp  완료 · $summary"
        }
        else {
            Write-Output "$stamp  실패 보고 · $errorCode · $detail"
        }
    }
    catch {
        # Reporting failed, so the task stays RUNNING. The lease reaper puts it
        # back on the queue; nothing is lost by giving up here.
        Write-Output "$stamp  결과 보고 실패 (HTTP $(Get-FailureStatus -Failure $_)) · 임대 만료로 회수됩니다"
    }
}

# Both manual and automatic starts share this guard before the first claim.
$runnerHash = [Security.Cryptography.SHA256]::Create()
try {
    $runnerKey = [BitConverter]::ToString($runnerHash.ComputeHash(
        [Text.Encoding]::UTF8.GetBytes($BaseUri.TrimEnd('/').ToLowerInvariant()))).Replace('-', '')
}
finally { $runnerHash.Dispose() }
$runnerMutex = [Threading.Mutex]::new($false, "Local\AXMS-CodingRunner-$runnerKey")
$runnerLockHeld = $false
try {
    try { $runnerLockHeld = $runnerMutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $runnerLockHeld = $true }
    if (-not $runnerLockHeld) {
        Write-Output 'CODING RUNNER PRESERVED: another runner already owns this target; no task was claimed.'
        return
    }

Write-Output "실행기 시작 · $runnerId"
Write-Output "저장소  $repositoryRoot"
Write-Output "작업폴더 $WorkRoot"
Write-Output "대상    $claimUri"
Write-Output "주기    ${PollIntervalSeconds}초 · 중지는 Ctrl+C"
Write-Output ''

do {
    $stamp = (Get-Date).ToString('HH:mm:ss')
    try {
        $task = Invoke-RunnerRequest -Uri $claimUri -Body @{
            schemaVersion = '1.0'
            runnerId      = $runnerId
            traceId       = [guid]::NewGuid().ToString()
        }
        if ($StartupSignalPath) {
            # A local acknowledgement failure must not strand a task already claimed.
            try { Set-Content -LiteralPath $StartupSignalPath -Value ([string]$PID) -Encoding ASCII }
            catch { Write-Warning 'Runner startup acknowledgement could not be written.' }
            $StartupSignalPath = ''
        }
        if ($null -eq $task) {
            Write-Output "$stamp  할 일 없음"
        }
        else {
            $kind = if ($task.PSObject.Properties.Match('kind').Count -gt 0) { $task.kind } else { '(kind 없음)' }
            Write-Output "$stamp  작업 받음 · $kind"
            Complete-RunnerTask -Task $task
        }
    }
    catch {
        $status = Get-FailureStatus -Failure $_
        if ($status -eq 0) {
            Write-Output "$stamp  할 일 없음 (Spring 통로 대기 중 · 연결 안 됨)"
        }
        elseif ($status -eq 401) {
            Write-Output "$stamp  인증 거부 (HTTP 401) · 자격증명이 이 DB 에 등록돼 있는지 확인"
        }
        else {
            Write-Output "$stamp  할 일 없음 (Spring 통로 대기 중 · HTTP $status)"
        }
    }

    if ($RunOnce) {
        break
    }
    Start-Sleep -Seconds $PollIntervalSeconds
}
while ($true)
}
finally {
    if ($runnerLockHeld) { $runnerMutex.ReleaseMutex() }
    $runnerMutex.Dispose()
}

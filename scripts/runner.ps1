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

    [switch]$RunOnce
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
    if (-not (Test-Path -LiteralPath (Join-Path $target 'compose.dev.yaml') -PathType Leaf)) {
        throw "RUNNER_WORKSPACE_EXPORT_FAILED|꺼낸 폴더에 compose.dev.yaml 이 없습니다: $target"
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
                return @{ repo = $repository; scanPath = $target; sha = 'unchanged'; note = '로컬 변경이 있어 갱신하지 않았습니다.' }
            }
            $current = "$(& git -C $target rev-parse HEAD 2>&1)".Trim()
            if ($current -ne $baseSha) {
                $moved = & git -C $target checkout --detach $baseSha 2>&1
                if ($LASTEXITCODE -ne 0) {
                    throw "RUNNER_SCAN_FAILED|스캔 폴더 갱신 실패: $(($moved | Select-Object -Last 2) -join ' ')"
                }
            }
            return @{ repo = $repository; scanPath = $target; sha = $baseSha; reused = $true }
        }

        $created = & git -C $source worktree add --detach $target $baseSha 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_SCAN_FAILED|$(($created | Select-Object -Last 2) -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return @{ repo = $repository; scanPath = $target; sha = $baseSha; reused = $false }
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
    $env:AXMS_PREVIEW_NAME = $PreviewProject
    $env:AXMS_PREVIEW_HTTP_PORT = "$PreviewHttpPort"
    $env:AXMS_PREVIEW_DB_PORT = "$PreviewDbPort"
    $env:AXMS_PREVIEW_SECRETS_ROOT = Join-Path (Join-Path $workspaceRoot 'urizo-final-backend') '.local\secrets'
    $frontend = Join-Path $WorkRoot 'ai-frontend'
    if (Test-Path -LiteralPath $frontend -PathType Container) {
        $env:AXMS_PREVIEW_FRONTEND_SOURCE = $frontend
    }
}

function Clear-PreviewEnvironment {
    foreach ($name in 'AXMS_PREVIEW_NAME', 'AXMS_PREVIEW_HTTP_PORT', 'AXMS_PREVIEW_DB_PORT',
        'AXMS_PREVIEW_SECRETS_ROOT', 'AXMS_PREVIEW_FRONTEND_SOURCE') {
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

    # BUILD already exported this workspace and produced the images from it, so
    # the same directory is used here for the Compose files.
    $workspaceId = Get-PayloadValue -Payload $Payload -Name 'workspaceId'
    if ($workspaceId) {
        $backendWorktree = Get-WorkspaceHostPath -WorkspaceId $workspaceId
        if (-not (Test-Path -LiteralPath $backendWorktree -PathType Container)) {
            throw "RUNNER_WORKSPACE_MISSING|꺼낸 작업 폴더가 없습니다. BUILD 를 먼저 실행하세요: $backendWorktree"
        }
    }
    else {
        $backendWorktree = Get-AiWorktreePath -Repository 'backend'
    }
    if (-not (Test-Path -LiteralPath $PreviewOverlay -PathType Leaf)) {
        throw "RUNNER_OVERLAY_MISSING|미리보기 설정 파일이 없습니다: $PreviewOverlay"
    }

    $dumpBytes = 0
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Set-PreviewEnvironment
    try {
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

    # One repository maps to a fixed set of services. The runner never accepts a
    # service name from the payload: that would let the queue pick build targets.
    $services = switch ($repository) {
        'backend' { @('spring-app', 'flyway-migration') }
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
    if ($workspaceId) {
        $backendWorktree = Export-McpWorkspaceToHost -Repository $repository -WorkspaceId $workspaceId
    }
    else {
        $backendWorktree = Get-AiWorktreePath -Repository 'backend'
    }
    $frontendWorktree = if ($repository -eq 'frontend') { Get-AiWorktreePath -Repository 'frontend' } else { '' }
    if (-not (Test-Path -LiteralPath $PreviewOverlay -PathType Leaf)) {
        throw "RUNNER_OVERLAY_MISSING|미리보기 설정 파일이 없습니다: $PreviewOverlay"
    }

    $env:AXMS_PREVIEW_SECRETS_ROOT = Join-Path (Join-Path $workspaceRoot 'urizo-final-backend') '.local\secrets'
    if ($frontendWorktree) { $env:AXMS_PREVIEW_FRONTEND_SOURCE = $frontendWorktree }

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
        $output = & docker @arguments 2>&1
    }
    finally {
        $ErrorActionPreference = $previous
        Remove-Item Env:AXMS_PREVIEW_FRONTEND_SOURCE -ErrorAction SilentlyContinue
        Remove-Item Env:AXMS_PREVIEW_SECRETS_ROOT -ErrorAction SilentlyContinue
    }
    if ($LASTEXITCODE -ne 0) {
        $tail = ($output | Select-Object -Last 5) -join ' '
        throw "RUNNER_BUILD_FAILED|$tail"
    }
    return @{ repo = $repository; services = $services; projectDirectory = $backendWorktree }
}

function Invoke-Tests {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }

    # The runtime image has no Maven and no test dependencies: the Dockerfile
    # builds with -DskipTests on purpose. Tests therefore run in the build stage,
    # and the dependency cache is a named volume so only the first run downloads.
    $worktree = Get-AiWorktreePath -Repository $repository
    $stageImage = "axms/preview-$repository-test:latest"
    $target = switch ($repository) {
        'backend' { 'build' }
        'frontend' { throw 'RUNNER_KIND_NOT_IMPLEMENTED|frontend 테스트는 아직 구현하지 않았습니다.' }
        default { throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $repository" }
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $build = & docker build --target $target -t $stageImage $worktree 2>&1
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

function Invoke-CreatePullRequest {
    param($Payload)

    $repository = Get-PayloadValue -Payload $Payload -Name 'repo'
    $branch = Get-PayloadValue -Payload $Payload -Name 'branch'
    $title = Get-PayloadValue -Payload $Payload -Name 'title'
    if (-not $repository) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 repo 가 없습니다.' }
    if (-not $branch) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 branch 가 없습니다.' }
    if (-not $title) { throw 'RUNNER_PAYLOAD_INVALID|payload 에 title 이 없습니다.' }

    # Branch names are issued by Spring. Refusing anything else keeps a generated
    # payload from pushing to a name that looks like a person's work.
    if ($branch -notmatch '^system/llmops-[a-z0-9][a-z0-9-]*$') {
        throw "RUNNER_PAYLOAD_INVALID|허용되지 않은 브랜치 이름입니다: $branch"
    }

    $worktree = Get-AiWorktreePath -Repository $repository
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        # Nothing to review is not an error. It happens whenever the run produced
        # no change, and failing here would retry a task that can never succeed.
        $ahead = @(& git -C $worktree rev-list --count origin/dev..HEAD 2>&1)
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PR_FAILED|커밋 수를 확인하지 못했습니다: $($ahead -join ' ')"
        }
        if ([int]"$($ahead[0])".Trim() -eq 0) {
            return @{ repo = $repository; created = $false; reason = '커밋이 없어 PR 을 만들 수 없습니다.' }
        }

        # The same applies to a missing gh: report it and let the rest of the run
        # stand, rather than throwing away work that already passed review.
        & gh --version 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            return @{ repo = $repository; created = $false; reason = 'gh 가 없어 PR 을 만들 수 없습니다.' }
        }

        $current = "$(@(& git -C $worktree rev-parse --abbrev-ref HEAD 2>&1)[0])".Trim()
        if ($current -ne $branch) {
            $switched = & git -C $worktree switch -c $branch 2>&1
            if ($LASTEXITCODE -ne 0) {
                throw "RUNNER_PR_FAILED|브랜치 생성 실패: $(($switched | Select-Object -Last 2) -join ' ')"
            }
        }

        $pushed = & git -C $worktree push -u origin $branch 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PR_FAILED|push 실패: $(($pushed | Select-Object -Last 2) -join ' ')"
        }

        $body = Get-PayloadValue -Payload $Payload -Name 'body'
        if (-not $body) { $body = $title }
        $created = & gh pr create --repo (Get-RemoteSlug -Worktree $worktree) --base dev --head $branch --title $title --body $body 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "RUNNER_PR_FAILED|PR 생성 실패: $(($created | Select-Object -Last 2) -join ' ')"
        }
    }
    finally {
        $ErrorActionPreference = $previous
    }
    return @{ repo = $repository; created = $true; url = "$(@($created)[-1])".Trim() }
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
            default {
                # Fixed allowlist: an unknown or not-yet-built command is refused
                # rather than interpreted. The runner holds the Docker privilege.
                throw "RUNNER_KIND_NOT_IMPLEMENTED|아직 구현하지 않은 명령입니다: $($Task.kind)"
            }
        }
    }
    catch {
        $parts = "$($_.Exception.Message)" -split '\|', 2
        $outcome = 'PERMANENT_FAILURE'
        $errorCode = if ($parts[0] -match '^[A-Z][A-Z0-9_]{2,119}$') { $parts[0] } else { 'RUNNER_COMMAND_FAILED' }
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
    if ($null -ne $errorCode) { $body['errorCode'] = $errorCode }

    $stamp = (Get-Date).ToString('HH:mm:ss')
    try {
        Invoke-RunnerRequest -Uri "$BaseUri/internal/coding/runner/tasks/$($Task.taskId)/outcomes" -Body $body | Out-Null
        if ($outcome -eq 'SUCCEEDED') {
            $summary = if ($result.ContainsKey('worktreePath')) {
                $result.worktreePath + $(if ($result.reused) { ' (기존 폴더 재사용)' } else { '' })
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

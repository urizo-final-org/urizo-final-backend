function Assert-AxmsGitHubPrInput {
    param(
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$Repository,
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$Branch,
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$WorkspaceId,
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$CandidateSha,
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$DiffDigest,
        [Parameter(Mandatory = $true)][AllowNull()][AllowEmptyString()][string]$ValidationHash
    )

    if ($Repository -notin @('backend', 'frontend')) {
        throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $Repository"
    }
    if ($Branch -notmatch '^system/llmops-[a-z0-9][a-z0-9-]*$') {
        throw "RUNNER_PAYLOAD_INVALID|허용되지 않은 브랜치 이름입니다: $Branch"
    }
    if ($WorkspaceId -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$') {
        throw 'RUNNER_PAYLOAD_INVALID|payload 의 workspaceId 형식이 올바르지 않습니다.'
    }
    if ($CandidateSha -notmatch '^sha1:[0-9a-f]{40}$') {
        throw 'RUNNER_PAYLOAD_INVALID|payload 의 candidateSha 형식이 올바르지 않습니다.'
    }
    if ($DiffDigest -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'RUNNER_PAYLOAD_INVALID|payload 의 diffDigest 형식이 올바르지 않습니다.'
    }
    if ($ValidationHash -notmatch '^sha256:[0-9a-f]{64}$') {
        throw 'RUNNER_PAYLOAD_INVALID|payload 의 validationHash 형식이 올바르지 않습니다.'
    }
}

function Resolve-AxmsGitHubSlug {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$OriginUrl
    )

    if ($Repository -notin @('backend', 'frontend')) {
        throw "RUNNER_PAYLOAD_INVALID|알 수 없는 저장소입니다: $Repository"
    }
    $slug = $null
    if ($OriginUrl -match '^https://github\.com/([^/]+/[^/]+?)(?:\.git)?/?$') {
        $slug = $Matches[1]
    }
    elseif ($OriginUrl -match '^git@github\.com:([^/]+/[^/]+?)(?:\.git)?$') {
        $slug = $Matches[1]
    }
    $expectedName = "urizo-final-$Repository"
    if (-not $slug -or ($slug -split '/', 2)[1] -ne $expectedName) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|선택 저장소와 canonical origin 이 일치하지 않습니다.'
    }
    return $slug
}

function Assert-AxmsGitHubPrWorkspaceBinding {
    param(
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$WorkspaceMarker,
        [Parameter(Mandatory = $true)][string]$OriginUrl,
        [Parameter(Mandatory = $true)][string]$CandidateSha,
        [Parameter(Mandatory = $true)][string]$ActualHeadSha,
        [Parameter(Mandatory = $true)][string]$ExpectedDiffDigest,
        [Parameter(Mandatory = $true)][string]$ActualDiffDigest
    )

    if ($WorkspaceMarker -ne $Repository) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|꺼낸 Workspace marker가 선택 저장소와 다릅니다.'
    }
    $slug = Resolve-AxmsGitHubSlug -Repository $Repository -OriginUrl $OriginUrl
    if ($ActualHeadSha -ne $CandidateSha) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|작업 폴더 HEAD 가 승인된 candidateSha 와 다릅니다.'
    }
    if ($ActualDiffDigest -ne $ExpectedDiffDigest) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|staged Diff가 승인된 diffDigest와 다릅니다.'
    }
    return $slug
}

function Enter-AxmsGitHubAppEnvironment {
    param([Parameter(Mandatory = $true)][string]$Token)

    $previous = [pscustomobject]@{
        GhToken = $env:GH_TOKEN
        GhPrompt = $env:GH_PROMPT_DISABLED
        GitPrompt = $env:GIT_TERMINAL_PROMPT
        GcmInteractive = $env:GCM_INTERACTIVE
    }
    $env:GH_TOKEN = $Token
    $env:GH_PROMPT_DISABLED = '1'
    $env:GIT_TERMINAL_PROMPT = '0'
    $env:GCM_INTERACTIVE = 'Never'
    return $previous
}

function Exit-AxmsGitHubAppEnvironment {
    param([Parameter(Mandatory = $true)]$Previous)

    $env:GH_TOKEN = $Previous.GhToken
    $env:GH_PROMPT_DISABLED = $Previous.GhPrompt
    $env:GIT_TERMINAL_PROMPT = $Previous.GitPrompt
    $env:GCM_INTERACTIVE = $Previous.GcmInteractive
}

function New-AxmsPullRequestBody {
    param(
        [Parameter(Mandatory = $true)][string]$Body,
        [Parameter(Mandatory = $true)][string]$CandidateSha,
        [Parameter(Mandatory = $true)][string]$HeadSha,
        [Parameter(Mandatory = $true)][string]$ValidationHash
    )

    if ($Body -match '<!--\s*axms-(?:candidate|head|validation):') {
        throw 'RUNNER_PAYLOAD_INVALID|PR 본문에 예약된 AXMS marker가 포함되어 있습니다.'
    }
    $normalizedBody = ($Body -replace "`r`n", "`n" -replace "`r", "`n").TrimEnd()
    return $normalizedBody + "`n`n" +
        "<!-- axms-candidate:$CandidateSha -->`n" +
        "<!-- axms-head:$HeadSha -->`n" +
        "<!-- axms-validation:$ValidationHash -->"
}

function ConvertTo-AxmsBase64Url {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    return [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-AxmsOpenSslPath {
    $command = Get-Command openssl -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }

    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($git) {
        $gitRoot = Split-Path -Parent (Split-Path -Parent $git.Source)
        $bundled = Join-Path $gitRoot 'usr/bin/openssl.exe'
        if (Test-Path -LiteralPath $bundled -PathType Leaf) { return $bundled }
    }
    throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App JWT 서명에 필요한 기존 OpenSSL을 찾지 못했습니다.'
}

function New-AxmsGitHubAppJwt {
    param(
        [Parameter(Mandatory = $true)][string]$AppId,
        [Parameter(Mandatory = $true)][string]$PrivateKeyPath
    )

    $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $header = ConvertTo-AxmsBase64Url -Bytes (
        [Text.Encoding]::UTF8.GetBytes('{"alg":"RS256","typ":"JWT"}'))
    $claims = @{ iat = $now - 60; exp = $now + 540; iss = $AppId } |
        ConvertTo-Json -Compress
    $payload = ConvertTo-AxmsBase64Url -Bytes ([Text.Encoding]::UTF8.GetBytes($claims))
    $unsigned = "$header.$payload"
    if ($PrivateKeyPath -match '["\r\n]') {
        throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App private-key 경로가 올바르지 않습니다.'
    }

    $process = [Diagnostics.Process]::new()
    $signatureBytes = [IO.MemoryStream]::new()
    try {
        $openssl = Get-AxmsOpenSslPath
        $process.StartInfo.FileName = $openssl
        $process.StartInfo.Arguments = 'dgst -sha256 -sign "' + $PrivateKeyPath + '"'
        $process.StartInfo.UseShellExecute = $false
        $process.StartInfo.CreateNoWindow = $true
        $process.StartInfo.RedirectStandardInput = $true
        $process.StartInfo.RedirectStandardOutput = $true
        $process.StartInfo.RedirectStandardError = $true
        if (-not $process.Start()) {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App JWT 서명 프로세스를 시작하지 못했습니다.'
        }
        $process.StandardInput.Write($unsigned)
        $process.StandardInput.Close()
        $process.StandardOutput.BaseStream.CopyTo($signatureBytes)
        $errorText = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0 -or $signatureBytes.Length -eq 0) {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App JWT 서명에 실패했습니다.'
        }
        $signature = ConvertTo-AxmsBase64Url -Bytes $signatureBytes.ToArray()
        return "$unsigned.$signature"
    }
    finally {
        $signatureBytes.Dispose()
        $process.Dispose()
    }
}

function Invoke-AxmsGhJson {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string]$InputJson
    )

    $raw = if ($null -eq $InputJson) {
        @(& gh @Arguments 2>&1)
    }
    else {
        @($InputJson | & gh @Arguments 2>&1)
    }
    if ($LASTEXITCODE -ne 0) {
        $detail = "$($raw -join ' ')"
        if ($detail -match '(?i)(rate limit|timed? out|timeout|temporar|HTTP 5\d\d|502|503|504|could not resolve host|connection (reset|refused|closed)|failed to connect|network is unreachable|TLS handshake)') {
            throw 'RUNNER_GITHUB_TRANSIENT|GitHub API 요청이 일시 실패했습니다.'
        }
        throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App API 요청이 거절되었습니다.'
    }
    try { return (($raw -join '') | ConvertFrom-Json) }
    catch { throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App API 응답이 올바르지 않습니다.' }
}

Add-Type -AssemblyName System.Net.Http -ErrorAction Stop

function Invoke-AxmsGitHubAppJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Jwt,
        [string]$InputJson,
        [System.Net.Http.HttpClient]$HttpClient
    )

    $hasInputJson = $PSBoundParameters.ContainsKey('InputJson')
    $isAppRead = $Method -eq 'GET' -and $Path -eq '/app' -and -not $hasInputJson
    $isTokenCreate = $Method -eq 'POST' -and
        $Path -match '^/app/installations/[1-9][0-9]{0,19}/access_tokens$' -and
        $hasInputJson
    if (-not $isAppRead -and -not $isTokenCreate) {
        throw 'RUNNER_GITHUB_APP_BLOCKED|허용되지 않은 GitHub App API 요청입니다.'
    }

    $disposeClient = $null -eq $HttpClient
    if ($disposeClient) {
        $handler = [System.Net.Http.HttpClientHandler]::new()
        $handler.AllowAutoRedirect = $false
        $client = [System.Net.Http.HttpClient]::new($handler, $true)
    }
    else { $client = $HttpClient }
    $request = $null
    $response = $null
    try {
        $httpMethod = if ($Method -eq 'GET') {
            [System.Net.Http.HttpMethod]::Get
        }
        else { [System.Net.Http.HttpMethod]::Post }
        $request = [System.Net.Http.HttpRequestMessage]::new(
            $httpMethod, "https://api.github.com$Path")
        $request.Headers.Authorization =
            [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $Jwt)
        $request.Headers.Accept.Add(
            [System.Net.Http.Headers.MediaTypeWithQualityHeaderValue]::new(
                'application/vnd.github+json'))
        $request.Headers.Add('X-GitHub-Api-Version', '2022-11-28')
        $request.Headers.UserAgent.ParseAdd('AX-Module-Studio-GitHub-App')
        if ($hasInputJson) {
            $request.Content = [System.Net.Http.StringContent]::new(
                $InputJson, [Text.Encoding]::UTF8, 'application/json')
        }

        try {
            $response = $client.SendAsync($request).GetAwaiter().GetResult()
        }
        catch [System.OperationCanceledException] {
            throw 'RUNNER_GITHUB_TRANSIENT|GitHub App API 요청이 일시 실패했습니다.'
        }
        catch [System.Net.Http.HttpRequestException] {
            throw 'RUNNER_GITHUB_TRANSIENT|GitHub App API 요청이 일시 실패했습니다.'
        }
        catch {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App API 요청이 거절되었습니다.'
        }
        if (-not $response.IsSuccessStatusCode) {
            $statusCode = [int]$response.StatusCode
            [System.Collections.Generic.IEnumerable[string]]$rateLimitValues = $null
            $hasRateLimitRemaining = $response.Headers.TryGetValues(
                'X-RateLimit-Remaining', [ref]$rateLimitValues)
            $rateLimitRemaining = @()
            if ($hasRateLimitRemaining) {
                $rateLimitRemaining = @($rateLimitValues)
            }
            $isRateLimited = $statusCode -eq 403 -and (
                $response.Headers.RetryAfter -or
                ($rateLimitRemaining.Count -eq 1 -and
                    "$($rateLimitRemaining[0])" -eq '0'))
            if ($statusCode -eq 408 -or $statusCode -eq 429 -or $isRateLimited -or
                    ($statusCode -ge 500 -and $statusCode -le 599)) {
                throw 'RUNNER_GITHUB_TRANSIENT|GitHub App API 요청이 일시 실패했습니다.'
            }
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App API 요청이 거절되었습니다.'
        }
        $raw = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        try { return ($raw | ConvertFrom-Json) }
        catch { throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App API 응답이 올바르지 않습니다.' }
    }
    finally {
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        if ($disposeClient) { $client.Dispose() }
    }
}

function New-AxmsGitHubAppSession {
    param(
        [Parameter(Mandatory = $true)][string]$SecretsRoot,
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$RepositorySlug
    )

    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'RUNNER_GITHUB_APP_BLOCKED|gh 가 없어 GitHub App으로 PR을 만들 수 없습니다.'
    }
    $appIdPath = Join-Path $SecretsRoot 'github_app_id'
    $installationIdPath = Join-Path $SecretsRoot 'github_app_installation_id'
    $privateKeyPath = Join-Path $SecretsRoot 'github_app_private_key.pem'
    foreach ($path in @($appIdPath, $installationIdPath, $privateKeyPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw 'RUNNER_GITHUB_APP_BLOCKED|전용 GitHub App 자격증명 파일이 없습니다.'
        }
    }
    $appId = (Get-Content -LiteralPath $appIdPath -Raw).Trim()
    $installationId = (Get-Content -LiteralPath $installationIdPath -Raw).Trim()
    if ($appId -notmatch '^[1-9][0-9]{0,19}$' -or
            $installationId -notmatch '^[1-9][0-9]{0,19}$') {
        throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App 식별자 형식이 올바르지 않습니다.'
    }

    $repositoryName = ($RepositorySlug -split '/', 2)[1]
    if ($repositoryName -ne "urizo-final-$Repository") {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|선택 저장소와 token 범위가 일치하지 않습니다.'
    }
    $previousToken = $env:GH_TOKEN
    $previousPrompt = $env:GH_PROMPT_DISABLED
    $jwt = $null
    try {
        $env:GH_PROMPT_DISABLED = '1'
        $jwt = New-AxmsGitHubAppJwt -AppId $appId -PrivateKeyPath $privateKeyPath
        $app = Invoke-AxmsGitHubAppJson -Method GET -Path '/app' -Jwt $jwt
        if ("$($app.id)" -ne $appId -or
                "$($app.slug)" -notmatch '^[a-z0-9][a-z0-9-]*$') {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App identity가 설정과 일치하지 않습니다.'
        }

        $request = @{
            repositories = @($repositoryName)
            permissions = @{
                contents = 'write'
                pull_requests = 'write'
                metadata = 'read'
            }
        } | ConvertTo-Json -Depth 4 -Compress
        $installation = Invoke-AxmsGitHubAppJson -Method POST `
            -Path "/app/installations/$installationId/access_tokens" `
            -Jwt $jwt -InputJson $request
        if (-not $installation.token -or
                "$($installation.permissions.contents)" -ne 'write' -or
                "$($installation.permissions.pull_requests)" -ne 'write' -or
                "$($installation.permissions.metadata)" -ne 'read' -or
                @($installation.repositories).Count -ne 1 -or
                "$($installation.repositories[0].full_name)" -ne $RepositorySlug) {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App token 범위가 요청과 일치하지 않습니다.'
        }
        $extraPermissions = @($installation.permissions.PSObject.Properties |
            Where-Object { $_.Name -notin @('contents', 'pull_requests', 'metadata') })
        if ($extraPermissions.Count -ne 0) {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App token에 불필요한 권한이 포함되었습니다.'
        }

        $env:GH_TOKEN = "$($installation.token)"
        $botLogin = "$($app.slug)[bot]"
        $escapedBot = [Uri]::EscapeDataString($botLogin)
        $bot = Invoke-AxmsGhJson -Arguments @(
            'api', '--hostname', 'github.com', '-H',
            'X-GitHub-Api-Version: 2022-11-28', "/users/$escapedBot")
        if ("$($bot.login)" -ne $botLogin -or "$($bot.type)" -ne 'Bot' -or
                "$($bot.id)" -notmatch '^[1-9][0-9]*$') {
            throw 'RUNNER_GITHUB_APP_BLOCKED|GitHub App bot identity를 확인하지 못했습니다.'
        }
        return [pscustomobject]@{
            Token = "$($installation.token)"
            BotLogin = $botLogin
            BotId = "$($bot.id)"
        }
    }
    finally {
        $jwt = $null
        $env:GH_TOKEN = $previousToken
        $env:GH_PROMPT_DISABLED = $previousPrompt
    }
}

function Get-ExactPullRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Slug,
        [Parameter(Mandatory = $true)][string]$Branch,
        [Parameter(Mandatory = $true)][string]$CandidateSha,
        [Parameter(Mandatory = $true)][string]$ExpectedHeadSha,
        [Parameter(Mandatory = $true)][string]$ValidationHash,
        [Parameter(Mandatory = $true)][string]$Repository,
        [Parameter(Mandatory = $true)][string]$BotLogin,
        [Parameter(Mandatory = $true)][string]$Title,
        [Parameter(Mandatory = $true)][string]$Body
    )

    $raw = & gh pr list --repo "github.com/$Slug" --base dev --head $Branch --state all `
        --json number 2>&1
    if ($LASTEXITCODE -ne 0) { Throw-GitHubFailure -Output $raw -Operation 'PR 조회' }
    $items = @(("$($raw -join '')" | ConvertFrom-Json))
    if ($items.Count -eq 0) { return $null }
    if ($items.Count -ne 1) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|같은 base/head 조합의 PR 이 둘 이상입니다.'
    }
    $listedNumber = [int]$items[0].number
    $item = Invoke-AxmsGhJson -Arguments @(
        'api', '--hostname', 'github.com', '-H',
        'X-GitHub-Api-Version: 2022-11-28', "/repos/$Slug/pulls/$listedNumber")
    $state = if ($item.merged_at) { 'MERGED' }
        elseif ("$($item.state)" -eq 'open') { 'OPEN' }
        else { 'CLOSED' }
    $expectedUrl = "https://github.com/$Slug/pull/$listedNumber"
    $candidateMarker = "<!-- axms-candidate:$CandidateSha -->"
    $headMarker = "<!-- axms-head:$ExpectedHeadSha -->"
    $validationMarker = "<!-- axms-validation:$ValidationHash -->"
    if ([int]$item.number -ne $listedNumber `
            -or "$($item.html_url)" -ne $expectedUrl `
            -or "$($item.base.repo.full_name)" -ne $Slug `
            -or "$($item.head.repo.full_name)" -ne $Slug `
            -or "$($item.base.ref)" -ne 'dev' `
            -or "$($item.head.ref)" -ne $Branch `
            -or "sha1:$($item.head.sha)" -ne $ExpectedHeadSha `
            -or $state -notin @('OPEN', 'MERGED') `
            -or "$($item.user.login)" -ne $BotLogin `
            -or -not "$($item.body)".Contains($candidateMarker) `
            -or -not "$($item.body)".Contains($headMarker) `
            -or -not "$($item.body)".Contains($validationMarker)) {
        throw 'RUNNER_PR_SUBJECT_BLOCKED|기존 PR 이 승인된 repository/base/head/candidate 와 다릅니다.'
    }
    if ($state -eq 'OPEN' -and
            ("$($item.title)" -ne $Title -or "$($item.body)" -ne $Body)) {
        $bodyFile = Join-Path $env:TEMP "axms-pr-body-$([IO.Path]::GetRandomFileName()).md"
        [IO.File]::WriteAllText($bodyFile, $Body, [Text.UTF8Encoding]::new($false))
        try {
            $updated = & gh pr edit ([int]$item.number) --repo "github.com/$Slug" `
                --title $Title --body-file $bodyFile 2>&1
        }
        finally {
            Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        }
        if ($LASTEXITCODE -ne 0) {
            Throw-GitHubFailure -Output $updated -Operation 'PR 제목과 본문 갱신'
        }
        $item = Invoke-AxmsGhJson -Arguments @(
            'api', '--hostname', 'github.com', '-H',
            'X-GitHub-Api-Version: 2022-11-28', "/repos/$Slug/pulls/$listedNumber")
        if ([int]$item.number -ne $listedNumber `
                -or "$($item.html_url)" -ne $expectedUrl `
                -or "$($item.base.repo.full_name)" -ne $Slug `
                -or "$($item.head.repo.full_name)" -ne $Slug `
                -or "$($item.base.ref)" -ne 'dev' `
                -or "$($item.head.ref)" -ne $Branch `
                -or "$($item.state)" -ne 'open' `
                -or $item.merged_at `
                -or "$($item.user.login)" -ne $BotLogin `
                -or "$($item.title)" -ne $Title `
                -or "$($item.body)" -ne $Body `
                -or "sha1:$($item.head.sha)" -ne $ExpectedHeadSha) {
            throw 'RUNNER_PR_SUBJECT_BLOCKED|갱신된 PR 이 승인된 제목·본문·head와 다릅니다.'
        }
    }
    return @{
        repository = $Repository
        base = 'dev'
        head = $Branch
        candidateSha = $CandidateSha
        headSha = $ExpectedHeadSha
        validationHash = $ValidationHash
        prNumber = $listedNumber
        prUrl = "$($item.html_url)"
        state = $state
        authorLogin = $BotLogin
        reused = $true
    }
}

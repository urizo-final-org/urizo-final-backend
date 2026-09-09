[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$helperPath = Join-Path $PSScriptRoot 'github-app-pr.ps1'
$runnerPath = Join-Path $PSScriptRoot 'runner.ps1'
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $helperPath, [ref]$null, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw 'github-app-pr.ps1 has PowerShell parse errors.'
}
$parseErrors = $null
[System.Management.Automation.Language.Parser]::ParseFile(
    $runnerPath, [ref]$null, [ref]$parseErrors) | Out-Null
if ($parseErrors.Count -ne 0) {
    throw 'runner.ps1 has PowerShell parse errors.'
}

. $helperPath

function Assert-PrBotCheck {
    param([bool]$Condition, [Parameter(Mandatory = $true)][string]$Message)
    if (-not $Condition) { throw "PR BOT VERIFY FAILED: $Message" }
}

function Assert-PrBotBlocked {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$ExpectedCode
    )
    try { & $Action }
    catch {
        Assert-PrBotCheck ($_.Exception.Message.StartsWith("$ExpectedCode|")) `
            "unexpected guard error code: $($_.Exception.Message)"
        return
    }
    throw "PR BOT VERIFY FAILED: expected $ExpectedCode"
}

$candidate = 'sha1:' + ('1' * 40)
$digest = 'sha256:' + ('2' * 64)
$validation = 'sha256:' + ('3' * 64)
Assert-AxmsGitHubPrInput -Repository backend `
    -Branch system/llmops-unit-test -WorkspaceId workspace-1 `
    -CandidateSha $candidate -DiffDigest $digest -ValidationHash $validation
Assert-PrBotCheck ((Resolve-AxmsGitHubSlug -Repository backend `
    -OriginUrl 'https://github.com/example/urizo-final-backend.git') `
    -eq 'example/urizo-final-backend') 'HTTPS origin binding'
Assert-PrBotCheck ((Resolve-AxmsGitHubSlug -Repository frontend `
    -OriginUrl 'git@github.com:example/urizo-final-frontend.git') `
    -eq 'example/urizo-final-frontend') 'SSH origin binding'

Assert-PrBotBlocked -ExpectedCode RUNNER_PAYLOAD_INVALID -Action {
    Assert-AxmsGitHubPrInput -Repository unknown `
        -Branch system/llmops-unit-test -WorkspaceId workspace-1 `
        -CandidateSha $candidate -DiffDigest $digest -ValidationHash $validation
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PAYLOAD_INVALID -Action {
    Assert-AxmsGitHubPrInput -Repository '' `
        -Branch system/llmops-unit-test -WorkspaceId workspace-1 `
        -CandidateSha $candidate -DiffDigest $digest -ValidationHash $validation
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PAYLOAD_INVALID -Action {
    Assert-AxmsGitHubPrInput -Repository backend `
        -Branch feature/personal -WorkspaceId workspace-1 `
        -CandidateSha $candidate -DiffDigest $digest -ValidationHash $validation
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PAYLOAD_INVALID -Action {
    Assert-AxmsGitHubPrInput -Repository backend `
        -Branch system/llmops-unit-test -WorkspaceId workspace-1 `
        -CandidateSha $candidate -DiffDigest $digest `
        -ValidationHash ('sha256:' + ('x' * 64))
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
    Resolve-AxmsGitHubSlug -Repository backend `
        -OriginUrl 'https://github.com/example/urizo-final-frontend.git'
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
    Assert-AxmsGitHubPrWorkspaceBinding -Repository backend `
        -WorkspaceMarker frontend `
        -OriginUrl 'https://github.com/example/urizo-final-backend.git' `
        -CandidateSha $candidate -ActualHeadSha $candidate `
        -ExpectedDiffDigest $digest -ActualDiffDigest $digest
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
    Assert-AxmsGitHubPrWorkspaceBinding -Repository backend `
        -WorkspaceMarker backend `
        -OriginUrl 'https://github.com/example/urizo-final-backend.git' `
        -CandidateSha $candidate -ActualHeadSha ('sha1:' + ('4' * 40)) `
        -ExpectedDiffDigest $digest -ActualDiffDigest $digest
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
    Assert-AxmsGitHubPrWorkspaceBinding -Repository backend `
        -WorkspaceMarker backend `
        -OriginUrl 'https://github.com/example/urizo-final-backend.git' `
        -CandidateSha $candidate -ActualHeadSha $candidate `
        -ExpectedDiffDigest $digest -ActualDiffDigest ('sha256:' + ('5' * 64))
}
Assert-PrBotBlocked -ExpectedCode RUNNER_PAYLOAD_INVALID -Action {
    New-AxmsPullRequestBody -Body '<!-- axms-candidate:spoof -->' `
        -CandidateSha $candidate -HeadSha $candidate -ValidationHash $validation
}

$body = New-AxmsPullRequestBody -Body 'body' `
    -CandidateSha $candidate -HeadSha $candidate -ValidationHash $validation
Assert-PrBotCheck ($body.Contains("<!-- axms-candidate:$candidate -->")) `
    'candidate marker'
Assert-PrBotCheck ($body.Contains("<!-- axms-head:$candidate -->")) `
    'head marker'
Assert-PrBotCheck ($body.Contains("<!-- axms-validation:$validation -->")) `
    'validation marker'

$savedGhToken = $env:GH_TOKEN
$savedGhPrompt = $env:GH_PROMPT_DISABLED
$savedGitPrompt = $env:GIT_TERMINAL_PROMPT
$savedGcmInteractive = $env:GCM_INTERACTIVE
$env:GH_TOKEN = 'existing-token'
$env:GH_PROMPT_DISABLED = 'existing-prompt'
$env:GIT_TERMINAL_PROMPT = 'existing-git-prompt'
$env:GCM_INTERACTIVE = 'existing-gcm'
try {
    $previousEnvironment = Enter-AxmsGitHubAppEnvironment -Token 'fixture-app-token'
    Assert-PrBotCheck ($env:GH_TOKEN -eq 'fixture-app-token') 'App token environment'
    Assert-PrBotCheck ($env:GH_PROMPT_DISABLED -eq '1') 'gh prompt disabled'
    Assert-PrBotCheck ($env:GIT_TERMINAL_PROMPT -eq '0') 'Git prompt disabled'
    Assert-PrBotCheck ($env:GCM_INTERACTIVE -eq 'Never') 'GCM prompt disabled'
    Exit-AxmsGitHubAppEnvironment -Previous $previousEnvironment
    Assert-PrBotCheck ($env:GH_TOKEN -eq 'existing-token') 'GH_TOKEN restoration'
    Assert-PrBotCheck ($env:GH_PROMPT_DISABLED -eq 'existing-prompt') `
        'GH prompt restoration'
    Assert-PrBotCheck ($env:GIT_TERMINAL_PROMPT -eq 'existing-git-prompt') `
        'Git prompt restoration'
    Assert-PrBotCheck ($env:GCM_INTERACTIVE -eq 'existing-gcm') 'GCM restoration'
}
finally {
    $env:GH_TOKEN = $savedGhToken
    $env:GH_PROMPT_DISABLED = $savedGhPrompt
    $env:GIT_TERMINAL_PROMPT = $savedGitPrompt
    $env:GCM_INTERACTIVE = $savedGcmInteractive
}

$handlerSource = @'
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Threading;
using System.Threading.Tasks;

public sealed class AxmsPrBotHttpHandler : HttpMessageHandler
{
    public string AuthorizationScheme { get; private set; }
    public string AuthorizationParameter { get; private set; }
    public string Method { get; private set; }
    public string RequestUri { get; private set; }
    public string RequestBody { get; private set; }
    public HttpStatusCode StatusCode { get; set; }
    public string ResponseJson { get; set; }
    public int? RetryAfterSeconds { get; set; }
    public string RateLimitRemaining { get; set; }

    public AxmsPrBotHttpHandler()
    {
        StatusCode = HttpStatusCode.OK;
        ResponseJson = "{}";
    }

    protected override Task<HttpResponseMessage> SendAsync(
        HttpRequestMessage request, CancellationToken cancellationToken)
    {
        AuthorizationScheme = request.Headers.Authorization == null
            ? null : request.Headers.Authorization.Scheme;
        AuthorizationParameter = request.Headers.Authorization == null
            ? null : request.Headers.Authorization.Parameter;
        Method = request.Method.Method;
        RequestUri = request.RequestUri.AbsoluteUri;
        RequestBody = request.Content == null
            ? null : request.Content.ReadAsStringAsync().GetAwaiter().GetResult();
        var response = new HttpResponseMessage(StatusCode);
        response.Content = new StringContent(ResponseJson);
        if (RetryAfterSeconds.HasValue)
            response.Headers.RetryAfter = new RetryConditionHeaderValue(
                System.TimeSpan.FromSeconds(RetryAfterSeconds.Value));
        if (RateLimitRemaining != null)
            response.Headers.TryAddWithoutValidation(
                "X-RateLimit-Remaining", RateLimitRemaining);
        return Task.FromResult(response);
    }
}
'@
if ($PSVersionTable.PSVersion.Major -le 5) {
    Add-Type -TypeDefinition $handlerSource `
        -ReferencedAssemblies ([System.Net.Http.HttpClient].Assembly.Location)
}
else { Add-Type -TypeDefinition $handlerSource }

$fixtureJwt = 'fixture.jwt.not-a-real-secret'
$appHandler = [AxmsPrBotHttpHandler]::new()
$appHandler.ResponseJson = '{"id":4871047,"slug":"urizo-final-pr-bot"}'
$appClient = [System.Net.Http.HttpClient]::new($appHandler, $false)
try {
    $appResult = Invoke-AxmsGitHubAppJson -Method GET -Path '/app' `
        -Jwt $fixtureJwt -HttpClient $appClient
    Assert-PrBotCheck ($appHandler.AuthorizationScheme -ceq 'Bearer') `
        'JWT Bearer authorization scheme at HTTP transport'
    Assert-PrBotCheck ($appHandler.AuthorizationParameter -ceq $fixtureJwt) `
        'JWT credential at HTTP transport'
    Assert-PrBotCheck ($appHandler.Method -ceq 'GET') 'App identity HTTP method'
    Assert-PrBotCheck ($appHandler.RequestUri -ceq 'https://api.github.com/app') `
        'App identity fixed endpoint'
    Assert-PrBotCheck ("$($appResult.id)" -ceq '4871047') 'App identity response'
    Assert-PrBotCheck (-not [Environment]::CommandLine.Contains($fixtureJwt)) `
        'JWT absent from process argv'
}
finally {
    $appClient.Dispose()
    $appHandler.Dispose()
}

$tokenRequest = @{
    repositories = @('urizo-final-backend')
    permissions = @{
        contents = 'write'
        pull_requests = 'write'
        metadata = 'read'
    }
} | ConvertTo-Json -Depth 4 -Compress
$tokenHandler = [AxmsPrBotHttpHandler]::new()
$tokenHandler.ResponseJson = '{"token":"fixture-installation-token"}'
$tokenClient = [System.Net.Http.HttpClient]::new($tokenHandler, $false)
try {
    $null = Invoke-AxmsGitHubAppJson -Method POST `
        -Path '/app/installations/160009041/access_tokens' `
        -Jwt $fixtureJwt -InputJson $tokenRequest -HttpClient $tokenClient
    Assert-PrBotCheck ($tokenHandler.AuthorizationScheme -ceq 'Bearer') `
        'Installation token Bearer authorization scheme at HTTP transport'
    Assert-PrBotCheck ($tokenHandler.AuthorizationParameter -ceq $fixtureJwt) `
        'Installation token JWT credential at HTTP transport'
    Assert-PrBotCheck ($tokenHandler.Method -ceq 'POST') 'Installation token HTTP method'
    Assert-PrBotCheck (
        $tokenHandler.RequestUri -ceq
            'https://api.github.com/app/installations/160009041/access_tokens') `
        'Installation token fixed endpoint'
    $observedTokenRequest = $tokenHandler.RequestBody | ConvertFrom-Json
    Assert-PrBotCheck (@($observedTokenRequest.repositories).Count -eq 1) `
        'Installation token single repository request'
    Assert-PrBotCheck (
        "$($observedTokenRequest.repositories[0])" -ceq 'urizo-final-backend') `
        'Installation token repository binding'
    Assert-PrBotCheck (
        @($observedTokenRequest.permissions.PSObject.Properties).Count -eq 3 -and
        $observedTokenRequest.permissions.metadata -ceq 'read') `
        'Installation token exact requested permissions'
}
finally {
    $tokenClient.Dispose()
    $tokenHandler.Dispose()
}

$blockedHandler = [AxmsPrBotHttpHandler]::new()
$blockedHandler.StatusCode = [System.Net.HttpStatusCode]::Unauthorized
$blockedClient = [System.Net.Http.HttpClient]::new($blockedHandler, $false)
try {
    Assert-PrBotBlocked -ExpectedCode RUNNER_GITHUB_APP_BLOCKED -Action {
        Invoke-AxmsGitHubAppJson -Method GET -Path '/app' `
            -Jwt $fixtureJwt -HttpClient $blockedClient
    }
}
finally {
    $blockedClient.Dispose()
    $blockedHandler.Dispose()
}
$transientHandler = [AxmsPrBotHttpHandler]::new()
$transientHandler.StatusCode = [System.Net.HttpStatusCode]::ServiceUnavailable
$transientClient = [System.Net.Http.HttpClient]::new($transientHandler, $false)
try {
    Assert-PrBotBlocked -ExpectedCode RUNNER_GITHUB_TRANSIENT -Action {
        Invoke-AxmsGitHubAppJson -Method GET -Path '/app' `
            -Jwt $fixtureJwt -HttpClient $transientClient
    }
}
finally {
    $transientClient.Dispose()
    $transientHandler.Dispose()
}
$rateLimitedHandler = [AxmsPrBotHttpHandler]::new()
$rateLimitedHandler.StatusCode = [System.Net.HttpStatusCode]::Forbidden
$rateLimitedHandler.RateLimitRemaining = '0'
$rateLimitedClient = [System.Net.Http.HttpClient]::new($rateLimitedHandler, $false)
try {
    Assert-PrBotBlocked -ExpectedCode RUNNER_GITHUB_TRANSIENT -Action {
        Invoke-AxmsGitHubAppJson -Method GET -Path '/app' `
            -Jwt $fixtureJwt -HttpClient $rateLimitedClient
    }
}
finally {
    $rateLimitedClient.Dispose()
    $rateLimitedHandler.Dispose()
}
$unusedClient = [System.Net.Http.HttpClient]::new()
try {
    Assert-PrBotBlocked -ExpectedCode RUNNER_GITHUB_APP_BLOCKED -Action {
        Invoke-AxmsGitHubAppJson -Method GET -Path '/user' `
            -Jwt $fixtureJwt -HttpClient $unusedClient
    }
}
finally { $unusedClient.Dispose() }
$fixtureJwt = $null

$helperText = Get-Content -LiteralPath $helperPath -Raw
Assert-PrBotCheck (-not $helperText.Contains('$env:GH_TOKEN = $jwt')) `
    'JWT is not exported through GH_TOKEN'
Assert-PrBotCheck ($helperText.Contains('$handler.AllowAutoRedirect = $false')) `
    'GitHub App HTTP redirects disabled'
Assert-PrBotCheck ($helperText.Contains('@($installation.repositories).Count -ne 1')) `
    'Installation response remains single-repository fail-closed'
Assert-PrBotCheck ($helperText.Contains('$env:GH_TOKEN = $previousToken')) `
    'Session GH_TOKEN restoration remains present'

$sessionFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'axms-pr-bot-session-' + [guid]::NewGuid().ToString('N'))
$originalAppJwt = ${function:New-AxmsGitHubAppJwt}
$originalAppJson = ${function:Invoke-AxmsGitHubAppJson}
$originalSessionGhJson = ${function:Invoke-AxmsGhJson}
$script:sessionExtraPermission = $false
$script:sessionTokenRequest = $null
try {
    New-Item -ItemType Directory -Path $sessionFixtureRoot | Out-Null
    [IO.File]::WriteAllText(
        (Join-Path $sessionFixtureRoot 'github_app_id'), '12345')
    [IO.File]::WriteAllText(
        (Join-Path $sessionFixtureRoot 'github_app_installation_id'), '67890')
    [IO.File]::WriteAllText(
        (Join-Path $sessionFixtureRoot 'github_app_private_key.pem'), 'fixture-key')
    function global:New-AxmsGitHubAppJwt {
        param([string]$AppId, [string]$PrivateKeyPath)
        return 'fixture.jwt'
    }
    function global:Invoke-AxmsGitHubAppJson {
        param(
            [string]$Method,
            [string]$Path,
            [string]$Jwt,
            [string]$InputJson,
            [System.Net.Http.HttpClient]$HttpClient
        )
        if ($Method -eq 'GET') {
            return [pscustomobject]@{ id = 12345; slug = 'axms-test' }
        }
        $script:sessionTokenRequest = $InputJson | ConvertFrom-Json
        $permissions = [ordered]@{
            contents = 'write'
            pull_requests = 'write'
            metadata = 'read'
        }
        if ($script:sessionExtraPermission) { $permissions.issues = 'read' }
        return [pscustomobject]@{
            token = 'fixture-installation-token'
            permissions = [pscustomobject]$permissions
            repositories = @(
                [pscustomobject]@{ full_name = 'example/urizo-final-backend' })
        }
    }
    function global:Invoke-AxmsGhJson {
        param([string[]]$Arguments, [string]$InputJson)
        return [pscustomobject]@{
            login = 'axms-test[bot]'
            type = 'Bot'
            id = 98765
        }
    }
    $savedSessionToken = $env:GH_TOKEN
    $env:GH_TOKEN = 'existing-session-token'
    try {
        $session = New-AxmsGitHubAppSession -SecretsRoot $sessionFixtureRoot `
            -Repository backend -RepositorySlug 'example/urizo-final-backend'
        Assert-PrBotCheck ($session.Token -ceq 'fixture-installation-token') `
            'installation token returned only to caller'
        Assert-PrBotCheck ($session.BotLogin -ceq 'axms-test[bot]') `
            'verified App bot login'
        Assert-PrBotCheck ($session.BotId -ceq '98765') 'verified App bot id'
        Assert-PrBotCheck ($env:GH_TOKEN -ceq 'existing-session-token') `
            'session setup restores GH_TOKEN'
        Assert-PrBotCheck (
            @($script:sessionTokenRequest.repositories).Count -eq 1 -and
            $script:sessionTokenRequest.repositories[0] -ceq 'urizo-final-backend') `
            'session token request is repository-scoped'
        Assert-PrBotCheck (
            @($script:sessionTokenRequest.permissions.PSObject.Properties).Count -eq 3 -and
            $script:sessionTokenRequest.permissions.contents -ceq 'write' -and
            $script:sessionTokenRequest.permissions.pull_requests -ceq 'write' -and
            $script:sessionTokenRequest.permissions.metadata -ceq 'read') `
            'session token request has exact write permissions'

        $script:sessionExtraPermission = $true
        Assert-PrBotBlocked -ExpectedCode RUNNER_GITHUB_APP_BLOCKED -Action {
            New-AxmsGitHubAppSession -SecretsRoot $sessionFixtureRoot `
                -Repository backend -RepositorySlug 'example/urizo-final-backend'
        }
    }
    finally { $env:GH_TOKEN = $savedSessionToken }
}
finally {
    Set-Item Function:\New-AxmsGitHubAppJwt $originalAppJwt
    Set-Item Function:\Invoke-AxmsGitHubAppJson $originalAppJson
    Set-Item Function:\Invoke-AxmsGhJson $originalSessionGhJson
    if (Test-Path -LiteralPath $sessionFixtureRoot -PathType Container) {
        Get-ChildItem -LiteralPath $sessionFixtureRoot -File |
            Remove-Item -Force
        Remove-Item -LiteralPath $sessionFixtureRoot -Force
    }
}

$script:prMode = 'OPEN'
$script:prRepository = 'example/urizo-final-backend'
$script:prAuthor = 'axms-test[bot]'
$script:prEditCalls = 0
$script:prApiCalls = 0
$script:prRepoTargets = @()
$script:editBodyWasExactUtf8 = $false
$desiredTitle = 'SYSTEM-LLMOPS-TEST automated coding change'
$unicodeLine = 'fixture ' + [char]0xD55C + [char]0xAE00
$desiredBody = New-AxmsPullRequestBody -Body "$unicodeLine`r`nsecond line" `
    -CandidateSha $candidate -HeadSha $candidate -ValidationHash $validation
Assert-PrBotCheck (-not $desiredBody.Contains("`r")) 'PR body normalized to LF'
$originalGhJson = ${function:Invoke-AxmsGhJson}
function global:gh {
    param([Parameter(ValueFromRemainingArguments = $true)]$Arguments)
    $global:LASTEXITCODE = 0
    if ($Arguments[0] -eq 'pr' -and $Arguments[1] -eq 'list') {
        $repoIndex = [Array]::IndexOf($Arguments, '--repo')
        $script:prRepoTargets += $Arguments[$repoIndex + 1]
        return '[{"number":42}]'
    }
    if ($Arguments[0] -eq 'pr' -and $Arguments[1] -eq 'edit') {
        $repoIndex = [Array]::IndexOf($Arguments, '--repo')
        $bodyFileIndex = [Array]::IndexOf($Arguments, '--body-file')
        $script:prRepoTargets += $Arguments[$repoIndex + 1]
        Assert-PrBotCheck ($bodyFileIndex -ge 0) 'OPEN edit uses body file'
        $bytes = [IO.File]::ReadAllBytes($Arguments[$bodyFileIndex + 1])
        $hasBom = $bytes.Length -ge 3 -and $bytes[0] -eq 0xef `
            -and $bytes[1] -eq 0xbb -and $bytes[2] -eq 0xbf
        $script:editBodyWasExactUtf8 = -not $hasBom -and
            [Text.Encoding]::UTF8.GetString($bytes) -ceq $desiredBody
        $script:prEditCalls++
        return 'updated'
    }
    throw 'PR BOT VERIFY FAILED: unmocked gh command'
}
function global:Invoke-AxmsGhJson {
    param([string[]]$Arguments, [string]$InputJson)
    $script:prApiCalls++
    $isMerged = $script:prMode -eq 'MERGED'
    $isClosed = $script:prMode -eq 'CLOSED'
    $title = if ($script:prMode -eq 'OPEN' -and $script:prEditCalls -eq 0) {
        'old title'
    }
    else { $desiredTitle }
    return [pscustomobject]@{
        number = 42
        html_url = 'https://github.com/example/urizo-final-backend/pull/42'
        state = $(if ($isMerged -or $isClosed) { 'closed' } else { 'open' })
        merged_at = $(if ($isMerged) { '2026-09-08T00:00:00Z' } else { $null })
        title = $title
        body = $desiredBody
        user = [pscustomobject]@{ login = $script:prAuthor }
        base = [pscustomobject]@{
            ref = 'dev'
            repo = [pscustomobject]@{ full_name = $script:prRepository }
        }
        head = [pscustomobject]@{
            ref = 'system/llmops-unit-test'
            sha = $candidate.Substring('sha1:'.Length)
            repo = [pscustomobject]@{ full_name = $script:prRepository }
        }
    }
}
$savedGhHost = $env:GH_HOST
$env:GH_HOST = 'enterprise.example.test'
try {
    $openReceipt = Get-ExactPullRequest `
        -Slug 'example/urizo-final-backend' -Branch 'system/llmops-unit-test' `
        -CandidateSha $candidate -ExpectedHeadSha $candidate `
        -ValidationHash $validation -Repository backend `
        -BotLogin 'axms-test[bot]' -Title $desiredTitle -Body $desiredBody
    Assert-PrBotCheck ($openReceipt.state -eq 'OPEN') 'OPEN receipt state'
    Assert-PrBotCheck ($openReceipt.reused -eq $true) 'OPEN receipt reuse'
    Assert-PrBotCheck ($script:prEditCalls -eq 1) 'OPEN title/body update'
    Assert-PrBotCheck ($script:prApiCalls -eq 2) 'OPEN update readback'
    Assert-PrBotCheck $script:editBodyWasExactUtf8 'OPEN exact UTF-8 body file'
    Assert-PrBotCheck (
        @($script:prRepoTargets | Where-Object {
            $_ -ne 'github.com/example/urizo-final-backend'
        }).Count -eq 0) 'list/edit explicit github.com repository target'
    $runnerText = Get-Content -LiteralPath $runnerPath -Raw
    Assert-PrBotCheck (
        $runnerText.Contains(
            '$created = & gh pr create --repo "github.com/$slug"')) `
        'create explicit github.com repository target'
    Assert-PrBotCheck ($runnerText.Contains('--body-file $bodyFile')) `
        'create uses UTF-8 body file'

    $script:prMode = 'MERGED'
    $script:prEditCalls = 0
    $script:prApiCalls = 0
    $mergedReceipt = Get-ExactPullRequest `
        -Slug 'example/urizo-final-backend' -Branch 'system/llmops-unit-test' `
        -CandidateSha $candidate -ExpectedHeadSha $candidate `
        -ValidationHash $validation -Repository backend `
        -BotLogin 'axms-test[bot]' -Title $desiredTitle -Body $desiredBody
    Assert-PrBotCheck ($mergedReceipt.state -eq 'MERGED') 'MERGED receipt state'
    Assert-PrBotCheck ($script:prEditCalls -eq 0) 'MERGED remains immutable'

    $script:prMode = 'CLOSED'
    Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
        Get-ExactPullRequest `
            -Slug 'example/urizo-final-backend' -Branch 'system/llmops-unit-test' `
            -CandidateSha $candidate -ExpectedHeadSha $candidate `
            -ValidationHash $validation -Repository backend `
            -BotLogin 'axms-test[bot]' -Title $desiredTitle -Body $desiredBody
    }

    $script:prMode = 'OPEN'
    $script:prAuthor = 'personal-user'
    Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
        Get-ExactPullRequest `
            -Slug 'example/urizo-final-backend' -Branch 'system/llmops-unit-test' `
            -CandidateSha $candidate -ExpectedHeadSha $candidate `
            -ValidationHash $validation -Repository backend `
            -BotLogin 'axms-test[bot]' -Title $desiredTitle -Body $desiredBody
    }
    $script:prAuthor = 'axms-test[bot]'
    $script:prRepository = 'example/urizo-final-frontend'
    Assert-PrBotBlocked -ExpectedCode RUNNER_PR_SUBJECT_BLOCKED -Action {
        Get-ExactPullRequest `
            -Slug 'example/urizo-final-backend' -Branch 'system/llmops-unit-test' `
            -CandidateSha $candidate -ExpectedHeadSha $candidate `
            -ValidationHash $validation -Repository backend `
            -BotLogin 'axms-test[bot]' -Title $desiredTitle -Body $desiredBody
    }
}
finally {
    $env:GH_HOST = $savedGhHost
    Remove-Item Function:\gh -ErrorAction SilentlyContinue
    Set-Item Function:\Invoke-AxmsGhJson $originalGhJson
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'axms-pr-bot-test-' + [guid]::NewGuid().ToString('N'))
$keyPath = Join-Path $fixtureRoot 'test-private-key.pem'
$jwt = $null
try {
    New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
    $openssl = Get-AxmsOpenSslPath
    $keyProcess = New-Object System.Diagnostics.Process
    try {
        $keyProcess.StartInfo.FileName = $openssl
        $keyProcess.StartInfo.Arguments =
            'genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "' + $keyPath + '"'
        $keyProcess.StartInfo.UseShellExecute = $false
        $keyProcess.StartInfo.CreateNoWindow = $true
        $keyProcess.StartInfo.RedirectStandardOutput = $true
        $keyProcess.StartInfo.RedirectStandardError = $true
        if (-not $keyProcess.Start()) {
            throw 'PR BOT VERIFY FAILED: disposable RSA key generation'
        }
        $null = $keyProcess.StandardOutput.ReadToEnd()
        $null = $keyProcess.StandardError.ReadToEnd()
        $keyProcess.WaitForExit()
        if ($keyProcess.ExitCode -ne 0) {
            throw 'PR BOT VERIFY FAILED: disposable RSA key generation'
        }
    }
    finally {
        $keyProcess.Dispose()
    }
    $jwt = New-AxmsGitHubAppJwt -AppId '12345' -PrivateKeyPath $keyPath
    $parts = @($jwt -split '\.')
    Assert-PrBotCheck ($parts.Count -eq 3) 'JWT segment count'
    Assert-PrBotCheck ($parts[0] -match '^[A-Za-z0-9_-]+$') 'JWT header encoding'
    Assert-PrBotCheck ($parts[1] -match '^[A-Za-z0-9_-]+$') 'JWT payload encoding'
    Assert-PrBotCheck ($parts[2] -match '^[A-Za-z0-9_-]{64,}$') 'RS256 signature encoding'
}
finally {
    $jwt = $null
    if (Test-Path -LiteralPath $keyPath -PathType Leaf) {
        Remove-Item -LiteralPath $keyPath -Force
    }
    if (Test-Path -LiteralPath $fixtureRoot -PathType Container) {
        Remove-Item -LiteralPath $fixtureRoot -Force
    }
}

Write-Output 'GITHUB APP PR BOT VERIFY PASS: guards, origin, markers, and in-memory RS256 signing.'

[CmdletBinding()]
param(
    [ValidateSet('full')]
    [string]$Profile = 'full',

    [int]$WaitTimeoutSeconds = 180,

    [switch]$SnapshotOnly,

    [string]$ProductProbeScript
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($WaitTimeoutSeconds -lt 30 -or $WaitTimeoutSeconds -gt 1800) {
    throw 'WaitTimeoutSeconds must be between 30 and 1800.'
}
if ($SnapshotOnly -and $ProductProbeScript) {
    throw 'ProductProbeScript cannot be combined with SnapshotOnly.'
}

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'health.ps1') -Profile $Profile -WaitTimeoutSeconds $WaitTimeoutSeconds
if ($LASTEXITCODE -ne 0) {
    throw 'Infrastructure health gate failed before full local E2E verification.'
}

$httpPort = if ($env:AXMS_HTTP_PORT) { [int]$env:AXMS_HTTP_PORT } else { 18080 }
$baseUri = "http://127.0.0.1:$httpPort"

function Get-HttpStatus {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [hashtable]$Headers = @{}
    )

    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Headers $Headers -TimeoutSec 10
        return [int]$response.StatusCode
    }
    catch {
        if ($_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        return 0
    }
}

foreach ($path in @('/', '/nginx-health', '/api/health', '/api/readiness')) {
    if ((Get-HttpStatus -Uri "$baseUri$path") -ne 200) {
        throw "Full local route failed: $path"
    }
}

if ((Get-HttpStatus -Uri "$baseUri/api/projects") -ne 401) {
    throw 'The product API did not enforce the local session boundary.'
}

$providerOverview = $null
if (-not $SnapshotOnly) {
    $providerOverview = Invoke-RestMethod -UseBasicParsing `
        -Uri "$baseUri/internal/dev/provider-credentials" -TimeoutSec 10
    $verifiedProviderCount = @(
        $providerOverview.providers |
            Where-Object { $_.configured -and $_.state -eq 'VERIFIED' }
    ).Count
    if ($verifiedProviderCount -lt 3) {
        throw 'Fewer than three local Provider CMS credentials are VERIFIED.'
    }
    $providerOverview = $null
}

# This acceptance path runs on the development session, which now lives behind the
# 'dev-session' profile. Without it the stack requires a real administrator login and
# this endpoint is absent, so explicitly include 'dev-session' in the
# AXMS_SPRING_PROFILES_ACTIVE Compose override before creating the verification stack.
try {
    $session = Invoke-RestMethod -UseBasicParsing -Uri "$baseUri/internal/dev/product-session" -TimeoutSec 10
}
catch {
    throw ('The local product session endpoint is unavailable. Stage 3-5 verification ' +
        "requires 'dev-session' through AXMS_SPRING_PROFILES_ACTIVE; " +
        'production authentication remains active by default.')
}
if (($session.schemaVersion -ne '1.0') -or
        ($session.tokenType -ne 'Bearer') -or
        [string]::IsNullOrWhiteSpace([string]$session.accessToken)) {
    throw 'The local product session endpoint returned an invalid contract.'
}

$accessToken = [string]$session.accessToken
$authorization = "$($session.tokenType) $accessToken"

function Invoke-AxmsJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [object]$Body = $null,
        [string]$IdempotencyKey
    )

    $headers = @{ Authorization = $authorization }
    if ($IdempotencyKey) {
        $headers['Idempotency-Key'] = $IdempotencyKey
    }
    $parameters = @{
        UseBasicParsing = $true
        Uri             = "$baseUri$Path"
        Method          = $Method
        Headers         = $headers
        TimeoutSec      = 20
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    try {
        return Invoke-RestMethod @parameters
    }
    catch {
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        throw "Product request failed with HTTP $statusCode`: $Method $Path"
    }
}

function Assert-ContractValue {
    param(
        [Parameter(Mandatory = $true)][object]$Actual,
        [Parameter(Mandatory = $true)][object]$Expected,
        [Parameter(Mandatory = $true)][string]$Name
    )

    if ([string]$Actual -ne [string]$Expected) {
        throw "Unexpected product contract value for $Name."
    }
}

function Get-AxmsHttpFailureBody {
    param(
        [Parameter(Mandatory = $true)][object]$Failure
    )

    if ($null -ne $Failure.ErrorDetails -and
            -not [string]::IsNullOrWhiteSpace([string]$Failure.ErrorDetails.Message)) {
        return [string]$Failure.ErrorDetails.Message
    }

    $response = $Failure.Exception.Response
    if ($null -eq $response) {
        return ''
    }
    $contentProperty = $response.PSObject.Properties['Content']
    if ($null -ne $contentProperty -and $null -ne $contentProperty.Value) {
        $readTask = $contentProperty.Value.ReadAsStringAsync()
        return [string]$readTask.GetAwaiter().GetResult()
    }
    if ($null -ne $response.PSObject.Methods['GetResponseStream']) {
        $stream = $response.GetResponseStream()
        if ($null -ne $stream) {
            $reader = [System.IO.StreamReader]::new($stream)
            try {
                return $reader.ReadToEnd()
            }
            finally {
                $reader.Dispose()
            }
        }
    }
    return ''
}

function Invoke-AxmsExpectedError {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][object]$Body,
        [Parameter(Mandatory = $true)][int]$ExpectedStatus
    )

    $parameters = @{
        UseBasicParsing = $true
        Uri             = "$baseUri$Path"
        Method          = $Method
        Headers         = @{ Authorization = $authorization }
        ContentType     = 'application/json; charset=utf-8'
        Body            = $Body | ConvertTo-Json -Compress -Depth 20
        TimeoutSec      = 20
    }
    $requestFailure = $null
    try {
        [void](Invoke-WebRequest @parameters)
    }
    catch {
        $requestFailure = $_
    }
    if ($null -eq $requestFailure) {
        throw "Product request unexpectedly succeeded: $Method $Path"
    }
    if ($null -eq $requestFailure.Exception.Response) {
        throw "Product request failed without an HTTP response: $Method $Path"
    }
    $statusCode = [int]$requestFailure.Exception.Response.StatusCode
    if ($statusCode -ne $ExpectedStatus) {
        throw "Product request failed with unexpected HTTP $statusCode`: $Method $Path"
    }
    $rawBody = Get-AxmsHttpFailureBody -Failure $requestFailure
    if ([string]::IsNullOrWhiteSpace($rawBody)) {
        throw "Product error response body was empty: $Method $Path"
    }
    try {
        return $rawBody | ConvertFrom-Json
    }
    catch {
        throw "Product error response body was not valid JSON: $Method $Path"
    }
}

function ConvertTo-ProfileAuthoringSnapshot {
    param(
        [Parameter(Mandatory = $true)][object]$VersionedSnapshot
    )

    $authoring = [ordered]@{}
    foreach ($field in @(
            'nodes',
            'edges',
            'config',
            'modelBindings',
            'toolPolicy',
            'guardrailProfileKey')) {
        $property = $VersionedSnapshot.PSObject.Properties[$field]
        if ($null -eq $property) {
            throw "The Profile fixture is missing authoring field: $field"
        }
        $authoring[$field] = $property.Value
    }
    return $authoring
}

function Publish-AdminProfileFixture {
    param(
        [Parameter(Mandatory = $true)][string]$FixturePath
    )

    if (-not (Test-Path -LiteralPath $FixturePath -PathType Leaf)) {
        throw 'The requested Profile fixture was not found.'
    }
    $fixture = [System.IO.File]::ReadAllText($FixturePath) | ConvertFrom-Json
    if ($fixture.contractVersion -ne '1.0' -or $fixture.profileKey -ne 'LLM_OPS') {
        throw 'The requested LLM_OPS Profile fixture identity is invalid.'
    }
    $createBody = [ordered]@{
        profileKey = 'LLM_OPS'
        snapshot   = ConvertTo-ProfileAuthoringSnapshot -VersionedSnapshot $fixture
    }

    # Profile draft creation intentionally has no idempotency contract. Do not retry this POST.
    $created = Invoke-AxmsJson -Method POST -Path '/api/admin/ai/profile-versions' `
        -Body $createBody
    Assert-ContractValue -Actual $created.status -Expected 'DRAFT' `
        -Name 'admin Profile draft status'
    Assert-ContractValue -Actual $created.profileKey -Expected 'LLM_OPS' `
        -Name 'admin Profile key'
    if ([int]$created.profileVersion -lt 1 -or
            [string]::IsNullOrWhiteSpace([string]$created.profileVersionId)) {
        throw 'The admin Profile create response returned an invalid identity.'
    }
    Assert-ContractValue -Actual $created.snapshot.contractVersion -Expected '1.0' `
        -Name 'admin Profile Snapshot contract version'
    Assert-ContractValue -Actual $created.snapshot.profileVersionId `
        -Expected $created.profileVersionId -Name 'admin Profile Snapshot id'
    Assert-ContractValue -Actual $created.snapshot.profileKey -Expected $created.profileKey `
        -Name 'admin Profile Snapshot key'
    Assert-ContractValue -Actual $created.snapshot.profileVersion `
        -Expected $created.profileVersion -Name 'admin Profile Snapshot version'

    $active = Invoke-AxmsJson -Method POST `
        -Path "/api/admin/ai/profile-versions/$($created.profileVersionId)/activate"
    Assert-ContractValue -Actual $active.status -Expected 'ACTIVE' `
        -Name 'admin Profile activation'
    Assert-ContractValue -Actual $active.profileVersionId -Expected $created.profileVersionId `
        -Name 'admin Profile activation identity'
    Assert-ContractValue -Actual $active.profileVersion -Expected $created.profileVersion `
        -Name 'admin Profile activation version'
    return $active
}

function Invoke-CodingJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('GET', 'POST')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$TraceId,
        [object]$Body = $null,
        [string]$IdempotencyKey,
        [string]$CsrfToken
    )

    $headers = @{ 'X-Trace-Id' = $TraceId }
    if ($Method -eq 'POST') {
        if ([string]::IsNullOrWhiteSpace($CsrfToken) -or [string]::IsNullOrWhiteSpace($IdempotencyKey)) {
            throw 'Coding mutation requires CSRF and idempotency context.'
        }
        $headers['Origin'] = $baseUri
        $headers['X-AXMS-CSRF'] = $CsrfToken
        $headers['Idempotency-Key'] = $IdempotencyKey
    }
    $parameters = @{
        UseBasicParsing = $true
        Uri             = "$baseUri$Path"
        Method          = $Method
        Headers         = $headers
        TimeoutSec      = 20
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json; charset=utf-8'
        $parameters.Body = $Body | ConvertTo-Json -Compress -Depth 20
    }
    try {
        return Invoke-RestMethod @parameters
    }
    catch {
        $statusCode = 0
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
        }
        throw "Coding request failed with HTTP $statusCode`: $Method $Path"
    }
}

function Wait-CodingJob {
    param(
        [Parameter(Mandatory = $true)][string]$JobId,
        [Parameter(Mandatory = $true)][string]$TraceId,
        [Parameter(Mandatory = $true)][string[]]$DesiredStatuses
    )

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitTimeoutSeconds)
    do {
        $job = Invoke-CodingJson -Method GET -Path "/internal/dev/coding-jobs/$JobId" `
            -TraceId $TraceId
        if ($DesiredStatuses -contains [string]$job.status) {
            return $job
        }
        if (@('COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED') -contains [string]$job.status) {
            throw "Coding job reached an unexpected terminal state: $($job.status)"
        }
        Start-Sleep -Milliseconds 300
    }
    while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "Timed out waiting for coding job state: $($DesiredStatuses -join ', ')"
}

function Wait-ProductJob {
    param(
        [Parameter(Mandatory = $true)][string]$JobId,
        [Parameter(Mandatory = $true)][string[]]$DesiredStatuses,
        [int]$RetryLimit = 1
    )

    $retryCount = 0
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitTimeoutSeconds)
    do {
        $job = Invoke-AxmsJson -Method GET -Path "/api/agent-jobs/$JobId"
        if ($DesiredStatuses -contains [string]$job.status) {
            return $job
        }
        if ($job.status -eq 'FAILED' -and $null -ne $job.failure `
                -and $job.failure.retryable -and $retryCount -lt $RetryLimit) {
            $retryBody = @{
                schemaVersion        = '1.0'
                expectedStateVersion = [int]$job.stateVersion
            }
            $retryKey = "axms-full-e2e-retry-$JobId-v$($job.stateVersion)"
            [void](Invoke-AxmsJson -Method POST -Path "/api/agent-jobs/$JobId/retry" `
                    -IdempotencyKey $retryKey -Body $retryBody)
            $retryCount++
        }
        elseif (@('FAILED', 'CANCELLED', 'SUCCEEDED') -contains [string]$job.status) {
            throw "Product job reached an unexpected terminal state: $($job.status)"
        }
        Start-Sleep -Milliseconds 300
    }
    while ([DateTimeOffset]::UtcNow -lt $deadline)

    throw "Timed out waiting for product job state: $($DesiredStatuses -join ', ')"
}

function Invoke-PrivateValkey {
    param(
        [Parameter(Mandatory = $true)][string[]]$CommandArguments
    )

    $composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
    $dockerArguments = @(
        'compose', '-f', $composeFile, '--profile', $Profile,
        'exec', '-T', 'valkey', 'sh', '-ec',
        'REDISCLI_AUTH="$(cat /run/secrets/valkey_password)" exec valkey-cli --no-auth-warning --raw "$@"',
        '--'
    ) + $CommandArguments
    $commandOutput = @(& docker @dockerArguments)
    if ($LASTEXITCODE -ne 0) {
        throw 'The private Valkey duplicate-delivery probe failed.'
    }
    return ($commandOutput -join '').Trim()
}

try {
    if (-not $SnapshotOnly) {
    $productRunId = [Guid]::NewGuid().ToString('N')
    $projectBody = @{
        schemaVersion = '1.0'
        name          = 'AXMS Full Local E2E Project'
        description   = 'Version-managed deterministic full-profile acceptance fixture.'
    }
    $project = Invoke-AxmsJson -Method POST -Path '/api/projects' `
        -IdempotencyKey "axms-full-e2e-create-project-$productRunId" -Body $projectBody
    $projectReplay = Invoke-AxmsJson -Method POST -Path '/api/projects' `
        -IdempotencyKey "axms-full-e2e-create-project-$productRunId" -Body $projectBody
    Assert-ContractValue -Actual $projectReplay.projectId -Expected $project.projectId `
        -Name 'project idempotent replay'

    $projects = Invoke-AxmsJson -Method GET -Path '/api/projects'
    if (@($projects.items | Where-Object { $_.projectId -eq $project.projectId }).Count -ne 1) {
        throw 'The created project is missing from the authoritative project list.'
    }

    $connectorBody = @{
        schemaVersion     = '1.0'
        name              = 'LOCAL_PUBLIC_DATA_FIXTURE'
        baseUrl           = 'https://public-data.fixture.invalid'
        endpoint          = '/v1/documents'
        method            = 'GET'
        authentication    = @{
            type      = 'API_KEY'
            location  = 'QUERY'
            name      = 'serviceKey'
            secretRef = 'fixture://public-data/local-v1'
        }
        requestParameters = @()
        response          = @{ itemsPath = '$.items'; totalCountPath = '$.total' }
        pagination        = @{
            type              = 'PAGE'
            pageParameter     = 'page'
            pageSizeParameter = 'pageSize'
            startPage         = 1
            pageSize          = 20
        }
        documentMapping   = @{
            documentId     = '$.id'
            title          = '$.title'
            content        = '$.content'
            category       = '$.category'
            sourceUrl      = '$.sourceUrl'
            sourceUpdatedAt = '$.updatedAt'
        }
    }
    $connector = Invoke-AxmsJson -Method POST `
        -Path "/api/projects/$($project.projectId)/connectors" `
        -IdempotencyKey "axms-full-e2e-create-connector-$productRunId" -Body $connectorBody
    Assert-ContractValue -Actual $connector.status -Expected 'DRAFT' -Name 'connector initial status'

    $previewBody = @{ schemaVersion = '1.0'; maxItems = 2; parameters = @{} }
    $preview = Invoke-AxmsJson -Method POST -Path "/api/connectors/$($connector.connectorId)/preview" `
        -IdempotencyKey "axms-full-e2e-preview-connector-$productRunId" -Body $previewBody
    Assert-ContractValue -Actual $preview.itemCount -Expected 2 -Name 'fixture preview item count'
    Assert-ContractValue -Actual $preview.totalCount -Expected 3 -Name 'fixture preview total count'
    if (-not $preview.truncated -or @($preview.documents).Count -ne 2) {
        throw 'The deterministic connector preview contract is invalid.'
    }

    $activationBody = @{ schemaVersion = '1.0' }
    $activeConnector = Invoke-AxmsJson -Method POST `
        -Path "/api/connectors/$($connector.connectorId)/versions/$($connector.connectorVersionId)/activate" `
        -IdempotencyKey "axms-full-e2e-activate-connector-$productRunId" -Body $activationBody
    Assert-ContractValue -Actual $activeConnector.status -Expected 'ACTIVE' -Name 'connector activation'

    $syncBody = @{ schemaVersion = '1.0'; connectorVersionId = [string]$connector.connectorVersionId }
    $syncAccepted = Invoke-AxmsJson -Method POST -Path "/api/connectors/$($connector.connectorId)/sync" `
        -IdempotencyKey "axms-full-e2e-sync-connector-$productRunId" -Body $syncBody
    Assert-ContractValue -Actual $syncAccepted.status -Expected 'QUEUED' -Name 'connector sync acceptance'
    [void](Wait-ProductJob -JobId ([string]$syncAccepted.jobId) -DesiredStatuses @('SUCCEEDED'))

    $knowledgeBody = @{
        schemaVersion = '1.0'
        projectId     = [string]$project.projectId
        name          = 'AXMS Deterministic Knowledge'
        description   = 'Knowledge built from the local connector fixture.'
    }
    $knowledge = Invoke-AxmsJson -Method POST -Path '/api/knowledge-bases' `
        -IdempotencyKey "axms-full-e2e-create-knowledge-$productRunId" -Body $knowledgeBody

    $buildBody = @{
        schemaVersion      = '1.0'
        connectorVersionId = [string]$connector.connectorVersionId
        label              = 'full-local-v1'
    }
    $buildAccepted = Invoke-AxmsJson -Method POST `
        -Path "/api/knowledge-bases/$($knowledge.knowledgeBaseId)/versions" `
        -IdempotencyKey "axms-full-e2e-build-knowledge-$productRunId" -Body $buildBody
    Assert-ContractValue -Actual $buildAccepted.status -Expected 'QUEUED' -Name 'knowledge build acceptance'

    $knowledgeVersion = Invoke-AxmsJson -Method GET `
        -Path "/api/knowledge-versions/$($buildAccepted.knowledgeVersionId)"
    if ($knowledgeVersion.status -ne 'ACTIVE') {
        $approvalJob = Wait-ProductJob -JobId ([string]$buildAccepted.jobId) `
            -DesiredStatuses @('WAITING_APPROVAL')
        $knowledgeVersion = Invoke-AxmsJson -Method GET `
            -Path "/api/knowledge-versions/$($buildAccepted.knowledgeVersionId)"
        Assert-ContractValue -Actual $knowledgeVersion.status -Expected 'APPROVAL_PENDING' `
            -Name 'knowledge build approval state'
        Assert-ContractValue -Actual $knowledgeVersion.documentCount -Expected 3 `
            -Name 'knowledge document count'
        Assert-ContractValue -Actual $knowledgeVersion.chunkCount -Expected 3 `
            -Name 'knowledge chunk count'

        $knowledgeActivationBody = @{
            schemaVersion        = '1.0'
            expectedStateVersion = [int]$approvalJob.stateVersion
        }
        $knowledgeVersion = Invoke-AxmsJson -Method POST `
            -Path "/api/knowledge-versions/$($buildAccepted.knowledgeVersionId)/activate" `
            -IdempotencyKey "axms-full-e2e-activate-knowledge-$productRunId" `
            -Body $knowledgeActivationBody
    }
    Assert-ContractValue -Actual $knowledgeVersion.status -Expected 'ACTIVE' -Name 'knowledge activation'
    Assert-ContractValue -Actual $knowledgeVersion.documentCount -Expected 3 -Name 'knowledge document count'
    Assert-ContractValue -Actual $knowledgeVersion.chunkCount -Expected 3 -Name 'knowledge chunk count'
    [void](Wait-ProductJob -JobId ([string]$buildAccepted.jobId) -DesiredStatuses @('SUCCEEDED') -RetryLimit 0)

    $chatbotBody = @{
        schemaVersion   = '1.0'
        name            = 'AXMS Full Local Chatbot'
        knowledgeBaseId = [string]$knowledge.knowledgeBaseId
    }
    $chatbot = Invoke-AxmsJson -Method POST -Path "/api/projects/$($project.projectId)/chatbots" `
        -IdempotencyKey "axms-full-e2e-create-chatbot-$productRunId" -Body $chatbotBody
    Assert-ContractValue -Actual $chatbot.status -Expected 'ACTIVE' -Name 'chatbot activation'

    $queryBody = @{
        schemaVersion = '1.0'
        query         = '119'
        topK          = 3
    }
    $query = Invoke-AxmsJson -Method POST -Path "/api/chatbots/$($chatbot.chatbotId)/query" `
        -IdempotencyKey "axms-full-e2e-query-chatbot-$productRunId" -Body $queryBody
    $queryReplay = Invoke-AxmsJson -Method POST -Path "/api/chatbots/$($chatbot.chatbotId)/query" `
        -IdempotencyKey "axms-full-e2e-query-chatbot-$productRunId" -Body $queryBody
    Assert-ContractValue -Actual $query.outcome -Expected 'ANSWERED' -Name 'grounded RAG outcome'
    Assert-ContractValue -Actual $query.knowledgeVersionId -Expected $knowledgeVersion.knowledgeVersionId `
        -Name 'RAG active knowledge version'
    Assert-ContractValue -Actual $queryReplay.queryId -Expected $query.queryId -Name 'RAG idempotent replay'
    if (@($query.citations).Count -lt 1) {
        throw 'The grounded RAG answer did not include a citation.'
    }

    $refusedQueryBody = @{
        schemaVersion = '1.0'
        query         = 'quantum astrophysics'
        topK          = 3
    }
    $refusedQuery = Invoke-AxmsJson -Method POST `
        -Path "/api/chatbots/$($chatbot.chatbotId)/query" `
        -IdempotencyKey "axms-full-e2e-refused-query-$productRunId" -Body $refusedQueryBody
    Assert-ContractValue -Actual $refusedQuery.outcome -Expected 'REFUSED' `
        -Name 'ungrounded RAG refusal'
    if ($null -eq $refusedQuery.citations -or @($refusedQuery.citations).Count -ne 0) {
        throw 'The ungrounded RAG refusal unexpectedly included citations.'
    }
    }

    $codingSession = Invoke-RestMethod -UseBasicParsing `
        -Uri "$baseUri/internal/dev/coding-jobs/session" -TimeoutSec 10
    if (($codingSession.schemaVersion -ne '1.0') -or
            (-not $codingSession.enabled) -or
            [string]::IsNullOrWhiteSpace([string]$codingSession.csrfToken)) {
        throw 'The local coding job session endpoint returned an invalid contract.'
    }

    $commonProfileFixturePath = Join-Path $repositoryRoot `
        'contracts\fixtures\orchestration\llm-ops-common-runtime.snapshot.valid.json'
    $commonProfile = Publish-AdminProfileFixture -FixturePath $commonProfileFixturePath
    $profileVersionId = [string]$commonProfile.profileVersionId

    $codingRunId = [Guid]::NewGuid().ToString('N')
    $codingTraceId = [Guid]::NewGuid().ToString()
    $codingCreateBody = @{
        schemaVersion       = '1.0'
        profileVersionId    = $profileVersionId
        actorId            = '11111111-1111-4111-8111-111111111111'
        projectId          = '22222222-2222-4222-8222-222222222222'
        repositoryId       = '33333333-3333-4333-8333-333333333333'
        graphStep          = 'plan'
        baseSha            = 'sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
        contextDigest      = 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
        policyHash         = 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
        promptVersion      = 'full-local-common-v1'
        allowedCapabilities = @('CHAT', 'TOOL_CALLING')
        allowedNodes       = @('plan')
        expiresAt          = [DateTimeOffset]::UtcNow.AddMinutes(30).ToString('o')
    }
    $codingCreateKey = "axms-full-coding-create-$codingRunId"
    $codingJob = Invoke-CodingJson -Method POST -Path '/internal/dev/coding-jobs' `
        -TraceId $codingTraceId -CsrfToken ([string]$codingSession.csrfToken) `
        -IdempotencyKey $codingCreateKey -Body $codingCreateBody
    $codingReplay = Invoke-CodingJson -Method POST -Path '/internal/dev/coding-jobs' `
        -TraceId $codingTraceId -CsrfToken ([string]$codingSession.csrfToken) `
        -IdempotencyKey $codingCreateKey -Body $codingCreateBody
    Assert-ContractValue -Actual $codingReplay.jobId -Expected $codingJob.jobId `
        -Name 'coding job idempotent replay'
    Assert-ContractValue -Actual $codingJob.profileVersionId -Expected $profileVersionId `
        -Name 'coding immutable Profile Version binding'

    $codingState = Wait-CodingJob -JobId ([string]$codingJob.jobId) -TraceId $codingTraceId `
        -DesiredStatuses @('COMPLETED')
    $codingFailureProperty = $codingState.PSObject.Properties['failure']
    if ($null -ne $codingFailureProperty -and $null -ne $codingFailureProperty.Value) {
        throw 'The common Profile-bound Coding job completed with a failure payload.'
    }
    if ([int]$codingState.stateVersion -lt 3) {
        throw 'Coding job completed before the expected resolve/claim/outcome lifecycle.'
    }

    $completedStateVersion = [int]$codingState.stateVersion
    $completedFinishedAt = [string]$codingState.finishedAt
    $codingQueue = 'axms:coding:jobs:v1'
    $codingProcessingQueue = "$codingQueue`:processing"
    $duplicatePayload = @{ jobId = [string]$codingJob.jobId } | ConvertTo-Json -Compress
    $queueLength = Invoke-PrivateValkey -CommandArguments @(
        'LPUSH', $codingQueue, $duplicatePayload)
    if ($queueLength -notmatch '^\d+$' -or [int]$queueLength -lt 1) {
        throw 'The completed Coding job duplicate delivery was not published.'
    }

    $replayDeadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitTimeoutSeconds)
    do {
        $queuedPosition = Invoke-PrivateValkey -CommandArguments @(
            'LPOS', $codingQueue, $duplicatePayload)
        $processingPosition = Invoke-PrivateValkey -CommandArguments @(
            'LPOS', $codingProcessingQueue, $duplicatePayload)
        if ([string]::IsNullOrWhiteSpace($queuedPosition) -and
                [string]::IsNullOrWhiteSpace($processingPosition)) {
            break
        }
        Start-Sleep -Milliseconds 200
    }
    while ([DateTimeOffset]::UtcNow -lt $replayDeadline)
    if (-not [string]::IsNullOrWhiteSpace($queuedPosition) -or
            -not [string]::IsNullOrWhiteSpace($processingPosition)) {
        throw 'The production WorkerLoop did not acknowledge the completed Job replay.'
    }

    $replayedCodingState = Invoke-CodingJson -Method GET `
        -Path "/internal/dev/coding-jobs/$($codingJob.jobId)" -TraceId $codingTraceId
    Assert-ContractValue -Actual $replayedCodingState.status -Expected 'COMPLETED' `
        -Name 'completed Coding job replay status'
    Assert-ContractValue -Actual $replayedCodingState.stateVersion -Expected $completedStateVersion `
        -Name 'completed Coding job replay state version'
    Assert-ContractValue -Actual $replayedCodingState.finishedAt -Expected $completedFinishedAt `
        -Name 'completed Coding job replay finish time'
    Assert-ContractValue -Actual $replayedCodingState.profileVersionId -Expected $profileVersionId `
        -Name 'completed Coding job replay Profile binding'

    $invalidFixture = [System.IO.File]::ReadAllText($commonProfileFixturePath) | ConvertFrom-Json
    $invalidAuthoring = ConvertTo-ProfileAuthoringSnapshot -VersionedSnapshot $invalidFixture
    $invalidCheckNodes = @($invalidAuthoring['nodes'] | Where-Object { $_.id -eq 'check' })
    if ($invalidCheckNodes.Count -ne 1) {
        throw 'The common Profile fixture does not contain exactly one check node.'
    }
    $invalidCheckNodes[0].handlerKey = 'fixture.unregistered'
    $invalidCreateBody = [ordered]@{
        profileKey = 'LLM_OPS'
        snapshot   = $invalidAuthoring
    }
    $invalidProfileError = Invoke-AxmsExpectedError -Method POST `
        -Path '/api/admin/ai/profile-versions' -Body $invalidCreateBody -ExpectedStatus 400
    Assert-ContractValue -Actual $invalidProfileError.schemaVersion -Expected '1.0' `
        -Name 'admin Profile validation error contract version'
    Assert-ContractValue -Actual $invalidProfileError.error.code `
        -Expected 'CONTRACT_VALIDATION_FAILED' `
        -Name 'admin Profile unregistered Handler fail-closed code'
    Assert-ContractValue -Actual $invalidProfileError.error.retryable -Expected $false `
        -Name 'admin Profile unregistered Handler fail-closed retryability'

    $profileVersions = Invoke-AxmsJson -Method GET `
        -Path '/api/admin/ai/profile-versions?profileKey=LLM_OPS'
    $activeProfileVersions = @($profileVersions | Where-Object { $_.status -eq 'ACTIVE' })
    if ($activeProfileVersions.Count -ne 1 -or
            [string]$activeProfileVersions[0].profileVersionId -ne $profileVersionId) {
        throw 'The rejected Profile authoring request changed the active LLM_OPS authority.'
    }

    $codingProfileFixturePath = Join-Path $repositoryRoot `
        'contracts\fixtures\orchestration\llm-ops-coding-handler.snapshot.valid.json'
    $codingProfile = Publish-AdminProfileFixture -FixturePath $codingProfileFixturePath
    $codingProfileVersionId = [string]$codingProfile.profileVersionId
    $profileVersions = Invoke-AxmsJson -Method GET `
        -Path '/api/admin/ai/profile-versions?profileKey=LLM_OPS'
    $activeProfileVersions = @($profileVersions | Where-Object { $_.status -eq 'ACTIVE' })
    if ($activeProfileVersions.Count -ne 1 -or
            [string]$activeProfileVersions[0].profileVersionId -ne $codingProfileVersionId) {
        throw 'The valid Coding Handler Profile was not left as the active LLM_OPS authority.'
    }

    if ($ProductProbeScript) {
        $resolvedProbe = (Resolve-Path -LiteralPath $ProductProbeScript).Path
        $repositoryPrefix = $repositoryRoot.TrimEnd('\') + '\'
        if ((-not (Test-Path -LiteralPath $resolvedProbe -PathType Leaf)) -or
                ([System.IO.Path]::GetExtension($resolvedProbe) -ne '.ps1') -or
                (-not $resolvedProbe.StartsWith(
                    $repositoryPrefix, [StringComparison]::OrdinalIgnoreCase))) {
            throw 'ProductProbeScript must be a version-managed Backend repository script.'
        }
        & $resolvedProbe
        if ($LASTEXITCODE -ne 0) {
            throw 'The additional version-managed full local E2E probe failed.'
        }
    }
}
finally {
    $accessToken = $null
    $authorization = $null
    $session = $null
    $codingSession = $null
}

if ($SnapshotOnly) {
    Write-Output 'SNAPSHOT-ONLY E2E PASS: Admin Profile publication, production Registry/WorkerLoop completion, completed Job duplicate-delivery safety, unregistered Handler fail-closed, and active Coding Handler Profile verified.'
}
else {
    Write-Output 'Frontend/Nginx, Spring/Core DB/Valkey Batch, Connector/Knowledge/RAG, admin Profile publication, common Profile-bound LangGraph completion, completed Job duplicate-delivery safety, authoring fail-closed, and active Coding Handler Profile E2E passed.'
}

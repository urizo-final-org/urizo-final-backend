[CmdletBinding()]
param(
    [ValidateSet('full')]
    [string]$Profile = 'full',

    [int]$WaitTimeoutSeconds = 180,

    [string]$ProductProbeScript
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($WaitTimeoutSeconds -lt 30 -or $WaitTimeoutSeconds -gt 1800) {
    throw 'WaitTimeoutSeconds must be between 30 and 1800.'
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

$session = Invoke-RestMethod -UseBasicParsing -Uri "$baseUri/internal/dev/product-session" -TimeoutSec 10
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
        if (@('FAILED', 'CANCELLED', 'EXPIRED') -contains [string]$job.status) {
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

try {
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

    $codingSession = Invoke-RestMethod -UseBasicParsing `
        -Uri "$baseUri/internal/dev/coding-jobs/session" -TimeoutSec 10
    if (($codingSession.schemaVersion -ne '1.0') -or
            (-not $codingSession.enabled) -or
            [string]::IsNullOrWhiteSpace([string]$codingSession.csrfToken)) {
        throw 'The local coding job session endpoint returned an invalid contract.'
    }

    $codingRunId = [Guid]::NewGuid().ToString('N')
    $codingTraceId = [Guid]::NewGuid().ToString()
    $codingCreateBody = @{
        schemaVersion       = '1.0'
        actorId            = '11111111-1111-4111-8111-111111111111'
        projectId          = '22222222-2222-4222-8222-222222222222'
        repositoryId       = '33333333-3333-4333-8333-333333333333'
        graphStep          = 'plan'
        baseSha            = 'sha1:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
        contextDigest      = 'sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
        policyHash         = 'sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
        promptVersion      = 'full-local-coding-v1'
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

    $codingState = Wait-CodingJob -JobId ([string]$codingJob.jobId) -TraceId $codingTraceId `
        -DesiredStatuses @('WAITING_APPROVAL', 'COMPLETED')
    if ($codingState.status -eq 'WAITING_APPROVAL') {
        $resumeBody = @{
            schemaVersion        = '1.0'
            expectedStateVersion = [int]$codingState.stateVersion
            targetStatus         = 'RUNNING'
        }
        $resumeKey = "axms-full-coding-resume-$codingRunId"
        $codingState = Invoke-CodingJson -Method POST `
            -Path "/internal/dev/coding-jobs/$($codingJob.jobId)/transitions" `
            -TraceId $codingTraceId -CsrfToken ([string]$codingSession.csrfToken) `
            -IdempotencyKey $resumeKey -Body $resumeBody
        Assert-ContractValue -Actual $codingState.status -Expected 'RUNNING' `
            -Name 'coding approval resume transition'
    }
    $codingState = Wait-CodingJob -JobId ([string]$codingJob.jobId) -TraceId $codingTraceId `
        -DesiredStatuses @('COMPLETED')
    if ([int]$codingState.stateVersion -lt 6) {
        throw 'Coding job completed without the expected claim/interrupt/resume lifecycle.'
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

Write-Output 'Frontend/Nginx, Spring/Core DB/Valkey Batch, Connector/Knowledge/RAG, and LangGraph checkpoint interrupt/resume E2E passed.'

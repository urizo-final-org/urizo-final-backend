[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PythonExecutable,

    [string] $OrchestratorRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not $OrchestratorRoot) {
    $OrchestratorRoot = Join-Path (Split-Path $repositoryRoot -Parent) 'urizo-final-orchestrator'
}
$OrchestratorRoot = (Resolve-Path -LiteralPath $OrchestratorRoot).Path
if (-not (Test-Path -LiteralPath $PythonExecutable -PathType Leaf)) {
    throw 'The requested Python executable was not found.'
}

& (Join-Path $PSScriptRoot 'sync-dev-database-roles.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local database role synchronization failed.'
}

$dockerBinCandidates = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\DockerDesktop\resources\bin'),
    'C:\Program Files\Docker\Docker\resources\bin'
)
$dockerBin = $dockerBinCandidates |
    Where-Object { Test-Path -LiteralPath (Join-Path $_ 'docker.exe') } |
    Select-Object -First 1
if (-not $dockerBin) {
    throw 'Docker CLI was not found in an approved Docker Desktop installation path.'
}
$docker = Join-Path $dockerBin 'docker.exe'
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$databaseContainer = (& $docker compose -f $composeFile ps -q database).Trim()
if (-not $databaseContainer) {
    throw 'Local PostgreSQL container was not found.'
}

$credentialFile = Join-Path $repositoryRoot '.local\secrets\coding_model_bridge_service_token'
if (-not (Test-Path -LiteralPath $credentialFile -PathType Leaf)) {
    throw 'Local coding service credential file is missing.'
}
$activeCredentialCount = (& $docker exec $databaseContainer psql `
    -U bootstrap_admin `
    -d ax_module_studio `
    -v ON_ERROR_STOP=1 `
    -Atc "SELECT count(*) FROM app.coding_service_credential WHERE status = 'ACTIVE' AND valid_from <= now() AND (valid_until IS NULL OR valid_until > now());").Trim()
if ($LASTEXITCODE -ne 0 -or $activeCredentialCount -ne '1') {
    throw 'Exactly one active local coding service credential is required.'
}

if (-not $env:JAVA_HOME) {
    $knownJdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
    if (-not (Test-Path -LiteralPath (Join-Path $knownJdk 'bin\java.exe'))) {
        throw 'JDK 21 was not found.'
    }
    $env:JAVA_HOME = $knownJdk
}
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'

Push-Location $repositoryRoot
try {
    & '.\mvnw.cmd' -o -Pspring-ai-product -DskipTests compile `
        dependency:build-classpath `
        '-Dmdep.outputFile=target/local-model-turn-runtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) {
        throw 'Backend local smoke compile or classpath resolution failed.'
    }
}
finally {
    Pop-Location
}

$classpathFile = Join-Path $repositoryRoot 'target\local-model-turn-runtime-classpath.txt'
if (-not (Test-Path -LiteralPath $classpathFile -PathType Leaf)) {
    throw 'Backend runtime classpath was not produced.'
}
$applicationClasspath = (Join-Path $repositoryRoot 'target\classes') + ';' +
    ([System.IO.File]::ReadAllText($classpathFile).Trim())

$listener = [System.Net.Sockets.TcpListener]::new(
    [System.Net.IPAddress]::Loopback,
    0)
$listener.Start()
$port = ([System.Net.IPEndPoint] $listener.LocalEndpoint).Port
$listener.Stop()

$jobId = $null
$traceId = [Guid]::NewGuid().ToString()
$contextDigest = 'sha256:' + ('c' * 64)

$logSuffix = [Guid]::NewGuid().ToString('N')
$stdoutLog = Join-Path $repositoryRoot "target\local-model-turn-smoke-$logSuffix.out.log"
$stderrLog = Join-Path $repositoryRoot "target\local-model-turn-smoke-$logSuffix.err.log"
$backendProcess = $null
$previousPythonPath = $env:PYTHONPATH
try {
    $backendProcess = Start-Process `
        -FilePath $java `
        -ArgumentList @(
            '-cp',
            $applicationClasspath,
            'org.urizo.axmodulestudio.backend.AxModuleStudioBackendApplication',
            '--spring.profiles.active=dev,coding-job-local-fixture,coding-model-turn-local-mock',
            '--ax.coding.job-lifecycle.enabled=true',
            '--ax.coding.model-turn-bridge.enabled=true',
            '--server.address=127.0.0.1',
            "--server.port=$port"
        ) `
        -WorkingDirectory $repositoryRoot `
        -WindowStyle Hidden `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru

    $healthUri = "http://127.0.0.1:$port/api/health"
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $ready = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($backendProcess.HasExited) {
            throw 'Local mock Backend exited before becoming healthy.'
        }
        try {
            $health = Invoke-RestMethod -Uri $healthUri -TimeoutSec 2
            if ($health.status -eq 'UP') {
                $ready = $true
                break
            }
        }
        catch {
            # Bounded startup polling; detailed application logs remain local.
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw 'Local mock Backend did not become healthy within 45 seconds.'
    }

    $jobSession = Invoke-RestMethod `
        -Uri "http://127.0.0.1:$port/internal/dev/coding-jobs/session" `
        -Headers @{ 'X-Trace-Id' = $traceId } `
        -TimeoutSec 5
    $mutationHeaders = @{
        'X-Trace-Id' = $traceId
        'X-AXMS-CSRF' = $jobSession.csrfToken
        'Origin' = 'http://127.0.0.1:5173'
    }
    $createHeaders = $mutationHeaders.Clone()
    $createHeaders['Idempotency-Key'] = "local.smoke.create.$([Guid]::NewGuid())"
    $createBody = @{
        schemaVersion = '1.0'
        actorId = [Guid]::NewGuid().ToString()
        projectId = [Guid]::NewGuid().ToString()
        repositoryId = [Guid]::NewGuid().ToString()
        graphStep = 'plan'
        baseSha = 'sha1:' + ('a' * 40)
        contextDigest = $contextDigest
        policyHash = 'sha256:' + ('d' * 64)
        promptVersion = 'local-smoke-v1'
        allowedCapabilities = @('CHAT')
        allowedNodes = @('plan')
        expiresAt = [DateTimeOffset]::UtcNow.AddMinutes(5).ToString('o')
    } | ConvertTo-Json -Compress
    $createdJob = Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:$port/internal/dev/coding-jobs" `
        -Headers $createHeaders `
        -ContentType 'application/json' `
        -Body $createBody `
        -TimeoutSec 5
    $jobId = $createdJob.jobId

    $transitionHeaders = $mutationHeaders.Clone()
    $transitionHeaders['Idempotency-Key'] = "local.smoke.start.$([Guid]::NewGuid())"
    $transitionBody = @{
        schemaVersion = '1.0'
        expectedStateVersion = $createdJob.stateVersion
        targetStatus = 'RUNNING'
    } | ConvertTo-Json -Compress
    $runningJob = Invoke-RestMethod `
        -Method Post `
        -Uri "http://127.0.0.1:$port/internal/dev/coding-jobs/$jobId/transitions" `
        -Headers $transitionHeaders `
        -ContentType 'application/json' `
        -Body $transitionBody `
        -TimeoutSec 5
    if ($runningJob.status -ne 'RUNNING' -or $runningJob.stateVersion -ne 2) {
        throw 'Authoritative local coding Job did not enter RUNNING state version 2.'
    }

    $env:PYTHONPATH = Join-Path $OrchestratorRoot 'src'
    $verificationScript = Join-Path $OrchestratorRoot 'tests\verify_local_model_turn_roundtrip.py'
    & $PythonExecutable $verificationScript `
        --endpoint "http://127.0.0.1:$port/internal/coding/model-turns" `
        --credential-file $credentialFile `
        --job-id $jobId `
        --expected-state-version $runningJob.stateVersion `
        --trace-id $traceId
    if ($LASTEXITCODE -ne 0) {
        throw 'Spring-Orchestrator local Model Turn round trip failed.'
    }
}
finally {
    if ($backendProcess -and -not $backendProcess.HasExited) {
        Stop-Process -Id $backendProcess.Id -Force
        [void] $backendProcess.WaitForExit(5000)
    }
    if ($null -eq $previousPythonPath) {
        Remove-Item Env:PYTHONPATH -ErrorAction SilentlyContinue
    }
    else {
        $env:PYTHONPATH = $previousPythonPath
    }

    if ($jobId) {
        $cleanupSql = "BEGIN; SET ROLE dev_operator; DELETE FROM app.coding_model_turn_idempotency WHERE job_id = '$jobId'; DELETE FROM app.coding_job_lifecycle_command WHERE job_id = '$jobId'; DELETE FROM app.coding_job WHERE job_id = '$jobId'; COMMIT;"
        & $docker exec $databaseContainer psql `
            -U bootstrap_admin `
            -d ax_module_studio `
            -v ON_ERROR_STOP=1 `
            -P pager=off `
            -c $cleanupSql
        if ($LASTEXITCODE -ne 0) {
            Write-Warning 'Local Model Turn smoke Job cleanup needs attention.'
        }
    }

    foreach ($logPath in @($stdoutLog, $stderrLog)) {
        if ((Test-Path -LiteralPath $logPath) -and
                $logPath.StartsWith((Join-Path $repositoryRoot 'target\local-model-turn-smoke-'))) {
            Remove-Item -LiteralPath $logPath -Force
        }
    }
    if (Test-Path -LiteralPath $classpathFile) {
        Remove-Item -LiteralPath $classpathFile -Force
    }
}

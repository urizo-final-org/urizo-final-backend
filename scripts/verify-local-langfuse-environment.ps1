[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$helperScript = Join-Path $PSScriptRoot 'local-langfuse-environment.ps1'
$bootstrapScript = Join-Path $PSScriptRoot 'bootstrap-dev.ps1'
$rebuildScript = Join-Path $PSScriptRoot 'rebuild-local-service.ps1'
$startScript = Join-Path $PSScriptRoot 'start-cms-local.ps1'
foreach ($script in @($helperScript, $bootstrapScript, $rebuildScript, $startScript)) {
    $parseErrors = $null
    [void][Management.Automation.Language.Parser]::ParseFile($script, [ref]$null, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) {
        throw "$(Split-Path -Leaf $script) has $($parseErrors.Count) PowerShell parse error(s)."
    }
}

. $helperScript
$temporaryParent = [IO.Path]::GetTempPath().TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar)
$temporaryRoot = Join-Path $temporaryParent ("axms-langfuse-env-test-{0}" -f [Guid]::NewGuid())
$validFile = Join-Path $temporaryRoot 'valid.env'
$partialFile = Join-Path $temporaryRoot 'partial.env'
$unknownFile = Join-Path $temporaryRoot 'unknown.env'
$malformedFile = Join-Path $temporaryRoot 'malformed.env'
$names = @('LANGFUSE_BASE_URL', 'LANGFUSE_PUBLIC_KEY', 'LANGFUSE_SECRET_KEY')
$previous = @{}
try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot)
    [IO.File]::WriteAllLines($validFile, @(
        'LANGFUSE_BASE_URL=https://example.invalid',
        'LANGFUSE_PUBLIC_KEY="public-test-value"',
        "LANGFUSE_SECRET_KEY='secret-test-value'"
    ), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines($partialFile, @(
        'LANGFUSE_BASE_URL=https://example.invalid',
        'LANGFUSE_PUBLIC_KEY=public-test-value'
    ), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines($unknownFile, @(
        'LANGFUSE_BASE_URL=https://example.invalid',
        'LANGFUSE_PUBLIC_KEY=public-test-value',
        'LANGFUSE_SECRET_KEY=secret-test-value',
        'UNRELATED_SECRET=must-not-load'
    ), [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllLines($malformedFile, @(
        'LANGFUSE_BASE_URL=https://example.invalid',
        'LANGFUSE_PUBLIC_KEY=public-test-value',
        'LANGFUSE_SECRET_KEY="unterminated-test-value'
    ), [Text.UTF8Encoding]::new($false))

    foreach ($name in $names) {
        $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, "previous-$name", 'Process')
    }

    $state = Enter-AxmsLocalLangfuseEnvironment -Path $validFile -Required
    if (-not $state.Loaded -or
            [Environment]::GetEnvironmentVariable('LANGFUSE_BASE_URL', 'Process') -ne 'https://example.invalid' -or
            [Environment]::GetEnvironmentVariable('LANGFUSE_PUBLIC_KEY', 'Process') -ne 'public-test-value' -or
            [Environment]::GetEnvironmentVariable('LANGFUSE_SECRET_KEY', 'Process') -ne 'secret-test-value') {
        throw 'Valid local Langfuse configuration was not loaded exactly.'
    }
    Exit-AxmsLocalLangfuseEnvironment -State $state
    foreach ($name in $names) {
        if ([Environment]::GetEnvironmentVariable($name, 'Process') -ne "previous-$name") {
            throw "Process environment was not restored for '$name'."
        }
    }

    foreach ($invalidFile in @($partialFile, $unknownFile, $malformedFile)) {
        $failedClosed = $false
        try {
            [void](Enter-AxmsLocalLangfuseEnvironment -Path $invalidFile -Required)
        }
        catch {
            $failedClosed = $true
        }
        if (-not $failedClosed) {
            throw "Invalid fixture unexpectedly loaded: $(Split-Path -Leaf $invalidFile)"
        }
    }

    $missingFailedClosed = $false
    try {
        [void](Enter-AxmsLocalLangfuseEnvironment -Path (Join-Path $temporaryRoot 'missing.env') -Required)
    }
    catch {
        $missingFailedClosed = $true
    }
    if (-not $missingFailedClosed) {
        throw 'Required missing local Langfuse configuration did not fail closed.'
    }

    $bootstrapSource = Get-Content -Raw -LiteralPath $bootstrapScript
    $rebuildSource = Get-Content -Raw -LiteralPath $rebuildScript
    $startSource = Get-Content -Raw -LiteralPath $startScript
    if ($bootstrapSource -notmatch 'Enter-AxmsLocalLangfuseEnvironment' -or
            $bootstrapSource -notmatch 'Exit-AxmsLocalLangfuseEnvironment' -or
            $rebuildSource -notmatch 'Enter-AxmsLocalLangfuseEnvironment' -or
            $rebuildSource -notmatch 'Exit-AxmsLocalLangfuseEnvironment' -or
            $startSource -notmatch 'RefreshLocalObservability' -or
            $startSource -notmatch 'Enter-AxmsLocalLangfuseEnvironment' -or
            $startSource -notmatch '--no-deps --force-recreate --wait' -or
            $startSource -notmatch 'spring-app coding-runtime') {
        throw 'Local runtime entry points are not wired to the Langfuse environment contract.'
    }

    Write-Output 'LOCAL LANGFUSE ENVIRONMENT TEST PASS: allowlist, completeness, process restoration, and runtime wiring are verified.'
}
finally {
    foreach ($name in $names) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
    if (Test-Path -LiteralPath $temporaryRoot) {
        $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
        $expectedPrefix = [IO.Path]::GetFullPath($temporaryParent + [IO.Path]::DirectorySeparatorChar)
        if (-not $resolvedTemporaryRoot.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove an unexpected local Langfuse test directory.'
        }
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

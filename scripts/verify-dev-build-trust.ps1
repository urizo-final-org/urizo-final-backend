[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repositoryRoot 'compose.dev.yaml'
$trustOverrideFile = Join-Path $repositoryRoot 'compose.dev-build-trust.yaml'
$trustInitializer = Join-Path $PSScriptRoot 'initialize-dev-build-trust.ps1'

$dockerCommand = Get-Command docker -ErrorAction Stop
$temporaryParent = [System.IO.Path]::GetTempPath().TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar)
$temporaryRoot = Join-Path $temporaryParent ("axms-build-trust-test-{0}" -f [Guid]::NewGuid())

function Invoke-ComposeConfig {
    param(
        [Parameter(Mandatory = $true)][string[]]$Files,
        [Parameter(Mandatory = $true)][bool]$ShouldSucceed,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $arguments = @('compose')
    foreach ($file in $Files) {
        $arguments += @('-f', $file)
    }
    $arguments += @('config', '--quiet')

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $dockerCommand.Source @arguments *> $null
    $succeeded = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $previousErrorActionPreference
    if ($succeeded -ne $ShouldSucceed) {
        throw $FailureMessage
    }
}

function Invoke-ComposeBuildCheck {
    param(
        [Parameter(Mandatory = $true)][string[]]$Files,
        [Parameter(Mandatory = $true)][bool]$ShouldSucceed,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    $arguments = @('compose')
    foreach ($file in $Files) {
        $arguments += @('-f', $file)
    }
    $arguments += @('build', '--check', 'spring-app')

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & $dockerCommand.Source @arguments *> $null
    $succeeded = $LASTEXITCODE -eq 0
    $ErrorActionPreference = $previousErrorActionPreference
    if ($succeeded -ne $ShouldSucceed) {
        throw $FailureMessage
    }
}

try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot)
    $temporaryCompose = Join-Path $temporaryRoot 'compose.dev.yaml'
    Copy-Item -LiteralPath $composeFile -Destination $temporaryCompose
    [System.IO.File]::WriteAllText(
        (Join-Path $temporaryRoot 'Dockerfile'),
        "FROM scratch AS migration`nFROM scratch AS runtime`n" +
            "LABEL org.axms.test=`"build-trust-stat-only`"`n",
        [System.Text.UTF8Encoding]::new($false))
    $temporaryScripts = Join-Path $temporaryRoot 'scripts'
    [void](New-Item -ItemType Directory -Path $temporaryScripts)

    $secretDirectory = Join-Path $temporaryRoot '.local\secrets'
    [void](New-Item -ItemType Directory -Force -Path $secretDirectory)
    foreach ($name in @(
            'postgres_superuser_password',
            'migration_owner_password',
            'cms_app_password',
            'cms_master_key',
            'dbeaver_reader_password',
            'ai_workspace_password',
            'dev_operator_password',
            'coding_model_bridge_service_token',
            'checkpoint_postgres_password',
            'checkpoint_encryption_key',
            'valkey_password',
            'valkey_acl')) {
        [System.IO.File]::WriteAllText(
            (Join-Path $secretDirectory $name),
            'test-only-placeholder',
            [System.Text.UTF8Encoding]::new($false))
    }

    Invoke-ComposeConfig -Files @($temporaryCompose) -ShouldSucceed $true `
        -FailureMessage 'Fresh-clone base Compose configuration must not require local build trust material.'
    Invoke-ComposeBuildCheck -Files @($temporaryCompose) -ShouldSucceed $true `
        -FailureMessage 'Fresh-clone base Compose build check must not require local build trust material.'

    if (-not (Test-Path -LiteralPath $trustOverrideFile -PathType Leaf)) {
        throw 'The opt-in build trust Compose override is missing.'
    }
    $temporaryOverride = Join-Path $temporaryRoot 'compose.dev-build-trust.yaml'
    Copy-Item -LiteralPath $trustOverrideFile -Destination $temporaryOverride
    Invoke-ComposeConfig -Files @($temporaryCompose, $temporaryOverride) -ShouldSucceed $true `
        -FailureMessage 'The opt-in build trust Compose override must be structurally valid.'
    Invoke-ComposeBuildCheck -Files @($temporaryCompose, $temporaryOverride) -ShouldSucceed $false `
        -FailureMessage 'Opt-in build trust must fail closed when its local CA bundle is absent.'

    $temporaryInitializer = Join-Path $temporaryScripts 'initialize-dev-build-trust.ps1'
    Copy-Item -LiteralPath $trustInitializer -Destination $temporaryInitializer
    & $temporaryInitializer

    $bundle = Join-Path $temporaryRoot '.local\runtime-trust\build-extra-ca.pem'
    if (-not (Test-Path -LiteralPath $bundle -PathType Leaf)) {
        throw 'The opt-in build trust initializer did not create the expected CA bundle.'
    }
    $firstContent = [System.IO.File]::ReadAllText($bundle)
    if (-not $firstContent.Contains('-----BEGIN CERTIFICATE-----') -or
            -not $firstContent.Contains('-----END CERTIFICATE-----')) {
        throw 'The opt-in build trust initializer created an invalid CA bundle.'
    }

    Invoke-ComposeConfig -Files @($temporaryCompose, $temporaryOverride) -ShouldSucceed $true `
        -FailureMessage 'Opt-in build trust Compose configuration must accept the generated CA bundle.'
    Invoke-ComposeBuildCheck -Files @($temporaryCompose, $temporaryOverride) -ShouldSucceed $true `
        -FailureMessage 'Opt-in build trust Compose build check must accept the generated CA bundle.'

    & $temporaryInitializer
    $secondContent = [System.IO.File]::ReadAllText($bundle)
    if (-not [string]::Equals($firstContent, $secondContent, [StringComparison]::Ordinal)) {
        throw 'The opt-in build trust initializer is not idempotent.'
    }

    Write-Output 'Dev build trust regression checks passed without displaying certificate contents.'
}
finally {
    $resolvedTemporaryRoot = [System.IO.Path]::GetFullPath($temporaryRoot)
    $resolvedTemporaryParent = [System.IO.Path]::GetFullPath($temporaryParent) +
        [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedTemporaryRoot.StartsWith($resolvedTemporaryParent, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemporaryRoot).StartsWith(
                'axms-build-trust-test-',
                [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

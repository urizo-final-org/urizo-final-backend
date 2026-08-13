[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trustDirectory = Join-Path (Join-Path $repositoryRoot '.local') 'runtime-trust'
$buildBundle = Join-Path $trustDirectory 'build-extra-ca.pem'
$runningOnWindows = $PSVersionTable.PSEdition -eq 'Desktop' -or
    [bool](Get-Variable IsWindows -ValueOnly -ErrorAction SilentlyContinue)
$runningOnMacOS = [bool](Get-Variable IsMacOS -ValueOnly -ErrorAction SilentlyContinue)

[void](New-Item -ItemType Directory -Force -Path $trustDirectory)

function Protect-LocalPath {
    param([Parameter(Mandatory = $true)][string]$LiteralPath)

    if (-not $runningOnWindows) {
        & chmod 600 $LiteralPath
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict local build trust permissions: $LiteralPath"
        }
    }
}

if (Test-Path -LiteralPath $buildBundle -PathType Leaf) {
    $existing = [System.IO.File]::ReadAllText($buildBundle)
    if (-not ($existing.Contains('-----BEGIN CERTIFICATE-----') -and
            $existing.Contains('-----END CERTIFICATE-----'))) {
        throw 'The existing opt-in local build trust bundle is invalid.'
    }
    Protect-LocalPath -LiteralPath $buildBundle
    Write-Output 'Opt-in local build trust bundle is ready (certificate contents not displayed).'
    return
}

if ($runningOnWindows) {
    $encodedCertificates = [System.Collections.Generic.SortedSet[string]]::new(
        [System.StringComparer]::Ordinal)
    $stores = @(
        'Cert:\CurrentUser\Root',
        'Cert:\CurrentUser\CA',
        'Cert:\LocalMachine\Root',
        'Cert:\LocalMachine\CA'
    )

    foreach ($store in $stores) {
        if (-not (Test-Path -LiteralPath $store)) {
            continue
        }
        foreach ($certificate in Get-ChildItem -LiteralPath $store) {
            if ($null -eq $certificate.RawData -or $certificate.RawData.Length -eq 0) {
                continue
            }
            [void]$encodedCertificates.Add(
                [Convert]::ToBase64String(
                    $certificate.RawData,
                    [Base64FormattingOptions]::InsertLineBreaks))
        }
    }

    if ($encodedCertificates.Count -eq 0) {
        throw 'No public certificates were available for the opt-in local build trust bundle.'
    }

    $builder = [System.Text.StringBuilder]::new()
    foreach ($encoded in $encodedCertificates) {
        [void]$builder.AppendLine('-----BEGIN CERTIFICATE-----')
        [void]$builder.AppendLine($encoded)
        [void]$builder.AppendLine('-----END CERTIFICATE-----')
    }
    $bundleText = $builder.ToString()
    $builder.Clear() | Out-Null
}
elseif ($runningOnMacOS) {
    $userProfile = [Environment]::GetFolderPath([Environment+SpecialFolder]::UserProfile)
    $keychains = @(
        '/Library/Keychains/System.keychain',
        (Join-Path $userProfile 'Library/Keychains/login.keychain-db')
    )
    $pemChunks = [System.Collections.Generic.List[string]]::new()
    foreach ($keychain in $keychains) {
        if (-not (Test-Path -LiteralPath $keychain)) {
            continue
        }
        $output = & security find-certificate -a -p $keychain 2>$null
        if ($LASTEXITCODE -eq 0 -and $output) {
            $pemChunks.Add(($output -join [Environment]::NewLine))
        }
    }
    if ($pemChunks.Count -eq 0) {
        throw 'No public certificates were available for the opt-in local build trust bundle.'
    }
    $bundleText = $pemChunks -join [Environment]::NewLine
}
else {
    $bundleText = ''
    foreach ($candidate in @(
            '/etc/ssl/certs/ca-certificates.crt',
            '/etc/pki/tls/certs/ca-bundle.crt')) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $bundleText = [System.IO.File]::ReadAllText($candidate)
            break
        }
    }
    if (-not $bundleText.Contains('-----BEGIN CERTIFICATE-----')) {
        throw 'No public certificates were available for the opt-in local build trust bundle.'
    }
}

if (-not ($bundleText.Contains('-----BEGIN CERTIFICATE-----') -and
        $bundleText.Contains('-----END CERTIFICATE-----'))) {
    throw 'The generated opt-in local build trust bundle is invalid.'
}

[System.IO.File]::WriteAllText(
    $buildBundle,
    $bundleText,
    [System.Text.UTF8Encoding]::new($false))
Protect-LocalPath -LiteralPath $buildBundle

Write-Output 'Opt-in local build trust bundle is ready (certificate contents not displayed).'

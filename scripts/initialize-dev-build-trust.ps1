[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trustDirectory = Join-Path $repositoryRoot '.local\runtime-trust'
$nodeBundle = Join-Path $trustDirectory 'node-extra-ca.pem'

[void](New-Item -ItemType Directory -Force -Path $trustDirectory)

if (Test-Path -LiteralPath $nodeBundle -PathType Leaf) {
    $existing = [System.IO.File]::ReadAllText($nodeBundle)
    if ($existing.Contains('-----BEGIN CERTIFICATE-----') -and
            $existing.Contains('-----END CERTIFICATE-----')) {
        Write-Output 'Local Node build trust bundle is ready (certificate contents not displayed).'
        return
    }
    throw 'The existing local Node build trust bundle is invalid.'
}

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
    throw 'No public certificates were available for the local Node build trust bundle.'
}

$builder = [System.Text.StringBuilder]::new()
foreach ($encoded in $encodedCertificates) {
    [void]$builder.AppendLine('-----BEGIN CERTIFICATE-----')
    [void]$builder.AppendLine($encoded)
    [void]$builder.AppendLine('-----END CERTIFICATE-----')
}

[System.IO.File]::WriteAllText(
    $nodeBundle,
    $builder.ToString(),
    [System.Text.UTF8Encoding]::new($false))
$builder.Clear() | Out-Null

Write-Output 'Local Node build trust bundle is ready (certificate contents not displayed).'

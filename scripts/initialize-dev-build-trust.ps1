[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$trustDirectory = Join-Path (Join-Path $repositoryRoot '.local') 'runtime-trust'
$nodeBundle = Join-Path $trustDirectory 'node-extra-ca.pem'
$javaTrustStore = Join-Path $trustDirectory 'cacerts'

[void](New-Item -ItemType Directory -Force -Path $trustDirectory)

function Protect-LocalPath {
    param([Parameter(Mandatory = $true)][string]$LiteralPath)

    if (-not $IsWindows) {
        & chmod 600 $LiteralPath
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict local build trust permissions: $LiteralPath"
        }
    }
}

if (Test-Path -LiteralPath $nodeBundle -PathType Leaf) {
    $existing = [System.IO.File]::ReadAllText($nodeBundle)
    if (-not ($existing.Contains('-----BEGIN CERTIFICATE-----') -and
            $existing.Contains('-----END CERTIFICATE-----'))) {
        throw 'The existing local Node build trust bundle is invalid.'
    }
    Write-Output 'Local Node build trust bundle is ready (certificate contents not displayed).'
}
else {
    if ($IsWindows) {
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
        $bundleText = $builder.ToString()
        $builder.Clear() | Out-Null
    }
    elseif ($IsMacOS) {
        $keychains = @('/Library/Keychains/System.keychain', (Join-Path $HOME 'Library/Keychains/login.keychain-db'))
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
            throw 'No public certificates were available for the local Node build trust bundle.'
        }
        $bundleText = $pemChunks -join [Environment]::NewLine
    }
    else {
        $bundleText = ''
        foreach ($candidate in @('/etc/ssl/certs/ca-certificates.crt', '/etc/pki/tls/certs/ca-bundle.crt')) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $bundleText = [System.IO.File]::ReadAllText($candidate)
                break
            }
        }
        if (-not $bundleText.Contains('-----BEGIN CERTIFICATE-----')) {
            throw 'No public certificates were available for the local Node build trust bundle.'
        }
    }

    [System.IO.File]::WriteAllText(
        $nodeBundle,
        $bundleText,
        [System.Text.UTF8Encoding]::new($false))
    Protect-LocalPath -LiteralPath $nodeBundle

    Write-Output 'Local Node build trust bundle is ready (certificate contents not displayed).'
}

if (Test-Path -LiteralPath $javaTrustStore -PathType Leaf) {
    Write-Output 'Local Java build trust store is ready (contents not displayed).'
}
else {
    $sourceCacerts = $null
    if ($env:JAVA_HOME) {
        $candidate = Join-Path (Join-Path $env:JAVA_HOME 'lib') (Join-Path 'security' 'cacerts')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $sourceCacerts = $candidate
        }
    }
    if (-not $sourceCacerts -and $IsMacOS) {
        $javaHomeOutput = & /usr/libexec/java_home 2>$null
        if ($LASTEXITCODE -eq 0 -and $javaHomeOutput) {
            $candidate = Join-Path (Join-Path ($javaHomeOutput | Select-Object -First 1) 'lib') (Join-Path 'security' 'cacerts')
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $sourceCacerts = $candidate
            }
        }
    }
    if (-not $sourceCacerts) {
        foreach ($candidate in @('/etc/ssl/certs/java/cacerts', '/etc/pki/java/cacerts')) {
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                $sourceCacerts = $candidate
                break
            }
        }
    }
    if (-not $sourceCacerts) {
        throw 'No local JDK cacerts trust store was found. Set JAVA_HOME to a local JDK installation and retry.'
    }

    Copy-Item -LiteralPath $sourceCacerts -Destination $javaTrustStore
    Protect-LocalPath -LiteralPath $javaTrustStore
    Write-Output 'Local Java build trust store is ready (contents not displayed).'
}

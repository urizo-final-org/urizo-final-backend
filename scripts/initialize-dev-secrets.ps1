[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$secretDirectory = Join-Path (Join-Path $repositoryRoot '.local') 'secrets'
[void](New-Item -ItemType Directory -Force -Path $secretDirectory)

function Test-WindowsHost {
    # $IsWindows exists only in PowerShell 6 and later, and this script runs under
    # Set-StrictMode, which turns reading an undefined variable into a terminating
    # error rather than yielding $false. Windows PowerShell 5.1 runs on Windows and
    # nowhere else, so its major version answers the question without the variable.
    if ($PSVersionTable.PSVersion.Major -lt 6) {
        return $true
    }
    return $IsWindows
}

function Protect-LocalPath {
    param([Parameter(Mandatory = $true)][string]$LiteralPath)

    if (Test-WindowsHost) {
        $identity = [System.Security.Principal.WindowsIdentity]::GetCurrent()
        $sid = $identity.User.Value
        $grant = "*$($sid):(F)"
        & "$env:SystemRoot\System32\icacls.exe" $LiteralPath '/inheritance:r' '/grant:r' $grant | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict local secret permissions: $LiteralPath"
        }
    }
    else {
        $mode = if (Test-Path -LiteralPath $LiteralPath -PathType Container) { '700' } else { '600' }
        & chmod $mode $LiteralPath
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to restrict local secret permissions: $LiteralPath"
        }
    }
}

# Test-Path also succeeds for a directory, so generation is skipped and the size
# check below reads .Length off a DirectoryInfo, which has no such property. The
# raw failure only says the property is missing, naming neither the secret nor the
# cause, so report both here. Docker leaves an empty directory behind whenever a
# Compose bind mount points at a secret file that does not exist yet.
function Get-SecretFileLength {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Name
    )

    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer) {
        throw ("The local secret '$Name' is a directory, not a file: $Path. " +
            'Docker creates an empty directory when a Compose bind mount points at a ' +
            'missing secret file. Remove that directory and run this script again.')
    }
    return $item.Length
}

function New-PasswordFile {
    param([Parameter(Mandatory = $true)][string]$Name)

    $path = Join-Path $secretDirectory $Name
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 36
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $random.GetBytes($bytes)
        try {
            $value = [Convert]::ToBase64String($bytes)
            [System.IO.File]::WriteAllText(
                $path,
                $value,
                [System.Text.UTF8Encoding]::new($false))
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
            $random.Dispose()
            $value = $null
        }
    }
    Protect-LocalPath -LiteralPath $path
}

function New-MasterKeyFile {
    $path = Join-Path $secretDirectory 'cms_master_key'
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 32
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $random.GetBytes($bytes)
        try {
            [System.IO.File]::WriteAllBytes($path, $bytes)
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
            $random.Dispose()
        }
    }
    if ((Get-SecretFileLength -Path $path -Name 'cms_master_key') -ne 32) {
        throw 'The local CMS master key must contain exactly 32 bytes.'
    }
    Protect-LocalPath -LiteralPath $path
}

function New-CheckpointEncryptionKeyFile {
    $path = Join-Path $secretDirectory 'checkpoint_encryption_key'
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 32
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $random.GetBytes($bytes)
        try {
            [System.IO.File]::WriteAllBytes($path, $bytes)
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
            $random.Dispose()
        }
    }
    if ((Get-SecretFileLength -Path $path -Name 'checkpoint_encryption_key') -ne 32) {
        throw 'The local checkpoint encryption key must contain exactly 32 bytes.'
    }
    Protect-LocalPath -LiteralPath $path
}

function New-ValkeyAclFile {
    $passwordPath = Join-Path $secretDirectory 'valkey_password'
    $aclPath = Join-Path $secretDirectory 'valkey_acl'
    if (-not (Test-Path -LiteralPath $passwordPath -PathType Leaf)) {
        throw 'The local Valkey password file is missing.'
    }
    if (-not (Test-Path -LiteralPath $aclPath)) {
        $password = [System.IO.File]::ReadAllText($passwordPath).Trim()
        try {
            if ($password.Length -lt 43 -or $password.Length -gt 512) {
                throw 'The local Valkey credential has an invalid length.'
            }
            $acl = "user default on >$password ~* &* +@all`n"
            [System.IO.File]::WriteAllText(
                $aclPath,
                $acl,
                [System.Text.UTF8Encoding]::new($false))
        }
        finally {
            $password = $null
            $acl = $null
        }
    }
    Protect-LocalPath -LiteralPath $aclPath
}

function New-ServiceTokenFile {
    param([Parameter(Mandatory = $true)][string]$Name)

    $path = Join-Path $secretDirectory $Name
    if (-not (Test-Path -LiteralPath $path)) {
        $bytes = New-Object byte[] 48
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        $random.GetBytes($bytes)
        try {
            $value = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
            [System.IO.File]::WriteAllText(
                $path,
                $value,
                [System.Text.UTF8Encoding]::new($false))
        }
        finally {
            [Array]::Clear($bytes, 0, $bytes.Length)
            $random.Dispose()
            $value = $null
        }
    }
    $length = Get-SecretFileLength -Path $path -Name $Name
    if ($length -lt 43 -or $length -gt 512) {
        throw "The local service credential has an invalid length: $Name"
    }
    Protect-LocalPath -LiteralPath $path
}

Protect-LocalPath -LiteralPath $secretDirectory
New-PasswordFile -Name 'postgres_superuser_password'
New-PasswordFile -Name 'migration_owner_password'
New-PasswordFile -Name 'cms_app_password'
New-PasswordFile -Name 'auth_jwt_signing_key'
New-PasswordFile -Name 'dbeaver_reader_password'
New-PasswordFile -Name 'ai_workspace_password'
New-PasswordFile -Name 'dev_operator_password'
New-PasswordFile -Name 'checkpoint_postgres_password'
New-PasswordFile -Name 'valkey_password'
New-MasterKeyFile
New-CheckpointEncryptionKeyFile
New-ValkeyAclFile
New-ServiceTokenFile -Name 'coding_model_bridge_service_token'
New-ServiceTokenFile -Name 'mcp_service_token'

Write-Output "Local encrypted-secret material is ready under $secretDirectory (values not displayed)."

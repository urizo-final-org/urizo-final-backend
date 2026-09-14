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

# 커넥터 원천 인증 값은 공공데이터포털에서 발급받는 것이라 이 스크립트가 만들어 줄 수 없다.
# 그렇다고 파일을 없는 채로 두면 Compose가 file Secret을 stat하지 못해 spring-app이 기동조차
# 하지 못한다 — 커넥터를 쓰지 않는 팀원 PC까지 함께 막힌다. 그래서 빈 파일만 놓아 둔다.
#
# 가짜 값을 넣지 않는 이유: ConnectorSecretResolver가 빈 값을 "Connector secret is empty"로
# 수집 시점에 명확히 거절한다. 그럴듯한 가짜 키를 넣으면 원천이 401을 돌려주고 원인이
# 한 겹 더 숨는다. 이미 실제 값이 든 파일은 건드리지 않는다.
function New-ConnectorSecretPlaceholder {
    param([Parameter(Mandatory = $true)][string]$Name)

    $path = Join-Path $secretDirectory $Name
    if (Test-Path -LiteralPath $path) {
        # 디렉터리면 이 수정 이전에 Compose가 남긴 흔적이다. 원인과 조치를 그쪽이 알려준다.
        [void](Get-SecretFileLength -Path $path -Name $Name)
    }
    else {
        [System.IO.File]::WriteAllText($path, '', [System.Text.UTF8Encoding]::new($false))
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
New-ConnectorSecretPlaceholder -Name 'connector_tour_api'
New-ConnectorSecretPlaceholder -Name 'connector_sme_support_api'

Write-Output "Local encrypted-secret material is ready under $secretDirectory (values not displayed)."
Write-Output ("Connector source keys are placeholders. RAG collection needs a real key written into " +
    "$secretDirectory\connector_<name>; every other feature runs without them.")

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'sync-dev-database-roles.ps1')
if ($LASTEXITCODE -ne 0) {
    throw 'Local database role synchronization failed.'
}

$passwordPath = Join-Path $repositoryRoot '.local\secrets\migration_owner_password'
if (-not (Test-Path -LiteralPath $passwordPath)) {
    throw 'Migration credential file is missing.'
}

if (-not $env:JAVA_HOME) {
    $knownJdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
    if (-not (Test-Path -LiteralPath (Join-Path $knownJdk 'bin\java.exe'))) {
        throw 'JDK 21 was not found.'
    }
    $env:JAVA_HOME = $knownJdk
}

$migrationPassword = [System.IO.File]::ReadAllText($passwordPath).Trim()
try {
    $env:AXMS_DB_URL = 'jdbc:postgresql://127.0.0.1:15432/ax_module_studio'
    $env:AXMS_MIGRATION_PASSWORD = $migrationPassword
    Push-Location $repositoryRoot
    try {
        & '.\mvnw.cmd' -o '-Dflyway.validateMigrationNaming=true' flyway:migrate flyway:info
        if ($LASTEXITCODE -ne 0) {
            throw 'Flyway migration or history verification failed.'
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    Remove-Item Env:AXMS_DB_URL -ErrorAction SilentlyContinue
    Remove-Item Env:AXMS_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    $migrationPassword = $null
}

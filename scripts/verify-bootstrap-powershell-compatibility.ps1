[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$bootstrapScript = Join-Path $PSScriptRoot 'bootstrap-dev.ps1'
$parseErrors = $null
[void][Management.Automation.Language.Parser]::ParseFile(
    $bootstrapScript,
    [ref]$null,
    [ref]$parseErrors)
if ($parseErrors.Count -gt 0) {
    throw "bootstrap-dev.ps1 has $($parseErrors.Count) PowerShell parse error(s)."
}

$bootstrapSource = Get-Content -Raw -LiteralPath $bootstrapScript
$roleSyncMatch = [regex]::Match(
    $bootstrapSource,
    '(?ms)^\s*\$checkpointRoleSync\s*=\s*@''\r?\n(?<script>.*?)\r?\n''@')
if (-not $roleSyncMatch.Success) {
    throw 'Checkpoint role synchronization script was not found in bootstrap-dev.ps1.'
}
if (-not $bootstrapSource.Contains('$checkpointRoleSync = $checkpointRoleSync.Replace("`r`n", "`n")') -or
        -not $bootstrapSource.Contains('$checkpointRoleSync | & $docker exec -i $checkpointContainer bash -seu')) {
    throw 'Checkpoint role synchronization must use normalized standard input instead of a native multiline argument.'
}

$roleSyncScript = $roleSyncMatch.Groups['script'].Value.Replace("`r`n", "`n")
if ([regex]::Matches($roleSyncScript, "\\`n").Count -ne 3) {
    throw 'Checkpoint role synchronization line continuations were not preserved.'
}

$temporaryParent = [IO.Path]::GetTempPath().TrimEnd(
    [IO.Path]::DirectorySeparatorChar,
    [IO.Path]::AltDirectorySeparatorChar)
$temporaryRoot = Join-Path $temporaryParent ("axms-bootstrap-argv-test-{0}" -f [Guid]::NewGuid())
$captureScript = Join-Path $temporaryRoot 'capture.ps1'
$captureResult = Join-Path $temporaryRoot 'result.json'
$captureSource = @'
$ErrorActionPreference = 'Stop'
$stream = [Console]::OpenStandardInput()
$memory = New-Object IO.MemoryStream
$stream.CopyTo($memory)
$result = [ordered]@{
    arguments = @($args | ForEach-Object {
        [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$_))
    })
    stdin = [Convert]::ToBase64String($memory.ToArray())
}
$memory.Dispose()
[IO.File]::WriteAllText(
    $env:AXMS_CAPTURE_RESULT,
    ($result | ConvertTo-Json -Compress),
    [Text.UTF8Encoding]::new($false))
'@

try {
    [void](New-Item -ItemType Directory -Path $temporaryRoot)
    [IO.File]::WriteAllText($captureScript, $captureSource, [Text.UTF8Encoding]::new($false))
    $env:AXMS_CAPTURE_RESULT = $captureResult
    $nativeCapture = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'

    $roleSyncScript | & $nativeCapture -NoProfile -ExecutionPolicy Bypass -File $captureScript `
        exec -i test-container bash -seu
    if ($LASTEXITCODE -ne 0) {
        throw "Native argument capture failed with exit code $LASTEXITCODE."
    }

    $capture = Get-Content -Raw -LiteralPath $captureResult | ConvertFrom-Json
    $capturedArguments = @($capture.arguments | ForEach-Object {
        [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($_))
    })
    $expectedArguments = @('exec', '-i', 'test-container', 'bash', '-seu')
    if (($capturedArguments -join "`n") -cne ($expectedArguments -join "`n")) {
        throw 'PowerShell changed the checkpoint docker exec argument boundary.'
    }

    $capturedInput = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($capture.stdin))
    $expectedInput = $roleSyncScript + [Environment]::NewLine
    if ($capturedInput -cne $expectedInput) {
        throw 'PowerShell changed checkpoint role synchronization newlines, quotes, or backslashes.'
    }

    Write-Output "Bootstrap PowerShell compatibility regression passed on PowerShell $($PSVersionTable.PSVersion)."
}
finally {
    Remove-Item Env:AXMS_CAPTURE_RESULT -ErrorAction SilentlyContinue
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($temporaryRoot)
    $resolvedTemporaryParent = [IO.Path]::GetFullPath($temporaryParent) +
        [IO.Path]::DirectorySeparatorChar
    if ($resolvedTemporaryRoot.StartsWith($resolvedTemporaryParent, [StringComparison]::OrdinalIgnoreCase) -and
            (Split-Path -Leaf $resolvedTemporaryRoot).StartsWith(
                'axms-bootstrap-argv-test-',
                [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:AxmsLocalLangfuseEnvironmentNames = @(
    'LANGFUSE_BASE_URL',
    'LANGFUSE_PUBLIC_KEY',
    'LANGFUSE_SECRET_KEY'
)

function Enter-AxmsLocalLangfuseEnvironment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [switch]$Required
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        if ($Required) {
            throw 'Local Langfuse configuration is required but was not found in the ignored local secrets directory.'
        }
        Write-Warning 'Local Langfuse configuration was not found; provider tracing remains disabled.'
        return [pscustomobject]@{
            Loaded = $false
            Previous = @{}
        }
    }

    $values = @{}
    $lineNumber = 0
    foreach ($line in [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8)) {
        $lineNumber += 1
        $candidate = $line.Trim()
        if (-not $candidate -or $candidate.StartsWith('#')) {
            continue
        }
        if ($candidate -notmatch '^(?:export\s+)?(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(?<value>.*)$') {
            throw "Local Langfuse configuration line $lineNumber must use NAME=VALUE syntax."
        }

        $name = $Matches.name
        if ($name -notin $script:AxmsLocalLangfuseEnvironmentNames) {
            throw "Local Langfuse configuration contains unsupported variable '$name'."
        }
        if ($values.ContainsKey($name)) {
            throw "Local Langfuse configuration contains duplicate variable '$name'."
        }

        $value = $Matches.value.Trim()
        if ($value.Length -gt 0) {
            $firstCharacter = $value.Substring(0, 1)
            $lastCharacter = $value.Substring($value.Length - 1, 1)
            $startsQuoted = $firstCharacter -in @('"', "'")
            $endsQuoted = $lastCharacter -in @('"', "'")
            if ($startsQuoted -or $endsQuoted) {
                if ($value.Length -lt 2 -or $firstCharacter -ne $lastCharacter) {
                    throw "Local Langfuse configuration variable '$name' has malformed quotes."
                }
                $value = $value.Substring(1, $value.Length - 2)
            }
        }
        if (-not $value) {
            throw "Local Langfuse configuration variable '$name' must not be empty."
        }
        $values[$name] = $value
    }

    foreach ($name in $script:AxmsLocalLangfuseEnvironmentNames) {
        if (-not $values.ContainsKey($name)) {
            throw "Local Langfuse configuration is missing required variable '$name'."
        }
    }

    $previous = @{}
    $applied = [System.Collections.Generic.List[string]]::new()
    try {
        foreach ($name in $script:AxmsLocalLangfuseEnvironmentNames) {
            $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
            $applied.Add($name)
        }
    }
    catch {
        foreach ($name in $applied) {
            [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
        }
        throw
    }

    return [pscustomobject]@{
        Loaded = $true
        Previous = $previous
    }
}

function Exit-AxmsLocalLangfuseEnvironment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [object]$State
    )

    if (-not $State.Loaded) {
        return
    }
    foreach ($name in $script:AxmsLocalLangfuseEnvironmentNames) {
        [Environment]::SetEnvironmentVariable($name, $State.Previous[$name], 'Process')
    }
}

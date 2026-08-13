[CmdletBinding()]
param(
    [SecureString]$ApiKey
)

$ErrorActionPreference = 'Stop'

if ($null -eq $ApiKey) {
    $ApiKey = Read-Host 'DJI API Key' -AsSecureString
}

$pointer = [IntPtr]::Zero
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($ApiKey)
    $plainText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    if ([string]::IsNullOrWhiteSpace($plainText)) {
        throw 'DJI API Key cannot be empty.'
    }

    $gradleDirectory = Join-Path $env:USERPROFILE '.gradle'
    $propertiesPath = Join-Path $gradleDirectory 'gradle.properties'
    [IO.Directory]::CreateDirectory($gradleDirectory) | Out-Null
    $existing = if (Test-Path -LiteralPath $propertiesPath) {
        [IO.File]::ReadAllLines($propertiesPath)
    } else {
        [string[]]@()
    }
    $retained = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $existing) {
        if ($line -notmatch '^\s*DJI_API_KEY\s*=') {
            $retained.Add($line)
        }
    }
    $retained.Add("DJI_API_KEY=$plainText")
    [IO.File]::WriteAllLines($propertiesPath, $retained, [Text.UTF8Encoding]::new($false))
    Write-Host 'DJI API Key has been stored in the user Gradle configuration.'
} finally {
    if ($pointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

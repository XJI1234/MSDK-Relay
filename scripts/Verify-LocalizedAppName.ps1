param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [string]$Aapt2Path = "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\aapt2.exe"
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "APK does not exist: $ApkPath"
}

if (-not (Test-Path -LiteralPath $Aapt2Path)) {
    throw "aapt2 does not exist: $Aapt2Path"
}

$resources = & $Aapt2Path dump resources $ApkPath
$appName = $resources | Select-String -Pattern '^\s+resource 0x[0-9a-f]+ string/app_name$' -Context 0,40

if ($appName.Count -ne 1) {
    throw 'The APK must contain exactly one string/app_name resource.'
}

$localizedValues = $appName.Context.PostContext
foreach ($locale in @('()', '(zh-rCN)', '(zh-rTW)', '(zh-rHK)')) {
    $entryPattern = "^\s+$([regex]::Escape($locale))\s+`"(.+)`"$"
    $entry = $localizedValues | Where-Object { $_ -match $entryPattern } | Select-Object -First 1
    if ($null -eq $entry -or $entry -notmatch '"MSDK Relay"$') {
        throw "string/app_name for $locale must be MSDK Relay. Actual: $entry"
    }
}

Write-Output 'APK localized app-name verification passed.'

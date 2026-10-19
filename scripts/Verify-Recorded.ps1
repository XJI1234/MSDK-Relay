param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleTasks
)
$ErrorActionPreference = 'Stop'
$mobileRoot = Split-Path -Parent $PSScriptRoot
$recordRunner = Join-Path (Split-Path -Parent $mobileRoot) 'XJICloud/scripts/test-records/run.mjs'
if (-not (Test-Path -LiteralPath $recordRunner)) { throw 'Missing XJICloud test-records runner. Keep both repositories in the same parent directory.' }
if ($env:XJI_RECORDED_GRADLE_CHILD -eq '1') {
    Set-Location -LiteralPath $mobileRoot
    & (Join-Path $mobileRoot 'gradlew.bat') @GradleTasks
    $gradleCode = $LASTEXITCODE
    if ($env:XJI_TEST_RECORDS_DIRECTORY) {
        $collector = Join-Path (Split-Path -Parent $mobileRoot) 'XJICloud/scripts/test-records/collect-gradle.mjs'
        & node $collector $mobileRoot $env:XJI_TEST_RECORDS_DIRECTORY
        if ($LASTEXITCODE -ne 0 -and $gradleCode -eq 0) { $gradleCode = 1 }
    }
    exit $gradleCode
}
if (-not $GradleTasks -or $GradleTasks.Count -eq 0) { $GradleTasks = @('test') }
$nodeCommand = (Get-Command node -ErrorAction Stop).Source
$previousValue = $env:XJI_RECORDED_GRADLE_CHILD
try {
    $env:XJI_RECORDED_GRADLE_CHILD = '1'
    Push-Location -LiteralPath $mobileRoot
    & $nodeCommand $recordRunner --label msdk-gradle -- powershell.exe -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath @GradleTasks
    $resultCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:XJI_RECORDED_GRADLE_CHILD = $previousValue
}
exit $resultCode

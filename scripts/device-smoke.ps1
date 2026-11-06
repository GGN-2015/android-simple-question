#requires -Version 7.0
param(
    [string]$Serial = 'emulator-5554',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repositoryRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $output = & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $($Arguments -join ' ')" }
    return $output
}

function Read-Ui {
    Invoke-Adb @('shell', 'uiautomator', 'dump', '/sdcard/simple-question-qa.xml') | Out-Null
    return [xml]((Invoke-Adb @('shell', 'cat', '/sdcard/simple-question-qa.xml')) -join "`n")
}

function Click-Text {
    param([string]$Text)
    for ($attempt = 0; $attempt -lt 5; $attempt++) {
        $ui = Read-Ui
        $node = $ui.SelectNodes('//node') | Where-Object { $_.text -eq $Text } | Select-Object -First 1
        if ($node) {
            $coordinates = [regex]::Matches($node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
            Invoke-Adb @('shell', 'input', 'tap', [string][int](($coordinates[0] + $coordinates[2]) / 2),
                [string][int](($coordinates[1] + $coordinates[3]) / 2)) | Out-Null
            Start-Sleep -Milliseconds 500
            return
        }
        Start-Sleep -Milliseconds 300
    }
    throw "UI text not found: $Text"
}

if ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')).Trim() -ne '1') {
    throw 'This test clears app data and is restricted to Android emulators.'
}
if (-not $SkipBuild) {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest
    if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
}
Invoke-Adb @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
Invoke-Adb @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
Invoke-Adb @('shell', 'pm', 'clear', 'com.ggn.simplequestion')
$result = (Invoke-Adb @('shell', 'am', 'instrument', '-w',
    'com.ggn.simplequestion.test/com.ggn.simplequestion.SmokeInstrumentation')) -join "`n"
Write-Output $result
if ($result -match 'FAIL:' -or $result -notmatch 'PASS:') { throw 'Instrumentation failed' }

Invoke-Adb @('shell', 'am', 'force-stop', 'com.ggn.simplequestion')
Invoke-Adb @('shell', 'am', 'start', '-n', 'com.ggn.simplequestion/.MainActivity') | Out-Null
Start-Sleep -Milliseconds 800
$ui = Read-Ui
$texts = $ui.SelectNodes('//node') | ForEach-Object { $_.text }
if ($texts -notcontains 'Draft before rotation' -or $texts -notcontains '50.0%') {
    throw 'Process restart failed to restore draft and accuracy'
}
Write-Output 'PASS: force-stop and relaunch restore draft and accuracy'
Invoke-Adb @('shell', 'input', 'keyevent', '4') | Out-Null
Click-Text '结束自测'
Invoke-Adb @('shell', 'input', 'keyevent', '4') | Out-Null
Click-Text '导出全部'
Click-Text 'SAVE'
$ui = Read-Ui
if ($ui.SelectNodes('//node') | Where-Object { $_.text -eq 'REPLACE' }) { Click-Text 'REPLACE' }
Start-Sleep -Milliseconds 600
$exported = ((Invoke-Adb @('shell', "cat '/sdcard/Download/简问-全部题集.json'")) -join "`n") | ConvertFrom-Json
if ($exported.schemaVersion -ne 1 -or $exported.sets[0].questions.Count -ne 2) {
    throw 'Exported JSON does not contain the expected questions'
}
Write-Output 'PASS: export through Android document picker'
Click-Text '导入 JSON'
Click-Text '简问-全部题集.json'
Click-Text '确认导入'
Start-Sleep -Milliseconds 600
$stored = ((Invoke-Adb @('shell', 'run-as', 'com.ggn.simplequestion', 'cat', 'files/question-sets.json')) -join "`n") | ConvertFrom-Json
if ($stored.sets.Count -ne 2 -or $stored.sets[0].id -eq $stored.sets[1].id) {
    throw 'Import failed to append a distinct copy'
}
Write-Output 'PASS: import exported JSON through Android document picker'
New-Item -ItemType Directory -Force -Path build\qa | Out-Null
Invoke-Adb @('pull', '/sdcard/Android/data/com.ggn.simplequestion/files/qa/', 'build/qa')
Write-Output 'All device smoke tests passed.'

# Install the freshly built debug APK on the connected device (MIUI-safe path).
# Usage: powershell -File scripts\install.ps1
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).
$ErrorActionPreference = "Continue"
$adb = "<LOCAL_HOME>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apk = "<LOCAL_HOME>\WorkBuddy\ReadIt\app\build\outputs\apk\debug\app-arm64-v8a-debug.apk"

if (-not (Test-Path $apk)) { Write-Output "missing apk: $apk"; exit 1 }

# MIUI rejects direct `pm install` from a local path -> push first, then install by path.
& $adb push $apk /data/local/tmp/readit_new.apk 2>$null | Out-Null
$out = (& $adb shell pm install -r -d /data/local/tmp/readit_new.apk 2>&1) -join " "
& $adb shell rm /data/local/tmp/readit_new.apk 2>$null | Out-Null
$v = (& $adb shell dumpsys package com.readit.eink 2>&1 | Select-String "lastUpdateTime") -join " "
Write-Output "install: $out"
Write-Output "pkg: $v"

# Install the freshly built debug APK on the connected device (MIUI-safe path).
# Usage: powershell -File scripts\install.ps1 [-Serial <device>]
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).
#
# The APK is split per ABI (isUniversalApk=false), so pick the one matching the
# device: KY-01L is armeabi-v7a, the M4 test phone is arm64-v8a. Installing the
# wrong split fails with INSTALL_FAILED_NO_MATCHING_ABIS.
param([string]$Serial = "")

$ErrorActionPreference = "Continue"
$adb = "<LOCAL_HOME>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$apkDir = "<LOCAL_HOME>\WorkBuddy\ReadIt\app\build\outputs\apk\debug"

# adb needs one unambiguous target when several devices are plugged in.
$devices = @(& $adb devices 2>$null | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] })
if ($Serial -eq "") {
    if ($devices.Count -eq 0) { Write-Output "no device online"; exit 1 }
    if ($devices.Count -gt 1) {
        Write-Output ("multiple devices, pass -Serial. online: " + ($devices -join ", "))
        exit 2
    }
    $Serial = $devices[0]
}
$t = @("-s", $Serial)

$abi = ((& $adb @t shell getprop ro.product.cpu.abi 2>$null) -join "").Trim()
if ($abi -match "arm64") { $abiFile = "app-arm64-v8a-debug.apk" }
elseif ($abi -match "armeabi") { $abiFile = "app-armeabi-v7a-debug.apk" }
else { Write-Output "unknown abi '$abi'"; exit 1 }
$apk = Join-Path $apkDir $abiFile
Write-Output "device: $Serial  abi: $abi  apk: $abiFile"

if (-not (Test-Path $apk)) { Write-Output "missing apk: $apk"; exit 1 }

# MIUI rejects direct `pm install` from a local path -> push first, then install by path.
& $adb @t push $apk /data/local/tmp/readit_new.apk 2>$null | Out-Null
$out = (& $adb @t shell pm install -r -d /data/local/tmp/readit_new.apk 2>&1) -join " "
& $adb @t shell rm /data/local/tmp/readit_new.apk 2>$null | Out-Null
$v = (& $adb @t shell dumpsys package com.readit.eink 2>&1 | Select-String "lastUpdateTime") -join " "
Write-Output "install: $out"
Write-Output "pkg: $v"

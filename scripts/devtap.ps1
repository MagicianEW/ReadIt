# ReadIt device smoke helper.
# Usage: powershell -File scripts\devtap.ps1 -Y 850 -Tag scan
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).
param(
    [Parameter(Mandatory=$true)][int]$Y,
    [Parameter(Mandatory=$true)][string]$Tag,
    [int]$WaitMs = 7000
)

$ErrorActionPreference = "Continue"
$adb = "<LOCAL_HOME>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$tmp = "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp"

# clean slate: force-stop then relaunch shelf so the list is fully built before we tap
& $adb shell am force-stop com.readit.eink 2>&1 | Out-Null
Start-Sleep -Milliseconds 900
& $adb shell am start -n com.readit.eink/com.readit.ui.shelf.ShelfActivity 2>&1 | Out-Null
Start-Sleep -Milliseconds 4500
& $adb logcat -c 2>&1 | Out-Null

$opened = $false
for ($i = 0; $i -lt 4; $i++) {
    & $adb shell input tap 400 $Y 2>&1 | Out-Null
    Start-Sleep -Milliseconds 1700
    $top = (& $adb shell dumpsys activity activities 2>&1 | Select-String "topResumedActivity") -join " "
    if ($top -match "ReaderActivity") { $opened = $true; break }
}

Start-Sleep -Milliseconds $WaitMs
& $adb shell screencap -p "/sdcard/$Tag.png" 2>&1 | Out-Null
& $adb pull "/sdcard/$Tag.png" (Join-Path $tmp "$Tag.png") 2>&1 | Out-Null
$top = (& $adb shell dumpsys activity activities 2>&1 | Select-String "topResumedActivity") -join " "
"opened=$opened" | Out-File -Encoding UTF8 (Join-Path $tmp "log_$Tag.txt")
"top=$top" | Out-File -Encoding UTF8 -Append (Join-Path $tmp "log_$Tag.txt")
& $adb logcat -d -s ReadIt:* 2>&1 | Out-File -Encoding UTF8 -Append (Join-Path $tmp "log_$Tag.txt")
# full log as well: WebView/chromium console output does not use the ReadIt tag
& $adb logcat -d > (Join-Path $tmp "full_$Tag.log") 2>&1
Write-Output "opened=$opened"

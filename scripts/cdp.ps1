param(
    [Parameter(Mandatory=$true)][string]$Expr,
    [int]$WaitMs = 0,
    [int]$AwaitPromise = 0
)
$ErrorActionPreference = "Continue"
$adb = "<LOCAL_HOME>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$py = "<LOCAL_HOME>\.workbuddy\binaries\python\versions\3.13.12\python.exe"
$sock = ((& $adb shell "cat /proc/net/unix | grep webview_devtools" 2>&1) -join " ").Trim()
if ($sock -match "@webview_devtools_remote_(\d+)") {
    $name = "webview_devtools_remote_" + $Matches[1]
    & $adb forward tcp:9222 "localabstract:$name" 2>&1 | Out-Null
    Start-Sleep -Milliseconds 500
    if ($WaitMs -gt 0) { Start-Sleep -Milliseconds $WaitMs }
    # cdp_eval.py takes an expression FILE, not a raw string. Persist first.
    $exprFile = "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\cdp_expr.js"
    [System.IO.File]::WriteAllText($exprFile, $Expr, (New-Object System.Text.UTF8Encoding($false)))
    & $py "<LOCAL_HOME>\WorkBuddy\ReadIt\tools\cdp_eval.py" http://127.0.0.1:9222 $exprFile $AwaitPromise 2>&1 |
        Out-File -Encoding UTF8 "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\cdp_last.txt"
    Write-Output "ok pid=$($Matches[1])"
} else {
    "no devtools socket" | Out-File -Encoding UTF8 "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\cdp_last.txt"
    Write-Output "no socket"
}

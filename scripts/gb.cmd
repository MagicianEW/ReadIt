@echo off
rem ReadIt fast rebuild: assembleDebug only (no clean, no unit tests).
rem Use this for on-device iteration; use gc.cmd for the full gate (clean + tests + apk).
rem ASCII only on purpose: cmd.exe reads .cmd in the console ANSI codepage (CP936 here),
rem so non-ASCII comment bytes can desync line parsing.
rem Everything is redirected inside cmd so PowerShell never sees gradle stderr progress
rem output (which PowerShell turns into an ErrorRecord and aborts the whole command).
set "JAVA_HOME=<LOCAL_TOOLS>\jdk\jdk-17"
set "ANDROID_HOME=<LOCAL_HOME>\AppData\Local\Android\Sdk"
set "ANDROID_SDK_ROOT=<LOCAL_HOME>\AppData\Local\Android\Sdk"
call "<LOCAL_HOME>\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat" -p "<LOCAL_HOME>\WorkBuddy\ReadIt" --console=plain :app:assembleDebug > "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\build.log" 2>&1
if errorlevel 1 exit 1

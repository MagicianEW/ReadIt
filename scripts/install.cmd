@echo off
rem ReadIt install wrapper: install the freshly built debug APK on the connected device.
rem ASCII only on purpose: cmd.exe reads .cmd in the console ANSI codepage (CP936 here),
rem so non-ASCII comment bytes can desync line parsing.
rem
rem Why a wrapper: this machine's PowerShell ExecutionPolicy is Restricted, so install.ps1
rem cannot be invoked directly from a PowerShell prompt. Bypass is scoped to this child
rem process only. install.ps1 itself picks the APK matching the device ABI and accepts an
rem optional -Serial when several devices are plugged in.
rem
rem Everything is redirected inside cmd so PowerShell never sees native stderr progress
rem output (which PowerShell turns into an ErrorRecord and aborts the whole command).
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "<LOCAL_HOME>\WorkBuddy\ReadIt\scripts\install.ps1" > "<LOCAL_HOME>\WorkBuddy\ReadIt\.workbuddy\tmp\install.log" 2>&1
if errorlevel 1 exit 1

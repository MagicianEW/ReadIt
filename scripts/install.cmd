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
rem Run from the repo root: paths below are relative on purpose (the repo must not
rem contain personal absolute paths). Toolchain paths come from scripts\local.env.ps1.
if not exist ".workbuddy\tmp" mkdir ".workbuddy\tmp"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\install.ps1" > ".workbuddy\tmp\install.log" 2>&1
if errorlevel 1 exit 1

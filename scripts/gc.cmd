@echo off
rem ReadIt gate: clean + unit tests + assembleDebug.
rem Run from the repo root. ASCII only on purpose: cmd.exe reads .cmd in the
rem console ANSI codepage (CP936 here), so non-ASCII bytes can desync parsing,
rem and percent-expansions are avoided entirely (project convention).
rem Toolchain paths come from scripts\local.env.ps1 (gitignored) via env.ps1.
rem Output is tee'd into .workbuddy\tmp\build.log by build.ps1; the cmd-level
rem redirect keeps any native stderr away from the calling PowerShell.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\build.ps1" -Task "clean testDebugUnitTest assembleDebug" -Log build.log > nul 2>&1
if errorlevel 1 exit 1

@echo off
rem ReadIt fast rebuild: assembleDebug only (no clean, no unit tests).
rem Use this for on-device iteration; use gc.cmd for the full gate (clean + tests + apk).
rem Run from the repo root. ASCII only, no percent expansions (project convention).
rem Toolchain paths come from scripts\local.env.ps1 (gitignored) via env.ps1.
rem Output is tee'd into .workbuddy\tmp\build.log by build.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\build.ps1" -Task assembleDebug -Log build.log > nul 2>&1
if errorlevel 1 exit 1

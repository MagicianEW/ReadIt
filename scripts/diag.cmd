@echo off
rem Diagnostic single-task run with stacktrace.
rem Run from the repo root. ASCII only, no percent expansions (project convention).
rem Toolchain paths come from scripts\local.env.ps1 (gitignored) via env.ps1.
rem Output is tee'd into .workbuddy\tmp\diag.log by build.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\build.ps1" -Task "checkDebugDuplicateClasses --stacktrace" -Log diag.log > nul 2>&1
if errorlevel 1 exit 1

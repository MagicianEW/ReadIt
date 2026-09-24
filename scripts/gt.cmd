@echo off
rem ReadIt gradle test re-run wrapper (forces execution, ignores build cache).
rem Run from the repo root. ASCII only, no percent expansions (project convention).
rem Toolchain paths come from scripts\local.env.ps1 (gitignored) via env.ps1.
rem Output is tee'd into .workbuddy\tmp\test.log by build.ps1.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "scripts\build.ps1" -Task "testDebugUnitTest --rerun" -Log test.log > nul 2>&1
if errorlevel 1 exit 1

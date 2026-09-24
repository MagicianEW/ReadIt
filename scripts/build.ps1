# ReadIt / build helper - single gradle entry point for all .cmd wrappers.
#
# Usage (PowerShell, from anywhere; paths are resolved from the script location):
#   powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Task assembleDebug
#   powershell -ExecutionPolicy Bypass -File scripts\build.ps1 -Task "clean testDebugUnitTest assembleDebug"
#
# Task tokens are whitespace/comma separated. Tokens starting with "-" are passed
# to gradle as flags (e.g. --rerun, --stacktrace); "clean" stays a root-project
# task; anything else is prefixed with ":app:".
#
# Toolchain resolution (no personal paths in the repo):
#   scripts\env.ps1 -> scripts\local.env.ps1 (gitignored, per machine) -> env vars.
# Gradle is invoked through the repo's own gradlew.bat. Output is tee'd into
# .workbuddy\tmp\<log> and the gradle exit code is returned to the caller.
#
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).

param(
    [string]$Task = "assembleDebug",
    [switch]$Clean,
    [string]$Log = "build.log",
    [switch]$Stacktrace
)

$ErrorActionPreference = "Continue"
$ProgressPreference = 'SilentlyContinue'

. (Join-Path $PSScriptRoot "env.ps1")
$root = $ReadItRoot

$gradlew = Join-Path $root "gradlew.bat"
if (-not (Test-Path $gradlew)) { throw "gradlew.bat not found: $gradlew" }

$logPath = Join-Path (Join-Path $root ".workbuddy\tmp") $Log
New-Item -ItemType Directory -Path (Split-Path $logPath) -Force | Out-Null

$taskList = @($Task -split "[\s,]+" | Where-Object { $_ })
$argsList = @("-p", $root, "--console=plain")
if ($Clean -and ($taskList -notcontains "clean")) { $argsList += "clean" }
if ($Stacktrace) { $argsList += "--stacktrace" }
foreach ($t in $taskList) {
    if ($t.StartsWith("-")) { $argsList += $t }
    elseif ($t -eq "clean") { $argsList += "clean" }
    else { $argsList += ":app:$t" }
}

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "ANDROID_HOME=$env:ANDROID_HOME"
Write-Host "gradlew -> $gradlew"
Write-Host "tasks   -> $($argsList -join ' ')"

# Out-File utf8, not Tee-Object: PS 5.1 Tee-Object always writes UTF-16LE, which
# breaks every grep-based log check downstream.
& $gradlew @argsList *>&1 | Out-File -FilePath $logPath -Encoding utf8
Write-Host "log -> $logPath"
exit $LASTEXITCODE

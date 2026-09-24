# Shared toolchain resolution for ReadIt helper scripts. Dot-source me:
#   . (Join-Path $PSScriptRoot "env.ps1")
# then use: $ReadItRoot, $ReadItAdb, $ReadItPython.
#
# No personal paths are stored in the repo. Resolution order:
#   1. scripts\local.env.ps1  (gitignored, machine specific; see local.env.ps1.example)
#   2. existing environment variables (JAVA_HOME / ANDROID_HOME / ANDROID_SDK_ROOT)
#
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).

$ReadItRoot = Split-Path -Parent $PSScriptRoot

$localEnv = Join-Path $PSScriptRoot "local.env.ps1"
if (Test-Path $localEnv) { . $localEnv }

if (-not $env:JAVA_HOME) {
    throw "JAVA_HOME is not set. Copy scripts\local.env.ps1.example to scripts\local.env.ps1 and fill in your paths, or set JAVA_HOME."
}
if (-not $env:ANDROID_HOME) {
    if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
}

$ReadItAdb = "adb"
if ($env:ANDROID_HOME) {
    $adbCandidate = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
    if (Test-Path $adbCandidate) { $ReadItAdb = $adbCandidate }
}

$ReadItPython = "python"
if ($env:READIT_PYTHON -and (Test-Path $env:READIT_PYTHON)) { $ReadItPython = $env:READIT_PYTHON }

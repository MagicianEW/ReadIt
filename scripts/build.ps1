# ReadIt / 阅即 — 本地构建脚本
# 用法（PowerShell）：
#   .\scripts\build.ps1                # 编译 debug 包
#   .\scripts\build.ps1 -Task test     # 跑单元测试
#   .\scripts\build.ps1 -Clean         # 先 clean 再编译
#
# 说明：本机 services.gradle.org 不通，构建统一走 ~/.gradle/wrapper/dists 里的 Gradle 8.13 缓存。

param(
    [string]$Task = "assembleDebug",
    [switch]$Clean
)

$ErrorActionPreference = "Continue"
$ProgressPreference = 'SilentlyContinue'

$root = Split-Path -Parent $PSScriptRoot
$env:JAVA_HOME = "<LOCAL_TOOLS>\jdk\jdk-17"
$env:ANDROID_HOME = "<LOCAL_HOME>\AppData\Local\Android\Sdk"
$env:ANDROID_SDK_ROOT = "<LOCAL_HOME>\AppData\Local\Android\Sdk"

$gradle = "<LOCAL_HOME>\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    throw "未找到缓存的 Gradle 8.13：$gradle"
}

$log = Join-Path $root ".workbuddy\tmp\build.log"
New-Item -ItemType Directory -Path (Split-Path $log) -Force | Out-Null

$argsList = @("-p", $root, "--console=plain")
if ($Clean) { $argsList += "clean" }
$argsList += ":app:$Task"

Write-Host "JAVA_HOME=$env:JAVA_HOME"
Write-Host "gradle -> $gradle"
Write-Host "task   -> :app:$Task"

& $gradle $argsList *>&1 | Tee-Object -FilePath $log
exit $LASTEXITCODE

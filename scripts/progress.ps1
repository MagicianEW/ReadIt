# ReadIt 阅读进度保存/恢复的真机验证。
#
# 背景：onPause 曾经在「异步打开期间」（PDF 扫描检测 / DOCX 转档，此时 mode 仍是 TXT
# 而画布为空）把 canvas.currentOffset()=0 当进度无条件覆盖写回，等于静默清零。
# 本脚本把该场景固化成可重复的自动化用例。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\progress.ps1 -Book 04_pdf_text_20p.pdf -Pages 3
#   powershell -ExecutionPolicy Bypass -File scripts\progress.ps1 -Book 03_docx_sample.docx -Pages 3
#   powershell -ExecutionPolicy Bypass -File scripts\progress.ps1 -Book 01_txt_chapters.txt -Pages 3 -AbortMs 900
#
# -AbortMs > 0：点开书后 N 毫秒就按返回（模拟「大文件还没加载完就退出」），
#               用于回归「异步打开把进度清零」这一类缺陷。
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).

param(
    [Parameter(Mandatory = $true)][string]$Book,
    [int]$Pages = 0,
    [int]$AbortMs = 0,
    [int]$WaitMs = 6000
)

$ErrorActionPreference = "Continue"
$adb = "<LOCAL_HOME>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$pkg = "com.readit.eink"
$root = "<LOCAL_HOME>\WorkBuddy\ReadIt"
$out = Join-Path $root ".workbuddy\tmp\progress"
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Adb([string[]]$argv) { & $adb @argv 2>$null }

function Get-RowCenter([string]$fileName) {
    Adb @("shell", "uiautomator", "dump", "/sdcard/p_ui.xml") | Out-Null
    Adb @("pull", "/sdcard/p_ui.xml", (Join-Path $out "ui.xml")) | Out-Null
    $p = Join-Path $out "ui.xml"
    if (-not (Test-Path $p)) { return $null }
    try { $doc = [xml](Get-Content $p -Raw -Encoding UTF8) } catch { return $null }
    foreach ($n in $doc.SelectNodes("//node")) {
        if ($n.text -eq $fileName -and $n.bounds -match "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
            $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
            $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
            return "$([int]$x) $([int]$y)"
        }
    }
    return $null
}

function Get-SafeId([string]$name) { return ($name -replace "[^A-Za-z0-9._-]", "_") }

function Read-Progress([string]$id) {
    $f = "files/progress/$id.json"
    $raw = (Adb @("shell", "run-as", $pkg, "cat", $f)) -join ""
    if ([string]::IsNullOrWhiteSpace($raw)) { return "<none>" }
    return $raw.Trim()
}

function Clear-Progress([string]$id) {
    Adb @("shell", "run-as", $pkg, "rm", "-f", "files/progress/$id.json") | Out-Null
}

function Open-Book([string]$fileName) {
    Adb @("shell", "am", "force-stop", $pkg) | Out-Null
    Start-Sleep -Milliseconds 800
    Adb @("shell", "am", "start", "-n", "$pkg/com.readit.ui.shelf.ShelfActivity") | Out-Null
    Start-Sleep -Milliseconds 4000
    $pos = Get-RowCenter $fileName
    if (-not $pos) { return $null }
    Adb @("shell", "input", "tap", ($pos -split "\s+")[0], ($pos -split "\s+")[1]) | Out-Null
    return $pos
}

# 正文三分区：右侧 1/4 为下一页。stage 之外的工具栏区域不参与，故用屏宽 85% / 屏高 45%。
function Tap-NextPage() {
    $wm = (Adb @("shell", "wm", "size")) -join ""
    $w = 1080; $h = 2400
    if ($wm -match "(\d+)x(\d+)") { $w = [int]$Matches[1]; $h = [int]$Matches[2] }
    Adb @("shell", "input", "tap", ([int]($w * 0.85)), ([int]($h * 0.45))) | Out-Null
}

$id = Get-SafeId $Book
$log = @()

$log += "book=$Book id=$id pages=$Pages abortMs=$AbortMs"

Adb @("shell", "am", "force-stop", $pkg) | Out-Null
Start-Sleep -Milliseconds 600
Clear-Progress $id
Start-Sleep -Milliseconds 300
$log += "STEP0 cleared       -> $(Read-Progress $id)"

# ---- 第一轮：打开并翻页，制造一个非零进度 -------------------------------
Adb @("logcat", "-c") | Out-Null
$pos = Open-Book $Book
if (-not $pos) { $log += "RESULT=ROW_NOT_FOUND"; $log | Out-File -Encoding UTF8 (Join-Path $out "result.txt"); $log | Write-Output; exit 2 }
$log += "STEP1 opened at $pos"

if ($AbortMs -gt 0) {
    Start-Sleep -Milliseconds $AbortMs
    Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
} else {
    Start-Sleep -Milliseconds $WaitMs
    for ($i = 0; $i -lt $Pages; $i++) {
        Tap-NextPage
        Start-Sleep -Milliseconds 1200
    }
    Start-Sleep -Milliseconds 1500
    Adb @("shell", "input", "keyevent", "KEYCODE_BACK") | Out-Null
}
Start-Sleep -Milliseconds 2500

$afterRound1 = Read-Progress $id
$log += "STEP2 after round1  -> $afterRound1"
Adb @("logcat", "-d", "-s", "ReadIt:*") | Out-File -Encoding UTF8 (Join-Path $out "log_round1.txt")

# ---- 第二轮：重新打开，检查是否恢复到同一位置 ---------------------------
$pos2 = Open-Book $Book
if (-not $pos2) { $log += "RESULT=ROW_NOT_FOUND_2"; $log | Out-File -Encoding UTF8 (Join-Path $out "result.txt"); $log | Write-Output; exit 2 }
Start-Sleep -Milliseconds $WaitMs
Adb @("logcat", "-d", "-s", "ReadIt:*") | Out-File -Encoding UTF8 (Join-Path $out "log_round2.txt")
Adb @("shell", "screencap", "-p", "/sdcard/p_restore.png") | Out-Null
Adb @("pull", "/sdcard/p_restore.png", (Join-Path $out "restore.png")) | Out-Null

$afterRound2 = Read-Progress $id
$log += "STEP3 after round2  -> $afterRound2"

if ($afterRound1 -eq $afterRound2 -and $afterRound2 -ne "<none>") { $log += "RESULT=PASS (reopen kept same position)" }
elseif ($afterRound2 -eq "<none>") { $log += "RESULT=FAIL (progress lost)" }
else { $log += "RESULT=WARN (position changed across reopen)" }

$log | Out-File -Encoding UTF8 (Join-Path $out "result.txt")
$log | Write-Output

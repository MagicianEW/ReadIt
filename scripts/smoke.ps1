# ReadIt real-device smoke pass over the standard 8-format fixture set.
#
# Picks each row by its *filename* from a uiautomator dump instead of hardcoded Y
# coordinates, so the pass does not silently drift when the shelf order changes.
#
# Usage: powershell -ExecutionPolicy Bypass -File scripts\smoke.ps1
# Outputs per-book logs/screenshots into .workbuddy\tmp\smoke\
# ASCII only on purpose (PowerShell 5.1 + non-ASCII script bytes is a known trap).
$ErrorActionPreference = "Continue"

. (Join-Path $PSScriptRoot "env.ps1")
$adb = $ReadItAdb
$root = $ReadItRoot
$out = Join-Path $root ".workbuddy\tmp\smoke"
New-Item -ItemType Directory -Force -Path $out | Out-Null

$books = @(
    "01_txt_chapters.txt",
    "02_epub_sample.epub",
    "03_docx_sample.docx",
    "04_pdf_text_20p.pdf",
    "05_pdf_scan_10p.pdf",
    "06_pdf_encrypted.pdf",
    "07_pdf_aes.pdf",
    "08_pdf_scanimg_10p.pdf"
)

function Get-RowCenter([string]$fileName) {
    # returns "x y" or $null
    & $adb shell uiautomator dump /sdcard/shelf_ui.xml 2>$null | Out-Null
    & $adb pull /sdcard/shelf_ui.xml (Join-Path $out "shelf_ui.xml") 2>$null | Out-Null
    $p = Join-Path $out "shelf_ui.xml"
    if (-not (Test-Path $p)) { return $null }
    try { $doc = [xml](Get-Content $p -Raw -Encoding UTF8) } catch { return $null }
    foreach ($n in $doc.SelectNodes("//node")) {
        if ($n.text -eq $fileName) {
            if ($n.bounds -match "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
                $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
                $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
                return "$([int]$x) $([int]$y)"
            }
        }
    }
    return $null
}

$summary = @()
foreach ($b in $books) {
    $tag = ($b -replace "\.[a-z]+$", "")
    & $adb shell am force-stop com.readit.eink 2>$null | Out-Null
    Start-Sleep -Milliseconds 800
    & $adb shell am start -n com.readit.eink/com.readit.ui.shelf.ShelfActivity 2>$null | Out-Null
    Start-Sleep -Milliseconds 4000

    $pos = Get-RowCenter $b
    if (-not $pos) { $summary += "$tag`tROW_NOT_FOUND"; continue }

    & $adb logcat -c 2>$null | Out-Null
    $xy = $pos -split "\s+"
    & $adb shell input tap $xy[0] $xy[1] 2>$null | Out-Null
    Start-Sleep -Milliseconds 6000

    $top = (& $adb shell dumpsys activity activities 2>$null | Select-String "topResumedActivity") -join " "
    $reached = if ($top -match "ReaderActivity") { "ReaderActivity" } else { "OTHER" }

    & $adb shell screencap -p "/sdcard/smoke_$tag.png" 2>$null | Out-Null
    & $adb pull "/sdcard/smoke_$tag.png" (Join-Path $out "$tag.png") 2>$null | Out-Null
    & $adb logcat -d -s ReadIt:* 2>$null | Out-File -Encoding UTF8 (Join-Path $out "log_$tag.txt")

    $summary += "$tag`ttap=$pos`ttop=$reached"
}

$summary | Out-File -Encoding UTF8 (Join-Path $out "SUMMARY.txt")
$summary | ForEach-Object { Write-Output $_ }

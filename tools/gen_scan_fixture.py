# -*- coding: utf-8 -*-
"""生成一个「仿真扫描件」PDF 夹具，用于真正验证 F08 扫描版检测。

背景：原有 fixture readit_notext_10p.pdf 是 build_pdf(with_text=False) 生成的
**空白页**（既无文本也无图），设备上只会得到
  scanned=false ... (low text but no full-page image (0.00))
—— 因为 F08 的判定是「文本少 **且** 有整页大图」。空白页没有图，天然不构成扫描件，
所以那条分支（判定为扫描 → 弹出「强制导入」确认框）此前从未被跑过。

本脚本造一个每页铺满一幅大栅格图的 PDF：
  - 页面 A4 (595x842pt)，图像 1240x1754 px
  - 面积比 = 1240*1754 / (595*842) ≈ 4.34，远超 minImageAreaRatio=0.5
  - 页面无文本层 -> chars/page = 0 < minCharsPerPage=100
  => 应判定 scanned=true

图像用纯 stdlib(zlib+struct) 手写灰度 PNG，不依赖 Pillow。
"""

import os
import struct
import sys
import zlib

sys.stdout.reconfigure(encoding="utf-8")

import fitz  # PyMuPDF

_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(_ROOT, ".workbuddy", "tmp", "testbooks", "08_pdf_scanimg_10p.pdf")
PNG = os.path.join(_ROOT, ".workbuddy", "tmp", "scan_page.png")

W, H = 1240, 1754
BG = 236      # 纸底灰
FG = 48       # 墨色
MARGIN_TOP = 110
MARGIN_BOTTOM = 110
MARGIN_X = 85
LINE_PITCH = 34
LINE_INK = 13


def build_rows():
    rows = []
    line_index = 0
    rnd = 20260914
    for y in range(H):
        if y < MARGIN_TOP or y > H - MARGIN_BOTTOM:
            rows.append(bytes([250]) * W)
            continue
        within = (y - MARGIN_TOP) % LINE_PITCH
        if within >= LINE_INK:
            rows.append(bytes([BG]) * W)
            continue

        row = bytearray([BG]) * W
        x = MARGIN_X
        while x < W - MARGIN_X:
            rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF
            word = 28 + (rnd % 74)
            if x + word > W - MARGIN_X:
                word = W - MARGIN_X - x
            for i in range(x, x + word):
                row[i] = FG
            rnd = (rnd * 1103515245 + 12345) & 0x7FFFFFFF
            x += word + 11 + (rnd % 17)
        rows.append(bytes(row))
        line_index += 1
    return rows


def write_gray_png(path, rows, w, h):
    raw = bytearray()
    for r in rows:
        raw.append(0)          # filter type 0
        raw += r

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        c += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return c

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 0, 0, 0, 0))  # 8bit GREY
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


rows = build_rows()
n = write_gray_png(PNG, rows, W, H)
print("png:", PNG, n, "bytes")

doc = fitz.open()
for i in range(10):
    page = doc.new_page(width=595, height=842)   # A4 pt
    page.insert_image(page.rect, filename=PNG)
doc.save(OUT, deflate=True, garbage=4)
doc.close()

print("written:", OUT, os.path.getsize(OUT), "bytes")

chk = fitz.open(OUT)
print("pages:", chk.page_count)
p0 = chk[0]
print("page0 text chars:", len(p0.get_text().strip()))
imgs = p0.get_images(full=True)
print("page0 images:", len(imgs))
if imgs:
    xref = imgs[0][0]
    info = chk.extract_image(xref)
    print("  image:", info["width"], "x", info["height"], info["ext"])
page_area = 595 * 842
if imgs:
    print("  area ratio =", round(info["width"] * info["height"] / page_area, 2))
chk.close()

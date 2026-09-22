# -*- coding: utf-8 -*-
"""生成「内置打包字体」（app/src/main/assets/fonts/）。

为什么要有这个脚本：字体是二进制产物，直接往仓库里丢两个 ttf/otf，过半年没人知道
它们从哪来、用了什么字表、授权是什么。这里把「下载源 + 版本 + 字表口径 + 子集化命令」
全部固化，换字体/补字时改参数重跑即可。

授权（**两款均为 SIL OFL 1.1，允许商用、允许再分发**）：
  - Noto Serif SC（思源宋体）  Copyright 2012-2023 Adobe / Google
  - LXGW WenKai（霞鹜文楷）    Copyright 2021 LXGW Studio（基于 Klee One，Copyright 2020 Fontworks）
OFL 要求随附版权声明与许可证全文 -> 一并生成 LICENSE.txt 打进 APK。

体积口径（APK 每 ABI 分包红线 16MB，这两款子集 + zip 压缩后约 +1.6MB）：
  字表 = GB2312 **一级字库**（高频 3755 字）+ ASCII + 常用标点 ≈ 4023 字，
  覆盖现代汉语 99.9% 以上；生僻字由 Android 自动 fallback 到系统字体（不会出豆腐块）。

用法：
  <python> tools/gen_packaged_fonts.py             # 下载 + 子集化 + 生成 LICENSE
  <python> tools/gen_packaged_fonts.py --license   # 只重新生成 LICENSE.txt
依赖：fonttools（pyftsubset）
"""
import argparse
import os
import subprocess
import sys
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts")
TMP = os.path.join(ROOT, ".workbuddy", "tmp", "fontsrc")

SOURCES = {
    "notoserif_sc": {
        "url": "https://raw.githubusercontent.com/googlefonts/noto-cjk/main/Serif/SubsetOTF/SC/NotoSerifSC-Regular.otf",
        "src_name": "serifsc.otf",
        "out_name": "readit_font_notoserif_sc.otf",
        "css": "ReadIt Serif SC",
        "copyright": "Copyright 2012-2023 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'. "
                     "Noto is a trademark of Google Inc.",
    },
    "lxgw_wenkai": {
        "url": "https://github.com/lxgw/LxgwWenKai/releases/download/v1.520/LXGWWenKai-Regular.ttf",
        "src_name": "lxgw.ttf",
        "out_name": "readit_font_lxgw_wenkai.ttf",
        "css": "ReadIt WenKai",
        "copyright": "Copyright (c) 2021, LXGW Studio (https://lxgw.github.io/). "
                     "This Font Software is based on Klee One (Copyright 2020 The Klee One Project Authors).",
    },
}

OFL_URL = "https://raw.githubusercontent.com/googlefonts/noto-cjk/main/Serif/LICENSE"


def char_table():
    """GB2312 一级字库（高频 3755 字）+ ASCII + 常用标点/全角符号。"""
    chars = [chr(c) for c in range(0x20, 0x7F)]          # ASCII 可打印
    chars += [chr(c) for c in range(0x3000, 0x3040)]     # CJK 标点
    chars += [chr(c) for c in range(0xFF01, 0xFF65)]     # 全角 ASCII / 标点
    for hi in range(0xB0, 0xD8):                          # GB2312 一级：0xB0A1-0xD7F9
        for lo in range(0xA1, 0xFF):
            try:
                chars.append(bytes([hi, lo]).decode("gb2312"))
            except UnicodeDecodeError:
                pass
    chars += list("—…·°′″§№※")
    return "".join(sorted(set(chars)))


def download(url, dest):
    if os.path.exists(dest) and os.path.getsize(dest) > 1024 * 1024:
        print("skip download (cached): " + dest)
        return
    print("downloading: " + url)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with urllib.request.urlopen(url, timeout=300) as r:
        data = r.read()
    with open(dest, "wb") as f:
        f.write(data)
    print("  -> %.1f MB" % (len(data) / 1024 / 1024))


def subset(src, out, txt_file):
    subprocess.run(
        ["pyftsubset", src,
         "--text-file=" + txt_file,
         "--output-file=" + out,
         "--no-hinting",
         "--drop-tables+=DSIG"],
        check=True,
    )
    print("  -> %s %.2f MB" % (os.path.basename(out), os.path.getsize(out) / 1024 / 1024))


def license_text():
    with urllib.request.urlopen(OFL_URL, timeout=60) as r:
        ofl = r.read().decode("utf-8")
    head = [
        "ReadIt 内置字体",
        "",
        "本目录中的字体文件由 tools/gen_packaged_fonts.py 生成（下载源、字表口径均记录在该脚本里）。",
        "字体本身以 SIL Open Font License 1.1 授权，允许商用与再分发；",
        "仅做了「按 GB2312 一级字库子集化」这一项修改（未改动字形设计）。",
        "",
    ]
    for key in ("notoserif_sc", "lxgw_wenkai"):
        s = SOURCES[key]
        head += [
            "----------------------------------------------------------------",
            s["out_name"],
            "  family: " + s["css"],
            "  source: " + s["url"],
            "  " + s["copyright"],
            "",
        ]
    head.append("----------------------------------------------------------------")
    head.append("")
    return "\n".join(head) + ofl


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--license", action="store_true", help="只重新生成 LICENSE.txt")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "LICENSE.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write(license_text())
    print("LICENSE.txt written")
    if args.license:
        return

    os.makedirs(TMP, exist_ok=True)
    txt_file = os.path.join(TMP, "chars_l1.txt")
    table = char_table()
    with open(txt_file, "w", encoding="utf-8") as f:
        f.write(table)
    print("char table: %d chars" % len(table))

    for key, s in SOURCES.items():
        src = os.path.join(TMP, s["src_name"])
        download(s["url"], src)
        subset(src, os.path.join(OUT, s["out_name"]), txt_file)


if __name__ == "__main__":
    main()

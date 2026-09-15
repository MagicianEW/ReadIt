#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""ReadIt APK 体积体检工具。

用途
----
规范 §3.4 规定「单 ABI 分包 APK 稳定低于 16MB / Universal 低于 20MB」。
本脚本把 APK 拆到 ZIP 条目级，给出三件事：

1. 真实内容体积（各条目 compressed size 之和）与文件体积的差额；
2. **条目之间的零字节空洞** —— AGP 增量打包会留下这种纯浪费（P4 曾因此虚增 4.3MB）；
3. BouncyCastle PQC 查表资源是否被误打入包（应恒为 0 条）。

用法
----
    python tools/apk_size_check.py [APK目录或单个APK]

不带参数时默认扫描 app/build/outputs/apk/debug。
定位「有没有空洞」比「文件多大」更重要：文件大可以接受，空洞一定是 bug。
"""

import glob
import os
import struct
import sys
import zipfile

# Windows 上 Python 默认用 locale 编码（CP936）写 stdout，被管道/重定向读取时
# 会出现「UTF-8 字节按 GBK 解码」的乱码。显式固定为 UTF-8，行为才可预期。
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

DEFAULT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "build", "outputs", "apk", "debug",
)

TARGET_SPLIT = 16 * 1048576        # §3.4 单 ABI 分包目标
CEILING_UNIVERSAL = 20 * 1048576   # §3.4 Universal 红线


def analyze(path):
    data = open(path, "rb").read()
    total = len(data)
    print("=" * 78)
    print("APK : %s" % os.path.basename(path))
    print("size: %.3f MB (%d bytes)" % (total / 1048576, total))

    with zipfile.ZipFile(path) as z:
        infos = z.infolist()

    # EOCD -> 中央目录偏移
    eocd = data.rfind(b"PK\x05\x06")
    cd_size, cd_off = struct.unpack_from("<II", data, eocd + 12)

    # 每个条目的真实占用区间（本地头 + 文件名 + 扩展字段 + 压缩数据）
    rows = []
    for i in infos:
        off = i.header_offset
        fname_len, extra_len = struct.unpack_from("<HH", data, off + 26)
        lh = 30 + fname_len + extra_len
        rows.append((off, off + lh + i.compress_size, i))
    rows.sort(key=lambda r: r[0])

    # 空洞 = 相邻条目之间未被任何条目覆盖的区间
    gaps, cursor = [], 0
    for off, end, i in rows:
        if off > cursor:
            gaps.append((cursor, off - cursor, i.filename))
        cursor = max(cursor, end)

    sum_c = sum(i.compress_size for i in infos)
    gap_total = sum(g[1] for g in gaps)
    print("entries=%d  sum_compressed=%.3f MB  holes=%d / %.3f MB  trailing=%d"
          % (len(infos), sum_c / 1048576, len(gaps), gap_total / 1048576, cd_off - cursor))
    print("--> real content = %.3f MB   waste = %.3f MB" % (sum_c / 1048576, gap_total / 1048576))
    if gaps:
        print("    *** 发现空洞（增量打包残留），必须全量 clean 重建 ***")
        for s, n, who in sorted(gaps, key=lambda g: -g[1])[:6]:
            zeros = data[s:s + n].count(0)
            print("      %.3f MB  zeros=%d%%  before %s"
                  % (n / 1048576, 100 * zeros // max(n, 1), who))

    pqc = [i for i in infos if i.filename.startswith("org/bouncycastle/pqc/")]
    print("PQC resources packaged: %d entries, %.3f MB (must be 0)"
          % (len(pqc), sum(p.compress_size for p in pqc) / 1048576))
    for p in sorted(pqc, key=lambda x: -x.compress_size)[:6]:
        print("      %.3f MB  %s" % (p.compress_size / 1048576, p.filename))

    print("top-8 entries:")
    for i in sorted(infos, key=lambda x: -x.compress_size)[:8]:
        print("      %.3f MB  %s" % (i.compress_size / 1048576, i.filename))

    dex = sum(i.compress_size for i in infos if i.filename.endswith(".dex"))
    so = sum(i.compress_size for i in infos if i.filename.endswith(".so"))
    assets = sum(i.compress_size for i in infos if i.filename.startswith("assets/"))
    res = sum(i.compress_size for i in infos if i.filename.startswith("res/"))
    print("breakdown: dex=%.3f  so=%.3f  assets=%.3f  res=%.3f  pqc=%.3f  other=%.3f"
          % (dex / 1048576, so / 1048576, assets / 1048576, res / 1048576,
             sum(p.compress_size for p in pqc) / 1048576,
             (sum_c - dex - so - assets - res - sum(p.compress_size for p in pqc)) / 1048576))

    print("VERDICT: %.3f MB | <20MB ceiling: %s | <16MB split target: %s | holes: %s"
          % (total / 1048576,
             "PASS" if total < CEILING_UNIVERSAL else "FAIL",
             "PASS" if total < TARGET_SPLIT else "FAIL",
             "none" if not gaps else "PRESENT"))


def main():
    args = sys.argv[1:]
    if not args:
        targets = sorted(glob.glob(os.path.join(DEFAULT_DIR, "*.apk")))
    else:
        targets = []
        for a in args:
            if os.path.isdir(a):
                targets.extend(sorted(glob.glob(os.path.join(a, "*.apk"))))
            else:
                targets.append(a)
    if not targets:
        print("no APK found. build first: scripts/g.cmd")
        return 1
    for p in targets:
        analyze(p)
    return 0


if __name__ == "__main__":
    sys.exit(main())

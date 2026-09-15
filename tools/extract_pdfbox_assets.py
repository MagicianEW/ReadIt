# -*- coding: utf-8 -*-
"""把 pdfbox-android AAR 里的 resources 解到 app/src/test/resources，
让 JVM 单元测试能加载 GlyphList / AFM 等资源（真机上这些由 AAR assets 提供）。
"""
import os
import zipfile
import sys

AAR = sys.argv[1]
DEST = sys.argv[2]

count = 0
sizes = 0
with zipfile.ZipFile(AAR) as z:
    names = z.namelist()
    targets = [n for n in names if n.startswith("assets/")]
    print("assets entries:", len(targets))
    for n in targets:
        if n.endswith("/"):
            continue
        rel = n[len("assets/"):]
        if not rel:
            continue
        out = os.path.join(DEST, rel.replace("/", os.sep))
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with z.open(n) as src, open(out, "wb") as dst:
            data = src.read()
            dst.write(data)
            sizes += len(data)
        count += 1
    # 同时统计 classes.jar 里是否自带资源
    if "classes.jar" in names:
        print("classes.jar size:", z.getinfo("classes.jar").file_size)

print("extracted files:", count, "bytes:", sizes)

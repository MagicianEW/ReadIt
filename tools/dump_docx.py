# -*- coding: utf-8 -*-
"""把需求报告 docx 的正文抽成纯文本，便于按章节检索。"""
import re
import sys
import zipfile

src = sys.argv[1]
dst = sys.argv[2]

with zipfile.ZipFile(src) as z:
    xml = z.read("word/document.xml").decode("utf-8", "ignore")

# 段落切分
paras = re.findall(r"<w:p[ >].*?</w:p>|<w:p/>", xml, re.S)
lines = []
for p in paras:
    texts = re.findall(r"<w:t[^>]*>(.*?)</w:t>", p, re.S)
    line = "".join(texts)
    line = (line.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'"))
    lines.append(line.strip())

with open(dst, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))

print("paragraphs=%d chars=%d" % (len(lines), sum(len(x) for x in lines)))

# -*- coding: utf-8 -*-
"""
生成 EPUB 测试夹具（P2 F03 / F10 回归）。

输出目录：app/src/test/resources/fixtures
  readit_sample.epub       EPUB3 + nav XHTML 目录（含二级嵌套）
  readit_sample_ncx.epub   EPUB2 + NCX 目录
  readit_broken.epub       非良构 XHTML（&nbsp; + 未闭合标签）、无目录文档

用法：python tools/gen_epub_fixtures.py
"""
import os
import zipfile

OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                       "app", "src", "test", "resources", "fixtures")

CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="{opf}" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
"""

OPF3 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:readit:fixture:sample</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:language>zh-CN</dc:language>
    <meta property="dcterms:modified">2026-09-14T00:00:00Z</meta>
  </metadata>
  <manifest>
{items}
  </manifest>
  <spine>
{itemrefs}
  </spine>
</package>
"""

OPF2 = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">urn:readit:fixture:ncx</dc:identifier>
    <dc:title>{title}</dc:title>
    <dc:language>zh-CN</dc:language>
  </metadata>
  <manifest>
{items}
  </manifest>
  <spine toc="ncx">
{itemrefs}
  </spine>
</package>
"""

NAV = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>目录</title></head>
<body>
<nav epub:type="toc" id="toc">
  <h1>目录</h1>
  <ol>
    <li><a href="chap01.xhtml">第一章 开篇</a>
      <ol>
        <li><a href="chap01.xhtml#s1">第一节 起因</a></li>
        <li><a href="chap01.xhtml#s2">第二节 经过</a></li>
      </ol>
    </li>
    <li><a href="chap02.xhtml">第二章 发展</a></li>
    <li><a href="chap03.xhtml">第三章 结局</a></li>
  </ol>
</nav>
<nav epub:type="landmarks"><h1>导航</h1><ol><li><a href="chap01.xhtml">正文</a></li></ol></nav>
</body>
</html>
"""

NCX = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:readit:fixture:ncx"/></head>
  <docTitle><text>目录测试书</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>第一章 开篇</text></navLabel>
      <content src="chap01.xhtml"/>
      <navPoint id="np1-1" playOrder="2">
        <navLabel><text>第一节 起因</text></navLabel>
        <content src="chap01.xhtml#s1"/>
      </navPoint>
    </navPoint>
    <navPoint id="np2" playOrder="3">
      <navLabel><text>第二章 发展</text></navLabel>
      <content src="chap02.xhtml"/>
    </navPoint>
    <navPoint id="np3" playOrder="4">
      <navLabel><text>第三章 结局</text></navLabel>
      <content src="chap03.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
"""


def chapter(title, paragraphs, extra_body=""):
    body = "".join("<p>%s</p>" % p for p in paragraphs)
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<html xmlns="http://www.w3.org/1999/xhtml">\n'
            '<head><title>%s</title><meta charset="utf-8"/></head>\n'
            '<body><h1>%s</h1>%s%s</body>\n</html>\n' % (title, title, body, extra_body))


BROKEN_CHAPTER = ('<?xml version="1.0" encoding="UTF-8"?>\n'
                  '<html xmlns="http://www.w3.org/1999/xhtml">\n'
                  '<head><title>坏文档</title></head>\n'
                  '<body><h1>第一章&nbsp;坏文档</h1>'
                  '<p>这一段有一个未闭合的标签<br>'
                  '<p>实体 &mdash; 与 &nbsp; 混排，XmlPullParser 会失败，走正则降级\n'
                  '<p>第二段内容</body>\n</html>\n')


def write_epub(path, opf_text, files):
    """files: dict of {zip内路径: 内容(str)} 不含 mimetype"""
    if os.path.exists(path):
        os.remove(path)
    with zipfile.ZipFile(path, "w") as z:
        # EPUB 规范：mimetype 必须是第一个条目且不压缩
        info = zipfile.ZipInfo("mimetype")
        info.compress_type = zipfile.ZIP_STORED
        z.writestr(info, "application/epub+zip")
        z.writestr("META-INF/container.xml", CONTAINER.format(opf="OEBPS/content.opf"))
        for name, content in files.items():
            z.writestr(name, content)
    print("generated %s (%d bytes)" % (path, os.path.getsize(path)))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    chaps = {
        "OEBPS/chap01.xhtml": chapter("第一章 开篇", [
            "这是第一章的第一段。",
            "这是第一章的第二段。",
        ], extra_body='<h2 id="s1">第一节 起因</h2><p>起因段落。</p>'
                      '<h2 id="s2">第二节 经过</h2><p>经过段落。</p>'),
        "OEBPS/chap02.xhtml": chapter("第二章 发展", [
            "这是第二章的第一段。",
            "这是第二章的第二段。",
            "这是第二章的第三段。",
        ]),
        "OEBPS/chap03.xhtml": chapter("第三章 结局", [
            "这是第三章的唯一一段。",
        ]),
    }

    # --- 1) EPUB3 + nav ---
    items = ['<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>']
    items += ['<item id="c%d" href="chap0%d.xhtml" media-type="application/xhtml+xml"/>' % (i, i)
              for i in (1, 2, 3)]
    itemrefs = "".join('    <itemref idref="c%d"/>\n' % i for i in (1, 2, 3))
    files = dict(chaps)
    files["OEBPS/nav.xhtml"] = NAV
    files["OEBPS/content.opf"] = OPF3.format(
        title="目录测试书", items="\n".join("    " + i for i in items), itemrefs=itemrefs)
    write_epub(os.path.join(OUT_DIR, "readit_sample.epub"), None, files)

    # --- 2) EPUB2 + NCX ---
    items2 = ['<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>']
    items2 += ['<item id="c%d" href="chap0%d.xhtml" media-type="application/xhtml+xml"/>' % (i, i)
               for i in (1, 2, 3)]
    files2 = dict(chaps)
    files2["OEBPS/toc.ncx"] = NCX
    files2["OEBPS/content.opf"] = OPF2.format(
        title="目录测试书", items="\n".join("    " + i for i in items2), itemrefs=itemrefs)
    write_epub(os.path.join(OUT_DIR, "readit_sample_ncx.epub"), None, files2)

    # --- 3) 坏文档 + 无目录 ---
    items3 = ['<item id="c1" href="chap01.xhtml" media-type="application/xhtml+xml"/>',
              '<item id="c2" href="chap02.xhtml" media-type="application/xhtml+xml"/>']
    itemrefs3 = '    <itemref idref="c1"/>\n    <itemref idref="c2"/>\n'
    files3 = {"OEBPS/chap01.xhtml": BROKEN_CHAPTER,
              "OEBPS/chap02.xhtml": chapter("第二章 正常", ["正常段落一。", "正常段落二。"])}
    files3["OEBPS/content.opf"] = OPF3.format(
        title="坏文档测试书", items="\n".join("    " + i for i in items3), itemrefs=itemrefs3)
    write_epub(os.path.join(OUT_DIR, "readit_broken.epub"), None, files3)


if __name__ == "__main__":
    main()

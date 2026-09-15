# -*- coding: utf-8 -*-
"""生成 P0-C 技术门禁用的基准语料（DOCX / PDF）。

输出到 app/src/test/resources/fixtures/
用法: <python> tools/gen_fixtures.py
"""
import os
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "app", "src", "test", "resources", "fixtures")
os.makedirs(OUT, exist_ok=True)


# --------------------------------------------------------------------------
# DOCX
# --------------------------------------------------------------------------
CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Default Extension="png" ContentType="image/png"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>
</Types>"""

RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>
<Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
</Relationships>"""

STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/></w:style>
</w:styles>"""

NUMBERING = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:abstractNum w:abstractNumId="0">
  <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:lvlJc w:val="left"/></w:lvl>
  <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%2."/><w:lvlJc w:val="left"/></w:lvl>
</w:abstractNum>
<w:abstractNum w:abstractNumId="1">
  <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="&#8226;"/><w:lvlJc w:val="left"/></w:lvl>
</w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
<w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>"""

HEADER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:p><w:r><w:t>ReadIt sample header</w:t></w:r></w:p>
</w:hdr>"""

DOCUMENT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
<w:body>
{BODY}
<w:sectPr/>
</w:body>
</w:document>"""


def para(text, style=None, num_id=None, ilvl=0):
    ppr = ""
    if style or num_id:
        parts = []
        if style:
            parts.append('<w:pStyle w:val="%s"/>' % style)
        if num_id:
            parts.append('<w:numPr><w:ilvl w:val="%d"/><w:numId w:val="%d"/></w:numPr>' % (ilvl, num_id))
        ppr = "<w:pPr>%s</w:pPr>" % "".join(parts)
    return "<w:p>%s<w:r><w:t xml:space=\"preserve\">%s</w:t></w:r></w:p>" % (ppr, text)


def rich_para():
    return (
        "<w:p><w:r><w:t xml:space=\"preserve\">Plain text </w:t></w:r>"
        "<w:r><w:rPr><w:b/></w:rPr><w:t>bold</w:t></w:r>"
        "<w:r><w:rPr><w:i/></w:rPr><w:t xml:space=\"preserve\"> italic </w:t></w:r>"
        "<w:r><w:rPr><w:u w:val=\"single\"/></w:rPr><w:t>underline</w:t></w:r>"
        "<w:r><w:rPr><w:strike/></w:rPr><w:t xml:space=\"preserve\"> strike</w:t></w:r>"
        "</w:p>"
    )


def simple_table():
    def cell(t):
        return (
            "<w:tc><w:tcPr><w:tcW w:w=\"3000\" w:type=\"dxa\"/></w:tcPr>"
            "<w:p><w:r><w:t>%s</w:t></w:r></w:p></w:tc>" % t
        )
    return (
        "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>"
        "<w:tr>%s%s</w:tr><w:tr>%s%s</w:tr></w:tbl>"
        % (cell("Header A"), cell("Header B"), cell("Cell 1"), cell("Cell 2"))
    )


def image_block():
    return (
        "<w:p><w:r><w:drawing>"
        "<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">"
        "<wp:extent cx=\"1905000\" cy=\"1270000\"/>"
        "<wp:docPr id=\"1\" name=\"Picture 1\"/>"
        "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
        "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"0\" name=\"image1.png\"/><pic:cNvPicPr/>"
        "</pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rId4\"/><a:stretch><a:fillRect/></a:stretch>"
        "</pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"1905000\" cy=\"1270000\"/>"
        "</a:xfrm></pic:spPr></pic:pic></a:graphicData></a:graphic>"
        "</wp:inline></w:drawing></w:r></w:p>"
    )


def unsupported_textbox():
    """分栏/文本框：属于明确不支持项，解析器应降级并给出提示，不崩溃。"""
    return (
        "<w:p><w:r><w:t>Above the unsupported block</w:t></w:r></w:p>"
        "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"wps\">"
        "<w:drawing><wp:inline><wp:extent cx=\"2000000\" cy=\"1000000\"/>"
        "<a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingShape\">"
        "<wps:wsp><wps:txbx><w:txbxContent><w:p><w:r><w:t>Text inside box</w:t></w:r></w:p>"
        "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic>"
        "</wp:inline></w:drawing></mc:Choice>"
        "<mc:Fallback><w:p><w:r><w:t>fallback</w:t></w:r></w:p></mc:Fallback>"
        "</mc:AlternateContent></w:r></w:p>"
        "<w:p><w:r><w:t>Below the unsupported block</w:t></w:r></w:p>"
    )


def build_document_body(page_multiplier=1):
    parts = []
    for i in range(page_multiplier):
        parts.append(para("Chapter %d Title" % (i + 1), style="Heading1"))
        parts.append(para("Section intro %d" % (i + 1), style="Heading2"))
        parts.append(para("Body paragraph number %d with enough words to be meaningful." % (i + 1)))
        parts.append(rich_para())
        parts.append(para("Ordered item one", num_id=1, ilvl=0))
        parts.append(para("Ordered item two", num_id=1, ilvl=0))
        parts.append(para("Ordered sub item", num_id=1, ilvl=1))
        parts.append(para("Bullet item one", num_id=2, ilvl=0))
        parts.append(para("Bullet item two", num_id=2, ilvl=0))
        parts.append(simple_table())
        parts.append(image_block())
        parts.append(unsupported_textbox())
    return "".join(parts)


# 1x1 transparent PNG
PNG_1PX = (
    b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00"
    b"\x1f\x15\xc4\x89\x00\x00\x00\nIDATx\x9cc\x00\x01\x00\x00\x05\x00\x01\r\n-\xb4\x00\x00"
    b"\x00\x00IEND\xaeB`\x82"
)


def make_docx(path, chapters=1):
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", RELS)
        z.writestr("word/_rels/document.xml.rels", DOC_RELS)
        z.writestr("word/document.xml", DOCUMENT.format(BODY=build_document_body(chapters)))
        z.writestr("word/styles.xml", STYLES)
        z.writestr("word/numbering.xml", NUMBERING)
        z.writestr("word/header1.xml", HEADER)
        z.writestr("word/media/image1.png", PNG_1PX)
    print("docx ->", path, os.path.getsize(path), "bytes")


def make_broken_docx(path):
    """畸形样本：缺 numbering.xml，document.xml 尾部截断，用于验证不崩溃 + 降级。"""
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", CONTENT_TYPES)
        z.writestr("_rels/.rels", RELS)
        z.writestr("word/document.xml", DOCUMENT.format(BODY=build_document_body(1))[: -len("</w:body></w:document>")])
        z.writestr("word/styles.xml", STYLES)
    print("broken docx ->", path, os.path.getsize(path), "bytes")


# --------------------------------------------------------------------------
# PDF
# --------------------------------------------------------------------------
def _esc(s):
    return s.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def build_pdf(path, pages=20, lines_per_page=45, with_text=True):
    """手写最小 PDF：每页一个内容流，with_text=False 时只画矩形（模拟无文本层的扫描版）。"""
    objs = {}
    font_obj = 3 + pages * 2  # last object number used for font

    kids = " ".join("%d 0 R" % (3 + i * 2) for i in range(pages))
    objs[1] = "<< /Type /Catalog /Pages 2 0 R >>"
    objs[2] = "<< /Type /Pages /Kids [%s] /Count %d >>" % (kids, pages)

    for i in range(pages):
        page_no = 3 + i * 2
        content_no = page_no + 1
        objs[page_no] = (
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            "/Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>" % (font_obj, content_no)
        )
        lines = []
        if with_text:
            lines.append("BT /F1 11 Tf 54 738 Td 14 TL")
            for ln in range(lines_per_page):
                text = "Page %d line %d: the quick brown fox jumps over the lazy dog." % (i + 1, ln + 1)
                lines.append("(%s) Tj T*" % _esc(text))
            lines.append("ET")
        else:
            lines.append("0.9 0.9 0.9 rg 54 54 504 684 re f")
        stream = "\n".join(lines).encode("latin-1")
        compressed = zlib.compress(stream, 6)
        objs[content_no] = b"<< /Length %d /Filter /FlateDecode >>\nstream\n" % len(compressed) + compressed + b"\nendstream"

    objs[font_obj] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"

    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for num in sorted(objs):
        offsets[num] = len(out)
        body = objs[num]
        if isinstance(body, str):
            body = body.encode("latin-1")
        out += b"%d 0 obj\n" % num + body + b"\nendobj\n"

    xref_pos = len(out)
    max_obj = max(objs)
    out += b"xref\n0 %d\n" % (max_obj + 1)
    out += b"0000000000 65535 f \n"
    for num in range(1, max_obj + 1):
        if num in offsets:
            out += b"%010d 00000 n \n" % offsets[num]
        else:
            out += b"0000000000 65535 f \n"
    out += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (max_obj + 1, xref_pos)

    with open(path, "wb") as f:
        f.write(bytes(out))
    print("pdf ->", path, os.path.getsize(path), "bytes, pages =", pages)


if __name__ == "__main__":
    make_docx(os.path.join(OUT, "readit_sample.docx"), chapters=1)
    make_docx(os.path.join(OUT, "readit_sample_20.docx"), chapters=20)
    make_broken_docx(os.path.join(OUT, "readit_broken.docx"))
    build_pdf(os.path.join(OUT, "readit_text_20p.pdf"), pages=20, with_text=True)
    build_pdf(os.path.join(OUT, "readit_text_1p.pdf"), pages=1, with_text=True)
    build_pdf(os.path.join(OUT, "readit_notext_10p.pdf"), pages=10, with_text=False)

package com.readit.doc

import com.readit.core.eal.DocxMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0-C 门禁 C2：内置 OOXML 子集解析器回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocxParserTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_docx_test").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("fixtures/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    @Test
    fun `subset mode renders headings runs lists and table`() {
        val result = DocxParser(mode = DocxMode.SUBSET).parse(fixture("readit_sample.docx"))

        println("[SUBSET warnings] ${result.warnings}")
        println("[SUBSET stats] ${result.stats}")
        val dump = File(tmp, "readit_sample.html")
        dump.writeText(result.html, Charsets.UTF_8)
        println("[SUBSET html] ${result.html.take(600)}")

        assertTrue("标题应识别为 h1", result.html.contains("<h1>Chapter 1 Title</h1>"))
        assertTrue("二级标题应识别为 h2", result.html.contains("<h2>Section intro 1</h2>"))

        assertTrue("粗体保留", result.html.contains("<b>bold</b>"))
        assertTrue("斜体保留", result.html.contains("<i> italic </i>"))
        assertTrue("下划线保留", result.html.contains("<u>underline</u>"))
        assertTrue("删除线保留", result.html.contains("<s> strike</s>"))

        assertTrue("有序列表应编号 1", result.html.contains("data-num=\"1\""))
        assertTrue("有序列表应编号 2", result.html.contains("data-num=\"2\""))
        assertTrue("有序列表底层靠 numbering.xml", result.html.contains("<ol>"))
        assertTrue("无序列表应识别", result.html.contains("<ul>"))
        assertTrue("二级列表应有缩进 class", result.html.contains("readit-lvl1"))

        assertTrue("表格应渲染", result.html.contains("<table"))
        assertTrue("表格内容不丢", result.html.contains("Header A") && result.html.contains("Cell 1"))

        assertEquals("应有 2 个标题", 2, result.stats.headings)
        assertEquals("应有 5 个列表项", 5, result.stats.listItems)
        assertEquals("应有 1 张表格", 1, result.stats.tables)
        assertEquals("图片计数应保留", 1, result.stats.images)

        assertTrue("SUBSET 档位不带图片应给出省略提示",
            result.warnings.any { it.contains("图片已省略") })
        assertTrue("文本框应降级提示",
            result.warnings.any { it.contains("文本框") })
        assertTrue("必须包含 Unsupported 块之后的正文（降级不中断）",
            result.html.contains("Below the unsupported block"))
        assertTrue("文本框内部文字不应出现在输出里（明确跳过）",
            !result.html.contains("Text inside box"))
    }

    @Test
    fun `broken docx does not crash and degrades`() {
        val file = fixture("readit_broken.docx")
        val result = try {
            DocxParser(mode = DocxMode.SUBSET).parse(file)
        } catch (e: Exception) {
            throw AssertionError("畸形 DOCX 导致崩溃: ${e.message}", e)
        }
        println("[BROKEN warnings] ${result.warnings}")
        println("[BROKEN stats] ${result.stats}")

        assertTrue("被截断的 XML 仍应产出部分内容", result.stats.paragraphs > 0)
        assertTrue("缺 numbering.xml 应提示列表降级",
            result.warnings.any { it.contains("numbering") })
        assertTrue("缺 numbering.xml 时列表退化为项目符号", result.html.contains("<ul>"))
    }

    @Test
    fun `text only mode extracts plain text`() {
        val parser = DocxParser(mode = DocxMode.TEXT_ONLY)
        val text = parser.parseTextOnly(fixture("readit_sample.docx"))
        assertTrue(text.contains("Chapter 1 Title"))
        assertTrue(text.contains("Ordered item one"))
        assertTrue("纯文本模式不应包含 HTML 标签", !text.contains("<p>"))

        val html = parser.parse(fixture("readit_sample.docx")).html
        assertTrue("TEXT_ONLY 应输出 <pre>", html.contains("<pre>"))
    }

    @Test
    fun `image is extracted in subset with image mode`() {
        val outDir = File(tmp, "media").apply { mkdirs() }
        val result = DocxParser(mode = DocxMode.SUBSET_WITH_IMAGE, mediaOutDir = outDir)
            .parse(fixture("readit_sample.docx"))
        assertEquals("图片应落盘", 1, result.mediaFiles.size)
        assertEquals("应引用 media 协议路径", true, result.html.contains("readit-media://word/media/image1.png"))
    }

    // ------------------------------------------------- 真实 OOXML 负样本

    /**
     * 程序化造一个最小 docx。
     *
     * 现有的 `readit_sample.docx` 夹具只覆盖「理想形态」（Heading1 styleId、单层表格、
     * 无 w:val、rels 里没有 hyperlink），而下面每条回归针对的都是**真实 Word/LibreOffice
     * 会产出、但夹具里没有**的形态。用二进制夹具补这些形态不可读，所以这里直接拼 zip。
     */
    private fun buildDocx(
        name: String,
        document: String,
        styles: String? = null,
        extraParts: Map<String, String> = emptyMap()
    ): File {
        val f = File(tmp, name)
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(path: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(path))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("word/document.xml", document)
            styles?.let { put("word/styles.xml", it) }
            extraParts.forEach { (k, v) -> put(k, v) }
        }
        return f
    }

    // 必须同时声明 `r` 前缀：Android 的 Xml.newPullParser() 默认开启命名空间处理，
    // 未绑定的前缀会让 safeNext 抛异常并**静默截断**整个文档（body 变空）。
    // 真实 docx 都有 xmlns:r，所以测试文档也照做。
    private val documentWrapper: (String) -> String = { body ->
        """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body>$body</w:body></w:document>"""
    }

    private val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="xml" ContentType="application/xml"/></Types>"""

    private val ROOT_RELS =
        """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

    /**
     * 嵌套表格**不能**吞掉外层内容。
     *
     * 原实现只有一个 `inTable` 布尔：内层 `<w:tbl>` 的 `rows.clear()` 抹掉外层已收集的行，
     * 内层 `<w:tc>` 的 `cellText.setLength(0)` 抹掉外层单元格文字，内层 `</w:tbl>` 置
     * `inTable=false` 后外层闭合标签全部走空分支 —— 外层表格**整块消失**且无任何提示。
     */
    @Test
    fun `nested table keeps the outer table content`() {
        val doc = documentWrapper(
            """
<w:tbl>
  <w:tr><w:tc><w:p><w:r><w:t>OUTER-A</w:t></w:r></w:p></w:tc>
        <w:tc><w:p><w:r><w:t>OUTER-B</w:t></w:r></w:p>
          <w:tbl><w:tr><w:tc><w:p><w:r><w:t>INNER</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
        </w:tc></w:tr>
  <w:tr><w:tc><w:p><w:r><w:t>OUTER-C</w:t></w:r></w:p></w:tc></w:tr>
</w:tbl>"""
        )
        val r = DocxParser(mode = DocxMode.SUBSET).parse(buildDocx("nested.docx", doc))
        println("[NESTED html] ${r.html}")
        assertTrue("外层第一格不能丢", r.html.contains("OUTER-A"))
        assertTrue("外层第二格不能丢", r.html.contains("OUTER-B"))
        assertTrue("外层第三行不能丢", r.html.contains("OUTER-C"))
        assertTrue("内层内容应保留（并入外层单元格）", r.html.contains("INNER"))
        assertEquals("只应渲染一张表", 1, r.stats.tables)
        assertTrue("嵌套表格必须如实告知", r.warnings.any { it.contains("嵌套表格") })
    }

    /**
     * 中文 Word 的标题样式存成数字 id（`w:val="1"`），且层级只写在 `styles.xml` 的
     * `w:outlineLvl` 里 —— 原实现只对 `pStyle` 字符串做 `startsWith("Heading")`，
     * 这类文档**一个标题都识别不出** → 目录全空且无 warning。
     */
    @Test
    fun `headings come from styles xml outlineLvl`() {
        val styles = """<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:styleId="1"><w:pPr><w:outlineLvl w:val="0"/></w:pPr></w:style>
  <w:style w:styleId="2"><w:pPr><w:outlineLvl w:val="1"/></w:pPr></w:style>
</w:styles>"""
        val doc = documentWrapper(
            """
<w:p><w:pPr><w:pStyle w:val="1"/></w:pPr><w:r><w:t>中文一级标题</w:t></w:r></w:p>
<w:p><w:pPr><w:pStyle w:val="2"/></w:pPr><w:r><w:t>中文二级标题</w:t></w:r></w:p>"""
        )
        val r = DocxParser(mode = DocxMode.SUBSET).parse(buildDocx("cnhead.docx", doc, styles))
        println("[CN HEADINGS] ${r.html}")
        assertTrue("一级标题应识别", r.html.contains("<h1>中文一级标题</h1>"))
        assertTrue("二级标题应识别", r.html.contains("<h2>中文二级标题</h2>"))
        assertEquals(2, r.stats.headings)
    }

    /** 段落级 `w:outlineLvl`（LibreOffice 的写法）也要认 */
    @Test
    fun `paragraph level outlineLvl is honoured`() {
        val doc = documentWrapper(
            """<w:p><w:pPr><w:outlineLvl w:val="1"/></w:pPr><w:r><w:t>直接标的二级</w:t></w:r></w:p>"""
        )
        val r = DocxParser(mode = DocxMode.SUBSET).parse(buildDocx("outlinelvl.docx", doc))
        assertTrue(r.html.contains("<h2>直接标的二级</h2>"))
    }

    /**
     * `w:val="0"` 是**关闭**。Word 在「取消继承加粗/下划线」时就是这么写的，
     * 只看元素是否出现会把它一律渲染成加粗/下划线。
     */
    @Test
    fun `w val zero turns formatting off`() {
        val doc = documentWrapper(
            """
<w:p><w:r><w:rPr><w:b w:val="0"/></w:rPr><w:t>NOT-BOLD</w:t></w:r>
       <w:r><w:rPr><w:b/></w:rPr><w:t>BOLD</w:t></w:r>
       <w:r><w:rPr><w:u w:val="none"/></w:rPr><w:t>NO-UNDERLINE</w:t></w:r></w:p>"""
        )
        val r = DocxParser(mode = DocxMode.SUBSET).parse(buildDocx("valoff.docx", doc))
        println("[VAL OFF] ${r.html}")
        assertTrue("w:val=0 不应渲染成 <b>", !r.html.contains("<b>NOT-BOLD</b>"))
        assertTrue("缺 w:val 的 <w:b/> 应仍然加粗", r.html.contains("<b>BOLD</b>"))
        assertTrue("w:val=none 不应渲染成 <u>", !r.html.contains("<u>NO-UNDERLINE</u>"))
    }

    /** `w:sz`（半磅）不还原的话，DOCX 自带的字号层级被拍平成一个大小 */
    @Test
    fun `w sz is preserved as inline font size`() {
        val doc = documentWrapper(
            """<w:p><w:r><w:rPr><w:sz w:val="48"/></w:rPr><w:t>BIG</w:t></w:r>
               <w:r><w:rPr><w:sz w:val="16"/></w:rPr><w:t>SMALL</w:t></w:r></w:p>"""
        )
        val r = DocxParser(mode = DocxMode.SUBSET).parse(buildDocx("sz.docx", doc))
        println("[SZ] ${r.html}")
        assertTrue("24pt 应还原", r.html.contains("font-size:24pt"))
        assertTrue("8pt 应还原", r.html.contains("font-size:8pt"))
    }

    /**
     * 页眉/页脚/脚注/批注我**根本没读**，KDoc 承诺过要 warning，代码里一条都没有。
     * 静默缺一大块内容、而提示框还在说「只简化了表格和图片」，比不提示更糟。
     */
    @Test
    fun `missing parts are reported honestly`() {
        val doc = documentWrapper("""<w:p><w:r><w:t>BODY</w:t></w:r></w:p>""")
        val r = DocxParser(mode = DocxMode.SUBSET).parse(
            buildDocx(
                "parts.docx", doc,
                extraParts = mapOf(
                    "word/header1.xml" to "<w:hdr/>",
                    "word/footer1.xml" to "<w:ftr/>",
                    "word/footnotes.xml" to "<w:footnotes/>",
                    "word/comments.xml" to "<w:comments/>"
                )
            )
        )
        println("[PARTS warnings] ${r.warnings}")
        assertTrue(r.warnings.any { it.contains("页眉") })
        assertTrue(r.warnings.any { it.contains("页脚") })
        assertTrue(r.warnings.any { it.contains("脚注") })
        assertTrue(r.warnings.any { it.contains("批注") })
    }

    /** rels 的 Target 不可信：`javascript:` 外链必须被丢掉 */
    @Test
    fun `javascript href is dropped`() {
        val doc = documentWrapper(
            """
<w:p><w:hyperlink r:id="rIdX"><w:r><w:t>CLICK</w:t></w:r></w:hyperlink></w:p>"""
        )
        // 手工塞一个 rels（buildDocx 只写固定 rels，这里改走临时包）
        val f = File(tmp, "jsurl.docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(p: String, b: String) {
                z.putNextEntry(java.util.zip.ZipEntry(p)); z.write(b.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", ROOT_RELS)
            put("word/document.xml", doc)
            put(
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdX" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="javascript:alert(1)"/>
</Relationships>"""
            )
        }
        val r = DocxParser(mode = DocxMode.SUBSET).parse(f)
        println("[JS URL] ${r.html}")
        assertTrue("javascript: 外链必须被丢掉", !r.html.contains("javascript:"))
        assertTrue("文本本身要留下", r.html.contains("CLICK"))
    }
}

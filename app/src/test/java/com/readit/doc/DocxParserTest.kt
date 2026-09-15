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
}

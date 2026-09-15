package com.readit.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P2：EPUB 文本抽取降级路径回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpubTextExtractorTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_epub_text_test").apply { mkdirs() }
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
    fun `extract keeps chapter order and toc offsets`() {
        val file = fixture("readit_sample.epub")
        val book = EpubParser.parse(file)
        val result = EpubTextExtractor().extract(file, book)

        assertTrue(result.text.contains("这是第一章的第一段。"))
        assertTrue(result.text.contains("这是第二章的第三段。"))
        assertTrue(result.text.contains("这是第三章的唯一一段。"))
        assertTrue(result.text.contains("起因段落。"))

        assertEquals(3, result.spineOffsets.size)
        assertTrue(result.spineOffsets[0] >= 0)
        assertTrue(result.spineOffsets[1] > result.spineOffsets[0])
        assertTrue(result.spineOffsets[2] > result.spineOffsets[1])

        assertEquals(book.toc.size, result.tocOffsets.size)
        // 目录序：第一章/第一节/第二节 → chap01；第二章 → chap02；第三章 → chap03
        assertEquals(result.spineOffsets[0], result.tocOffsets[0])
        assertEquals(result.spineOffsets[0], result.tocOffsets[1])
        assertEquals(result.spineOffsets[0], result.tocOffsets[2])
        assertEquals(result.spineOffsets[1], result.tocOffsets[3])
        assertEquals(result.spineOffsets[2], result.tocOffsets[4])
    }

    @Test
    fun `paragraph breaks survive extraction`() {
        val file = fixture("readit_sample.epub")
        val book = EpubParser.parse(file)
        val text = EpubTextExtractor().extract(file, book).text

        val lines = text.lines().filter { it.isNotBlank() }
        assertTrue("段落应各自成行: $lines", lines.size >= 8)
        assertTrue(lines.any { it == "这是第一章的第一段。" })
    }

    @Test
    fun `non well formed xhtml falls back to regex stripping`() {
        val file = fixture("readit_broken.epub")
        val book = EpubParser.parse(file)
        val result = EpubTextExtractor().extract(file, book)

        assertTrue(result.warnings.any { it.contains("regex fallback") })
        assertTrue(result.text.contains("坏文档"))
        assertTrue(result.text.contains("第二段内容"))
        assertFalse("不应残留标签", result.text.contains("<p>"))
        assertFalse("不应残留实体", result.text.contains("&nbsp;"))
        assertEquals(2, result.spineOffsets.size)
    }

    @Test
    fun `charset is detected from xml declaration`() {
        val extractor = EpubTextExtractor()
        val bytes = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html/>".toByteArray()
        assertEquals(Charsets.UTF_8, extractor.charsetOf(bytes))
        assertEquals(Charsets.UTF_8, extractor.charsetOf("<html><meta charset=\"utf-8\"/></html>".toByteArray()))
    }
}

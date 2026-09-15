package com.readit.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：DOCX 子集 HTML 的标题锚点注入、目录抽取与媒体路径相对化（F04 / F10）。
 *
 * 这几步原本埋在 [DocxParser] 里（依赖 android.util.Xml，JVM 跑不动），
 * 抽到 [DocxHtmlToc] 就是为了能在纯 JVM 上把边界钉住。
 */
class DocxHtmlTocTest {

    @Test
    fun `headings get sequential anchors in document order`() {
        val html = "<html><body><h1>Chapter 1</h1><p>text</p><h2>Sec A</h2><h3>Deep</h3></body></html>"
        val r = DocxHtmlToc.rewrite(html)

        assertEquals(listOf("Chapter 1", "Sec A", "Deep"), r.titles)
        assertEquals(listOf(0, 1, 2), r.depths)
        assertEquals(listOf(0, 1, 2), r.ids)
        assertTrue(r.html.contains("""<h1 id="readit-h-0">Chapter 1</h1>"""))
        assertTrue(r.html.contains("""<h2 id="readit-h-1">Sec A</h2>"""))
        assertTrue(r.html.contains("""<h3 id="readit-h-2">Deep</h3>"""))
    }

    @Test
    fun `html entities and inline tags are stripped from titles`() {
        val r = DocxHtmlToc.rewrite("<h1>Tom &amp; <b>Jerry</b></h1><h2>A&nbsp;B</h2>")
        assertEquals("Tom & Jerry", r.titles[0])
        assertEquals("A B", r.titles[1])
        // 原文（含标签）必须留在 HTML 里，只把纯文本抽给目录
        assertTrue(r.html.contains("<b>Jerry</b>"))
    }

    @Test
    fun `case insensitive tag match but lowercase output`() {
        val r = DocxHtmlToc.rewrite("<H1>Upper</H1>")
        assertEquals(listOf("Upper"), r.titles)
        assertTrue(r.html.contains("""<h1 id="readit-h-0">Upper</h1>"""))
    }

    @Test
    fun `non heading markup untouched and no headings yields empty toc`() {
        val plain = "<html><body><p>no headings here</p><table><tr><td>x</td></tr></table></body></html>"
        val r = DocxHtmlToc.rewrite(plain)
        assertTrue(r.isEmpty)
        assertEquals(plain, r.html)
    }

    @Test
    fun `h7 is not a heading anchor target`() {
        val r = DocxHtmlToc.rewrite("<h7>invalid</h7>")
        assertTrue(r.isEmpty)
    }

    @Test
    fun `media scheme is rewritten to bare filename`() {
        val html = """<p><img src="readit-media://word/media/image1.png" alt="image"/></p>""" +
            """<p><img src="readit-media://word/media/sub/pic2.jpeg" alt="image"/></p>"""
        val out = DocxHtmlToc.relativizeMedia(html)
        assertTrue(out.contains("""src="image1.png""""))
        assertTrue(out.contains("""src="pic2.jpeg""""))
        assertFalse(out.contains("readit-media://"))
    }

    @Test
    fun `scroll js targets the anchor id`() {
        val js = DocxHtmlToc.scrollJs(3)
        assertTrue(js.startsWith("javascript:"))
        assertTrue(js.contains("readit-h-3"))
    }
}

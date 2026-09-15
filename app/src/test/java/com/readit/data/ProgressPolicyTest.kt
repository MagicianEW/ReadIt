package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F09 回归：onPause 写进度的判定。
 *
 * 守的是一条**静默数据丢失**：打开 PDF / DOCX 时 `mode` 还是 TXT、画布却是空的，
 * 若按 TXT 分支无条件取 `canvas.currentOffset()`（=0）覆盖写回，用户上次读到的
 * 第 10 页进度就被清零，而且日志里只会看到一行正常的 `progress saved`。
 */
class ProgressPolicyTest {

    private fun input(
        mode: ReadMode,
        canvasHasDocument: Boolean = false,
        canvasOffset: Int = 0,
        canvasChapterIndex: Int = 0,
        epubCfi: String? = null,
        epubSpineIndex: Int = 0,
        pdfPage: Int = -1,
        pdfPageCount: Int = 0,
        docxHeading: Int = 0
    ) = ProgressPolicy.Input(
        mode = mode,
        now = 1_700_000_000_000L,
        canvasHasDocument = canvasHasDocument,
        canvasOffset = canvasOffset,
        canvasChapterIndex = canvasChapterIndex,
        epubCfi = epubCfi,
        epubSpineIndex = epubSpineIndex,
        pdfPage = pdfPage,
        pdfPageCount = pdfPageCount,
        docxHeading = docxHeading
    )

    // ---------------------------------------------------------------- 核心回归

    @Test
    fun `async pdf open must not zero existing progress`() {
        // PDF 还在做扫描检测：mode 仍是 TXT，画布空
        val d = ProgressPolicy.decide(input(ReadMode.TXT, canvasHasDocument = false))
        assertTrue("画布为空时必须跳过写入，否则把上次进度清零", d is ProgressPolicy.Decision.Skip)
    }

    @Test
    fun `async docx open must not zero existing progress`() {
        val d = ProgressPolicy.decide(input(ReadMode.TXT, canvasHasDocument = false, canvasOffset = 0))
        assertTrue(d is ProgressPolicy.Decision.Skip)
    }

    @Test
    fun `txt with document saves real offset`() {
        val d = ProgressPolicy.decide(
            input(ReadMode.TXT, canvasHasDocument = true, canvasOffset = 4321, canvasChapterIndex = 3)
        )
        assertTrue(d is ProgressPolicy.Decision.Save)
        val pos = (d as ProgressPolicy.Decision.Save).pos
        assertEquals(4321, pos.charOffset)
        assertEquals(3, pos.chapterIndex)
    }

    // ---------------------------------------------------------------- 各模式

    @Test
    fun `epub full skips until first cfi arrives`() {
        val blank = ProgressPolicy.decide(input(ReadMode.EPUB_FULL, epubCfi = null))
        val empty = ProgressPolicy.decide(input(ReadMode.EPUB_FULL, epubCfi = ""))
        assertTrue(blank is ProgressPolicy.Decision.Skip)
        assertTrue(empty is ProgressPolicy.Decision.Skip)
    }

    @Test
    fun `epub full saves cfi and spine index`() {
        val d = ProgressPolicy.decide(
            input(ReadMode.EPUB_FULL, epubCfi = "epubcfi(/6/14!/4/2/2)", epubSpineIndex = 6)
        )
        assertTrue(d is ProgressPolicy.Decision.Save)
        val pos = (d as ProgressPolicy.Decision.Save).pos
        assertEquals("epubcfi(/6/14!/4/2/2)", pos.cfi)
        assertEquals(6, pos.chapterIndex)
    }

    @Test
    fun `pdf render skips when document not open`() {
        val noDoc = ProgressPolicy.decide(input(ReadMode.PDF_RENDER, pdfPage = 0, pdfPageCount = 0))
        val badPage = ProgressPolicy.decide(input(ReadMode.PDF_RENDER, pdfPage = -1, pdfPageCount = 20))
        assertTrue(noDoc is ProgressPolicy.Decision.Skip)
        assertTrue(badPage is ProgressPolicy.Decision.Skip)
    }

    @Test
    fun `pdf render saves page and pageIndex`() {
        val d = ProgressPolicy.decide(input(ReadMode.PDF_RENDER, pdfPage = 9, pdfPageCount = 100))
        assertTrue(d is ProgressPolicy.Decision.Save)
        val pos = (d as ProgressPolicy.Decision.Save).pos
        assertEquals(9, pos.pageIndex)
        assertEquals(9, pos.chapterIndex)
    }

    @Test
    fun `docx html saves heading index unconditionally`() {
        // HTML 路径的位置就是标题序号，调用方进入该模式时已用存档初始化，写回幂等
        val d = ProgressPolicy.decide(input(ReadMode.DOCX_HTML, docxHeading = 4))
        assertTrue(d is ProgressPolicy.Decision.Save)
        assertEquals(4, (d as ProgressPolicy.Decision.Save).pos.chapterIndex)
    }

    @Test
    fun `text fallback modes skip while canvas empty`() {
        // EPUB/DOCX/PDF 的文本降级档都落在画布上：画布空即文档未就绪
        listOf(ReadMode.EPUB_TEXT, ReadMode.DOCX_TEXT, ReadMode.PDF_TEXT).forEach { m ->
            val d = ProgressPolicy.decide(input(m, canvasHasDocument = false))
            assertTrue("$m 画布为空时应跳过", d is ProgressPolicy.Decision.Skip)
        }
    }

    // ---------------------------------------------------------------- 默认值退化

    @Test
    fun `default input degrades to skip for every canvas mode`() {
        // 漏传字段（默认值 = 没有有效位置）必须退化成 Skip，宁可少写一次也不能写错
        listOf(ReadMode.TXT, ReadMode.EPUB_TEXT, ReadMode.DOCX_TEXT, ReadMode.PDF_TEXT).forEach { m ->
            assertTrue(
                "漏传 canvasHasDocument 的 $m 不得写入",
                ProgressPolicy.decide(ProgressPolicy.Input(mode = m, now = 1L)) is ProgressPolicy.Decision.Skip
            )
        }
    }

    @Test
    fun `default input degrades to skip for epub and pdf`() {
        assertTrue(ProgressPolicy.decide(ProgressPolicy.Input(ReadMode.EPUB_FULL, 1L)) is ProgressPolicy.Decision.Skip)
        assertTrue(ProgressPolicy.decide(ProgressPolicy.Input(ReadMode.PDF_RENDER, 1L)) is ProgressPolicy.Decision.Skip)
    }
}

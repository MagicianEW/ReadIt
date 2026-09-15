package com.readit.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0-C 门禁 C1：PdfBox-Android 文本提取能力基准。
 *
 * 说明：真机 A33 级 + 1GB 的基准数据需在 bootloader 真机上补齐；
 * 本用例给出「功能正确性 + 本机 JVM 相对基准」，用于判断：
 *  - 单页提取耗时是否可接受（决定是否需要按页流式 + 预加载策略）
 *  - 扫尾：无文本层 PDF 是否能被 3 页采样识别（F08 前置验证）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfTextBenchmarkTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_pdf_test").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("fixtures/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    private fun gcAndUsedHeapKb(): Long {
        repeat(2) { System.gc() }
        Thread.sleep(80)
        val r = Runtime.getRuntime()
        return (r.totalMemory() - r.freeMemory()) / 1024
    }

    @Test
    fun `per page extraction correctness and memory profile`() {
        val file = fixture("readit_text_20p.pdf")
        val before = gcAndUsedHeapKb()

        val t0 = System.nanoTime()
        val extractor = PdfTextExtractor().open(file)
        val openMs = (System.nanoTime() - t0) / 1_000_000
        val afterOpen = gcAndUsedHeapKb()

        assertEquals("页数应为 20", 20, extractor.pageCount)

        val first = extractor.extractPage(0)
        println("[PDF] page0 = ${first.take(60).replace("\n", " ")}")

        assertTrue("应抽取到正文", first.contains("Page 1 line 1"))
        assertTrue("每页应包含 45 行文本", first.lines().count { it.contains("quick brown fox") } == 45)

        val last = extractor.extractPage(19)
        assertTrue("末页应抽取到正文", last.contains("Page 20 line 45"))

        // 全文档按页流式提取
        val heapBeforeLoop = gcAndUsedHeapKb()
        val loopStart = System.nanoTime()
        var chars = 0
        var pages = 0
        extractor.forEachPage { _, text ->
            chars += text.length
            pages++
        }
        val loopMs = (System.nanoTime() - loopStart) / 1_000_000
        val heapAfterLoop = gcAndUsedHeapKb()

        extractor.close()

        // 全量一次性提取（对照）
        val heapBeforeAll = gcAndUsedHeapKb()
        val allStart = System.nanoTime()
        val allText = PdfTextExtractor.extractAll(file)
        val allMs = (System.nanoTime() - allStart) / 1_000_000
        val heapAfterAll = gcAndUsedHeapKb()

        println("------------------------------------------------------------")
        println("[PDF BENCHMARK] file=${file.name} size=${file.length()}B pages=20")
        println("  open            : ${openMs} ms, heap +${afterOpen - before} KB")
        println("  per-page stream : ${loopMs} ms total, ${loopMs / 20} ms/page, pages=$pages, chars=$chars")
        println("  per-page heap   : +${heapAfterLoop - heapBeforeLoop} KB")
        println("  extractAll      : ${allMs} ms, chars=${allText.length}, heap +${heapAfterAll - heapBeforeAll} KB")
        println("------------------------------------------------------------")

        assertTrue("流式单页平均耗时应 < 200ms/页（本机基准）", loopMs / 20 < 200)
        assertEquals("全文档字符数应一致（抽样比对）", true, allText.contains("Page 10 line 20"))
    }

    @Test
    fun `scanned pdf detection with 3 page sampling`() {
        val textBased = PdfTextExtractor().open(fixture("readit_text_20p.pdf"))
        val coverageText = textBased.textCoverage()
        println("[PDF] text pdf coverage = $coverageText")
        assertTrue("文本版应判定为有文本层", textBased.isTextBased())
        textBased.close()

        val scanned = PdfTextExtractor().open(fixture("readit_notext_10p.pdf"))
        val coverageNone = scanned.textCoverage()
        println("[PDF] no-text pdf coverage = $coverageNone")
        assertEquals("无文本层 PDF coverage 应为 0", 0f, coverageNone)
        assertTrue("无文本层 PDF 不应判定为文本版", !scanned.isTextBased())
        scanned.close()
    }
}

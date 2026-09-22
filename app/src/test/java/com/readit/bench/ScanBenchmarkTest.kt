package com.readit.bench

import com.readit.pdf.PdfTextExtractor
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P0 验收 F08：扫描版检测基准（规范要求 检出率 ≥ 95%、误判率 ≤ 5%）。
 *
 * 语料由 tools/gen_bench_corpus.py 生成，ground truth 在 truth.tsv（`文件名 \\t 0|1`）：
 *   - 正样本 30：每页铺满整页大栅格图、无文本层
 *   - 负样本 35：25 纯文本 / 5 带小图标的文本 / 5 纯空白页
 *     （空白页无文本也无图，按判定口径属「非扫描」，用来压误判率）
 *
 * 这是第一次把 detectScan 跑在**真正的扫描件**上：此前 `readit_notext_10p.pdf`
 * 只是空白页，压根进不了「低文本 + 整页大图」那条分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScanBenchmarkTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_scan_bench").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("bench/pdf/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    @Test
    fun `scan detection rate and false positive rate on corpus`() {
        val tsv = requireNotNull(javaClass.classLoader.getResourceAsStream("bench/pdf/truth.tsv")) {
            "truth.tsv missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

        val rows = tsv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split("\t") }
            .map { it[0] to (it.getOrNull(1)?.trim() == "1") }
            .toList()

        var tp = 0; var fn = 0; var tn = 0; var fp = 0
        val wrong = ArrayList<String>()

        for ((name, truth) in rows) {
            val f = fixture(name)
            val verdict = PdfTextExtractor().use { ex ->
                ex.open(f)
                ex.detectScan()
            }
            when {
                truth && verdict.scanned -> tp++
                truth && !verdict.scanned -> { fn++; wrong.add("漏检 $name -> ${verdict.describe()}") }
                !truth && verdict.scanned -> { fp++; wrong.add("误判 $name -> ${verdict.describe()}") }
                else -> tn++
            }
        }

        val detectRate = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
        val fpRate = if (tn + fp == 0) 0f else fp.toFloat() / (tn + fp)

        println("------------------------------------------------------------")
        println("[F08 扫描版基准] 语料 ${rows.size}（正样本 ${tp + fn} / 负样本 ${tn + fp}）")
        println("  检出率 : ${"%.1f".format(detectRate * 100)}%  (tp=$tp fn=$fn)")
        println("  误判率 : ${"%.1f".format(fpRate * 100)}%  (fp=$fp tn=$tn)")
        wrong.take(10).forEach { println("  $it") }
        println("------------------------------------------------------------")

        assertTrue("扫描版检出率应 ≥ 95%（实测 ${"%.1f".format(detectRate * 100)}%）", detectRate >= 0.95f)
        assertTrue("非扫描版误判率应 ≤ 5%（实测 ${"%.1f".format(fpRate * 100)}%）", fpRate <= 0.05f)
    }

    private inline fun <T> PdfTextExtractor.use(block: (PdfTextExtractor) -> T): T {
        return try {
            block(this)
        } finally {
            close()
        }
    }
}

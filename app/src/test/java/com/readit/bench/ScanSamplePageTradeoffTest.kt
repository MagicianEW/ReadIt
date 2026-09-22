package com.readit.bench

import com.readit.pdf.PdfTextExtractor
import com.readit.pdf.ScanThresholds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 「采样页数」这笔账：省了多少时间 vs 赔了多少准确率。
 *
 * 背景（§9.4）：PDF 首屏的 80~90% 花在串行的扫描检测上，而检测成本几乎全在
 * **PdfBox 文本抽取的一次性初始化（实测 KY-01L ≈2.0s）** 加上 **≈1.26s/页**。
 * 真机实测 `real_text.pdf`（20 页，冷启动）：
 *
 * | 采样页数 | detectScan 耗时 | PDF 首屏 |
 * |---|---|---|
 * | 3（原默认） | 5793ms | 6676ms |
 * | 1 | 3267ms | 4169ms |
 *
 * 所以「采样 3→1」确实省掉约 **2.5 秒**。但省时间不是白来的，代价必须被量出来，
 * 而不是靠同质语料里「1 页和 3 页都是 100%」蒙过去 —— 见下面两组语料的对比。
 *
 * **两组语料的区别是本用例存在的全部理由：**
 *
 * - `bench/pdf`（70 个）**每页形态相同**：扫描件每页都是扫描图、bgtext 每页都铺底图。
 *   既然每页都一样，采样 1 页和 5 页必然同结果 —— 这里的「100% / 0%」是**构造出来的**，
 *   不能作为「少采样不影响准确率」的证据。
 * - `bench/pdf_hetero`（10 个）**页与页不同**：文本文档中间插一整页图版、扫描书带文字扉页。
 *   只有这种两栖形态才能回答「采样 3→1 会损失什么」。
 *
 * 逻辑上也不难想通：一页「零文本 + 整页大图」的**图版**，与一页**扫描页**在单页内容上完全同构，
 * 单页采样**无法区分**二者；要区分只能去看别的页 —— 也就是说信息量不够，
 * 这不是实现瑕疵，是采样页数的硬下限。
 *
 * 断言口径：两个方向分开看。**漏检（把扫描版当文本版）比误判更糟**（用户会看到空白/乱码页），
 * 所以 1 页采样也守 检出率 ≥95%；误判率**只打印不断言** —— 上限该定多少是产品决策，
 * 由 BOSS 拍板（`ScanThresholds.samplePages` 对用户开放，设置页可改回 3）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ScanSamplePageTradeoffTest {

    private data class Score(
        val total: Int, val positives: Int, val negatives: Int,
        val tp: Int, val fn: Int, val tn: Int, val fp: Int,
        val wrong: List<String>
    ) {
        val detectRate: Float get() = if (positives == 0) 0f else tp.toFloat() / positives
        val fpRate: Float get() = if (negatives == 0) 0f else fp.toFloat() / negatives
    }

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_scan_tradeoff").apply { mkdirs() }
    }

    private fun fixture(dir: String, name: String): File {
        val f = File(tmp, name)
        if (!f.exists()) {
            javaClass.classLoader.getResourceAsStream("$dir/$name").use { src ->
                requireNotNull(src) { "fixture missing: $dir/$name" }
                f.outputStream().use { dst -> src.copyTo(dst) }
            }
        }
        return f
    }

    private fun loadTruth(dir: String): List<Pair<String, Boolean>> =
        requireNotNull(javaClass.classLoader.getResourceAsStream("$dir/truth.tsv")) {
            "$dir/truth.tsv missing"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.split("\t") }
            .map { it[0] to (it.getOrNull(1)?.trim() == "1") }
            .toList()

    private fun evaluate(dir: String, samplePages: Int): Score {
        var tp = 0; var fn = 0; var tn = 0; var fp = 0
        val wrong = ArrayList<String>()
        val rows = loadTruth(dir)
        for ((name, truth) in rows) {
            val verdict = PdfTextExtractor().use { ex ->
                ex.open(fixture(dir, name))
                ex.detectScan(ScanThresholds(samplePages = samplePages))
            }
            when {
                truth && verdict.scanned -> tp++
                truth && !verdict.scanned -> { fn++; wrong.add("漏检 $name :: ${verdict.describe()}") }
                !truth && verdict.scanned -> { fp++; wrong.add("误判 $name :: ${verdict.describe()}") }
                else -> tn++
            }
        }
        return Score(rows.size, tp + fn, tn + fp, tp, fn, tn, fp, wrong)
    }

    private fun report(dir: String, s: Score, samplePages: Int, verbose: Boolean) {
        println("  采样 $samplePages 页 -> 检出率 ${pct(s.detectRate)} (${s.tp}/${s.positives})"
                + "  误判率 ${pct(s.fpRate)} (${s.fp}/${s.negatives})")
        if (verbose) s.wrong.take(10).forEach { println("        $it") }
    }

    private fun pct(v: Float) = "%.1f%%".format(v * 100)

    private inline fun <T> PdfTextExtractor.use(block: (PdfTextExtractor) -> T): T =
        try { block(this) } finally { close() }

    @Test
    fun `sampling cost by page count on homogeneous and heterogeneous corpora`() {
        println("==================== F08 采样页数权衡 ====================")
        val corpora = listOf("bench/pdf" to "同质语料（每页形态相同）", "bench/pdf_hetero" to "异质语料（页与页不同）")

        val scores = HashMap<Pair<String, Int>, Score>()
        for ((dir, label) in corpora) {
            println("$label  $dir")
            for (n in intArrayOf(1, 3)) {
                val s = evaluate(dir, n)
                scores[dir to n] = s
                report(dir, s, n, verbose = true)
            }
        }
        println("=======================================================")

        // —— 断言 ——
        for ((dir, label) in corpora) {
            val three = scores.getValue(dir to 3)
            assertTrue(
                "$label($dir) 采样 3 页检出率应 ≥95%（实测 ${pct(three.detectRate)}）",
                three.detectRate >= 0.95f
            )
            assertTrue(
                "$label($dir) 采样 3 页误判率应 ≤5%（实测 ${pct(three.fpRate)}）",
                three.fpRate <= 0.05f
            )
            val one = scores.getValue(dir to 1)
            assertTrue(
                "$label($dir) 采样 1 页检出率应 ≥95%（实测 ${pct(one.detectRate)}）—— 漏检比误判更糟，不可退让",
                one.detectRate >= 0.95f
            )
        }
    }

    /**
     * 早退（production 路径）的两条不变量：
     *
     * 1. **早退只在「非扫描」已经成立时发生** —— 因为 `scanned = lowText && enoughImages`，
     *    而早退的条件正是把 `lowText` 证伪。所以早退不会把任何一份文档判成扫描版。
     * 2. **判成扫描版时一定没早退**（`lowText` 为真说明字符数上不去），
     *    于是扫描版仍按完整采样页数判定，识别力不打折。
     *
     * 顺带钉住「图版」这一类：`f08_plate_*` 的第 0 页是正文（有文本），早退立刻命中，
     * 于是不会走到那一页整页图版上去 —— 这正是「采样 3→1」会踩、而早退不会踩的坑。
     */
    @Test
    fun `early exit only fires when non-scanned is already proven`() {
        var early = 0
        var full = 0
        for (dir in listOf("bench/pdf", "bench/pdf_hetero")) {
            for ((name, truth) in loadTruth(dir)) {
                val v = PdfTextExtractor().use { ex ->
                    ex.open(fixture(dir, name))
                    ex.detectScan(ScanThresholds(samplePages = 3))
                }
                if (v.earlyExit) early++ else full++

                if (v.earlyExit) {
                    assertTrue("$name 早退却判成扫描版 :: ${v.describe()}", !v.scanned)
                    assertTrue("$name 早退了但 sampledPages 没减少 :: ${v.describe()}", v.sampledPages < 3)
                }
                if (v.scanned) {
                    assertEquals("$name 判为扫描版时不应早退 :: ${v.describe()}", 3, v.sampledPages)
                }
                assertEquals("$name 结论与真值不符 :: ${v.describe()}", truth, v.scanned)
            }
        }
        println("早退 $early 个 / 抽满 $full 个")
        assertTrue("文本类文档应能大量早退（实测 $early）", early >= 35)
    }
}

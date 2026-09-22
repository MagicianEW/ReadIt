package com.readit.bench

import com.readit.core.text.ChapterDetector
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 验收 F02：TXT 章节识别率基准（规范要求 > 90%）。
 *
 * 语料由 tools/gen_bench_corpus.py 生成，落 app/src/test/resources/bench/txt/，
 * ground truth 在 truth.tsv（文件名 \\t 标题行的行号列表）。
 *
 * 覆盖 12 种真实标题样式（第N章 / 第N章： / 第N节 / 第N回 / 卷N / Chapter N / N. / N、 /
 * 番外 / 附录 / 序言·楔子·后记·尾声），并注入三类**必须被拒绝**的负样本：
 *   - 句中出现「第X章」但并非标题（"他把书翻到第三章，指着那一行字问她认不认得。"）
 *   - 以数字编号开头、但以句号收尾的正文
 *   - 超长行
 * 只统计正样本召回率会掩盖「把正文当标题」的灾难性体验，所以精确率一并输出。
 */
class ChapterBenchmarkTest {

    private fun resourceText(name: String): String {
        val src = requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "missing $name" }
        return src.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun `chapter detection recall and precision on corpus`() {
        val tsv = resourceText("bench/txt/truth.tsv")
        val rows = tsv.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("\t")
                parts[0] to parts.getOrNull(1).orEmpty()
            }
            .toList()

        var tp = 0
        var fp = 0
        var fn = 0
        val misses = ArrayList<String>()

        for ((name, posField) in rows) {
            val text = resourceText("bench/txt/$name")
            val lines = text.split("\n")
            // 行号 -> 字符偏移
            val lineStarts = ArrayList<Int>(lines.size)
            var acc = 0
            for (l in lines) {
                lineStarts.add(acc)
                acc += l.length + 1
            }
            val truthOffsets = posField
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .map { lineStarts[it] }
                .toSet()

            val detected = ChapterDetector.detect(text).map { it.startOffset }.toSet()

            val hit = detected.intersect(truthOffsets)
            tp += hit.size
            fp += detected.size - hit.size
            val missed = truthOffsets - detected
            fn += missed.size

            if (detected.size != truthOffsets.size || missed.isNotEmpty()) {
                val lineOf: (Int) -> String = { off ->
                    "「${text.substring(off).lineSequence().firstOrNull().orEmpty().take(24)}」"
                }
                val fnSample = missed.take(3).map(lineOf)
                val fpSample = (detected - truthOffsets).take(3).map(lineOf)
                misses.add(
                    "$name: 应识别 ${truthOffsets.size} 实得 ${detected.size}" +
                        (if (fnSample.isNotEmpty()) " 漏=${fnSample.joinToString(" ")}" else "") +
                        (if (fpSample.isNotEmpty()) " 误=${fpSample.joinToString(" ")}" else "")
                )
            }
        }

        val recall = tp.toFloat() / (tp + fn)
        val precision = tp.toFloat() / (tp + fp)
        println("------------------------------------------------------------")
        println("[F02 章节基准] 语料 ${rows.size} 篇，标题行 ${tp + fn}")
        println("  召回率(识别率) : ${"%.1f".format(recall * 100)}%  (tp=$tp fn=$fn)")
        println("  精确率         : ${"%.1f".format(precision * 100)}%  (fp=$fp)")
        misses.take(8).forEach { println("  $it") }
        println("------------------------------------------------------------")

        assertTrue("章节识别率应 ≥ 90%（实测 ${"%.1f".format(recall * 100)}%）", recall >= 0.90f)
        assertTrue("章节精确率应 ≥ 90%（实测 ${"%.1f".format(precision * 100)}%）", precision >= 0.90f)
    }
}

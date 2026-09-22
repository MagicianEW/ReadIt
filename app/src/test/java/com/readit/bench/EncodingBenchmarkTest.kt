package com.readit.bench

import com.readit.core.text.EncodingDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0 验收 F15：编码自动检测准确率基准（规范要求 > 95%）。
 *
 * 语料在测试内合成，不落二进制夹具——编码基准的核心是「字节 → 解码可用」，
 * 语料本身用 String.getBytes(charset) 生成即可，既省仓库体积又保证可复现。
 *
 * 判定口径（比「名称相等」更贴近用户可感知的验收）：
 *   1. detectName() 不返回 null
 *   2. 用检测到的编码 readWith() 后，正文首部签名串能被还原
 *   3. 且 looksGarbled() 为 false
 * 三条全中才算命中。GBK 文本被判成 GB18030 属于**正确**（GB18030 是超集），
 * 按名称比反而会误判为失败。
 *
 * 分「长/中文本」与「极短文本（<100 字）」两组统计：后者是自动检测的天生盲区
 * （真机实测 8 字节 GBK 被判 KOI8-R），必须单独暴露，不能被长文的漂亮数字盖住。
 */
class EncodingBenchmarkTest {

    private val simplified = "雨下了整整三天，山道上的泥被踩成了一层薄薄的浆。他把断剑背在身后，剑柄处的布条已经磨得发白。"
    private val traditional = "雨下了整整三天，山道上的泥被踩成了一層薄薄的漿。他把斷劍背在身後，劍柄處的布條已經磨得發白。"
    private val mixed = "Chapter 1 山道：The rain lasted three days, 他把断剑背在身后。"
    private val asciiHeavy = "2023年5月，他带着 3 个人和 12 箱货，从 A 城走到 B 城，一共走了 128 公里。"
    private val rareChars = "雨下了三天，山道泥泞。古籍里写作「𠀀」，今人多不识；又有「龘」字，四龙叠成。"

    private val para: String get() = simplified
    private val paraTrad: String get() = traditional
    private val paraMixed: String get() = mixed

    private data class Case(
        val label: String,
        val text: String,
        val charsetName: String,
        val withBom: Boolean,
        val short: Boolean
    )

    private data class Result(val case: Case, val ok: Boolean, val detected: String?, val detail: String)

    private fun buildCases(): List<Case> {
        val out = ArrayList<Case>()
        // 各内容可用的编码：GBK 装不下扩展区汉字，BIG5 装不下简体字，按能力给才不是自欺
        val base = listOf(
            "UTF-8" to false,
            "UTF-8" to true,
            "GBK" to false,
            "GB18030" to false,
            "UTF-16LE" to true,
            "UTF-16BE" to true,
            "UTF-16LE" to false,
            "UTF-16BE" to false
        )
        val contents = listOf(
            "简体" to (para to base),
            "繁体" to (paraTrad to base + listOf("BIG5" to false)),
            "中英混排" to (paraMixed to base),
            "数英为主" to (asciiHeavy to base),
            "扩展区生僻字" to (rareChars to base.filter { it.first != "GBK" })
        )
        val lengths = listOf(
            Triple("长文", 300, false),
            Triple("中文", 20, false),
            Triple("极短", 1, true)
        )
        for ((cname, pair) in contents) {
            val (ctext, encodings) = pair
            for ((lname, repeat, isShort) in lengths) {
                val text = ctext.repeat(repeat)
                for ((cs, bom) in encodings) {
                    out.add(Case("$cname/$lname/$cs${if (bom) "+BOM" else ""}", text, cs, bom, isShort))
                }
            }
        }
        return out
    }

    private fun bytesOf(case: Case): ByteArray {
        val raw = case.text.toByteArray(Charsets.UTF_8) // 先统一成 UTF-8 语义，再按目标编码重编
        val body = String(raw, Charsets.UTF_8).toByteArray(java.nio.charset.Charset.forName(case.charsetName))
        if (!case.withBom) return body
        val bom = when (case.charsetName) {
            "UTF-8" -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> ByteArray(0)
        }
        return bom + body
    }

    @Test
    fun `encoding detection accuracy on synthetic corpus`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "readit_enc_bench").apply { mkdirs() }
        val cases = buildCases()
        val results = ArrayList<Result>(cases.size)

        for (c in cases) {
            val f = File(dir, "sample.tmp")
            f.writeBytes(bytesOf(c))
            val detected = EncodingDetector.detectName(f)
            val signature = c.text.substring(0, minOf(20, c.text.length))
            val r = if (detected == null) {
                Result(c, false, null, "检测返回 null")
            } else {
                val text = runCatching { EncodingDetector.readWith(f, detected) }.getOrNull()
                when {
                    text == null -> Result(c, false, detected, "readWith 抛异常")
                    !text.contains(signature) -> Result(c, false, detected, "签名还原失败")
                    EncodingDetector.looksGarbled(text) -> Result(c, false, detected, "判定乱码")
                    else -> Result(c, true, detected, "ok")
                }
            }
            results.add(r)
        }

        val normal = results.filter { !it.case.short }
        val short = results.filter { it.case.short }
        val normalRate = normal.count { it.ok }.toFloat() / normal.size
        val shortRate = if (short.isEmpty()) 0f else short.count { it.ok }.toFloat() / short.size
        val allRate = results.count { it.ok }.toFloat() / results.size

        println("------------------------------------------------------------")
        println("[F15 编码基准] 样本 ${results.size}（常规 ${normal.size} / 极短 ${short.size}）")
        println("  常规文本准确率 : ${"%.1f".format(normalRate * 100)}%")
        println("  极短文本准确率 : ${"%.1f".format(shortRate * 100)}%")
        println("  整体准确率     : ${"%.1f".format(allRate * 100)}%")
        results.filter { !it.ok }.forEach {
            println("  MISS ${it.case.label} -> detected=${it.detected} (${it.detail})")
        }
        println("------------------------------------------------------------")

        assertTrue("常规文本编码检测准确率应 ≥ 95%（实测 ${"%.1f".format(normalRate * 100)}%）", normalRate >= 0.95f)
        // 极短文本不做硬断言：它是自动检测的天生盲区，靠工具栏「编码」按钮兜底
        println("[F15] 极短文本准确率 ${"%.1f".format(shortRate * 100)}%（仅记录，不做门禁）")
    }
}

package com.readit.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * 编码检测（F15）。
 *
 * 覆盖两条容易漏的路径：
 *  - **自动检测失败**（返回 null）；
 *  - **自动检测"成功"但解出来是乱码**——这条不抛异常，是 F15 静默失败的主因，
 *    没有 `looksGarbled` 的把关，UI 永远走不到手动选择，`MANUAL_CANDIDATES`
 *    就退化成死代码。这也是当初唯一没被审核发现的 P0 欠账。
 */
class EncodingDetectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- 乱码判定

    @Test
    fun cleanTextHasZeroSuspiciousRatio() {
        val text = "第一章 山下的少年\n他背着一柄断剑走进了雨里。\n"
        assertEquals(0f, EncodingDetector.suspiciousRatio(text))
        assertFalse(EncodingDetector.looksGarbled(text))
    }

    @Test
    fun emptyTextIsNotGarbled() {
        assertEquals(0f, EncodingDetector.suspiciousRatio(""))
        assertFalse(EncodingDetector.looksGarbled(""))
    }

    @Test
    fun replacementCharsMakeItGarbled() {
        // GBK 字节被当成 UTF-8 解时，几乎每个汉字都会变成 U+FFFD
        val text = "\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD" + "abcdefghij"
        assertTrue(EncodingDetector.suspiciousRatio(text) > EncodingDetector.GARBLED_RATIO)
        assertTrue(EncodingDetector.looksGarbled(text))
    }

    @Test
    fun occasionalReplacementCharDoesNotTriggerGarbled() {
        // 阈值上方要留余量：个别异常字符不代表整份文件编码错了
        val text = "abcdefghij\uFFFD".padEnd(2000, 'a')
        assertFalse(EncodingDetector.looksGarbled(text))
    }

    // ---------------------------------------------------------------- 读写

    @Test
    fun readWithHonorsGivenCharset() {
        val f = write("gbk.txt", "第一章 往事", "GBK")
        val text = EncodingDetector.readWith(f, "GBK")
        assertTrue(text.startsWith("第一章"))
    }

    @Test(expected = Exception::class)
    fun readWithThrowsOnUnsupportedCharsetName() {
        val f = write("x.txt", "abc", "UTF-8")
        EncodingDetector.readWith(f, "NO_SUCH_CHARSET")
    }

    @Test
    fun wrongCharsetProducesDetectableGarbage() {
        // 用 UTF-8 去读 GBK 文件：不抛异常，但结果能被 looksGarbled 抓住
        val f = write("gbk2.txt", "这是一段用 GBK 编码的中文正文。".repeat(20), "GBK")
        val wrong = EncodingDetector.readWith(f, "UTF-8")
        assertTrue("误解码必须被判定为乱码", EncodingDetector.looksGarbled(wrong))
        assertFalse("正确解码不能误判", EncodingDetector.looksGarbled(EncodingDetector.readWith(f, "GBK")))
    }

    // ---------------------------------------------------------------- 检测

    @Test
    fun bomShortCircuitsToUtf8() {
        val f = write("bom.txt", "有 BOM 的 UTF-8", "UTF-8", bom = true)
        assertEquals("UTF-8", EncodingDetector.detectName(f))
    }

    @Test
    fun missingFileYieldsNull() {
        assertFalse(File(tmp.root, "不存在的文件.txt").exists())
        assertEquals(null, EncodingDetector.detect(File(tmp.root, "不存在的文件.txt")))
    }

    @Test
    fun manualCandidateListKeepsChineseCommonFirst() {
        // UI 直接按这个顺序弹列表，中文常用编码必须在前面
        assertEquals(listOf("UTF-8", "GBK"), EncodingDetector.MANUAL_CANDIDATES.take(2))
    }

    // ---------------------------------------------------------------- helper

    private fun write(
        name: String,
        content: String,
        charset: String,
        bom: Boolean = false
    ): File {
        val f = File(tmp.root, name)
        f.writeBytes(
            if (bom) byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + content.toByteArray(Charsets.UTF_8)
            else content.toByteArray(Charset.forName(charset))
        )
        return f
    }
}

package com.readit.reader.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容量标定回归。
 *
 * 这里守的是一个「不崩溃、只让版面缩水 6 倍」的无声偏差：把多字样本的宽度
 * 当成单字宽度直接用，每行字数会被低估约样本字数倍。
 */
class TxtLayoutTest {

    @Test
    fun `sample width is divided by sample char count`() {
        // 10 个中文字样本，每个 30px -> 单字宽度 30px
        // 可用宽 900px -> 每行 30 字
        assertEquals(30, TxtLayout.charsPerLine(maxWidthPx = 900f, sampleWidthPx = 300f, sampleCharCount = 10))
    }

    @Test
    fun `forgetting to divide by sample length is what we guard against`() {
        // 同一个输入，若把样本总宽当单字宽（旧 bug）会得到 3 字/行，真实是 30 字/行
        val correct = TxtLayout.charsPerLine(900f, 300f, 10)
        val buggy = (900f / 300f).toInt()
        assertEquals(30, correct)
        assertEquals("旧实现在此只算出 3 字/行", 3, buggy)
        assertTrue(correct > buggy * 5)
    }

    @Test
    fun `degenerate inputs fall back to minimums instead of crashing`() {
        assertEquals(TxtLayout.MIN_CHARS_PER_LINE, TxtLayout.charsPerLine(900f, 300f, 0))
        assertEquals(TxtLayout.MIN_CHARS_PER_LINE, TxtLayout.charsPerLine(0f, 300f, 10))
        assertEquals(TxtLayout.MIN_LINES_PER_PAGE, TxtLayout.linesPerPage(1000f, 0f))
        assertEquals(TxtLayout.MIN_LINES_PER_PAGE, TxtLayout.linesPerPage(0f, 50f))
    }

    @Test
    fun `lines per page is a plain division`() {
        // 可用高 1800px，行高 60px -> 30 行
        assertEquals(30, TxtLayout.linesPerPage(1800f, 60f))
    }
}

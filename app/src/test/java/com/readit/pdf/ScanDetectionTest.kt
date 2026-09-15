package com.readit.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：扫描版检测的阈值收敛与采样页选择（F08）。
 *
 * 检测本身要读 PDF（走 PdfBox），这里只覆盖两处最容易写错的纯逻辑：
 *  - 阈值越界时的收敛（sanitized），设置页允许用户乱填
 *  - 采样页索引的均匀分布（首末页必须命中，低配设备才不至于总看第 1 页）
 */
class ScanDetectionTest {

    @Test
    fun `sanitized clamps every threshold into range`() {
        val wild = ScanThresholds(
            samplePages = 99,
            minCharsPerPage = 99_999,
            minImagePageRatio = 3f,
            minImageAreaRatio = 0.0001f
        ).sanitized()
        assertEquals(10, wild.samplePages)
        assertEquals(2000, wild.minCharsPerPage)
        assertEquals(1f, wild.minImagePageRatio, 0.0001f)
        assertEquals(0.05f, wild.minImageAreaRatio, 0.0001f)

        val tiny = ScanThresholds(
            samplePages = -5,
            minCharsPerPage = -1,
            minImagePageRatio = -2f,
            minImageAreaRatio = 500f
        ).sanitized()
        assertEquals(1, tiny.samplePages)
        assertEquals(0, tiny.minCharsPerPage)
        assertEquals(0f, tiny.minImagePageRatio, 0.0001f)
        assertEquals(20f, tiny.minImageAreaRatio, 0.0001f)
    }

    @Test
    fun `default thresholds match spec`() {
        val d = ScanThresholds.DEFAULT
        assertEquals(3, d.samplePages)
        assertEquals(100, d.minCharsPerPage)
        assertEquals(0.5f, d.minImagePageRatio, 0.0001f)
        assertEquals(0.5f, d.minImageAreaRatio, 0.0001f)
    }

    @Test
    fun `verdict describe carries every signal`() {
        val v = ScanVerdict(
            scanned = true, sampledPages = 3, avgCharsPerPage = 12.5f,
            imagePageRatio = 0.67f, maxImageAreaRatio = 4.2f, reason = "test"
        )
        val s = v.describe()
        assertTrue(s.contains("scanned=true"))
        assertTrue(s.contains("pages=3"))
        assertTrue(s.contains("chars/page=12.5"))
        assertTrue(s.contains("imgPages=0.67"))
        assertTrue(s.contains("maxImgArea=4.20"))
    }

    // ---------------------------------------------------------- 采样页索引

    private fun sampleIndices(total: Int, n: Int) = PdfTextExtractor().sampleIndices(total, n)

    @Test
    fun `sample covers first and last page`() {
        val idx = sampleIndices(10, 3)
        assertEquals(listOf(0, 4, 9), idx)
    }

    @Test
    fun `sample count larger than total returns every page`() {
        assertEquals(listOf(0, 1, 2, 3, 4), sampleIndices(5, 10))
        assertEquals(listOf(0), sampleIndices(1, 3))
        assertEquals(emptyList<Int>(), sampleIndices(0, 3))
    }

    @Test
    fun `single sample lands in the middle`() {
        assertEquals(listOf(5), sampleIndices(10, 1))
        assertEquals(listOf(0), sampleIndices(1, 1))
    }

    @Test
    fun `samples are distinct for larger counts`() {
        listOf(7, 10, 20, 100).forEach { total ->
            listOf(2, 3, 6, 10).forEach { n ->
                val idx = sampleIndices(total, n)
                assertEquals("total=$total n=$n 不应重复采样", idx.size, idx.distinct().size)
                assertTrue("total=$total n=$n 必须包含首页", idx.first() == 0)
                assertTrue("total=$total n=$n 必须包含末页", idx.last() == total - 1)
                assertFalse("total=$total n=$n 不应越界", idx.any { it < 0 || it >= total })
            }
        }
    }
}

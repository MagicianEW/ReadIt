package com.readit.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3：PDF 裁边几何判定回归（F07）。
 *
 * [PdfCropper.decide] 刻意不依赖 Bitmap，所以这些用例跑在纯 JVM 上，
 * 覆盖「空白页不裁 / 满页不裁 / 正常白边裁 / 单边留白过大放弃」四类边界。
 */
class PdfCropperTest {

    private val w = 1000
    private val h = 1400

    /** 墨水比例高于 minInkRatio 的采样计数 */
    private val ink = 5_000
    private val sampled = 350_000

    @Test
    fun `no ink at all yields null`() {
        assertNull(PdfCropper.decide(w, h, w, h, -1, -1, 0, sampled))
    }

    @Test
    fun `ink ratio below threshold treated as blank page`() {
        // 10 / 350000 = 0.00003 < minInkRatio(0.002)
        assertNull(PdfCropper.decide(w, h, 100, 120, 880, 1280, 10, sampled))
    }

    @Test
    fun `full bleed page saves too little and yields null`() {
        // 墨水铺满整页 -> saved == 0 < 2%
        assertNull(PdfCropper.decide(w, h, 0, 0, w - 1, h - 1, ink, sampled))
    }

    @Test
    fun `normal white margin is cropped with padding`() {
        val crop = PdfCropper.decide(w, h, 100, 120, 880, 1280, ink, sampled)
        requireNotNull(crop) { "应识别出可裁的白边" }
        // paddingRatio 0.01 -> padX = 10, padY = 14
        assertEquals(90, crop.left)
        assertEquals(106, crop.top)
        assertEquals(891, crop.right)
        assertEquals(1295, crop.bottom)
        assertEquals(801, crop.width)
        assertEquals(1189, crop.height)
    }

    @Test
    fun `one side margin beyond limit aborts cropping`() {
        // left margin 400 > 1000 * 0.35 = 350
        assertNull(PdfCropper.decide(w, h, 400, 120, 880, 1280, ink, sampled))
        // top margin 600 > 1400 * 0.35 = 490
        assertNull(PdfCropper.decide(w, h, 100, 600, 880, 1280, ink, sampled))
    }

    @Test
    fun `tiny bitmaps are skipped`() {
        assertNull(PdfCropper.decide(2, 2, 0, 0, 1, 1, ink, sampled))
        assertNull(PdfCropper.decide(0, 0, 0, 0, -1, -1, ink, sampled))
    }

    @Test
    fun `custom max margin ratio widens tolerance`() {
        val lenient = PdfCropper.Config(maxMarginRatio = 0.45f)
        val crop = PdfCropper.decide(w, h, 400, 120, 880, 1280, ink, sampled, lenient)
        requireNotNull(crop) { "放宽阈值后应允许裁边" }
        assertEquals(390, crop.left)
    }

    @Test
    fun `crop bounds stay inside bitmap when ink hugs the edges`() {
        // 墨水矩形已贴到 3px 边距，padding 1px 仍然不能越界，且不能多裁
        val crop = PdfCropper.decide(100, 100, 3, 3, 96, 96, ink, sampled)
        requireNotNull(crop)
        assertEquals(2, crop.left)
        assertEquals(2, crop.top)
        assertEquals(98, crop.right)
        assertEquals(98, crop.bottom)
    }

    @Test
    fun `padding is never allowed to grow the box beyond page`() {
        // 墨水起于 (0,0)：padX/padY 只能被 coerceAtLeast(0) 兜住
        val crop = PdfCropper.decide(1000, 1400, 0, 0, 900, 1200, ink, sampled)
        requireNotNull(crop)
        assertEquals(0, crop.left)
        assertEquals(0, crop.top)
        assertEquals(911, crop.right)
        assertEquals(1215, crop.bottom)
    }
}

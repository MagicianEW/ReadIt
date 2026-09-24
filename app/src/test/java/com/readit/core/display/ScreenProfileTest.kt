package com.readit.core.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕画像判定（[ScreenProfile]）—— 纯函数，无 Android 依赖。
 *
 * 重点防的是**误报**：厂商把 density 分档设得与物理 dpi 不同是常态（Civi2 就是
 * 401.7dpi 物理 / 440dpi 分档），绝不能拿它当「系统在做缩放」告警出去。
 */
class ScreenProfileTest {

    // 三台实测机型的真实指标
    private val civi2 = ScreenMetrics(
        panelWidthPx = 1080, panelHeightPx = 2400,
        appWidthPx = 1080, appHeightPx = 2400,
        densityDpi = 440, xdpi = 401.639f, ydpi = 401.845f, swDp = 393
    )
    private val ky01l = ScreenMetrics(
        panelWidthPx = 480, panelHeightPx = 600,
        appWidthPx = 480, appHeightPx = 600,
        densityDpi = 280, xdpi = 280f, ydpi = 280f, swDp = 274
    )
    private val epd106 = ScreenMetrics(
        panelWidthPx = 758, panelHeightPx = 1024,
        appWidthPx = 758, appHeightPx = 1024,
        densityDpi = 212, xdpi = 212f, ydpi = 212f, swDp = 572
    )

    // ---------------- UI 尺寸档位 ----------------

    @Test
    fun `尺寸档位按 sw dp 分三档`() {
        assertEquals(UiSizeClass.COMPACT, ScreenProfile.classifySize(274))
        assertEquals(UiSizeClass.COMPACT, ScreenProfile.classifySize(393))
        assertEquals(UiSizeClass.COMPACT, ScreenProfile.classifySize(399))
        assertEquals(UiSizeClass.MEDIUM, ScreenProfile.classifySize(400))
        assertEquals(UiSizeClass.MEDIUM, ScreenProfile.classifySize(572))
        assertEquals(UiSizeClass.MEDIUM, ScreenProfile.classifySize(599))
        assertEquals(UiSizeClass.EXPANDED, ScreenProfile.classifySize(600))
        assertEquals(UiSizeClass.EXPANDED, ScreenProfile.classifySize(720))
    }

    /** 这是本次要修的核心问题：旧实现只有 sw600dp 一个断点，三台机器全是 COMPACT */
    @Test
    fun `EPD106 归入 MEDIUM 而不再是 COMPACT`() {
        assertEquals(UiSizeClass.MEDIUM, ScreenProfile.classifySize(epd106.swDp))
        assertEquals(UiSizeClass.COMPACT, ScreenProfile.classifySize(civi2.swDp))
        assertEquals(UiSizeClass.COMPACT, ScreenProfile.classifySize(ky01l.swDp))
    }

    /** 1080x2400 的手机只有 393dp，与 480x600 同属小屏 —— 给同一套尺寸是**正确**的 */
    @Test
    fun `高分辨率手机仍是 COMPACT（dp 口径，不是像素口径）`() {
        val r = ScreenProfile.resolve(civi2)
        assertEquals(UiSizeClass.COMPACT, r.sizeClass)
    }

    // ---------------- 系统缩放检测（防误报是重点） ----------------

    @Test
    fun `Civi2 物理dpi与分档不同但不算缩放`() {
        val r = ScreenProfile.resolve(civi2)
        // 物理 ~401.7dpi / 分档 440dpi —— 厂商正常选型
        assertTrue("密度比值应明显偏离 1", kotlin.math.abs(r.densityRatio - 0.913f) < 0.01f)
        assertFalse("绝不能因为密度分档不同就报缩放", r.scaledBySystem)
        assertEquals(1f, r.panelScale, 0.001f)
    }

    @Test
    fun `面板像素大于应用可见像素才算缩放合成`() {
        val scaled = civi2.copy(appWidthPx = 720, appHeightPx = 1600)
        val r = ScreenProfile.resolve(scaled)
        assertEquals(1.5f, r.panelScale, 0.001f)
        assertTrue(r.scaledBySystem)
    }

    @Test
    fun `面板与应用可见尺寸一致时不报缩放`() {
        listOf(civi2, ky01l, epd106).forEach { m ->
            assertFalse("${m.panelWidthPx}x${m.panelHeightPx} 不该报缩放", ScreenProfile.resolve(m).scaledBySystem)
        }
    }

    @Test
    fun `xdpi 取不到时回落到分档值，不谎报`() {
        val unknown = civi2.copy(xdpi = 0f, ydpi = 0f)
        assertEquals(440f, ScreenProfile.physicalDpi(unknown.xdpi, unknown.ydpi, unknown.densityDpi), 0.001f)
        assertEquals(1f, ScreenProfile.densityRatio(unknown), 0.001f)
    }

    @Test
    fun `物理dpi取xdpi与ydpi均值`() {
        assertEquals(401.742f, ScreenProfile.physicalDpi(401.639f, 401.845f, 440), 0.001f)
    }

    // ---------------- 渲染模式 ----------------

    @Test
    fun `AUTO 时墨水屏走锐利_LCD走平滑`() {
        assertEquals(RenderMode.SHARP, ScreenProfile.resolveRenderMode(RenderMode.AUTO, true))
        assertEquals(RenderMode.SMOOTH, ScreenProfile.resolveRenderMode(RenderMode.AUTO, false))
    }

    @Test
    fun `用户显式选择优先于检测结论`() {
        // LCD 上用户就要锐利
        assertEquals(RenderMode.SHARP, ScreenProfile.resolveRenderMode(RenderMode.SHARP, false))
        // 墨水屏上用户就要平滑
        assertEquals(RenderMode.SMOOTH, ScreenProfile.resolveRenderMode(RenderMode.SMOOTH, true))
    }

    @Test
    fun `未显式设置时按机型得出默认渲染模式`() {
        assertEquals(RenderMode.SHARP, ScreenProfile.resolve(epd106, RenderMode.AUTO, true).renderMode)
        assertEquals(RenderMode.SMOOTH, ScreenProfile.resolve(civi2, RenderMode.AUTO, false).renderMode)
    }

    // ---------------- 字号整像素吸附 ----------------

    @Test
    fun `字号吸附到整像素`() {
        // 14sp @2.75x = 38.5px -> 39（必须是「四舍五入」：kotlin.math.round 在 .5 处
        // 是奇进偶舍会给 38，吸附就失去意义了，所以实现里用 floor(x+0.5)）
        assertEquals(39f, ScreenProfile.snapTextSizePx(14f, 2.75f), 0.001f)
        assertEquals(60f, ScreenProfile.snapTextSizePx(20f, 3f), 0.001f)
        assertEquals(17f, ScreenProfile.snapTextSizePx(14f, 1.2f), 0.001f) // 16.8 -> 17
    }

    @Test
    fun `极小字号吸附后保底1px不归零`() {
        assertEquals(1f, ScreenProfile.snapTextSizePx(0.01f, 1f), 0.001f)
        assertEquals(1f, ScreenProfile.snapTextSizePx(0f, 2f), 0.001f)
    }

    // ---------------- 解析与摘要 ----------------

    @Test
    fun `枚举解析容错`() {
        assertEquals(RenderMode.SHARP, RenderMode.from("sharp"))
        assertEquals(RenderMode.AUTO, RenderMode.from(null))
        assertEquals(RenderMode.AUTO, RenderMode.from("nonsense"))
        assertEquals(UiSizeClass.MEDIUM, UiSizeClass.from("MEDIUM"))
        assertEquals(UiSizeClass.COMPACT, UiSizeClass.from("bogus"))
    }

    @Test
    fun `摘要含分辨率_档位与渲染模式`() {
        val s = ScreenProfile.describe(ScreenProfile.resolve(epd106, RenderMode.AUTO, true))
        assertTrue(s.contains("758x1024"))
        assertTrue(s.contains("sw572dp"))
        assertTrue(s.contains("档位=MEDIUM"))
        assertTrue(s.contains("渲染=SHARP"))
    }
}

package com.readit.core.light

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏幕灯手势与亮度映射回归。
 *
 * 这里唯一真正要守住的是 **灯手势不能吃掉翻页手势**：阅读器靠「横向滑动」和
 * 「点击三分区」翻页，任何一处判定放宽都可能让用户在左边缘翻不了页，
 * 而这类问题在单测之外几乎不会被人发现（手势看着"偶尔失灵"而已）。
 */
class LightGestureTest {

    /** 近似 KY-01L：480 宽、density 1.75，stage 高 500 */
    private val g = LightGesture.Geometry(widthPx = 480, heightPx = 500, density = 1.75f)

    // ---------------------------------------------------------------- 几何

    @Test
    fun edgeWidthIsClampedToScreen() {
        // 48dp * 1.75 = 84dp 会被 12% 屏宽（57.6）压回去
        assertTrue(g.edgeWidthPx > 0f)
        assertTrue("边缘条不能宽于 12% 屏宽", g.edgeWidthPx <= 480 * 0.12f + 0.01f)
        assertTrue("边缘条不能窄于 24dp", g.edgeWidthPx >= 24f * 1.75f - 0.01f)
    }

    @Test
    fun toggleCornerOnlyBottomLeft() {
        assertTrue(LightGesture.canToggleAt(g, 40f, 460f, lightSupported = true))
        assertFalse("上半部分不算左下角", LightGesture.canToggleAt(g, 40f, 100f, true))
        assertFalse("右侧不算左下角", LightGesture.canToggleAt(g, 300f, 460f, true))
        assertFalse("不支持开关时一律不算", LightGesture.canToggleAt(g, 40f, 460f, false))
    }

    @Test
    fun brightnessEdgeAndLightState() {
        assertTrue(LightGesture.canBrightnessAt(g, 10f, lightOn = true))
        assertFalse("灯关着时不能调亮度", LightGesture.canBrightnessAt(g, 10f, false))
        assertFalse("右侧边缘外不算", LightGesture.canBrightnessAt(g, 400f, true))
    }

    // ---------------------------------------------------------------- 竖划调亮度

    @Test
    fun swipeUpRaisesBrightness() {
        val d = LightGesture.brightnessDelta(g, 20f, 300f, 20f, 200f, lightOn = true)
        assertTrue("上划应当提高亮度，实际 $d", d > 0)
        assertEquals(10, d) // 100px / 40px每档 = 2 档 * 5
    }

    @Test
    fun swipeDownLowersBrightness() {
        val d = LightGesture.brightnessDelta(g, 20f, 200f, 20f, 300f, lightOn = true)
        assertTrue("下划应当降低亮度，实际 $d", d < 0)
        assertEquals(-10, d)
    }

    @Test
    fun horizontalSwipeIsNeverBrightness() {
        // 最要命的一条：左边缘附近横划必须是翻页，不是调亮度
        assertEquals(0, LightGesture.brightnessDelta(g, 20f, 300f, 220f, 305f, true))
        assertEquals(0, LightGesture.brightnessDelta(g, 20f, 300f, 220f, 300f, true))
    }

    @Test
    fun tinyMoveIsNotBrightness() {
        assertEquals(0, LightGesture.brightnessDelta(g, 20f, 300f, 22f, 290f, true))
    }

    @Test
    fun brightnessSwipeSwallowedWhenLightOff() {
        assertEquals(0, LightGesture.brightnessDelta(g, 20f, 300f, 20f, 200f, lightOn = false))
    }

    @Test
    fun swipeOutsideEdgeIsNotBrightness() {
        assertEquals(0, LightGesture.brightnessDelta(g, 300f, 300f, 300f, 200f, true))
    }

    // ---------------------------------------------------------------- 等级

    @Test
    fun levelClamped() {
        assertEquals(0, LightGesture.applyDelta(0, -30))
        assertEquals(100, LightGesture.applyDelta(100, 30))
        assertEquals(55, LightGesture.applyDelta(50, 5))
    }

    @Test
    fun windowBrightnessMapping() {
        assertEquals(ScreenLight.OFF, ScreenLight.windowBrightness(on = false, level = 80))
        assertEquals(0.5f, ScreenLight.windowBrightness(on = true, level = 50))
        assertEquals(1.0f, ScreenLight.windowBrightness(on = true, level = 100))
        // 开着但等级为 0 时要保底，否则屏幕全黑像死机
        assertEquals(ScreenLight.MIN_ON, ScreenLight.windowBrightness(on = true, level = 0))
    }

    // ---------------------------------------------------------------- 长按

    @Test
    fun longPressNeedsTimeAndStillness() {
        assertFalse(LightGesture.isLongPress(200L, movedBeyondSlop = false))
        assertTrue(LightGesture.isLongPress(600L, movedBeyondSlop = false))
        assertFalse("移动过就不算长按", LightGesture.isLongPress(600L, movedBeyondSlop = true))
    }

    @Test
    fun moveSlopThreshold() {
        assertFalse(LightGesture.movedBeyondSlop(100f, 100f, 105f, 103f))
        assertTrue(LightGesture.movedBeyondSlop(100f, 100f, 130f, 100f))
    }
}

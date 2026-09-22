package com.readit.core.light

import kotlin.math.abs

/**
 * 屏幕灯手势判定（纯函数，不碰 Android 类，JVM 可测）。
 *
 * 两条手势（需求）：
 * 1. **长按左下角** → 切换屏幕灯开关
 * 2. **左侧靠边竖划** → 灯亮时上划提高、下划降低亮度
 *
 * 之所以把几何和阈值全抽出来，是因为这里最容易出的错是**吃掉翻页手势**：
 * 阅读器原本靠「横向滑动」和「点击三分区」翻页，所以本文件只回答
 * 「这个点/这段位移属不属于灯手势」，**是否拦截**由 Activity 按下面的规则决定，
 * 保证横划翻页与点击翻页的优先级高于灯手势。
 */
object LightGesture {

    /** 长按判定（毫秒） */
    const val LONG_PRESS_MS = 500L

    /** 超过这个位移就认为用户在拖动，取消长按（px 比较，传入前自行换算密度） */
    const val MOVE_SLOP_PX = 16f

    /** 竖划判定：位移超过这个距离才算一次调光手势（px） */
    const val SWIPE_MIN_PX = 24f

    /** 左下角开关热区：宽 35%、高（底部）25% */
    const val CORNER_W_RATIO = 0.35f
    const val CORNER_H_RATIO = 0.25f

    /** 左侧边缘条宽度：48dp，但不宽于 12% 屏宽、不窄于 24dp */
    private const val EDGE_DP = 48f
    private const val EDGE_MIN_DP = 24f
    private const val EDGE_MAX_RATIO = 0.12f

    /** 竖划每跨过「屏高的百分之多少」调一档 */
    const val STEP_HEIGHT_RATIO = 0.08f

    /** 屏幕尺寸 + 密度 → 手势几何 */
    data class Geometry(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float = 1f
    ) {
        val edgeWidthPx: Float
            get() {
                val d = if (density <= 0f) 1f else density
                val want = EDGE_DP * d
                val max = widthPx * EDGE_MAX_RATIO
                val min = EDGE_MIN_DP * d
                return want.coerceAtMost(max.coerceAtLeast(min)).coerceAtLeast(min)
            }

        /** 每档对应的像素距离 */
        val stepPx: Float get() = (heightPx * STEP_HEIGHT_RATIO).coerceAtLeast(1f)

        /** 左下角（长按开关灯）热区 */
        fun inToggleCorner(x: Float, y: Float): Boolean =
            x >= 0 && x <= widthPx * CORNER_W_RATIO &&
                y >= heightPx * (1f - CORNER_H_RATIO) && y <= heightPx

        /** 左侧边缘条（竖划调亮度） */
        fun inBrightnessEdge(x: Float): Boolean = x >= 0 && x <= edgeWidthPx
    }

    /** 这个落点能不能触发「长按开关灯」 */
    fun canToggleAt(g: Geometry, x: Float, y: Float, lightSupported: Boolean): Boolean =
        lightSupported && g.inToggleCorner(x, y)

    /** 这个起始点能不能触发「竖划调亮度」 */
    fun canBrightnessAt(g: Geometry, x: Float, lightOn: Boolean): Boolean =
        lightOn && g.inBrightnessEdge(x)

    /**
     * 一次竖划 → 亮度增量（正=提高，负=降低，0=不算手势）。
     *
     * 三个硬条件（防止吃掉翻页/滚动）：
     * - 起点必须在左侧边缘条内；
     * - 位移以**竖向**为主（`|dy| > |dx|`），横划一律不认 —— 那是翻页；
     * - 超过 [SWIPE_MIN_PX] 才响应，避免和点击/轻微抖动混淆。
     */
    fun brightnessDelta(
        g: Geometry,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        lightOn: Boolean
    ): Int {
        if (!canBrightnessAt(g, startX, lightOn)) return 0
        val dx = endX - startX
        val dy = endY - startY
        if (abs(dy) <= SWIPE_MIN_PX) return 0
        if (abs(dy) <= abs(dx)) return 0
        val steps = (-dy / g.stepPx).toInt() // 上划 dy<0 → 正档位
        return if (steps == 0) 0 else steps * ScreenLight.LEVEL_STEP
    }

    /** 在 0..100 内夹取 */
    fun clampLevel(level: Int): Int = level.coerceIn(ScreenLight.LEVEL_MIN, ScreenLight.LEVEL_MAX)

    /** 当前等级 + 增量 → 新等级 */
    fun applyDelta(level: Int, delta: Int): Int = clampLevel(level + delta)

    /** 是否触发了长按（时长够且没被移动打断） */
    fun isLongPress(elapsedMs: Long, movedBeyondSlop: Boolean): Boolean =
        !movedBeyondSlop && elapsedMs >= LONG_PRESS_MS

    fun movedBeyondSlop(startX: Float, startY: Float, x: Float, y: Float): Boolean =
        abs(x - startX) > MOVE_SLOP_PX || abs(y - startY) > MOVE_SLOP_PX
}

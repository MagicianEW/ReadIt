package com.readit.core.display

/**
 * 屏幕画像：把「分辨率 / 密度」换算成 **UI 尺寸档位** 与 **文字渲染模式**。
 *
 * ## 为什么要有这个模块
 *
 * 之前应用完全不感知屏幕：分辨率只在设备画像匹配和「设备报告」里被读出来显示一下，
 * UI 尺寸全靠 `values-sw600dp` 一个资源限定符 —— 而实测三台机器**一台都没命中过**
 * （Civi2 是 1080x2400@440dpi，只有 sw393dp；EPD106 是 758x1024@212dpi，约 sw572dp）。
 * 结果就是 758px 的机器和 480px 的机器拿到**完全相同**的一套尺寸。
 *
 * 文字则一律走 `Paint(ANTI_ALIAS_FLAG)`：笔画边缘是灰阶过渡（实测正文区 4.5% 的像素
 * 落在中间灰、共 225 个灰阶级）。在 LCD 上这是对的，但在 **1bit 墨水屏面板**上这些
 * 中间灰会被抖动 / 阈值化成离散网点，肉眼就是**边缘发灰发糊**（用户反馈的「发虚」）。
 *
 * ## 本模块提供的两件事
 *
 * 1. **UI 尺寸档位**（[UiSizeClass]）：按 **dp 口径**（不是像素）分三档，
 *    与 `values / values-sw400dp / values-sw600dp` 资源桶对齐，
 *    并给出每档的尺寸基准，便于交叉校验系统到底选中了哪一桶。
 * 2. **文字渲染模式**（[RenderMode]）：`SMOOTH` 保留抗锯齿（LCD 观感最顺）；
 *    `SHARP` 关闭抗锯齿 + 关闭亚像素 + 字号吸附到整像素，让 1bit 面板**点对点**出字。
 *
 * 本文件刻意不依赖任何 Android 类型（与 [Inversion] 同一口径），便于 JVM 单测。
 */
object ScreenProfile {

    /**
     * 「面板像素 / 应用可见像素」的允许偏差：超过即认为系统在做缩放合成。
     *
     * 注意与**密度分档**区分开：`densityDpi`（如 440）不等于物理 dpi（如 401.7）是厂商的
     * 正常选型 —— 它只决定 1dp 折多少 px，UI 缓冲仍然是 1:1 打到面板上，**不会**重采样。
     * 真正会让画面发虚的是**尺寸**被改（`wm size` 覆写、或面板像素比 != 1.0）：
     * 缓冲比面板小，GPU 放大合成 = 真重采样。
     */
    const val PANEL_SCALE_TOLERANCE = 0.02f

    /** 进入 MEDIUM 档的最小 sw（dp） */
    const val SW_MEDIUM_DP = 400

    /** 进入 EXPANDED 档的最小 sw（dp）——与既有 `values-sw600dp` 同断点 */
    const val SW_EXPANDED_DP = 600

    // ------------------------------------------------------------------
    // 分类
    // ------------------------------------------------------------------

    /**
     * 按 **最小宽度 dp** 分档。
     *
     * 用 dp 而不是像素：布局能放多少内容取决于 dp（已折掉密度），
     * 1080x2400@440dpi 的手机只有 393dp，本质上与 480x600@280dpi（274dp）同属小屏，
     * 给它们同一套紧凑尺寸是**正确**的；反过来 758x1024@212dpi 有 572dp，
     * 该进 MEDIUM —— 这正是旧实现（`sw600dp` 单断点）漏掉的那一档。
     */
    fun classifySize(swDp: Int): UiSizeClass = when {
        swDp < SW_MEDIUM_DP -> UiSizeClass.COMPACT
        swDp < SW_EXPANDED_DP -> UiSizeClass.MEDIUM
        else -> UiSizeClass.EXPANDED
    }

    /**
     * 物理 dpi（xdpi / ydpi 均值）。
     *
     * 取值不可信时（部分 ROM 上报 0 或明显离谱）**回落到 densityDpi**，
     * 这样偏差恒为 1.0 —— 拿不到真实值就**不谎报**有缩放，避免误报吓到用户。
     */
    fun physicalDpi(xdpi: Float, ydpi: Float, densityDpi: Int): Float {
        if (densityDpi <= 0) return 0f
        val valid = listOf(xdpi, ydpi).filter { it in 40f..1000f }
        if (valid.isEmpty()) return densityDpi.toFloat()
        return valid.average().toFloat()
    }

    /**
     * 面板像素 / 应用可见像素 的比例。
     *
     * `1.0` 表示 UI 缓冲与面板同尺寸、1:1 呈现；`> 1` 表示缓冲比面板小、
     * 被 GPU 放大合成 —— **非整数倍放大就是真重采样**，这是「发虚」最狠的一条来源。
     * 取值不可信时返回 1.0（宁可不报，也不谎报）。
     */
    fun panelScale(metrics: ScreenMetrics): Float {
        val app = metrics.appWidthPx
        if (app <= 0 || metrics.panelWidthPx <= 0) return 1f
        return metrics.panelWidthPx.toFloat() / app.toFloat()
    }

    /**
     * 物理 dpi 与系统 density 分档的比值 —— **仅作参考，不代表有问题**。
     *
     * 实测 Civi2 是 401.7dpi 物理 / 440dpi 分档（比值 0.913），这是厂商正常选型，
     * 只影响「1dp 折多少 px」，**不会**导致重采样。所以这里**不参与** [scaledBySystem] 判定，
     * 只在设备信息里如实展示，避免拿它当告警把人吓一跳。
     */
    fun densityRatio(metrics: ScreenMetrics): Float {
        if (metrics.densityDpi <= 0) return 1f
        val phys = physicalDpi(metrics.xdpi, metrics.ydpi, metrics.densityDpi)
        return phys / metrics.densityDpi.toFloat()
    }

    /** 系统是否在做缩放合成（面板像素比超出 [PANEL_SCALE_TOLERANCE] 即算） */
    fun scaledBySystem(metrics: ScreenMetrics): Boolean {
        val s = panelScale(metrics)
        return s > 0f && kotlin.math.abs(s - 1f) > PANEL_SCALE_TOLERANCE
    }

    /**
     * 把用户偏好解析成实际生效的渲染模式。
     *
     * [RenderMode.AUTO] 才走检测结论：**疑似墨水屏 → SHARP，否则 SMOOTH**。
     * 用户一旦显式选过，就以用户选择为准（与档位「显式设置过就不覆盖」同一条纪律）。
     */
    fun resolveRenderMode(preference: RenderMode, einkLikely: Boolean): RenderMode =
        if (preference != RenderMode.AUTO) preference
        else if (einkLikely) RenderMode.SHARP
        else RenderMode.SMOOTH

    /**
     * 字号吸附到整像素。
     *
     * 非整数 px 的字号会让字形光栅化落在半个像素上，即使关了抗锯齿也会糊边。
     * 保底 1px，避免极小字号被吸附成 0 导致整页空白。
     */
    fun snapTextSizePx(sizeSp: Float, scaledDensity: Float): Float {
        // 用 floor(x + 0.5) 而不是 kotlin.math.round：后者在 .5 处是「奇进偶舍」
        // （实测 round(38.5f) == 38f），吸附结果不可预期，等于没吸附。
        val px = kotlin.math.floor(sizeSp * scaledDensity + 0.5f)
        return if (px < 1f) 1f else px
    }

    /** 完整解析：一次算出档位、渲染模式与系统缩放结论 */
    fun resolve(
        metrics: ScreenMetrics,
        preference: RenderMode = RenderMode.AUTO,
        einkLikely: Boolean = false
    ): ScreenProfileResult {
        val sizeClass = classifySize(metrics.swDp)
        val renderMode = resolveRenderMode(preference, einkLikely)
        return ScreenProfileResult(
            metrics = metrics,
            sizeClass = sizeClass,
            renderMode = renderMode,
            physicalDpi = physicalDpi(metrics.xdpi, metrics.ydpi, metrics.densityDpi),
            panelScale = panelScale(metrics),
            densityRatio = densityRatio(metrics),
            scaledBySystem = scaledBySystem(metrics),
            einkLikely = einkLikely
        )
    }

    /** 一行摘要，供日志与设置页使用 */
    fun describe(r: ScreenProfileResult): String {
        val m = r.metrics
        val scale = if (r.scaledBySystem) {
            "，系统缩放合成 ${"%.3f".format(r.panelScale)}× ⚠"
        } else {
            ""
        }
        return "${m.panelWidthPx}x${m.panelHeightPx} @${m.densityDpi}dpi sw${m.swDp}dp " +
            "档位=${r.sizeClass.key} 渲染=${r.renderMode.key}${scale}"
    }
}

/**
 * 文字渲染模式。
 *
 * - [SMOOTH]：抗锯齿开启。LCD / 高灰阶屏的最优解，边缘平滑。
 * - [SHARP]：**点对点**。关抗锯齿、关亚像素、字号吸附整像素。
 *   1bit 墨水屏上不会出现中间灰被抖动的情况，笔画实、边缘利。
 *   代价是斜线/曲线会有锯齿感 —— 所以只在疑似墨水屏（或用户手动指定）时启用。
 * - [AUTO]：跟随检测结论，未显式选择时的默认值。
 */
enum class RenderMode(val key: String) {
    AUTO("AUTO"),
    SMOOTH("SMOOTH"),
    SHARP("SHARP");

    companion object {
        fun from(raw: String?): RenderMode =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * UI 尺寸档位。
 *
 * 每档自带尺寸基准，用途有二：① 设置页/设备报告里显示当前档位；
 * ② 与系统实际选中的资源桶做**交叉校验**（两者不一致说明资源限定符没配上）。
 */
enum class UiSizeClass(
    val key: String,
    val toolbarIconDp: Int,
    val touchTargetDp: Int,
    val textSizeDefaultSp: Int
) {
    COMPACT("COMPACT", 36, 48, 14),
    MEDIUM("MEDIUM", 40, 52, 15),
    EXPANDED("EXPANDED", 44, 56, 16);

    companion object {
        fun from(raw: String?): UiSizeClass =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: COMPACT
    }
}

/**
 * 屏幕原始指标（由 Android 侧采集后传入，本文件只做纯计算）。
 *
 * `panel*` 是**物理面板**尺寸（`getRealMetrics`），`app*` 是**应用可见**尺寸
 * （`resources.displayMetrics`）—— 两者不一致才是系统在做缩放合成。
 */
data class ScreenMetrics(
    val panelWidthPx: Int,
    val panelHeightPx: Int,
    val appWidthPx: Int,
    val appHeightPx: Int,
    val densityDpi: Int,
    val xdpi: Float,
    val ydpi: Float,
    val swDp: Int
)

/** 解析结果 */
data class ScreenProfileResult(
    val metrics: ScreenMetrics,
    val sizeClass: UiSizeClass,
    val renderMode: RenderMode,
    val physicalDpi: Float,
    /** 面板像素 / 应用可见像素，>1 表示被放大合成（真重采样） */
    val panelScale: Float,
    /** 物理 dpi / density 分档，仅供展示，不代表有问题 */
    val densityRatio: Float,
    val scaledBySystem: Boolean,
    val einkLikely: Boolean
)

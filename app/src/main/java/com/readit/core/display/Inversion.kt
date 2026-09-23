package com.readit.core.display

/**
 * 反色（黑白置换）模式（F27）。
 *
 * 为什么不做「背景色 / 主题」：墨水屏只有黑、白两级（本应用全程禁灰阶，
 * 见 §2.2 与 `RefreshModeManager`），给用户一个「背景色」选项等于让他在
 * 两个颜色里选，没有信息量。真正有用的是**把黑白对调**——强光下、夜间、
 * 或某些面板偏色时更省眼，且由于只用到两个极端色，不会把墨水屏拖进
 * 灰阶刷新（那才是真正会留残影、会变慢的路径）。
 *
 * 本文件刻意不依赖任何 Android 类型，便于 JVM 单测。
 */
object Inversion {

    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** 正文字色 */
    fun fontColor(inverted: Boolean): Int = if (inverted) WHITE else BLACK

    /** 底色 */
    fun backColor(inverted: Boolean): Int = if (inverted) BLACK else WHITE

    /** CSS 色值（WebView 三条路径：EPUB / DOCX，以及 EPUB 的 html/body 兜底） */
    fun cssFont(inverted: Boolean): String = if (inverted) "#ffffff" else "#000000"

    fun cssBack(inverted: Boolean): String = if (inverted) "#000000" else "#ffffff"

    /**
     * 单个 ARGB 像素反色：**只翻 RGB，alpha 原样保留**。
     *
     * 透明底图（例如 PNG 封面、扫描件里的透明区域）如果连 alpha 一起取反，
     * 会从「全透明」变成「不透明」——不透明黑块盖住正文，肉眼看上去像渲染坏了。
     * 全流程里只有本函数做逐像素翻转，其余路径（PDF 走 ColorMatrix、
     * WebView 走 CSS）都在 Skia/Chromium 侧完成，不需要 CPU 逐像素。
     */
    fun invertArgb(src: Int): Int =
        (src and BLACK) or ((src and 0x00FFFFFF) xor 0x00FFFFFF)

    /**
     * PDF 用的 4x5 颜色矩阵：R' = 255 - R，G' = 255 - G，B' = 255 - B，A 不变。
     *
     * 与 [invertArgb] 语义一致，但走 Skia 的 `ColorMatrixColorFilter`，
     * 每帧零 CPU 成本（位图不被改写，只影响绘制）。低端 SoC 上这点很关键：
     * 一页 A4 扫描件是几百万像素，逐像素翻转会直接把首屏预算吃穿。
     */
    fun argbMatrix(): FloatArray = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    )

    /**
     * DOCX 注入的反色样式。
     *
     * 只改文字与底色，**不动图片**：照片 / 折线图被反成负片会彻底读不出来，
     * 而「文字白底黑字更省眼」才是这个模式的全部目的。
     * 表格边框单独给一档灰白，避免在纯黑底上完全消失。
     */
    fun docxCss(inverted: Boolean): String = if (!inverted) {
        ""
    } else {
        "html,body{background:#000000 !important;color:#ffffff !important;}" +
            "p,span,div,td,th,li,h1,h2,h3,h4,h5,h6,blockquote,pre{color:#ffffff !important;" +
            "background-color:transparent !important;border-color:#ffffff !important;}" +
            "table,tr,td,th{border-color:#ffffff !important;}"
    }
}

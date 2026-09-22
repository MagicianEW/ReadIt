package com.readit.pdf

/**
 * 扫描版检测阈值（规范 §3.2.4 / F08）。
 *
 * 全部可配置：设置页暴露「文本层字符阈值」与「图像页占比阈值」，
 * 阅读页对判定结果给出友好提示并支持「强制导入」（不承诺 100% 检测）。
 */
data class ScanThresholds(
    /** 采样页数（默认 3，均匀分布） */
    val samplePages: Int = 3,
    /** 单页文本层字符数低于该值即视为「无文本层」 */
    val minCharsPerPage: Int = 100,
    /** 采样页中「含整页大图」的比例达到该值才算扫描版 */
    val minImagePageRatio: Float = 0.5f,
    /** 图像像素面积 / 页面点面积 达到该比例才算「整页大图」 */
    val minImageAreaRatio: Float = 0.5f
) {
    fun sanitized(): ScanThresholds = copy(
        samplePages = samplePages.coerceIn(1, 10),
        minCharsPerPage = minCharsPerPage.coerceIn(0, 2000),
        minImagePageRatio = minImagePageRatio.coerceIn(0f, 1f),
        minImageAreaRatio = minImageAreaRatio.coerceIn(0.05f, 20f)
    )

    companion object {
        val DEFAULT = ScanThresholds()
    }
}

/**
 * 扫描版判定结果。
 *
 * @param sampledPages 实际抽过的页数；提前收工时小于计划采样页数
 * @param avgCharsPerPage 采样页平均文本层字符数
 * @param imagePageRatio 采样页中「含整页大图」的比例
 * @param maxImageAreaRatio 采样页里最大的「图像面积 / 页面面积」比值
 */
data class ScanVerdict(
    val scanned: Boolean,
    val sampledPages: Int,
    val avgCharsPerPage: Float,
    val imagePageRatio: Float,
    val maxImageAreaRatio: Float,
    val reason: String,
    /**
     * 本次检测的实际耗时（毫秒）。
     *
     * 采样页数直接决定它，而它又是 PDF 首屏延迟的主要成本项（§9.4），
     * 所以必须留在判定结果里随日志一起输出，否则「少采样省了多少」只靠猜。
     */
    val costMs: Long = 0,
    /**
     * 是否提前收工（没抽满计划的采样页数就定了结论）。
     *
     * `scanned = lowText && enoughImages`，而 `lowText` 只看「累计字符 < 阈值×页数」。
     * 所以累计字符一旦达标，`lowText` 已为假、`scanned` 必为假，其余页不必再抽 ——
     * 这是**等价推理不是近似**，用不到 1 页采样的代价就能拿到同样的省时（§9.4）。
     */
    val earlyExit: Boolean = false
) {
    fun describe(): String = String.format(
        java.util.Locale.ROOT,
        "scanned=%s pages=%d chars/page=%.1f imgPages=%.2f maxImgArea=%.2f cost=%dms early=%s (%s)",
        scanned, sampledPages, avgCharsPerPage, imagePageRatio, maxImageAreaRatio, costMs, earlyExit, reason
    )
}

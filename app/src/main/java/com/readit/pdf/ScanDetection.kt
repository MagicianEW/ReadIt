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
    val reason: String
) {
    fun describe(): String = String.format(
        java.util.Locale.ROOT,
        "scanned=%s pages=%d chars/page=%.1f imgPages=%.2f maxImgArea=%.2f (%s)",
        scanned, sampledPages, avgCharsPerPage, imagePageRatio, maxImageAreaRatio, reason
    )
}

package com.readit.reader.txt

/**
 * TXT 排版的「容量标定」——把像素尺寸换算成每行字数 / 每页行数。
 *
 * 单独抽出来的原因：这段算式出过一个**无声的严重偏差**。
 * 早期实现是：
 *
 * ```
 * val charW = paint.measureText("汉字abc")   // 6 个字符的总宽度
 * charsPerLine = (maxWidth / charW).toInt()  // 却当成"单字宽度"去除
 * ```
 *
 * 少除了一次样本字数，**每行字数被低估约 6 倍**（真机日志 `charsPerLine=7`，实际一行能排 40+ 字）。
 * 后果不是崩溃，而是「每页只排到实际容量的 1/5，屏幕下方大片空白，翻页次数凭空多 6 倍」——
 * 单元测试全绿（`TxtPager` 本身没错），错的是喂给它的标定值。
 */
object TxtLayout {

    /** 兜底：极端窄屏也至少排 6 字，避免出现 1 字/行的畸形分页 */
    const val MIN_CHARS_PER_LINE = 6

    /** 兜底：至少 1 行 */
    const val MIN_LINES_PER_PAGE = 1

    /**
     * 每行可容纳的字数。
     *
     * @param maxWidthPx 正文可用宽度（已扣左右边距）
     * @param sampleWidthPx [paint.measureText] 对**样本串**测得的宽度
     * @param sampleCharCount 样本串的字数（必须 > 0；除以它才是「单字宽度」）
     */
    fun charsPerLine(
        maxWidthPx: Float,
        sampleWidthPx: Float,
        sampleCharCount: Int
    ): Int {
        if (sampleCharCount <= 0) return MIN_CHARS_PER_LINE
        val charWidth = (sampleWidthPx / sampleCharCount).coerceAtLeast(1f)
        return (maxWidthPx / charWidth).toInt().coerceAtLeast(MIN_CHARS_PER_LINE)
    }

    /**
     * 每页可容纳的行数。
     *
     * @param usableHeightPx 正文可用高度（已扣上下边距）
     * @param lineHeightPx 单行高度
     */
    fun linesPerPage(usableHeightPx: Float, lineHeightPx: Float): Int {
        if (lineHeightPx <= 0f) return MIN_LINES_PER_PAGE
        return (usableHeightPx / lineHeightPx).toInt().coerceAtLeast(MIN_LINES_PER_PAGE)
    }
}

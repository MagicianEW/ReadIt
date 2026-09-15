package com.readit.pdf

/**
 * PDF 打开失败原因判定（规范 §9.3 边界场景表第 5 行：加密 PDF → 「提示不支持，不崩溃」）。
 *
 * 为什么要单独判：
 * 加密 PDF 在 Pdfium（渲染档）和 PdfBox（文本档）**都**读不了，而通用降级链路
 * （render 失败 → 退文本档）会先弹「已切换为文本模式」、再弹「没有可提取的文本层」，
 * 两条提示都不对，用户完全看不到真实原因。命中加密时应当直接给出「不支持」的明确提示。
 *
 * 不引入 PDFBox/Pdfium 的异常类型依赖：两边的报错文案来自不同库，直接在 cause 链上匹配
 * 关键字最稳（真机实测文案见下方标记）。
 */
object PdfOpenError {

    /**
     * 关键字全部小写比对。真机观测到的文案：
     *  - PdfBox：`Cannot decrypt PDF, the password is incorrect`
     *  - Pdfium：`pdfium load failed: Password required or incorrect password.`
     */
    private val ENCRYPTED_MARKERS = listOf(
        "password",
        "decrypt",
        "encrypted",
        "encryption"
    )

    /** cause 链上匹配层数上限，防御异常自引用导致的死循环 */
    private const val MAX_DEPTH = 8

    /** 该异常（含 cause 链）是否表示「需要密码 / 已加密」 */
    fun isEncrypted(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < MAX_DEPTH) {
            val m = e.message?.lowercase()
            if (m != null && ENCRYPTED_MARKERS.any { m.contains(it) }) return true
            if (e.cause === e) break
            e = e.cause
        }
        return false
    }
}

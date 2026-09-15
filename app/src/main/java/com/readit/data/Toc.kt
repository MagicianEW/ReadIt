package com.readit.data

/**
 * 统一的目录条目（F10）。
 *
 * 四条渲染路径共用同一份目录模型，跳转口径分别为：
 *  - TXT / EPUB 文本降级 / DOCX 文本降级：[offset] 为字符偏移，走 TxtCanvasView.goToOffset
 *  - EPUB 完整渲染：[spineIndex] 为 spine 下标，走 EpubWebView.displaySpine
 *  - PDF：[pageIndex] 为页码（0-based），走 PdfRenderView.goToPage
 *  - DOCX 子集渲染：[offset] 复用为标题锚点序号，走 WebView JS 滚动
 */
data class TocEntry(
    val title: String,
    val depth: Int = 0,
    /** 字符偏移，未命中为 -1 */
    val offset: Int = -1,
    /** EPUB spine 下标，未命中为 -1 */
    val spineIndex: Int = -1,
    /** PDF 页码（0-based），未命中为 -1 */
    val pageIndex: Int = -1
)

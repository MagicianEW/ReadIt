package com.readit.data

/**
 * 统一的目录条目（F10）。
 *
 * 四条渲染路径共用同一份目录模型，跳转口径分别为：
 *  - TXT / EPUB 文本降级 / DOCX 文本降级：[offset] 为字符偏移，走 TxtCanvasView.goToOffset
 *  - EPUB 完整渲染：[spineIndex] 为 spine 下标；**同一 spine 内的多个子章节**靠 [href]
 *    里的 `#fragment` 区分，走 EpubWebView.displayHref
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
    val pageIndex: Int = -1,
    /**
     * EPUB 目录原始 href（**含** `#fragment`）。
     *
     * 为什么必须单独留一份：单个 XHTML 承载多章的书（中文 EPUB 极常见）里，
     * 目录上相邻几项的 [spineIndex] 是**同一个值**，只按下标跳会全部落到该文件开头。
     * fragment 才能定位到文件内的具体锚点。
     */
    val href: String? = null
)

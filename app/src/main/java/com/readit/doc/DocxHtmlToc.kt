package com.readit.doc

/**
 * DOCX 子集 HTML 的标题锚点注入与目录抽取（F04 / F10）。
 *
 * 为什么独立成一个纯 Kotlin 对象：
 *  - [DocxParser] 依赖 android.util.Xml，无法在 JVM 单元测试里跑；
 *  - 而「给 h1~h6 打锚点 + 抽出目录」是纯字符串处理，理应可回归。
 *
 * 产物供 ReaderActivity 走 WebView 时使用：目录项 [ids] 直接拼成
 * `document.getElementById('readit-h-N').scrollIntoView()` 完成跳转。
 */
object DocxHtmlToc {

    /** 标题锚点前缀，与 ReaderActivity 的 JS 跳转口径保持一致 */
    const val ANCHOR_PREFIX = "readit-h-"

    private val HEADING = Regex(
        "<h([1-6])>(.*?)</h\\1>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val TAG = Regex("<[^>]*>")

    data class Rewritten(
        /** 注入 id 后的 HTML */
        val html: String,
        /** 标题纯文本（已去标签、还原实体） */
        val titles: List<String>,
        /** 层级，h1 → 0，h2 → 1 …（与 TocEntry.depth 口径一致） */
        val depths: List<Int>,
        /** 锚点序号，与 [titles] / [depths] 同下标 */
        val ids: List<Int>
    ) {
        val isEmpty: Boolean get() = titles.isEmpty()
    }

    /**
     * 扫描全部标题并按文档顺序编号。编号一旦确定就不再变动，
     * 与 ReaderActivity 侧「TocEntry.offset = 锚点序号」一一对应。
     */
    fun rewrite(html: String): Rewritten {
        val titles = ArrayList<String>()
        val depths = ArrayList<Int>()
        val ids = ArrayList<Int>()

        val out = HEADING.replace(html) { m ->
            val level = m.groupValues[1].toIntOrNull()?.coerceIn(1, 6) ?: 1
            val inner = m.groupValues[2]
            val id = titles.size
            titles.add(plainText(inner))
            depths.add(level - 1)
            ids.add(id)
            "<h$level id=\"$ANCHOR_PREFIX$id\">$inner</h$level>"
        }

        return Rewritten(out, titles, depths, ids)
    }

    /** 去标签 + 还原常见实体，用于目录显示文本 */
    fun plainText(html: String): String = html
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .trim()

    /** 生成跳转用 JS；标题不存在时是无副作用的空操作 */
    fun scrollJs(id: Int): String =
        "javascript:(function(){var e=document.getElementById('$ANCHOR_PREFIX$id');" +
            "if(e){e.scrollIntoView(true);}})()"

    private val MEDIA_SRC = Regex("""readit-media://[^"]*/([^"/]+)""")

    /**
     * 把 [DocxParser.IMG_SCHEME] 引用改成「与 HTML 同目录的裸文件名」。
     *
     * [DocxParser] 把内嵌图片落盘时只取 basename（`word/media/image1.png` → `image1.png`），
     * 而 WebView 通过 `loadDataWithBaseURL(outDir)` 加载，相对路径即可命中，
     * 不需要自定义 scheme 拦截（少一层 shouldInterceptRequest，E-Ink 上更快）。
     */
    fun relativizeMedia(html: String): String =
        MEDIA_SRC.replace(html) { it.groupValues[1] }
}

package com.readit.reader.docx

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.readit.core.display.Inversion
import com.readit.core.util.ReadItLog
import com.readit.doc.DocxHtmlToc
import org.json.JSONObject
import java.io.File

/**
 * DOCX 子集渲染宿主（F04）。
 *
 * 与 EPUB 的 [com.readit.reader.epub.EpubWebView] 的区别：
 *  - 这里不需要 epub.js，也没有分页引擎；HTML 由 [com.readit.doc.DocxConvertService] 预先生成
 *  - 用 loadDataWithBaseURL 挂载，baseUrl 指向产物目录 → 图片走相对路径直接命中
 *  - 「翻页」语义退化为整篇滚动（E-Ink 上滚动刷新代价大，故用标题锚点跳转代替长距离滚动）
 *
 * 安全/性能口径与 EPUB 一致：禁 JS 桥、禁文件跨域、图片按需加载、无动画。
 */
class DocxWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    init {
        setBackgroundColor(Inversion.backColor(false))
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ReadItLog.i("docx webview finished: $url")
                pageReady = true
                if (!loadedOnce) {
                    loadedOnce = true
                    onLoaded?.invoke() // §7 PDF/DOCX 首屏口径：内容真的挂上去了
                }
                pendingHeading?.let { id ->
                    pendingHeading = null
                    // 等一帧让布局定下来再滚，否则可能滚到旧高度
                    post { runScrollJs(id) }
                }
                post { runFontJs(fontFamily) }
                post { runInvertJs() }
            }
        }
        @Suppress("DEPRECATION")
        settings.javaScriptEnabled = true
        // 必须允许 file:// 访问：图片走 loadDataWithBaseURL 的 file:// baseUrl 相对路径命中。
        // 但禁止「file 页面再去读别的 file/XHR」以及任何网络加载，避免本地文件被外泄。
        @Suppress("DEPRECATION")
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowContentAccess = false
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = false
        settings.domStorageEnabled = false
        settings.databaseEnabled = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = true
        settings.blockNetworkLoads = true
        settings.useWideViewPort = false
        settings.loadWithOverviewMode = false
        settings.textZoom = 100
    }

    private var pendingHeading: Int? = null

    /**
     * 页面是否已经完成首次加载。
     *
     * 必须显式跟踪，不能靠「evaluateJavascript 会不会抛异常」判断就绪：
     * 在 about:blank 上执行 JS **不会抛异常**，只会静默无效。旧实现据此把
     * 「文档没就绪」当成「已就绪」，`pendingHeading` 永远不会被挂起，
     * 于是 `openDocxHtml` 里恢复上次阅读位置的那次 scrollToHeading 被吞掉，
     * 用户每次打开 DOCX 都从第 1 节开始。
     */
    @Volatile
    private var pageReady = false

    /** 首次加载完成回调（每本只触发一次），供 §7 首屏指标取时使用 */
    var onLoaded: (() -> Unit)? = null
    private var loadedOnce = false

    /** 当前字体（CSS font-family）；页面就绪后会重放一次 */
    private var fontFamily: String = DEFAULT_FONT_FAMILY

    /** 打包字体的 @font-face 源（相对 baseUrl），null = 用系统/默认族名，不需要下载字体 */
    private var fontFaceSrc: String? = null

    /** 反色状态（F27）；页面未就绪时挂起，等 onPageFinished 重放 */
    private var inverted: Boolean = false

    /**
     * 挂载 HTML。[baseDir] 为图片所在目录，null 时退化为无 baseUrl 的内联加载。
     */
    fun load(html: String, baseDir: File?) {
        // 新文档：上一次的待跳锚点作废，就绪位清空
        pendingHeading = null
        pageReady = false
        loadedOnce = false
        val base = baseDir?.let { "file://${it.absolutePath}/" }
        try {
            loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
        } catch (e: Throwable) {
            ReadItLog.e("docx load failed", e)
            loadData(html, "text/html", "utf-8")
        }
    }

    /**
     * 跳转到标题锚点。页面尚未就绪时挂起，等 `onPageFinished` 再执行——
     * 这是恢复阅读进度的唯一入口，不能被静默丢弃。
     */
    fun scrollToHeading(id: Int) {
        if (id < 0) return
        if (!pageReady) {
            pendingHeading = id
            return
        }
        runScrollJs(id)
    }

    private fun runScrollJs(id: Int) {
        try {
            evaluateJavascript(DocxHtmlToc.scrollJs(id), null)
        } catch (e: Throwable) {
            ReadItLog.w("docx scrollToHeading($id) failed: ${e.message}")
            pendingHeading = id
        }
    }

    /**
     * 切换字体（CSS font-family）。
     *
     * DOCX 的 HTML 里常带内联 `font-family`，只改 `body` 会被子元素覆盖，
     * 所以注入一条 `* { font-family: X !important }` 的 style 标签。
     * 页面未就绪时挂起，等 `onPageFinished` 再应用（同 `pendingHeading` 的处理）。
     *
     * [faceSrc] 是打包字体的@font-face 源——相对 `loadDataWithBaseURL` 的 baseUrl 的路径。
     * 之前内置字体在 DOCX 上「无效」并不是被 WebView 拦了，而是根本没给它参考=@font-face：
     * 图片走的就是同一套 file:// 相对路径，能显示说明子资源是放行的。
     * 只要有这条声明，CSS 就会自己去把字体捞回来。
     */
    fun setFontFamily(cssFamily: String, faceSrc: String? = null) {
        fontFamily = cssFamily
        fontFaceSrc = faceSrc
        if (!pageReady) return
        runFontJs(cssFamily)
    }

    private fun runFontJs(cssFamily: String) {
        try {
            val face = fontFaceSrc?.let { src ->
                "@font-face{font-family:$cssFamily;src:url('$src');font-display:block;}"
            }.orEmpty()
            val js = "(function(){var s=document.getElementById('readit-font');" +
                "if(!s){s=document.createElement('style');s.id='readit-font';" +
                "(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(face) + "+" +
                JSONObject.quote("*{font-family:$cssFamily !important;}") + ";})();"
            evaluateJavascript(js, null)
        } catch (e: Throwable) {
            ReadItLog.w("docx setFontFamily failed: ${e.message}")
        }
    }

    /**
     * 切换反色（F27）。注入的样式只改文字与底色，不动 img——
     * DOCX 里的照片/图表反成负片会读不出来。
     */
    fun setInverted(value: Boolean) {
        inverted = value
        setBackgroundColor(Inversion.backColor(value))
        if (!pageReady) return
        runInvertJs()
    }

    private fun runInvertJs() {
        try {
            val js = "(function(){var s=document.getElementById('readit-invert');" +
                "if(!s){s=document.createElement('style');s.id='readit-invert';" +
                "(document.head||document.documentElement).appendChild(s);}" +
                "s.textContent=" + JSONObject.quote(Inversion.docxCss(inverted)) + ";})();"
            evaluateJavascript(js, null)
        } catch (e: Throwable) {
            ReadItLog.w("docx setInverted failed: ${e.message}")
        }
    }

    /** 当前滚动位置比例，用于进度兜底（0f~1f） */
    fun scrollRatio(): Float {
        val range = contentHeight - height
        if (range <= 0) return 0f
        return (scrollY.toFloat() / range.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val DEFAULT_FONT_FAMILY = "sans-serif"
    }
}

package com.readit.reader.epub

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.readit.core.display.Inversion
import com.readit.core.util.ReadItLog
import com.readit.eink.BuildConfig
import org.json.JSONObject
import java.io.File

/**
 * EPUB 渲染器（规范 §3.2.2，P2 F03）。
 *
 * 承载本地内置的 epub.js v0.3.88（assets/readit_epub/），单版本锁定，不做运行时下载。
 * 仅在 WebView 能力分级通过（Chromium ≥ 55）时启用；不通过时由
 * [com.readit.epub.EpubTextExtractor] + TxtCanvasView 走降级路径。
 *
 * E-Ink 约定：白底黑字、禁用动画/过渡（见 reader.html）、翻页后由调用方请求整屏刷新。
 */
class EpubWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    interface Callback {
        /** 章节渲染完成；elapsedMs 为 JS 侧自 open 起算的耗时 */
        fun onRendered(href: String, elapsedMs: Long)

        /** 位置变化：cfi 用于进度持久化，page/total 用于页码显示 */
        fun onLocation(cfi: String, href: String, spineIndex: Int, page: Int, total: Int)

        fun onError(message: String)
    }

    var callback: Callback? = null

    @Volatile
    private var pageReady = false
    private var pending: Pending? = null
    private var openStartedAt = 0L

    @Volatile
    private var lastCfi: String = ""

    @Volatile
    private var lastSpineIndex: Int = 0

    private data class Pending(
        val file: File,
        val cfi: String?,
        val fontPercent: Int,
        val fontFamily: String,
        val inverted: Boolean
    )

    /** 反色状态（F27）；reader.html 侧自己维护同名标志，这里只负责投递 */
    private var inverted: Boolean = false

    init {
        configure()
        loadUrl(ASSET_URL)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    @Suppress("DEPRECATION")
    private fun configure() {
        // debug 包开 WebView 远程调试：EPUB 的 JS 出了名地难查（epub.js 静默失败时 logcat 全空），
        // 有 CDP 才能直接问页面真实状态（配合 tools/cdp_eval.py）。release 不受影响。
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            domStorageEnabled = true
            databaseEnabled = false
            defaultTextEncodingName = "utf-8"
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            blockNetworkImage = false
        }
        setBackgroundColor(Inversion.backColor(false))
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = false
        scrollBarStyle = SCROLLBARS_OUTSIDE_OVERLAY
        addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // 保留日志：reader.html 是否加载成功，直接决定后续 open() 会不会被消费
                ReadItLog.i("epub page finished: $url (pending=${pending != null})")
                pageReady = true
                pending?.let { p ->
                    pending = null
                    doOpen(p)
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // 禁止跳出阅读页（外部链接由 epub.js 内部处理，不走导航）
                return true
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                ReadItLog.e("epub resource error: ${request.url} -> ${error.description}")
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                ReadItLog.e("epub resource error: $failingUrl -> $errorCode $description")
            }
        }
        // ------------------------------------------------------------------
        // 控制台桥（P5 真机冒烟补）
        //
        // 之前完全没有接 onConsoleMessage：epub.js 的 JS 异常、脚本加载失败、
        // 「JSZip lib not loaded」这类信息在 logcat 里**一个字都看不到**，
        // 唯一的现象就是白屏。补上之后，WebView 侧任何异常都有据可查。
        // 返回 false，让 Chromium 也照常写一份到系统日志，便于用 chrome://inspect 对照。
        // ------------------------------------------------------------------
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                val line = "epub console[${msg.messageLevel()}] ${msg.message()}" +
                    " @${msg.sourceId()}:${msg.lineNumber()}"
                if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    ReadItLog.e(line)
                } else {
                    ReadItLog.w(line)
                }
                return false
            }
        }
    }

    // ------------------------------------------------------------------ 对外 API

    fun open(
        file: File,
        startCfi: String? = null,
        fontPercent: Int = DEFAULT_FONT_PERCENT,
        fontFamily: String = DEFAULT_FONT_FAMILY,
        inverted: Boolean = false
    ) {
        this.inverted = inverted
        setBackgroundColor(Inversion.backColor(inverted))
        val p = Pending(file, startCfi, fontPercent, fontFamily, inverted)
        if (!pageReady) {
            pending = p
            return
        }
        doOpen(p)
    }

    private fun doOpen(p: Pending) {
        // 单调时钟：墙钟会被 NTP 校时回拨，差值可能为负（与 core/util/Metrics.kt 口径一致）
        openStartedAt = android.os.SystemClock.elapsedRealtime()
        lastCfi = p.cfi.orEmpty()
        val url = "file://" + p.file.absolutePath
        val js = "ReadItEpub.open(${JSONObject.quote(url)}, ${JSONObject.quote(p.cfi.orEmpty())}, " +
            "${p.fontPercent}, ${JSONObject.quote(p.fontFamily)}, ${p.inverted});"
        post { evaluateJavascript(js, null) }
    }

    /** 目录跳转：传 spine 下标（epub.js spine.get 对 number 直接按下标取，最稳） */
    fun displaySpine(index: Int) {
        post { evaluateJavascript("ReadItEpub.display($index);", null) }
    }

    fun displayCfi(cfi: String) {
        post { evaluateJavascript("ReadItEpub.displayCfi(${JSONObject.quote(cfi)});", null) }
    }

    fun next() {
        post { evaluateJavascript("ReadItEpub.next();", null) }
    }

    fun prev() {
        post { evaluateJavascript("ReadItEpub.prev();", null) }
    }

    fun setFontPercent(percent: Int) {
        post { evaluateJavascript("ReadItEpub.setFont($percent);", null) }
    }

    /** 切换字体（CSS font-family）。传 [Fonts.cssFamily] 的结果即可。 */
    fun setFontFamily(cssFamily: String) {
        post {
            evaluateJavascript(
                "ReadItEpub.setFontFamily(${JSONObject.quote(cssFamily)});",
                null
            )
        }
    }

    /**
     * 切换反色（F27）。
     *
     * 网页底色也要一起换，否则 epub.js 分栏之后画布两侧的留白仍是白的，
     * 在墨水屏上会是一道刺眼的白边。
     */
    fun setInverted(value: Boolean) {
        inverted = value
        setBackgroundColor(Inversion.backColor(value))
        // reader.html 尚未载入时 ReadItEpub 还不存在，evaluateJavascript 会抛 ReferenceError
        // 并只留在 console 里 —— 打开过程本来就会带一次 inverted（见 open()），这里不必抢跑。
        if (!pageReady) return
        post { evaluateJavascript("ReadItEpub.setInverted($value);", null) }
    }

    fun currentCfi(): String = lastCfi

    fun currentSpineIndex(): Int = lastSpineIndex

    override fun destroy() {
        runCatching { evaluateJavascript("ReadItEpub.destroy();", null) }
        super.destroy()
    }

    // ------------------------------------------------------------------ JsBridge

    private inner class Bridge {

        @JavascriptInterface
        fun onRendered(href: String, elapsedMs: String) {
            // 注意：兜底与 sinceReaderLoad 都是「自 reader.html 载入起」的累计时长，不是本章渲染耗时。
            // 实测第 3 章打出 total=57008ms，容易被当成单次跳转耗时读走 —— 故把字段名改明白。
            val sinceReaderLoad = android.os.SystemClock.elapsedRealtime() - openStartedAt
            val ms = elapsedMs.toLongOrNull() ?: sinceReaderLoad
            ReadItLog.i("epub rendered: href=$href jsElapsed=${elapsedMs}ms sinceReaderLoad=${sinceReaderLoad}ms")
            post { callback?.onRendered(href, ms) }
        }

        @JavascriptInterface
        fun onLocation(cfi: String, href: String, spineIndex: String, page: String, total: String) {
            lastCfi = cfi
            lastSpineIndex = spineIndex.toIntOrNull() ?: lastSpineIndex
            post {
                callback?.onLocation(
                    cfi,
                    href,
                    spineIndex.toIntOrNull() ?: 0,
                    page.toIntOrNull() ?: 0,
                    total.toIntOrNull() ?: 0
                )
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            ReadItLog.e("epub js error: $message")
            post { callback?.onError(message) }
        }
    }

    companion object {
        const val BRIDGE_NAME = "ReadItBridge"
        const val ASSET_URL = "file:///android_asset/readit_epub/reader.html"
        const val DEFAULT_FONT_PERCENT = 100
        const val DEFAULT_FONT_FAMILY = "sans-serif"
    }
}

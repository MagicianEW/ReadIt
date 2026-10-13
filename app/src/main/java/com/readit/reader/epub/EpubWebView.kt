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
import android.webkit.WebResourceResponse
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

    /**
     * 本次打开的书籍绝对路径 —— 请求白名单里**唯一**允许的沙箱外文件。
     *
     * 为什么需要白名单：`allowFileAccessFromFileURLs` 必须为 true（epub.js 用 file:// XHR 读书），
     * 而章节 XHTML 与 `file:///android_asset/reader.html` 同源，所以书里的 `<script>` 与 reader.html
     * 处在同一个源里 —— 只要没有这层拦截，它就能 XHR 任意 file:// 路径。
     * 本应用持有 MANAGE_EXTERNAL_STORAGE，SD 卡上的书全在射程内。
     * 白名单是唯一可靠的闸门：WebViewClient.shouldInterceptRequest 对**所有帧**（含 iframe 与
     * XHR）的资源请求生效，书内脚本绕不过去。
     */
    @Volatile
    private var allowedBookPath: String? = null

    /**
     * 请求白名单：只放行 assets 与本次打开的那一本书，其余一律拒绝。
     *
     * 放行的三类：
     *  - `android_asset` 下的 reader.html、jszip.min.js、epub.min.js、打包字体；
     *  - 本次打开的书籍文件（`file://` + 绝对路径）；
     *  - `blob:` / `data:` —— epub.js 用 JSZip 把章节与图片转成 blob 注入 iframe，
     *    这两类不经过本回调，但放行以防不同 ROM 把它派发过来。
     */
    private fun allowResource(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        if (url.startsWith("blob:") || url.startsWith("data:")) return true
        if (url.startsWith(ASSET_URL.substringBefore("reader.html"))) return true
        val book = allowedBookPath ?: return false
        // 容忍 %20 等百分号编码差异：URL 里的空格会被编码，路径不会
        return url == "file://$book" || url == "file://${book.replace(" ", "%20")}"
    }

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
            // 书籍本体经 file:// XHR 读入（epub.js 的 ePub(url) 走 XHR），故 file 访问必须开。
            // 真正的闸门不是这两个开关，而是 allowFileAccessFromFileURLs + shouldInterceptRequest 白名单
            // （见 configure() 内的注释与 allowResource）。
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            // 跨源读取必须关：EPUB 里的章节 XHTML 与 reader.html 同源（都是 file://），
            // 开着它书内脚本就能读任意 origin 的内容。安全读取靠下面的请求白名单兜底。
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = true
            databaseEnabled = false
            defaultTextEncodingName = "utf-8"
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            // 断外网：EPUB 是纯本地渲染，联网一律没有正当理由。
            // 恶意书内脚本的真正危害是「读走 SD 卡上的书再 POST 出去」，断网即断掉外传通道。
            // 与 DocxWebView 同一口径。
            blockNetworkImage = true
            blockNetworkLoads = true
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

            /**
             * 资源白名单（安全闸门，见 [allowedBookPath] 的说明）。
             *
             * 命中白名单返回 null = 交给 WebView 正常加载；否则返回空响应而不是抛异常，
             * 让被拦下的请求安静失败（书里的远程图片/外链本来就该加载不出来，
             * 不该让整页渲染失败）。
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url?.toString()
                if (allowResource(url)) return null
                ReadItLog.w("epub request blocked: $url")
                return WebResourceResponse("text/plain", "utf-8", null)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? =
                if (allowResource(url)) null else {
                    ReadItLog.w("epub request blocked: $url")
                    WebResourceResponse("text/plain", "utf-8", null)
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
        // 先把白名单放行到这本书，再发起加载（epub.js 的 XHR 紧随其后）
        allowedBookPath = p.file.absolutePath
        val url = "file://" + p.file.absolutePath
        val js = "ReadItEpub.open(${JSONObject.quote(url)}, ${JSONObject.quote(p.cfi.orEmpty())}, " +
            "${p.fontPercent}, ${JSONObject.quote(p.fontFamily)}, ${p.inverted});"
        post { evaluateJavascript(js, null) }
    }

    /** 目录跳转：传 spine 下标（epub.js spine.get 对 number 直接按下标取，最稳） */
    fun displaySpine(index: Int) {
        post { evaluateJavascript("ReadItEpub.display($index);", null) }
    }

    /**
     * 目录跳转：传目录原始 href（**含** `#fragment`）。
     *
     * epub.js 的 `rendition.display(target)` 对字符串会走 `Contents.locate` 解析，
     * 因此能定位到「同一个 XHTML 内的某个锚点」——只传 spine 下标时，
     * 同一文件承载的多个子章节会全部跳到文件开头。
     */
    fun displayHref(href: String) {
        post {
            evaluateJavascript("ReadItEpub.displayHref(${JSONObject.quote(href)});", null)
        }
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

        /**
         * 存档 CFI 已失效（reader.html 侧 display(cfi) reject 后回落第 0 章时触发）。
         *
         * 必须把 [lastCfi] 清空，否则 `currentCfi()` 会继续返回那个坏值：
         * 1. `ProgressPolicy` 只在 cfi **为空**时才 Skip，空字符串正好触发回落；
         * 2. 下一次 `onLocation` 回调会写入新位置，坏值自然被覆盖。
         * 不清的话坏 CFI 会被 onPause 一路写回磁盘，用户每次打开都是白屏。
         */
        @JavascriptInterface
        fun onCfiInvalid(cfi: String) {
            ReadItLog.w("epub stored CFI invalid, cleared (was='$lastCfi')")
            lastCfi = ""
        }
    }

    companion object {
        const val BRIDGE_NAME = "ReadItBridge"
        const val ASSET_URL = "file:///android_asset/readit_epub/reader.html"
        const val DEFAULT_FONT_PERCENT = 100
        const val DEFAULT_FONT_FAMILY = "sans-serif"
    }
}

package com.readit.web

import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.readit.core.util.ReadItLog

/**
 * EPUB WebView 能力分级判定（规范 §3.2.2，P0-C 门禁 C3）。
 *
 * epub.js 0.3.x 基于 ES2015+，官方目标浏览器 Chrome 55+。
 * 判定流程按 API 分层：
 *  - API 26+ ：WebView.getCurrentWebViewPackage() 读取版本号（主路径）
 *  - API 19-25：UA 字符串解析 + JS 特性探测（无 getCurrentWebViewPackage）
 * 判定通过 → epub.js 完整渲染；不通过 → EPUB 文本抽取 + Canvas 降级渲染。
 */
object WebViewCapability {

    /** epub.js 0.3.x 官方要求的最低 Chromium 版本 */
    const val MIN_CHROMIUM = 55

    enum class Source { PACKAGE, USER_AGENT, JS_PROBE, UNKNOWN }

    enum class Level {
        /** 满足 epub.js 0.3.x 运行要求 */
        FULL_EPUBJS,

        /** 降级：文本抽取 + Canvas 渲染 */
        DEGRADED_TEXT
    }

    data class Report(
        val majorVersion: Int?,
        val source: Source,
        val level: Level,
        val rawVersionName: String? = null,
        val userAgent: String? = null
    ) {
        val supportsEpubJs: Boolean get() = level == Level.FULL_EPUBJS
    }

    /** 纯逻辑分级，供单元测试使用 */
    fun grade(
        packageVersion: String? = null,
        userAgent: String? = null,
        jsProbeVersion: Int? = null
    ): Report {
        packageVersion?.takeIf { it.isNotBlank() }?.let {
            val major = parseMajor(it)
            if (major != null) {
                return Report(major, Source.PACKAGE, levelOf(major), rawVersionName = it, userAgent = userAgent)
            }
        }
        jsProbeVersion?.let {
            return Report(it, Source.JS_PROBE, levelOf(it), userAgent = userAgent)
        }
        parseChrome(userAgent)?.let {
            return Report(it, Source.USER_AGENT, levelOf(it), userAgent = userAgent)
        }
        return Report(null, Source.UNKNOWN, Level.DEGRADED_TEXT, userAgent = userAgent)
    }

    /** 设备探测（需在 UI 线程/已初始化 WebView 环境调用） */
    fun probe(context: Context): Report {
        val ua = runCatching { android.webkit.WebSettings.getDefaultUserAgent(context) }.getOrNull()
            ?: runCatching { WebView(context).settings.userAgentString }.getOrNull()

        val pkgVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                WebView.getCurrentWebViewPackage()?.versionName
            }.getOrNull()
        } else null

        val report = grade(pkgVersion, ua)
        ReadItLog.i(
            "WebView capability: major=${report.majorVersion} src=${report.source} " +
                "level=${report.level} ua=${ua?.take(64)}"
        )
        return report
    }

    /**
     * API 19-25 使用的 JS 特性探测脚本。
     * 结果由 JS 回调注入：window.ReadItCapability.probe(major)
     */
    val JS_PROBE: String = """
        (function(){
          var major = -1;
          try {
            typeof Symbol === 'function' &&
            typeof Promise === 'function' &&
            typeof Proxy === 'function' &&
            Array.prototype.includes &&
            Object.assign;
            var m = navigator.userAgent.match(/Chrome\/(\d+)/);
            if (m) major = parseInt(m[1], 10);
          } catch (e) {}
          return major;
        })()
    """.trimIndent()

    fun levelOf(major: Int): Level =
        if (major >= MIN_CHROMIUM) Level.FULL_EPUBJS else Level.DEGRADED_TEXT

    private fun parseMajor(version: String): Int? {
        val m = Regex("(\\d+)").find(version) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun parseChrome(ua: String?): Int? {
        if (ua.isNullOrBlank()) return null
        val m = Regex("Chrome/(\\d+)").find(ua) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}

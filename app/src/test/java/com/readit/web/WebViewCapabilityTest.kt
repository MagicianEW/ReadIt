package com.readit.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-C 门禁 C3：WebView 能力分级判定逻辑回归。
 */
class WebViewCapabilityTest {

    @Test
    fun `api26 plus package version is primary source`() {
        val r = WebViewCapability.grade(packageVersion = "119.0.6045.163", userAgent = "Mozilla/5.0 Chrome/119")
        assertEquals(119, r.majorVersion)
        assertEquals(WebViewCapability.Source.PACKAGE, r.source)
        assertTrue(r.supportsEpubJs)
    }

    @Test
    fun `old webview degrades to text extraction`() {
        // KY-01L / Android 7.1 出厂 WebView ≈ Chromium 52
        val r = WebViewCapability.grade(packageVersion = "52.0.2743.100")
        assertEquals(52, r.majorVersion)
        assertEquals(WebViewCapability.Level.DEGRADED_TEXT, r.level)
        assertFalse("Chromium 52 不应走 epub.js 完整路径", r.supportsEpubJs)
    }

    @Test
    fun `threshold boundary is inclusive at 55`() {
        assertEquals(
            WebViewCapability.Level.FULL_EPUBJS,
            WebViewCapability.grade(packageVersion = "55.0.2883.91").level
        )
        assertEquals(
            WebViewCapability.Level.DEGRADED_TEXT,
            WebViewCapability.grade(packageVersion = "54.9.2883.91").level
        )
    }

    @Test
    fun `api19 to 25 falls back to user agent parsing`() {
        val r = WebViewCapability.grade(
            packageVersion = null,
            userAgent = "Mozilla/5.0 (Linux; Android 7.1.1; KY-01L) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.98 Mobile Safari/537.36"
        )
        assertEquals(WebViewCapability.Source.USER_AGENT, r.source)
        assertEquals(52, r.majorVersion)
        assertEquals(WebViewCapability.Level.DEGRADED_TEXT, r.level)
    }

    @Test
    fun `js probe overrides ua when present`() {
        val r = WebViewCapability.grade(
            packageVersion = null,
            userAgent = "Mozilla/5.0 ... Chrome/44.0.0.0",
            jsProbeVersion = 30
        )
        assertEquals(WebViewCapability.Source.JS_PROBE, r.source)
        assertEquals(30, r.majorVersion)
    }

    @Test
    fun `unknown capability degrades safely`() {
        val r = WebViewCapability.grade(null, null, null)
        assertEquals(WebViewCapability.Source.UNKNOWN, r.source)
        assertEquals(WebViewCapability.Level.DEGRADED_TEXT, r.level)
    }

    @Test
    fun `modern boox webview passes`() {
        val r = WebViewCapability.grade(
            packageVersion = "103.0.5060.71",
            userAgent = "Mozilla/5.0 Chrome/103.0.5060.71"
        )
        assertTrue(r.supportsEpubJs)
        assertEquals(WebViewCapability.MIN_CHROMIUM, 55)
    }
}

package com.readit.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV URL 规则回归（F13 / R19）。
 *
 * 重点是 [DavUrl.resolve] 对 **路径绝对引用** 的处理：RFC 4918 规定 `D:href` 是 URI 引用，
 * 主流服务端返回的是 `/remote.php/dav/files/u/ReadIt/x.pdf` 这种以 `/` 开头的路径。
 * 早期实现按「相对 base 拼接」处理，会把 base 路径重复一遍，
 * 请求变成 `.../ReadIt/remote.php/dav/files/u/ReadIt/x.pdf` → 必然 404，
 * 且表现为「同步静默什么都不下载」。这里的用例就是钉住这个行为。
 */
class DavUrlTest {

    // ---------------------------------------------------------------- hasScheme / isCleartext

    @Test
    fun `hasScheme recognises http and https`() {
        assertTrue(DavUrl.hasScheme("http://192.168.1.10/webdav"))
        assertTrue(DavUrl.hasScheme("https://dav.example.com/dav"))
        assertTrue(DavUrl.hasScheme("HTTPS://DAV.EXAMPLE.COM/dav"))
        assertFalse(DavUrl.hasScheme("192.168.1.10/webdav"))
        assertFalse(DavUrl.hasScheme("/remote.php/dav"))
    }

    @Test
    fun `isCleartext only true for http`() {
        assertTrue(DavUrl.isCleartext("http://nas.local:5005/webdav"))
        assertFalse(DavUrl.isCleartext("https://dav.example.com/dav"))
        assertFalse(DavUrl.isCleartext(""))
    }

    // ---------------------------------------------------------------- normalizeInput

    @Test
    fun `normalizeInput prepends http when scheme missing`() {
        assertEquals(
            "http://192.168.1.10:5005/webdav",
            DavUrl.normalizeInput("192.168.1.10:5005/webdav")
        )
    }

    @Test
    fun `normalizeInput keeps scheme and strips trailing slash and blanks`() {
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/u",
            DavUrl.normalizeInput("  https://dav.example.com/remote.php/dav/files/u/  ")
        )
    }

    @Test
    fun `normalizeInput returns empty for blank input`() {
        assertEquals("", DavUrl.normalizeInput("   "))
    }

    // ---------------------------------------------------------------- resolve

    @Test
    fun `resolve passes absolute url through`() {
        val href = "https://other.example.com/dav/book.pdf"
        assertEquals(href, DavUrl.resolve("https://dav.example.com/dav/", href))
    }

    @Test
    fun `resolve does not duplicate base path for path absolute href`() {
        val base = "https://dav.example.com/remote.php/dav/files/u/ReadIt/"
        val href = "/remote.php/dav/files/u/ReadIt/book.pdf"
        val got = DavUrl.resolve(base, href)
        assertEquals("https://dav.example.com/remote.php/dav/files/u/ReadIt/book.pdf", got)
        // 旧实现会拼成 base + href.trimStart('/')，这里显式钉住不会再出现
        assertNotEquals(base + href.trimStart('/'), got)
    }

    @Test
    fun `resolve appends relative reference under base directory`() {
        val base = "https://dav.example.com/dav/user/ReadIt/"
        assertEquals(
            "https://dav.example.com/dav/user/ReadIt/book.pdf",
            DavUrl.resolve(base, "book.pdf")
        )
    }

    @Test
    fun `resolve appends relative reference when base has no trailing slash`() {
        assertEquals(
            "https://dav.example.com/dav/ReadIt/book.pdf",
            DavUrl.resolve("https://dav.example.com/dav/ReadIt", "book.pdf")
        )
    }

    @Test
    fun `resolve keeps port for path absolute href`() {
        assertEquals(
            "http://192.168.1.10:5005/dav/ReadIt/book.txt",
            DavUrl.resolve("http://192.168.1.10:5005/dav/ReadIt/", "/dav/ReadIt/book.txt")
        )
    }

    @Test
    fun `resolve returns base for blank href`() {
        val base = "https://dav.example.com/dav/"
        assertEquals(base, DavUrl.resolve(base, "   "))
    }

    // ---------------------------------------------------------------- originOf

    @Test
    fun `originOf drops path and trailing slash`() {
        assertEquals("https://dav.example.com", DavUrl.originOf("https://dav.example.com/a/b/"))
        assertEquals("http://192.168.1.10:5005", DavUrl.originOf("http://192.168.1.10:5005"))
    }

    @Test
    fun `originOf returns null for schemeless input`() {
        assertNull(DavUrl.originOf("dav.example.com/dav"))
        assertNull(DavUrl.originOf(""))
    }

    // ---------------------------------------------------------------- encoding

    @Test
    fun `encodeSegment encodes space as percent20 not plus`() {
        assertEquals("My%20Book.epub", DavUrl.encodeSegment("My Book.epub"))
    }

    @Test
    fun `encodeSegment encodes non ascii name`() {
        val got = DavUrl.encodeSegment("\u4e09\u4f53.epub")
        assertTrue(got.startsWith("%"))
        assertFalse(got.contains("\u4e09\u4f53"))
    }

    @Test
    fun `encodePath keeps slash separators`() {
        assertEquals("a%20b/c%20d", DavUrl.encodePath("a b/c d"))
    }

    // ---------------------------------------------------------------- urlOf assembly

    @Test
    fun `urlOf joins collection dir and file name`() {
        val client = WebDavClient("http://192.168.1.10:5005/webdav", "u", "p", "/ReadIt")
        assertEquals(
            "http://192.168.1.10:5005/webdav/ReadIt/My%20Book.epub",
            client.urlOf("My Book.epub")
        )
    }

    @Test
    fun `urlOf falls back to base when dir empty`() {
        val client = WebDavClient("https://dav.example.com/dav/", "u", "p", "")
        assertEquals("https://dav.example.com/dav/book.txt", client.urlOf("book.txt"))
    }

    @Test
    fun `urlOf tolerates redundant slashes around dir`() {
        val client = WebDavClient("https://dav.example.com/dav/", "u", "p", "/books/")
        assertEquals("https://dav.example.com/dav/books/book.txt", client.urlOf("book.txt"))
    }
}

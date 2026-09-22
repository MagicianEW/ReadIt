package com.readit.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重命名判定回归（书架长按 → 重命名）。
 *
 * 这段判定是纯函数，所以能直接测 —— 它守的恰好是两件最容易出事的事：
 *  - **扩展名不能被改掉**：扩展名决定用哪条解析链路打开（TXT / EPUB / DOCX / PDF），
 *    用户顺手改一下，书就直接打不开了；
 *  - **重名必须挡住**：`renameTo` 在 POSIX 上会静默覆盖同名文件，
 *    放过去就是「另一本书凭空消失」的静默数据丢失。
 *
 * 不需要 Robolectric：`BookRename` 刻意不碰任何 Android API。
 */
class BookRenameTest {

    private val noConflict: (String) -> Boolean = { false }

    private fun decide(current: String, input: String, exists: (String) -> Boolean = noConflict) =
        BookRename.decide(current, input, exists)

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun `keeps the original extension when only the base name changes`() {
        val r = decide("旧书.txt", "新书")
        assertTrue(r.ok)
        assertEquals("新书.txt", r.newFileName)
    }

    @Test
    fun `does not double the extension if the user typed it too`() {
        assertEquals("b.txt", decide("a.txt", "b.txt").newFileName)
        // 用户自己打进来的大小写原样保留（解析链路本来就 `extension.lowercase()`，不靠文件名大小写）
        assertEquals("b.TXT", decide("a.txt", "b.TXT").newFileName)
    }

    @Test
    fun `works for books without an extension`() {
        assertEquals("x", decide("README", "x").newFileName)
    }

    @Test
    fun `leading dot is not treated as an extension`() {
        assertEquals("y", decide(".txt", "y").newFileName)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("y.txt", decide("x.txt", "  y  ").newFileName)
    }

    @Test
    fun `preserves non-ascii names`() {
        assertEquals("《剑啸山河》.txt", decide("x.txt", "《剑啸山河》").newFileName)
    }

    @Test
    fun `multi-dot name only splits the last extension`() {
        assertEquals("v2.txt", decide("v1", "v2.txt").newFileName)
        assertEquals("我的书.v2.txt", decide("a.txt", "我的书.v2").newFileName)
    }

    @Test
    fun `splitExt returns base and dotted extension`() {
        assertEquals("a" to ".txt", BookRename.splitExt("a.txt"))
        assertEquals("README" to "", BookRename.splitExt("README"))
        assertEquals(".txt" to "", BookRename.splitExt(".txt"))
    }

    // ---------------------------------------------------------------- 拒绝路径

    @Test
    fun `rejects empty and whitespace-only input`() {
        assertEquals(BookRename.Reason.EMPTY, decide("a.txt", "").reason)
        assertEquals(BookRename.Reason.EMPTY, decide("a.txt", "   ").reason)
        assertEquals(BookRename.Reason.EMPTY, decide("a.txt", "\t\n").reason)
    }

    @Test
    fun `rejects path separators and control characters`() {
        assertEquals(BookRename.Reason.ILLEGAL_CHAR, decide("a.txt", "../逃逸").reason)
        assertEquals(BookRename.Reason.ILLEGAL_CHAR, decide("a.txt", "子目录/书").reason)
        assertEquals(BookRename.Reason.ILLEGAL_CHAR, decide("a.txt", "子目录\\书").reason)
        assertEquals(BookRename.Reason.ILLEGAL_CHAR, decide("a.txt", "带\u0000空字节").reason)
        // 换行/制表符会把设备端日志与 shell 语句打乱
        assertEquals(BookRename.Reason.ILLEGAL_CHAR, decide("a.txt", "两\n行").reason)
    }

    @Test
    fun `same name is reported as unchanged rather than an error`() {
        val r = decide("a.txt", "a")
        assertEquals(BookRename.Reason.UNCHANGED, r.reason)
        assertFalse(r.ok)
        assertNull(r.newFileName)
    }

    @Test
    fun `refuses a name that is already taken`() {
        val r = decide("a.txt", "b", exists = { it == "b.txt" })
        assertEquals(BookRename.Reason.EXISTS, r.reason)
    }

    @Test
    fun `existence probe receives the candidate with extension`() {
        var probed: String? = null
        decide("a.txt", "b", exists = { probed = it; false })
        assertEquals("查重必须带扩展名，否则 b.txt 与 b.pdf 会互相放行", "b.txt", probed)
    }

    // ---------------------------------------------------------------- 长度（按 UTF-8 字节算）

    @Test
    fun `accepts a name exactly at the byte limit`() {
        // 196 个 ASCII + ".txt" = 200 字节
        val base = "a".repeat(196)
        assertTrue(decide("x.txt", base).ok)
    }

    @Test
    fun `rejects a name past the byte limit even if the char count looks small`() {
        // 中文 3 字节/字：67 字 = 201 字节，按字符数看完全「不长」
        val base = "中".repeat(67)
        assertEquals(BookRename.Reason.TOO_LONG, decide("x.txt", base).reason)
        assertTrue("64 字 = 192 字节，应当放行", decide("x.txt", "中".repeat(64)).ok)
    }
}

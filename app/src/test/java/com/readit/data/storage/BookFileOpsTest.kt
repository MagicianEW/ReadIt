package com.readit.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readit.data.ProgressStore
import com.readit.data.ReadingPosition
import com.readit.data.prefs.ReadItPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 删除 / 重命名书籍的落盘回归。
 *
 * 守两条容易静默出错的纪律（两条都不报错，只让用户的数据悄悄消失）：
 *
 *  1. **重命名必须把按书记忆一起搬走。** `bookId` 就是文件名（`ReaderActivity: bookId = file.name`），
 *     只改文件不搬记忆 → 重命名之后进度归零、编码回落到自动检测。
 *  2. **删除必须「先删文件、删成功了才清记忆」。** 反过来遇到只读目录就会变成
 *     「书还在，但读到哪、什么编码全忘了」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BookFileOpsTest {

    private lateinit var ctx: Context
    private lateinit var dir: File
    private lateinit var prefs: ReadItPrefs

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        dir = StorageManager.booksDir(ctx)
        dir.listFiles()?.forEach { it.deleteRecursively() }
        prefs = ReadItPrefs.get(ctx)
    }

    private fun makeBook(name: String, bytes: Int = 128): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes) { 3 }) }

    /** 造一套「按书记忆」：进度 + 手动编码 + 强制导入标记 —— 这三样都挂在文件名上 */
    private fun seedState(bookId: String, offset: Int) {
        ProgressStore.save(ctx, bookId, ReadingPosition(charOffset = offset, chapterIndex = 2))
        prefs.setCharsetFor(bookId, "GBK")
        prefs.markForceImport(bookId)
    }

    // ---------------------------------------------------------------- 重命名

    @Test
    fun `rename moves the file and carries progress, charset and force-import over`() {
        val old = makeBook("rn1-old.txt")
        seedState("rn1-old.txt", offset = 4242)

        val dest = StorageManager.renameBook(ctx, old, "rn1-新书.txt")

        assertNotNull(dest)
        assertEquals("rn1-新书.txt", dest!!.name)
        assertTrue(dest.exists())
        assertFalse("旧文件必须已经不在了", File(dir, "rn1-old.txt").exists())

        assertEquals(4242, ProgressStore.load(ctx, "rn1-新书.txt")?.charOffset)
        assertNull("旧书名的进度必须清掉，否则改回来会读到过期位置", ProgressStore.load(ctx, "rn1-old.txt"))
        assertEquals("GBK", prefs.charsetFor("rn1-新书.txt"))
        assertNull(prefs.charsetFor("rn1-old.txt"))
        assertTrue("强制导入标记也要跟着走，否则 PDF 会再被拦一次", prefs.isForceImport("rn1-新书.txt"))

        assertEquals(listOf("rn1-新书.txt"), StorageManager.listBooks(ctx).map { it.name })
    }

    @Test
    fun `rename refuses an existing target and changes nothing at all`() {
        val old = makeBook("rn2-old.txt")
        makeBook("rn2-taken.txt", bytes = 999)
        seedState("rn2-old.txt", offset = 77)

        val dest = StorageManager.renameBook(ctx, old, "rn2-taken.txt")

        assertNull(dest)
        assertTrue("原书必须原地不动", File(dir, "rn2-old.txt").exists())
        assertEquals("被占用的那本不能被覆盖", 999L, File(dir, "rn2-taken.txt").length())
        assertEquals("失败不能动记忆", 77, ProgressStore.load(ctx, "rn2-old.txt")?.charOffset)
    }

    @Test
    fun `rename to the identical name is a no-op that still returns the file`() {
        val old = makeBook("rn3.txt")
        val dest = StorageManager.renameBook(ctx, old, "rn3.txt")
        assertNotNull(dest)
        assertTrue(File(dir, "rn3.txt").exists())
    }

    @Test
    fun `renaming a book that had no saved state still works`() {
        val old = makeBook("rn4-fresh.txt")
        val dest = StorageManager.renameBook(ctx, old, "rn4-新名字.txt")
        assertNotNull(dest)
        assertTrue(File(dir, "rn4-新名字.txt").exists())
        assertNull(ProgressStore.load(ctx, "rn4-新名字.txt"))
    }

    // ---------------------------------------------------------------- 删除

    @Test
    fun `delete removes the file and its per-book state`() {
        val book = makeBook("dl1.txt")
        seedState("dl1.txt", offset = 1234)

        assertTrue(StorageManager.deleteBook(ctx, book))

        assertFalse(File(dir, "dl1.txt").exists())
        assertTrue(StorageManager.listBooks(ctx).isEmpty())
        assertNull(ProgressStore.load(ctx, "dl1.txt"))
        assertNull(prefs.charsetFor("dl1.txt"))
        assertFalse(prefs.isForceImport("dl1.txt"))
    }

    /**
     * 删不掉时**必须保留记忆**。
     *
     * 用一个非空目录冒充「删不掉的文件」：Linux 上 `File.delete()` 对非空目录返回 false，
     * 正好复现「只读目录 / 被占用」这条分支，而不用去改文件权限（Robolectric 里不可靠）。
     */
    @Test
    fun `delete keeps the state when the file cannot be removed`() {
        val stuck = File(dir, "dl2.txt").apply { mkdirs() }
        File(stuck, "child").writeBytes(ByteArray(4))
        seedState("dl2.txt", offset = 888)

        assertFalse("非空目录删不掉，必须如实返回失败", StorageManager.deleteBook(ctx, stuck))

        assertTrue(stuck.exists())
        assertEquals("文件还在，记忆就不能先清掉", 888, ProgressStore.load(ctx, "dl2.txt")?.charOffset)
        assertEquals("GBK", prefs.charsetFor("dl2.txt"))
    }

    @Test
    fun `delete on an already-missing file reports success and clears stale state`() {
        seedState("dl3.txt", offset = 5)
        val ghost = File(dir, "dl3.txt")
        assertFalse(ghost.exists())

        assertTrue("文件本就不在，算删成功（收尾清理）", StorageManager.deleteBook(ctx, ghost))
        assertNull("残留的孤儿记忆要收掉", ProgressStore.load(ctx, "dl3.txt"))
        assertNull(prefs.charsetFor("dl3.txt"))
    }

    @Test
    fun `deleting one book leaves the others untouched`() {
        val a = makeBook("dl4-a.txt")
        makeBook("dl4-b.txt")
        seedState("dl4-a.txt", offset = 1)
        seedState("dl4-b.txt", offset = 2)

        assertTrue(StorageManager.deleteBook(ctx, a))

        assertEquals(listOf("dl4-b.txt"), StorageManager.listBooks(ctx).map { it.name })
        assertEquals(2, ProgressStore.load(ctx, "dl4-b.txt")?.charOffset)
        assertEquals("GBK", prefs.charsetFor("dl4-b.txt"))
    }
}

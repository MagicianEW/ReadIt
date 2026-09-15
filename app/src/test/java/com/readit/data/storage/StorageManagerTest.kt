package com.readit.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * 导入原子性回归（规范 §5.2 / F14）。
 *
 * 守两条：
 *  1. 失败必须回滚，且**不能损坏已有同名书** —— 「先删旧文件再 rename」在 rename 失败时
 *     会让用户同时失去旧书和新书，属静默数据丢失。
 *  2. 临时文件不得被当成书列出来（否则书架上会出现 `.readit_tmp_x.part` 这种条目）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StorageManagerTest {

    private lateinit var ctx: Context
    private lateinit var src: File

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        StorageManager.booksDir(ctx).listFiles()?.forEach { it.delete() }
        src = File(ctx.cacheDir, "src.bin").apply { writeBytes(ByteArray(4096) { 7 }) }
    }

    private fun import(name: String = "a.txt", from: File = src, size: Long = -1L) =
        StorageManager.importAtomic(ctx, from, name, size)

    // ---------------------------------------------------------------- 正常路径

    @Test
    fun `import lands the file and filters temp files out of the shelf`() {
        val dest = import("a.txt")
        assertTrue(dest.exists())
        assertEquals(4096L, dest.length())

        val listed = StorageManager.listBooks(ctx).map { it.name }
        assertEquals(listOf("a.txt"), listed)
        assertTrue("临时文件不得出现在书架", listed.none { it.startsWith(".readit_tmp_") })
    }

    @Test
    fun `re-importing the same name replaces content atomically`() {
        import("a.txt")
        val v2 = File(ctx.cacheDir, "src2.bin").apply { writeBytes(ByteArray(10) { 1 }) }
        import("a.txt", from = v2)
        val dest = File(StorageManager.booksDir(ctx), "a.txt")
        assertEquals(10L, dest.length())
        assertEquals(1, dest.readBytes()[0].toInt())
    }

    // ---------------------------------------------------------------- 失败回滚

    @Test
    fun `size mismatch rolls back and keeps the previous book intact`() {
        import("a.txt")                       // 先有一本旧书，4096B
        val old = File(StorageManager.booksDir(ctx), "a.txt")
        assertEquals(4096L, old.length())

        assertThrows(IOException::class.java) { import("a.txt", size = 999_999L) }

        assertEquals("旧书必须原封不动", 4096L, old.length())
        assertNoTempLeft()
    }

    @Test
    fun `empty source is rejected and leaves nothing behind`() {
        val empty = File(ctx.cacheDir, "empty.bin").apply { writeBytes(ByteArray(0)) }
        assertThrows(IOException::class.java) { import("b.txt", from = empty) }
        assertFalse(File(StorageManager.booksDir(ctx), "b.txt").exists())
        assertNoTempLeft()
    }

    @Test
    fun `missing source is rejected`() {
        assertThrows(IOException::class.java) {
            import("c.txt", from = File(ctx.cacheDir, "does_not_exist.bin"))
        }
        assertNoTempLeft()
    }

    // ---------------------------------------------------------------- 清理

    @Test
    fun `purgeTemp removes leftovers and keeps real books`() {
        import("a.txt")
        File(StorageManager.booksDir(ctx), ".readit_tmp_leftover.part").writeBytes(ByteArray(3))
        StorageManager.purgeTemp(ctx)

        val names = StorageManager.listBooks(ctx).map { it.name }
        assertEquals(listOf("a.txt"), names)
        assertNoTempLeft()
    }

    private fun assertNoTempLeft() {
        val leftovers = StorageManager.booksDir(ctx).listFiles { f -> f.name.startsWith(".readit_tmp_") }
        assertTrue("临时文件未清理: ${leftovers?.map { it.name }}", leftovers.isNullOrEmpty())
    }
}

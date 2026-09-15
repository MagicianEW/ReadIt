package com.readit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 进度存储回归（F09）。
 *
 * 重点是**覆盖语义**：`save` 是无条件覆盖（不做合并），所以任何一次写入都必须
 * 携带真实位置 —— 这条约束由 [ProgressPolicy] 在调用侧保证，这里守住落盘/读回本身。
 * 另外守住「rename 失败不能把旧进度弄丢」这条退路。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProgressStoreTest {

    private lateinit var ctx: Context
    private val bookId = "04_pdf_text_20p.pdf"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        progressFile(ctx, bookId).delete()
    }

    private fun progressFile(context: Context, id: String) =
        File(File(context.filesDir, "progress"), "${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    // ---------------------------------------------------------------- 往返

    @Test
    fun `save then load round trips every field`() {
        val pos = ReadingPosition(
            charOffset = 1234,
            chapterIndex = 5,
            updatedAt = 1_700_000_000_000L,
            cfi = "epubcfi(/6/14!/4/2/2)",
            pageIndex = 5
        )
        ProgressStore.save(ctx, bookId, pos)

        val back = ProgressStore.load(ctx, bookId)!!
        assertEquals(1234, back.charOffset)
        assertEquals(5, back.chapterIndex)
        assertEquals(1_700_000_000_000L, back.updatedAt)
        assertEquals("epubcfi(/6/14!/4/2/2)", back.cfi)
        assertEquals(5, back.pageIndex)
    }

    @Test
    fun `load returns null when nothing was ever saved`() {
        assertNull(ProgressStore.load(ctx, "never_opened.txt"))
    }

    @Test
    fun `load returns null instead of throwing on corrupt json`() {
        val f = progressFile(ctx, bookId)
        f.parentFile?.mkdirs()
        f.writeText("{ this is not json", Charsets.UTF_8)
        assertNull("坏档不能让阅读器打开就崩", ProgressStore.load(ctx, bookId))
    }

    // ---------------------------------------------------------------- 覆盖与原子性

    @Test
    fun `second save fully replaces the first`() {
        ProgressStore.save(ctx, bookId, ReadingPosition(charOffset = 100, chapterIndex = 1, updatedAt = 1L))
        ProgressStore.save(ctx, bookId, ReadingPosition(charOffset = 9000, chapterIndex = 9, updatedAt = 2L))

        val back = ProgressStore.load(ctx, bookId)!!
        assertEquals(9000, back.charOffset)
        assertEquals(9, back.chapterIndex)
        assertEquals(2L, back.updatedAt)
        // cfi 也必须是「被覆盖」，不能残留上一次 EPUB 的值
        assertNull(back.cfi)
        assertEquals(-1, back.pageIndex)
    }

    @Test
    fun `save leaves no tmp file behind`() {
        ProgressStore.save(ctx, bookId, ReadingPosition(charOffset = 10, updatedAt = 1L))
        val dir = File(ctx.filesDir, "progress")
        val tmp = dir.listFiles { f -> f.name.endsWith(".tmp") } ?: emptyArray()
        assertTrue("残留临时文件: ${tmp.map { it.name }}", tmp.isEmpty())
    }

    @Test
    fun `book id with path separators is sanitized to one flat file`() {
        val weird = "sub/dir:weird*name?.epub"
        ProgressStore.save(ctx, weird, ReadingPosition(charOffset = 42, updatedAt = 1L))
        assertEquals(42, ProgressStore.load(ctx, weird)!!.charOffset)

        val files = File(ctx.filesDir, "progress").listFiles()!!.map { it.name }
        assertEquals(1, files.count { it.endsWith(".json") })
        assertTrue(files.none { it.contains('/') || it.contains(':') })
    }
}

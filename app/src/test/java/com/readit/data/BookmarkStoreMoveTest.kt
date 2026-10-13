package com.readit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `BookmarkStore.move` 的落盘行为（Robolectric，需要真实 filesDir）。
 *
 * 守的是一条具体的用户可见后果：重命名一本**没有书签**的书时，旧书名的侧车
 * （`filesDir/bookmarks/<旧名>.json`）原本既不搬家也不删除。而 `ProgressSync`
 * 是按目录枚举侧车的（`listJson(dir)`），于是这条没人认领的空记录会被当成
 * 「另一本书」推上云端，之后每次同步都跟着跑一遍。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BookmarkStoreMoveTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        BookmarkStore.dir(ctx).deleteRecursively()
    }

    private fun sidecars(): List<String> =
        BookmarkStore.dir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    @Test
    fun `empty sidecar is dropped so progress sync cannot resurrect it`() {
        val oldId = "old_book.txt"
        val newId = "new_book.txt"
        BookmarkStore.save(ctx, oldId, BookmarkFile()) // 空书签集，但侧车文件存在

        assertEquals("前置条件：旧侧车确实存在", 1, sidecars().size)

        BookmarkStore.move(ctx, oldId, newId)

        assertTrue("空侧车必须被清掉，否则会被 ProgressSync 当成另一本书推上云", sidecars().isEmpty())
    }

    @Test
    fun `non empty bookmarks still move and old file is removed`() {
        val oldId = "old2.txt"
        val newId = "new2.txt"
        var f = BookmarkFile()
        f = Bookmarks.add(f, Bookmark(charOffset = 42, label = "标记", createdAt = 1L))
        BookmarkStore.save(ctx, oldId, f)

        BookmarkStore.move(ctx, oldId, newId)

        assertEquals("旧侧车应已删除", listOf(BookmarkStore.fileNameFor(newId)), sidecars())
        assertEquals(1, BookmarkStore.load(ctx, newId).items.size)
        assertEquals(42, BookmarkStore.load(ctx, newId).items[0].charOffset)
    }

    @Test
    fun `unreadable sidecar is kept rather than deleted`() {
        // 解析失败的文件虽然也没用，但删掉是不可逆的；保留与 ProgressStore
        // 「move 校验不过就保留旧记录」是同一条纪律。
        val oldId = "corrupt.txt"
        val newId = "corrupt_new.txt"
        val dir = BookmarkStore.dir(ctx)
        dir.mkdirs()
        File(dir, BookmarkStore.fileNameFor(oldId)).writeText("{ this is not json", Charsets.UTF_8)

        BookmarkStore.move(ctx, oldId, newId)

        assertEquals("读不出来的记录必须留着", listOf(BookmarkStore.fileNameFor(oldId)), sidecars())
        assertFalse(File(dir, BookmarkStore.fileNameFor(newId)).exists())
    }

    @Test
    fun `move to same sanitised id is a no op`() {
        val id = "same.txt"
        BookmarkStore.save(ctx, id, BookmarkFile())
        BookmarkStore.move(ctx, id, id)
        // 同一路径直接 return，空侧车保留（没有发生「搬家」这件事，不该顺手删数据）
        assertEquals(1, sidecars().size)
    }
}

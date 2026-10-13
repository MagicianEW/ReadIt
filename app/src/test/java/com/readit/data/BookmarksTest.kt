package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书签纯函数（F25）。
 */
class BookmarksTest {

    private fun bm(offset: Int = 0, cfi: String? = null, page: Int = -1, label: String = "", at: Long = 0L) =
        Bookmark(charOffset = offset, cfi = cfi, pageIndex = page, label = label, createdAt = at)

    @Test
    fun `同一位置加两次只有一条`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(offset = 100, label = "第一次"))
        f = Bookmarks.add(f, bm(offset = 100, label = "第二次"))
        assertEquals(1, f.items.size)
        assertEquals("第二次", f.items[0].label)
    }

    @Test
    fun `不同位置各留一条`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(offset = 100))
        f = Bookmarks.add(f, bm(offset = 200))
        assertEquals(2, f.items.size)
    }

    @Test
    fun `CFI 优先于字符偏移做身份`() {
        // EPUB 两条 CFI 不同、字符偏移都是 0（EPUB 不写偏移）→ 必须算两条
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(cfi = "epubcfi(/6/4!/4/2/2)"))
        f = Bookmarks.add(f, bm(cfi = "epubcfi(/6/8!/4/2/10)"))
        assertEquals(2, f.items.size)

        // 同一条 CFI 重复加 → 仍是一条
        f = Bookmarks.add(f, bm(cfi = "epubcfi(/6/4!/4/2/2)"))
        assertEquals(2, f.items.size)
    }

    @Test
    fun `页码优先于字符偏移做身份`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(page = 7))
        f = Bookmarks.add(f, bm(page = 7))
        assertEquals(1, f.items.size)
        f = Bookmarks.add(f, bm(page = 8))
        assertEquals(2, f.items.size)
    }

    @Test
    fun `CFI 为空串时退化成页码`() {
        // EPUB 路径没 relocated 前 cfi 是空串，不能当成有效 CFI
        assertEquals(Bookmarks.key(null, 3, 0), Bookmarks.key("", 3, 0))
        assertEquals("page:3", Bookmarks.key("", 3, 0))
    }

    @Test
    fun `删除只删指定的一条`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(offset = 100))
        f = Bookmarks.add(f, bm(offset = 200))
        f = Bookmarks.remove(f, Bookmarks.key(null, -1, 100))
        assertEquals(1, f.items.size)
        assertEquals(200, f.items[0].charOffset)
    }

    @Test
    fun `删除不存在的键原样返回同一个对象`() {
        val f = Bookmarks.add(BookmarkFile(), bm(offset = 100))
        val after = Bookmarks.remove(f, "char:999")
        assertTrue("应当直接返回原对象，避免无谓写盘", after === f)
    }

    @Test
    fun `has 能查到当前位置`() {
        val f = Bookmarks.add(BookmarkFile(), bm(offset = 100))
        assertTrue(Bookmarks.has(f, Bookmarks.keyOfPosition(ReadingPosition(charOffset = 100))))
        assertFalse(Bookmarks.has(f, Bookmarks.keyOfPosition(ReadingPosition(charOffset = 101))))
    }

    @Test
    fun `超出上限丢最早加入的一条`() {
        var f = BookmarkFile()
        for (i in 1..(Bookmarks.MAX_PER_BOOK + 5)) {
            f = Bookmarks.add(f, bm(offset = i * 10, at = i.toLong()))
        }
        assertEquals(Bookmarks.MAX_PER_BOOK, f.items.size)
        // 最早加入的 5 条（offset 10..50）应当被丢掉
        assertEquals(60, f.items.first().charOffset)
        assertEquals((Bookmarks.MAX_PER_BOOK + 5) * 10, f.items.last().charOffset)
    }

    @Test
    fun `排序按阅读位置升序`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(offset = 300, at = 3))
        f = Bookmarks.add(f, bm(offset = 100, at = 1))
        f = Bookmarks.add(f, bm(offset = 200, at = 2))
        val sorted = Bookmarks.sorted(f).map { it.charOffset }
        assertEquals(listOf(100, 200, 300), sorted)
    }

    @Test
    fun `排序时页码书签排在字符偏移书签前`() {
        var f = BookmarkFile()
        f = Bookmarks.add(f, bm(offset = 9999))
        f = Bookmarks.add(f, bm(page = 1))
        val sorted = Bookmarks.sorted(f)
        assertEquals(1, sorted.first().pageIndex)
    }

    @Test
    fun `从 ReadingPosition 建书签保留三套坐标`() {
        val pos = ReadingPosition(charOffset = 42, chapterIndex = 3, cfi = "cfi-x", pageIndex = -1, updatedAt = 111)
        val b = Bookmark.fromPosition(pos, "第 3 章", 555)
        assertEquals(42, b.charOffset)
        assertEquals(3, b.chapterIndex)
        assertEquals("cfi-x", b.cfi)
        assertEquals(-1, b.pageIndex)
        assertEquals("第 3 章", b.label)
        assertEquals(555, b.createdAt)
    }

    // ------------------------------------------------------------ 并集合并（多设备）

    @Test
    fun `合并保留两台设备各自的收藏`() {
        val a = Bookmarks.add(BookmarkFile(), bm(offset = 100, at = 1))
        val b = Bookmarks.add(BookmarkFile(), bm(offset = 200, at = 2))
        val m = Bookmarks.merge(a, b)
        assertEquals(2, m.items.size)
        assertEquals(listOf(100, 200), Bookmarks.sorted(m).map { it.charOffset })
    }

    @Test
    fun `合并同位置取较新的那条`() {
        val a = Bookmarks.add(BookmarkFile(), bm(offset = 100, label = "旧的", at = 10))
        val b = Bookmarks.add(BookmarkFile(), bm(offset = 100, label = "新的", at = 20))
        val m = Bookmarks.merge(a, b)
        assertEquals(1, m.items.size)
        assertEquals("新的", m.items[0].label)
    }

    @Test
    fun `合并同位置时间相同时结果稳定`() {
        val a = Bookmarks.add(BookmarkFile(), bm(offset = 100, label = "A", at = 10))
        val b = Bookmarks.add(BookmarkFile(), bm(offset = 100, label = "B", at = 10))
        // 时间相同取后者（>= 覆盖），关键是两次合并结果一致、不产生第二条
        assertEquals(1, Bookmarks.merge(a, b).items.size)
        assertEquals(1, Bookmarks.merge(b, a).items.size)
    }

    @Test
    fun `合并是幂等的`() {
        val a = Bookmarks.add(BookmarkFile(), bm(offset = 100, at = 1))
        val b = Bookmarks.add(BookmarkFile(), bm(offset = 200, at = 2))
        val once = Bookmarks.merge(a, b)
        assertEquals(once.items.size, Bookmarks.merge(once, once).items.size)
    }

    @Test
    fun `合并超过上限时丢最老的`() {
        var a = BookmarkFile()
        var b = BookmarkFile()
        for (i in 1..(Bookmarks.MAX_PER_BOOK)) a = Bookmarks.add(a, bm(offset = i, at = i.toLong()))
        for (i in 1..5) b = Bookmarks.add(b, bm(offset = 10000 + i, at = (1000 + i).toLong()))
        val m = Bookmarks.merge(a, b)
        assertEquals(Bookmarks.MAX_PER_BOOK, m.items.size)
        // 最老的 5 条（at=1..5）被丢掉
        assertFalse(m.items.any { it.charOffset in 1..5 })
    }

    /**
     * 上一条用例的盲区：那里 offset 与 createdAt **同向**递增，所以「按阅读序裁剪」和
     * 「按加入时间裁剪」给出同一个答案，测不出实现用错了排序键。
     *
     * 真实事故场景是**反向**的：A 设备攒了 200 条书签，全在书的后半段（offset 大）、
     * 都是老条目；B 设备刚在**书首**（offset 0）加了 1 条，是最新的。
     * 若先按阅读位置排序再取尾部，B 那条落在首位 → 被裁掉 → 再随合并结果推回云端，
     * 于是该书签在**两台设备与云端同时消失**且无墓碑可恢复。
     * 这条用例把 offset 与 createdAt 反向，正是唯一能钉住排序键的构造。
     */
    @Test
    fun `合并超限时不能按阅读位置裁掉另一台设备刚加的书首书签`() {
        var a = BookmarkFile()
        // 200 条全在书尾（大 offset），createdAt 是老值
        for (i in 1..Bookmarks.MAX_PER_BOOK) {
            a = Bookmarks.add(a, bm(offset = 100_000 + i, at = i.toLong()))
        }
        // B 刚加的一条在书首，createdAt 最新
        val b = Bookmarks.add(BookmarkFile(), bm(offset = 0, at = 9_999_999L))

        val m = Bookmarks.merge(a, b)
        assertEquals(Bookmarks.MAX_PER_BOOK, m.items.size)
        assertTrue(
            "另一台设备刚加的书首书签必须留下",
            m.items.any { it.charOffset == 0 }
        )
        // 被丢的应该是 A 侧最老的那几条
        assertFalse(m.items.any { it.charOffset == 100_001 })
    }

    /**
     * 裁剪键与文档、与 [Bookmarks.add] 保持一致：都按 createdAt 丢最老的。
     * 两条路径曾经各写一份裁剪且排序键不同，这正是上面那个 bug 的来源。
     */
    @Test
    fun `merge 与 add 的裁剪口径一致`() {
        var a = BookmarkFile()
        for (i in 1..Bookmarks.MAX_PER_BOOK) a = Bookmarks.add(a, bm(offset = 100_000 + i, at = i.toLong()))
        val fresh = bm(offset = 50_000, at = 9_999_999L)

        val viaMerge = Bookmarks.merge(a, Bookmarks.add(BookmarkFile(), fresh))
        val viaAdd = Bookmarks.add(a, fresh)
        assertEquals(
            viaAdd.items.map { it.charOffset }.sorted(),
            viaMerge.items.map { it.charOffset }.sorted()
        )
    }
}

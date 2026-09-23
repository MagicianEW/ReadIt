package com.readit.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingStatsTest {

    private fun session(
        id: String = "a.txt",
        ms: Long = 60_000L,
        turns: Int = 10,
        at: Long = 1_700_000_000_000L,
        offset: Int = 0,
        pages: Int = 0
    ) = ReadingStats.Session(id, id, ms, turns, offset, pages, at)

    @Test
    fun `首次会话建立记录`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session())
        val b = f.books.getValue("a.txt")
        assertEquals(1, b.openCount)
        assertEquals(60_000L, b.totalMs)
        assertEquals(10, b.pageTurns)
        assertEquals(1, f.totalOpenCount)
        assertEquals(60_000L, f.totalMs)
        assertEquals(1_700_000_000_000L, f.firstReadAt)
        assertEquals(1_700_000_000_000L, b.lastReadAt)
    }

    @Test
    fun `多次会话累加而不是覆盖`() {
        var f = ReadingStats.apply(ReadingStatsFile(), session(ms = 60_000, turns = 10))
        f = ReadingStats.apply(f, session(ms = 30_000, turns = 5, at = 1_700_000_100_000L))
        assertEquals(2, f.books.getValue("a.txt").openCount)
        assertEquals(90_000L, f.books.getValue("a.txt").totalMs)
        assertEquals(15, f.books.getValue("a.txt").pageTurns)
        assertEquals(90_000L, f.totalMs)
        assertEquals(15, f.totalPageTurns)
        // firstReadAt 只记第一次，lastReadAt 跟最后一次
        assertEquals(1_700_000_000_000L, f.firstReadAt)
        assertEquals(1_700_000_100_000L, f.books.getValue("a.txt").lastReadAt)
    }

    @Test
    fun `过短会话被整条丢掉`() {
        val base = ReadingStatsFile()
        // 返回同一个对象，调用方据此就知道「这次不算数」，不用自己判断
        assertSame(base, ReadingStats.apply(base, session(ms = ReadingStats.MIN_SESSION_MS - 1)))
        val ok = ReadingStats.apply(base, session(ms = ReadingStats.MIN_SESSION_MS))
        assertEquals(1, ok.totalOpenCount)
    }

    @Test
    fun `过短会话不产生书籍条目`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session(ms = 10))
        assertTrue(f.books.isEmpty())
        assertEquals(0, f.totalOpenCount)
    }

    @Test
    fun `负翻页数被夹到 0`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session(turns = -5))
        assertEquals(0, f.books.getValue("a.txt").pageTurns)
        assertEquals(0, f.totalPageTurns)
    }

    @Test
    fun `位置为 0 时不覆盖旧位置`() {
        // 位置 0 是「没读到/不知道」，不是「用户退回到开头」
        var f = ReadingStats.apply(ReadingStatsFile(), session(offset = 5000, pages = 30))
        f = ReadingStats.apply(f, session(offset = 0, pages = 0))
        assertEquals(5000, f.books.getValue("a.txt").charOffset)
        assertEquals(30, f.books.getValue("a.txt").totalPages)
    }

    @Test
    fun `多本书互不干扰`() {
        var f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt", ms = 60_000))
        f = ReadingStats.apply(f, session(id = "b.pdf", ms = 120_000, turns = 3))
        assertEquals(2, f.books.size)
        assertEquals(60_000L, f.books.getValue("a.txt").totalMs)
        assertEquals(120_000L, f.books.getValue("b.pdf").totalMs)
        assertEquals(180_000L, f.totalMs)
    }

    @Test
    fun `删书清掉单本记录但不动全局累计`() {
        var f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt", ms = 60_000))
        f = ReadingStats.apply(f, session(id = "b.txt", ms = 120_000))
        val after = ReadingStats.without(f, "a.txt")
        assertEquals(setOf("b.txt"), after.books.keys)
        // 全局量是「我一共读了多少」，删书不该让它缩水
        assertEquals(2, after.totalOpenCount)
        assertEquals(180_000L, after.totalMs)
    }

    @Test
    fun `删除不存在的书是幂等的`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session())
        assertSame(f, ReadingStats.without(f, "nope.txt"))
    }

    @Test
    fun `重命名把记录搬到新名`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt", ms = 60_000, turns = 6))
        val after = ReadingStats.renamed(f, "a.txt", "b.txt")
        assertTrue(!after.books.containsKey("a.txt"))
        val b = after.books.getValue("b.txt")
        assertEquals(60_000L, b.totalMs)
        assertEquals(6, b.pageTurns)
        assertEquals("b.txt", b.title)
        // 全局量不受重命名影响
        assertEquals(f.totalMs, after.totalMs)
        assertEquals(f.totalOpenCount, after.totalOpenCount)
    }

    @Test
    fun `重命名不存在的书原样返回`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt"))
        assertSame(f, ReadingStats.renamed(f, "zz.txt", "yy.txt"))
    }

    @Test
    fun `同名校验改名是空操作`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session())
        assertSame(f, ReadingStats.renamed(f, "a.txt", "a.txt"))
    }

    @Test
    fun `时长分档`() {
        assertEquals(ReadingStats.Duration.Seconds(0), ReadingStats.duration(0))
        assertEquals(ReadingStats.Duration.Seconds(59), ReadingStats.duration(59_999))
        assertEquals(ReadingStats.Duration.MinutesSeconds(1, 1), ReadingStats.duration(61_000))
        assertEquals(ReadingStats.Duration.MinutesSeconds(59, 59), ReadingStats.duration(3_599_000))
        assertEquals(ReadingStats.Duration.HoursMinutes(1, 0), ReadingStats.duration(3_600_000))
        assertEquals(ReadingStats.Duration.HoursMinutes(2, 30), ReadingStats.duration(9_000_000))
    }

    @Test
    fun `负时长按 0 处理`() {
        assertEquals(ReadingStats.Duration.Seconds(0), ReadingStats.duration(-5_000))
    }

    @Test
    fun `排行榜按时长降序 同长按打开次数再按名字`() {
        var f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt", ms = 60_000))
        f = ReadingStats.apply(f, session(id = "b.txt", ms = 300_000))
        f = ReadingStats.apply(f, session(id = "c.txt", ms = 300_000))
        f = ReadingStats.apply(f, session(id = "c.txt", ms = 1_000, at = 1_700_000_100_000L))
        val top = ReadingStats.topBooks(f, 3)
        assertEquals(listOf("c.txt", "b.txt", "a.txt"), top.map { it.first })
    }

    @Test
    fun `排行榜 limit 为 0 返回空`() {
        val f = ReadingStats.apply(ReadingStatsFile(), session())
        assertTrue(ReadingStats.topBooks(f, 0).isEmpty())
        assertTrue(ReadingStats.topBooks(f, -3).isEmpty())
    }

    @Test
    fun `空标题不回退成空串 保留旧标题`() {
        var f = ReadingStats.apply(ReadingStatsFile(), session())
        f = ReadingStats.apply(f, session(id = "a.txt").copy(title = ""))
        assertEquals("a.txt", f.books.getValue("a.txt").title)
    }

    @Test
    fun `JSON 往返保真`() {
        val gson = com.google.gson.Gson()
        var f = ReadingStats.apply(ReadingStatsFile(), session(id = "a.txt", ms = 60_000, turns = 7))
        f = ReadingStats.apply(f, session(id = "b.txt", ms = 120_000))
        val back = gson.fromJson(gson.toJson(f), ReadingStatsFile::class.java)
        assertEquals(f, back)
    }
}

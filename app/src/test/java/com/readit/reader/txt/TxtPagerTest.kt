package com.readit.reader.txt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F01：懒分页正确性（不丢字、不重复、可反查页码）。
 */
class TxtPagerTest {

    private fun sample(paragraphs: Int = 60, paraLen: Int = 120): String {
        val sb = StringBuilder()
        repeat(paragraphs) { p ->
            val body = buildString {
                repeat(paraLen) { i -> append(('a' + ((p + i) % 26))) }
            }
            sb.append("第").append(p + 1).append("段 ").append(body).append('\n')
        }
        return sb.toString()
    }

    @Test
    fun `paging covers whole text without loss or duplication`() {
        val text = sample()
        val pager = TxtPager(text, charsPerLine = 20, linesPerPage = 10) // capacity = 200

        val rebuilt = StringBuilder()
        var index = 0
        while (true) {
            val page = pager.page(index) ?: break
            assertTrue("页码应与内容对齐", page.start < text.length || index == 0)
            rebuilt.append(page.text)
            index++
            if (index > 100000) break
        }
        println("[PAGER] pages = $index, capacity = ${pager.capacity}, len = ${text.length}")

        assertEquals("拼接所有页必须还原原文", text, rebuilt.toString())
        assertTrue("必须分出多页", index > 1)
    }

    @Test
    fun `page length respects capacity`() {
        val text = sample()
        val pager = TxtPager(text, 20, 10)
        var i = 0
        while (true) {
            val p = pager.page(i) ?: break
            assertTrue(
                "第 $i 页超长: ${p.length} > ${pager.capacity}",
                p.length <= pager.capacity
            )
            i++
        }
    }

    @Test
    fun `page of offset round trips`() {
        val text = sample()
        val pager = TxtPager(text, 20, 10)
        val total = run {
            var n = 0
            while (pager.page(n) != null) n++
            n
        }
        for (i in 0 until total) {
            val p = pager.page(i)!!
            assertEquals("offset ${p.start} 应定位到第 $i 页", i, pager.pageOfOffset(p.start))
            assertEquals("offset ${p.end - 1} 应定位到第 $i 页", i, pager.pageOfOffset(p.end - 1))
        }
        assertEquals("文末应定位到最后一页", total - 1, pager.pageOfOffset(text.length))
    }

    // ------------------------------------------------------------ 冷启动恢复（关键）

    @Test
    fun `page of offset works on a cold pager whose boundary cache holds only the first page`() {
        // 真实场景：冷启动时 TxtPager 刚被 new 出来，starts 里只有 [0]，
        // 渲染层立刻拿存档里的 charOffset 调 pageOfOffset —— 此前这条近路会
        // 直接返回 0，于是「每次都从第 1 页开始读」。
        val text = sample()
        val reference = TxtPager(text, 20, 10)
        val pages = ArrayList<TxtPager.Page>()
        var i = 0
        while (true) {
            pages.add(reference.page(i) ?: break)
            i++
        }
        assertTrue("样本必须能分出多页", pages.size > 5)

        // 每一个页首、页尾都在**全新 pager** 上单独验一次
        for (p in pages) {
            val cold = TxtPager(text, 20, 10)
            assertEquals("页首 ${p.start} 冷查应得第 ${p.index} 页", p.index, cold.pageOfOffset(p.start))
            val cold2 = TxtPager(text, 20, 10)
            assertEquals("页尾 ${p.end - 1} 冷查应得第 ${p.index} 页", p.index, cold2.pageOfOffset(p.end - 1))
        }
    }

    @Test
    fun `cold page of offset is monotonic and covers every page`() {
        val text = sample()
        val reference = TxtPager(text, 20, 10)
        val pages = ArrayList<TxtPager.Page>()
        var i = 0
        while (true) {
            pages.add(reference.page(i) ?: break)
            i++
        }

        val cold = TxtPager(text, 20, 10)
        val seen = HashSet<Int>()
        var prev = -1
        var offset = 0
        while (offset <= text.length) {
            val page = cold.pageOfOffset(offset)
            assertTrue("页码不得回退: offset=$offset -> $page (prev=$prev)", page >= prev)
            seen.add(page)
            prev = page
            offset += 1 // 逐字符步进，确保每一页都被采到（避免步长恰好跳过某页尾边界）
        }
        assertEquals("冷查必须能覆盖每一页", pages.indices.toSet(), seen)
    }

    @Test
    fun `empty and tiny text`() {
        val empty = TxtPager("", 20, 10)
        assertNotNull(empty.page(0))
        assertEquals("", empty.page(0)!!.text)
        assertNull(empty.page(1))

        val tiny = TxtPager("short", 20, 10)
        assertEquals("short", tiny.page(0)!!.text)
        assertEquals(0, tiny.pageOfOffset(3))
    }

    @Test
    fun `single very long line is hard cut`() {
        val text = "x".repeat(1000) // 无换行、无标点
        val pager = TxtPager(text, 20, 10) // capacity 200
        val pages = ArrayList<TxtPager.Page>()
        var i = 0
        while (true) {
            val p = pager.page(i) ?: break
            pages.add(p)
            i++
        }
        assertEquals("硬切应产生 5 页", 5, pages.size)
        assertEquals(1000, pages.sumOf { it.length })
    }

    @Test
    fun `estimated page count is close to real one`() {
        val text = sample()
        val pager = TxtPager(text, 20, 10)
        var real = 0
        while (pager.page(real) != null) real++
        val est = pager.estimatedPageCount()
        println("[PAGER] est = $est, real = $real")
        assertTrue("估算页数应与真实页数同量级（±30%）", est >= real * 0.7 && est <= real * 1.3)
    }

    // ------------------------------------------------------------ 断行点

    @Test
    fun `tab is a valid break point`() {
        // 回归：断行字符表里半角空格原本写了两次，第二次本该是制表符，
        // 于是缩进排版的 txt 永远不在 Tab 处断行，只能硬切在 Tab 串中间。
        val text = ("\t\t\t").repeat(400) // 1200 字符，无换行无句读
        val pager = TxtPager(text, charsPerLine = 20, linesPerPage = 10) // capacity 200
        val p0 = pager.page(0)!!
        assertEquals("第 0 页必须断在 Tab 之后", 200, p0.length)
    }

    @Test
    fun `paragraph break landing exactly on capacity is used`() {
        // end = from + capacity 恰好是一个换行时，原实现用 `nl < end` 把它挡掉了，
        // 于是最自然的断点被跳过，版面白白浪费。
        val capacity = 200
        val head = "a".repeat(capacity - 1)
        val text = head + "\n" + "b".repeat(500)
        val pager = TxtPager(text, charsPerLine = 20, linesPerPage = 10)
        assertEquals(capacity, pager.capacity)
        val p0 = pager.page(0)!!
        assertEquals("应直接用上换行这个断点", capacity, p0.length)
        assertEquals("下一页应从换行之后开始", 'b', pager.page(1)!!.text[0])
    }

    @Test
    fun `no page ever exceeds capacity after the break table change`() {
        val text = "\t".repeat(997) + "。".repeat(997) + "，".repeat(997)
        val pager = TxtPager(text, 20, 10)
        var i = 0
        val joined = StringBuilder()
        while (true) {
            val p = pager.page(i) ?: break
            assertTrue("第 $i 页超长 ${p.length} > ${pager.capacity}", p.length <= pager.capacity)
            assertTrue("分页必须推进", p.length > 0)
            joined.append(p.text)
            i++
        }
        assertEquals("拼接必须还原原文", text, joined.toString())
    }

    @Test
    fun `estimated page count stays within a sane band of the exact one`() {
        // totalPages() 在拖动排版滑条期间改用估算值（否则每格都全书分页），
        // 所以这里守住「估算不能离谱到让用户以为跳页了」。
        val text = sample()
        val pager = TxtPager(text, 20, 10)
        var real = 0
        while (pager.page(real) != null) real++
        val est = pager.estimatedPageCount()
        println("[PAGER] est=$est real=$real")
        assertTrue("估算 $est 与真实 $real 偏差过大", est >= real * 0.7 && est <= real * 1.3)
    }
}

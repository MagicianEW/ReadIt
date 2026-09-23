package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多设备进度合并（F25）。
 *
 * 这些用例就是「两台设备轮流读同一本书」的全部剧本：谁的时间戳新谁赢、
 * 旧设备不许冲掉新进度、位置相同不产生写入。
 */
class ProgressMergeTest {

    private fun pos(offset: Int = 0, cfi: String? = null, page: Int = -1, at: Long = 0L) =
        ReadingPosition(charOffset = offset, cfi = cfi, pageIndex = page, updatedAt = at)

    @Test
    fun `两边都空视为一致`() {
        assertTrue(ProgressMerge.decide(null, null) is ProgressMerge.Decision.Identical)
    }

    @Test
    fun `本地为空直接采用远端`() {
        val r = pos(offset = 500, at = 100)
        val d = ProgressMerge.decide(null, r)
        assertTrue(d is ProgressMerge.Decision.TakeRemote)
        assertEquals(500, (d as ProgressMerge.Decision.TakeRemote).pos.charOffset)
    }

    @Test
    fun `远端为空保留本地`() {
        assertTrue(ProgressMerge.decide(pos(offset = 1, at = 100), null) is ProgressMerge.Decision.KeepLocal)
    }

    @Test
    fun `远端更新则采用远端`() {
        val d = ProgressMerge.decide(pos(offset = 100, at = 1000), pos(offset = 900, at = 2000))
        assertTrue(d is ProgressMerge.Decision.TakeRemote)
        assertEquals(900, (d as ProgressMerge.Decision.TakeRemote).pos.charOffset)
    }

    @Test
    fun `本地更新则保留本地`() {
        val d = ProgressMerge.decide(pos(offset = 900, at = 3000), pos(offset = 100, at = 1000))
        assertTrue(d is ProgressMerge.Decision.KeepLocal)
    }

    @Test
    fun `时间戳打平保留本地`() {
        // 两台设备同一秒各写一次，不能来回覆盖（否则每次同步都有文件在动）
        val d = ProgressMerge.decide(pos(offset = 100, at = 1000), pos(offset = 900, at = 1000))
        assertTrue(d is ProgressMerge.Decision.KeepLocal)
    }

    @Test
    fun `位置相同即视为一致`() {
        // 同一页在不同设备上各开过一次：时间戳不同、位置一样 → 不该触发写入
        val a = pos(offset = 100, cfi = null, page = -1, at = 1000)
        val b = pos(offset = 100, cfi = null, page = -1, at = 9999)
        assertTrue(ProgressMerge.decide(a, b) is ProgressMerge.Decision.Identical)
    }

    @Test
    fun `CFI 相同但时间戳不同也算一致`() {
        val a = ReadingPosition(cfi = "cfi-1", chapterIndex = 2, updatedAt = 1)
        val b = ReadingPosition(cfi = "cfi-1", chapterIndex = 2, updatedAt = 2)
        assertTrue(ProgressMerge.decide(a, b) is ProgressMerge.Decision.Identical)
    }

    @Test
    fun `远端时间戳缺失时不覆盖本地`() {
        // 老版本写的 JSON 没有 updatedAt（=0）：绝不允许它冲掉本地新进度
        val d = ProgressMerge.decide(pos(offset = 900, at = 5000), pos(offset = 100, at = 0))
        assertTrue(d is ProgressMerge.Decision.KeepLocal)
    }

    @Test
    fun `本地时间戳缺失时接受远端`() {
        val d = ProgressMerge.decide(pos(offset = 100, at = 0), pos(offset = 900, at = 5000))
        assertTrue(d is ProgressMerge.Decision.TakeRemote)
    }

    @Test
    fun `两边时间戳都缺失且位置不同时保留本地`() {
        val d = ProgressMerge.decide(pos(offset = 100, at = 0), pos(offset = 900, at = 0))
        assertTrue(d is ProgressMerge.Decision.KeepLocal)
    }

    // ------------------------------------------------------------ JsonFileMerge

    @Test
    fun `JSON 合并_内容相同视为一致`() {
        assertTrue(JsonFileMerge.decide("{}", "{}", 1, 2) is JsonFileMerge.Decision.Identical)
    }

    @Test
    fun `JSON 合并_本地缺失采用远端`() {
        val d = JsonFileMerge.decide(null, "{\"a\":1}", 0, 100)
        assertTrue(d is JsonFileMerge.Decision.TakeRemote)
        assertEquals("{\"a\":1}", (d as JsonFileMerge.Decision.TakeRemote).content)
    }

    @Test
    fun `JSON 合并_远端时间新则采用远端`() {
        assertTrue(JsonFileMerge.decide("{\"a\":1}", "{\"a\":2}", 100, 200) is JsonFileMerge.Decision.TakeRemote)
    }

    @Test
    fun `JSON 合并_远端时间戳缺失不覆盖本地`() {
        assertTrue(JsonFileMerge.decide("{\"a\":1}", "{\"a\":2}", 100, 0) is JsonFileMerge.Decision.KeepLocal)
    }
}

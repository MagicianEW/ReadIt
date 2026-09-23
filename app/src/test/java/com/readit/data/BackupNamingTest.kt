package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 备份命名（F28）。
 *
 * 时区在测试里固定成 UTC：`yyyyMMdd-HHmmss` 是按**本地时区**渲染的，
 * 不定时区的话，跑在 GMT+8 的机器和 GMT 的 CI 上会得到不同字符串，
 * 断言就会时绿时红（而这类失败最容易被当成「实现有问题」白查半天）。
 *
 * 时间点一律用 [utc] 现算，不写死 epoch 常量 —— 手算秒数错一次，
 * 失败信息会指向实现而不是测试。
 */
class BackupNamingTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance(utc, Locale.US)
        c.clear()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun withUtc(block: () -> Unit) {
        val old = TimeZone.getDefault()
        try {
            TimeZone.setDefault(utc)
            block()
        } finally {
            TimeZone.setDefault(old)
        }
    }

    @Test
    fun `时间与文件名互逆`() = withUtc {
        val at = utc(2025, 9, 22, 10, 30)
        val name = BackupNaming.fileName(at)
        assertEquals("readit_backup_20250922-103000.zip", name)
        assertEquals(at, BackupNaming.parse(name))
    }

    @Test
    fun `秒级往返精确无误`() = withUtc {
        for (s in 0 until 10) {
            val at = utc(2026, 1, 5, 23, 59) + s * 1000L
            assertEquals(at, BackupNaming.parse(BackupNaming.fileName(at)))
        }
    }

    @Test
    fun `识别备份文件名`() = withUtc {
        assertTrue(BackupNaming.isBackup("readit_backup_20250922-103000.zip"))
        assertFalse(BackupNaming.isBackup("book.txt"))
        assertFalse(BackupNaming.isBackup("readit_progress_book.txt.json"))
        // 只有前缀没有时间戳不算
        assertFalse(BackupNaming.isBackup("readit_backup_.zip"))
        // 扩展名不对不算
        assertFalse(BackupNaming.isBackup("readit_backup_20250922-103000.json"))
    }

    @Test
    fun `非法时间戳解析为 null`() = withUtc {
        assertNull(BackupNaming.parse("readit_backup_20251340-999999.zip"))
        assertNull(BackupNaming.parse("readit_backup_abcd.zip"))
        assertNull(BackupNaming.parse("book.txt"))
    }

    @Test
    fun `宽松模式不该把越界日期算成合法值`() = withUtc {
        // 2 月 30 日：宽松解析会悄悄滚到 3 月 2 日，用户就会在列表里看到不存在的日期
        assertNull(BackupNaming.parse("readit_backup_20250230-120000.zip"))
    }

    @Test
    fun `按时间倒序且无法解析的排最后`() = withUtc {
        val old = BackupNaming.fileName(utc(2024, 1, 1, 8, 0))
        val mid = BackupNaming.fileName(utc(2025, 6, 15, 12, 0))
        val new = BackupNaming.fileName(utc(2026, 3, 20, 21, 45))
        val broken = "readit_backup_broken.zip"
        val sorted = BackupNaming.sortedDesc(listOf(old, broken, new, mid))
        assertEquals(listOf(new, mid, old, broken), sorted)
    }

    @Test
    fun `乱序输入也能排成时间序`() = withUtc {
        // names 按时间升序生成；倒序排列（最新在前）才是期望结果
        val names = (1..10).map { BackupNaming.fileName(utc(2026, 5, 1, 9, 0) + it * 60_000L) }
        val shuffled = names.reversed().toMutableList()
        assertEquals(names.reversed(), BackupNaming.sortedDesc(shuffled))
        // 同一批名字换个乱序再排，结果必须一致（排序不能依赖输入顺序）
        assertEquals(names.reversed(), BackupNaming.sortedDesc(shuffled.shuffled()))
    }

    @Test
    fun `展示文案在无法解析时回退为原文件名`() {
        assertEquals("readit_backup_broken.zip", BackupNaming.display("readit_backup_broken.zip"))
    }

    @Test
    fun `展示文案可读且带日期`() = withUtc {
        val shown = BackupNaming.display("readit_backup_20250922-103000.zip")
        assertTrue("期望以日期开头，实际=$shown", shown.startsWith("2025-09-22"))
        assertTrue("期望含时间，实际=$shown", shown.contains("10:30"))
    }
}

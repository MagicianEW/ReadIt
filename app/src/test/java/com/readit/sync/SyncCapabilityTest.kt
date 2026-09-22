package com.readit.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §2.3 分级 → 后台同步策略的边界测试（未闭环项 A1）。
 *
 * 重点是把 API 边界钉死：22/23 与 25/26 是两个分界，错一档就会
 * 在 B 级设备上起通知、或在 C 级设备上被系统静默掐掉。
 */
class SyncCapabilityTest {

    // ------------------------------------------------------------ modeOf

    @Test
    fun `api19 与 api20 只支持手动同步`() {
        assertEquals(SyncCapability.Mode.MANUAL_ONLY, SyncCapability.modeOf(19))
        assertEquals(SyncCapability.Mode.MANUAL_ONLY, SyncCapability.modeOf(20))
    }

    @Test
    fun `api21 与 api22 仍只支持手动同步`() {
        // A 级（21-22）在 §2.3 里没有后台同步策略，刻意不调度
        assertEquals(SyncCapability.Mode.MANUAL_ONLY, SyncCapability.modeOf(21))
        assertEquals(SyncCapability.Mode.MANUAL_ONLY, SyncCapability.modeOf(22))
    }

    @Test
    fun `api23 到 api25 走后台降频档`() {
        for (api in 23..25) {
            assertEquals("api=$api", SyncCapability.Mode.BACKGROUND, SyncCapability.modeOf(api))
        }
    }

    @Test
    fun `api26 起转前台服务档`() {
        for (api in intArrayOf(26, 27, 28, 29, 31, 33, 34)) {
            assertEquals("api=$api", SyncCapability.Mode.FOREGROUND, SyncCapability.modeOf(api))
        }
    }

    // ------------------------------------------------------------ intervalHours

    @Test
    fun `手动档间隔为 0`() {
        assertEquals(0, SyncCapability.intervalHours(19))
        assertEquals(0, SyncCapability.intervalHours(22))
    }

    @Test
    fun `B 级间隔最长 —— 降频要求`() {
        for (api in 23..25) {
            assertEquals("api=$api", 24, SyncCapability.intervalHours(api))
        }
        // 降频必须真的比 C/D 档长，否则「降频」二字落空
        assertTrue(SyncCapability.intervalHours(23) > SyncCapability.intervalHours(26))
    }

    @Test
    fun `C 与 D 级间隔为 6 小时`() {
        for (api in intArrayOf(26, 28, 29, 34)) {
            assertEquals("api=$api", 6, SyncCapability.intervalHours(api))
        }
    }

    // ------------------------------------------------------------ shouldSchedule

    @Test
    fun `三个条件齐备才调度`() {
        assertTrue(SyncCapability.shouldSchedule(25, enabled = true, configured = true))
        assertTrue(SyncCapability.shouldSchedule(29, enabled = true, configured = true))
    }

    @Test
    fun `关掉开关就不调度`() {
        assertFalse(SyncCapability.shouldSchedule(25, enabled = false, configured = true))
        assertFalse(SyncCapability.shouldSchedule(29, enabled = false, configured = true))
    }

    @Test
    fun `没配 WebDAV 就不调度`() {
        assertFalse(SyncCapability.shouldSchedule(25, enabled = true, configured = false))
        assertFalse(SyncCapability.shouldSchedule(29, enabled = true, configured = false))
    }

    @Test
    fun `档位不支持时开关打开也不调度`() {
        // 关键回归点：API 19-22 上即便用户开了开关，也必须摘掉任务，
        // 否则 JobScheduler 在 API19/20 上根本不存在，调用即抛。
        assertFalse(SyncCapability.shouldSchedule(19, enabled = true, configured = true))
        assertFalse(SyncCapability.shouldSchedule(22, enabled = true, configured = true))
    }

    @Test
    fun `常量互不冲突且与通知渠道无交叉`() {
        assertTrue(SyncCapability.JOB_ID != SyncCapability.NOTIFICATION_ID)
        assertTrue(SyncCapability.CHANNEL_ID.isNotBlank())
        assertTrue(SyncCapability.FLEX_MS > 0L)
    }
}

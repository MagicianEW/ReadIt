package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页方式的解析与回落规则。
 *
 * 守的是两条：
 *  1. 未知值（配置被手改 / 跨版本读到新枚举）不能崩，回落到默认的点按翻页；
 *  2. **墨水屏一律强制点按翻页** —— 动画在墨水屏上是残影灾难，
 *     连续滚动也不符合这种屏的翻页习惯。
 */
class PageTurnModeTest {

    @Test
    fun `from parses all known keys`() {
        for (m in PageTurnMode.values()) {
            assertEquals(m, PageTurnMode.from(m.key))
        }
    }

    @Test
    fun `from is case insensitive and trims`() {
        assertEquals(PageTurnMode.SCROLL, PageTurnMode.from(" scroll "))
        assertEquals(PageTurnMode.ANIM, PageTurnMode.from("anim"))
    }

    @Test
    fun `from falls back to tap for unknown or null`() {
        assertEquals(PageTurnMode.TAP, PageTurnMode.from(null))
        assertEquals(PageTurnMode.TAP, PageTurnMode.from(""))
        assertEquals(PageTurnMode.TAP, PageTurnMode.from("WHATEVER"))
        assertEquals(PageTurnMode.TAP, PageTurnMode.from("AUTO"))
    }

    @Test
    fun `eink forces tap regardless of preference`() {
        for (m in PageTurnMode.values()) {
            assertEquals(
                "墨水屏上 $m 必须回落点按翻页",
                PageTurnMode.TAP,
                resolvePageTurnMode(m, einkLikely = true)
            )
        }
    }

    @Test
    fun `non eink keeps the preference`() {
        for (m in PageTurnMode.values()) {
            assertEquals(m, resolvePageTurnMode(m, einkLikely = false))
        }
    }

    @Test
    fun `tap stays tap on eink`() {
        assertEquals(PageTurnMode.TAP, resolvePageTurnMode(PageTurnMode.TAP, einkLikely = true))
    }

    @Test
    fun `every mode has a distinct key`() {
        val keys = PageTurnMode.values().map { it.key }
        assertEquals("枚举 key 不能重复", keys.size, keys.toSet().size)
        assertTrue(keys.all { it.isNotBlank() })
    }
}

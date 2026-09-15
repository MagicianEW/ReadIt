package com.readit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键采集器回归（§10 R06「按键学习 + 社区数据库」）。
 */
class KeyProbeTest {

    @Test
    fun `first press of a combination counts as new`() {
        val p = KeyProbe()
        assertTrue(p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true))
        assertEquals(1, p.size)
        assertEquals(1, p.samples()[0].count)
    }

    @Test
    fun `same combination is not new but increments count`() {
        val p = KeyProbe()
        p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true)
        assertFalse(p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true))
        assertEquals(1, p.size)
        assertEquals(2, p.samples()[0].count)
    }

    @Test
    fun `same keyCode with different scanCode is a separate combination`() {
        val p = KeyProbe()
        p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true)
        p.record(92, "KEYCODE_PAGE_UP", 999, "GAMEPAD", isDown = true)
        assertEquals(2, p.size)
    }

    @Test
    fun `key up events are ignored`() {
        val p = KeyProbe()
        assertFalse(p.record(4, "KEYCODE_BACK", 158, "KEYBOARD", isDown = false))
        assertEquals(0, p.size)
    }

    @Test
    fun `clear empties the probe`() {
        val p = KeyProbe()
        p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true)
        p.clear()
        assertEquals(0, p.size)
        assertTrue(p.samples().isEmpty())
    }

    @Test
    fun `samples keep insertion order`() {
        val p = KeyProbe()
        p.record(4, "KEYCODE_BACK", 158, "KEYBOARD", isDown = true)
        p.record(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD", isDown = true)
        assertEquals(listOf("KEYCODE_BACK", "KEYCODE_PAGE_UP"), p.samples().map { it.name })
    }

    // ---------------------------------------------------------------- 逻辑名推导

    @Test
    fun `logical names map the common page keys`() {
        val samples = listOf(
            KeySample(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD"),
            KeySample(93, "KEYCODE_PAGE_DOWN", 109, "KEYBOARD"),
            KeySample(4, "KEYCODE_BACK", 158, "KEYBOARD")
        )
        assertEquals(
            mapOf("PAGE_UP" to "KEYCODE_PAGE_UP", "PAGE_DOWN" to "KEYCODE_PAGE_DOWN", "BACK" to "KEYCODE_BACK"),
            KeyProbe.logicalInputKeys(samples)
        )
    }

    @Test
    fun `unknown keycodes are not guessed into the device library`() {
        // 厂商自定义码不该被猜成翻页键 —— 猜错会写进设备库误导所有人
        val samples = listOf(KeySample(1000, "KEYCODE_UNKNOWN", 0, "OTHER"))
        assertTrue(KeyProbe.logicalInputKeys(samples).isEmpty())
    }

    @Test
    fun `duplicate logical names keep the first keycode`() {
        // 同一台机器上 DPAD_UP 与 PAGE_UP 都在，取先出现的那个
        val samples = listOf(
            KeySample(19, "KEYCODE_DPAD_UP", 103, "DPAD"),
            KeySample(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD")
        )
        assertEquals(mapOf("PAGE_UP" to "KEYCODE_DPAD_UP"), KeyProbe.logicalInputKeys(samples))
    }

    @Test
    fun `empty samples produce empty mapping`() {
        assertTrue(KeyProbe.logicalInputKeys(emptyList()).isEmpty())
    }
}

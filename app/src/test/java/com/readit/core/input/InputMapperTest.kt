package com.readit.core.input

import android.view.KeyEvent
import com.readit.core.input.InputMapper.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F11/F19：触控分区与实体键映射。
 */
class InputMapperTest {

    @Test
    fun `touch thirds map to prev menu next`() {
        val w = 600
        assertEquals(Action.PREV_PAGE, InputMapper.actionForTouch(10f, w))
        assertEquals(Action.PREV_PAGE, InputMapper.actionForTouch(199f, w))
        assertEquals(Action.MENU, InputMapper.actionForTouch(300f, w))
        assertEquals(Action.NEXT_PAGE, InputMapper.actionForTouch(401f, w))
        assertEquals(Action.NEXT_PAGE, InputMapper.actionForTouch(599f, w))
        // 零宽度保护
        assertEquals(Action.MENU, InputMapper.actionForTouch(0f, 0))
    }

    @Test
    fun `default key mapping`() {
        assertEquals(Action.PREV_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_PAGE_UP, emptyMap()))
        assertEquals(Action.NEXT_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_PAGE_DOWN, emptyMap()))
        assertEquals(Action.PREV_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_DPAD_LEFT, emptyMap()))
        assertEquals(Action.NEXT_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, emptyMap()))
        assertEquals(Action.BACK, InputMapper.actionForKey(KeyEvent.KEYCODE_BACK, emptyMap()))
    }

    @Test
    fun `gesture takeover only inside stage and drawer closed`() {
        // 正文舞台内、抽屉关着 -> 接管（三分区语义）
        assertTrue(InputMapper.shouldHandleGesture(stageContainsTouch = true, drawerOpen = false))
        // 舞台之外（底部工具栏 目录/排版/刷新/设置、页码栏）-> 必须还给子 View
        assertFalse(
            "stage 外的点击若被吞掉，工具栏按钮就永远点不动（真机冒烟回归点）",
            InputMapper.shouldHandleGesture(stageContainsTouch = false, drawerOpen = false)
        )
        // 抽屉打开时它覆盖在 stage 之上，条目必须能点
        assertFalse(InputMapper.shouldHandleGesture(stageContainsTouch = true, drawerOpen = true))
        assertFalse(InputMapper.shouldHandleGesture(stageContainsTouch = false, drawerOpen = true))
    }

    @Test
    fun `user learned mapping overrides default`() {
        val user = mapOf(
            KeyEvent.KEYCODE_VOLUME_UP to Action.NEXT_PAGE, // 反转
            KeyEvent.KEYCODE_DPAD_LEFT to Action.NONE
        )
        assertEquals(Action.NEXT_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_VOLUME_UP, user))
        // 映射为 NONE 表示用户显式禁用，应回落到默认
        assertEquals(Action.PREV_PAGE, InputMapper.actionForKey(KeyEvent.KEYCODE_DPAD_LEFT, user))
    }

    @Test
    fun `unknown key returns null so ui can prompt`() {
        assertNull(InputMapper.actionForKey(KeyEvent.KEYCODE_A, emptyMap()))
        assertNull(InputMapper.actionForKey(KeyEvent.KEYCODE_UNKNOWN, emptyMap()))
    }

    @Test
    fun `only whitelisted keys can be learned`() {
        assertTrue(InputMapper.canLearn(KeyEvent.KEYCODE_PAGE_DOWN))
        assertTrue(InputMapper.canLearn(KeyEvent.KEYCODE_VOLUME_UP))
        assertFalse("字母键不应被学习（系统/输入法消费）", InputMapper.canLearn(KeyEvent.KEYCODE_A))
        assertFalse(InputMapper.canLearn(KeyEvent.KEYCODE_HOME))
    }

    @Test
    fun `learn persists only learnable keys`() {
        val persisted = LinkedHashMap<Int, String>()
        assertTrue(
            InputMapper.learn(KeyEvent.KEYCODE_PAGE_UP, Action.MENU) { k, a -> persisted[k] = a }
        )
        assertEquals("menu", persisted[KeyEvent.KEYCODE_PAGE_UP])

        assertFalse(
            InputMapper.learn(KeyEvent.KEYCODE_A, Action.MENU) { k, a -> persisted[k] = a }
        )
        assertEquals("不可学习的按键不应落库", 1, persisted.size)

        assertFalse(InputMapper.learn(0, Action.MENU) { _, _ -> })
    }
}

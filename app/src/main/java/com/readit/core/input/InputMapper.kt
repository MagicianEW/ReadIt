package com.readit.core.input

import android.view.KeyEvent

/**
 * 输入映射（规范 §4.4，F11/F19）。
 *
 * 触控：左侧 1/3 上一页、右侧 1/3 下一页、中间 1/3 菜单；长按选中/菜单。
 * 实体键：DPAD / PAGE_UP|DOWN / VOLUME / BACK，可用户自定义映射。
 * 按键学习向导：拦截到的 KeyCode 写入 readit_prefs；系统消费掉的按键无法学习（返回 null 由 UI 提示）。
 */
object InputMapper {

    enum class Action(val key: String) {
        PREV_PAGE("prev_page"),
        NEXT_PAGE("next_page"),
        MENU("menu"),
        BACK("back"),
        NONE("none");

        companion object {
            fun from(k: String?): Action = values().firstOrNull { it.key == k } ?: NONE
        }
    }

    /** 默认按键映射（可被用户覆盖） */
    val DEFAULT_KEYS: Map<Int, Action> = mapOf(
        KeyEvent.KEYCODE_DPAD_LEFT to Action.PREV_PAGE,
        KeyEvent.KEYCODE_DPAD_UP to Action.PREV_PAGE,
        KeyEvent.KEYCODE_DPAD_RIGHT to Action.NEXT_PAGE,
        KeyEvent.KEYCODE_DPAD_DOWN to Action.NEXT_PAGE,
        KeyEvent.KEYCODE_PAGE_UP to Action.PREV_PAGE,
        KeyEvent.KEYCODE_PAGE_DOWN to Action.NEXT_PAGE,
        KeyEvent.KEYCODE_VOLUME_UP to Action.PREV_PAGE,
        KeyEvent.KEYCODE_VOLUME_DOWN to Action.NEXT_PAGE,
        KeyEvent.KEYCODE_BACK to Action.BACK
    )

    /** 可被学习的按键白名单（其余按键交给系统） */
    val LEARNABLE_KEYS: Set<Int> = setOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_CAMERA,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_ENTER
    )

    /** 触控分区判定：x 为相对 View 的坐标，width 为 View 宽度 */
    fun actionForTouch(x: Float, width: Int): Action {
        if (width <= 0) return Action.MENU
        return when {
            x < width / 3f -> Action.PREV_PAGE
            x > width * 2f / 3f -> Action.NEXT_PAGE
            else -> Action.MENU
        }
    }

    /**
     * 阅读页是否应接管该手势（三分区语义）。
     *
     * 只有「按下点落在正文舞台 stage 之内」且「目录抽屉未打开」时才接管；
     * 其余一律交还给子 View。否则底部工具栏（目录/排版/刷新/设置）、页码栏、
     * 目录抽屉条目都会收不到点击——真机冒烟曾因此出现「所有按钮都点不动」。
     */
    fun shouldHandleGesture(stageContainsTouch: Boolean, drawerOpen: Boolean): Boolean =
        stageContainsTouch && !drawerOpen

    /**
     * 按键 -> 动作。
     * @param userMap 用户学习/自定义映射（优先）；缺失时回落默认；都没有则 null（UI 提示无法识别）
     */
    fun actionForKey(keyCode: Int, userMap: Map<Int, Action>): Action? =
        userMap[keyCode]?.takeIf { it != Action.NONE } ?: DEFAULT_KEYS[keyCode]

    /** 该按键是否可被学习 */
    fun canLearn(keyCode: Int): Boolean = keyCode in LEARNABLE_KEYS

    /**
     * 把学习结果落到 prefs。
     * @return true=学习成功；false=系统已消费/不支持该按键
     */
    fun learn(
        keyCode: Int,
        action: Action,
        onPersist: (Int, String) -> Unit
    ): Boolean {
        if (keyCode <= 0 || !canLearn(keyCode)) return false
        onPersist(keyCode, action.key)
        return true
    }
}

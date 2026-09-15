package com.readit.diag

/**
 * 一次按键采样。
 *
 * @param keyCode  KeyEvent.getKeyCode()
 * @param name     KeyEvent.keyCodeToString() 的结果（如 `KEYCODE_PAGE_UP`），由调用方传入，
 *                 这样本类不依赖 Android，能在 JVM 上跑回归
 * @param scanCode 物理扫描码（同一 keyCode 在不同固件上可能不同）
 * @param source   输入来源（键盘 / 遥控器 / 手柄 / 未知）
 * @param count    该组合被观察到的次数
 */
data class KeySample(
    val keyCode: Int = 0,
    val name: String = "",
    val scanCode: Int = 0,
    val source: String = "",
    val count: Int = 1
)

/**
 * 按键采集器（§10 R06 缓解措施「按键学习 + 社区数据库」的输入侧）。
 *
 * 为什么需要它：未知 E-Ink 设备上「按键码无法识别」是概率中等的风险，
 * 而缓解手段是「按键学习 + 社区数据库」。学习向导只能覆盖用户自己那几台设备，
 * 社区数据库要靠用户把**本机的原始按键码**交回来 —— 这个类就是采集那一层。
 *
 * 只记录 ACTION_DOWN 的首次出现；长按重复（repeatCount > 0）不重复计数，
 * 否则一个「按住翻页」就能刷出几百条噪声。
 */
class KeyProbe {

    private val seen = LinkedHashMap<String, KeySample>()

    /**
     * 记录一次按键。
     *
     * @param isDown event.action == ACTION_DOWN
     * @return true 表示这是一个**新**的 keyCode/scanCode 组合（UI 可据此提示）
     */
    fun record(keyCode: Int, name: String, scanCode: Int, source: String, isDown: Boolean): Boolean {
        if (!isDown) return false
        val key = "$keyCode/$scanCode"
        val old = seen[key]
        seen[key] = old?.copy(count = old.count + 1)
            ?: KeySample(keyCode = keyCode, name = name, scanCode = scanCode, source = source, count = 1)
        return old == null
    }

    fun samples(): List<KeySample> = seen.values.toList()

    val size: Int get() = seen.size

    fun clear() = seen.clear()

    companion object {
        /**
         * 从采样推导 `readit_device_profiles.json` 的 `input_keys` 字段
         * （格式：逻辑名 -> KEYCODE_xxx，见 assets 里的 KY-01L 条目）。
         *
         * 只映射**语义明确**的按键；其余（如厂商自定义码）不猜，
         * 留给社区维护者人工判断 —— 猜错会写进设备库误导所有人。
         */
        val LOGICAL_BY_KEYCODE: Map<String, String> = linkedMapOf(
            "KEYCODE_PAGE_UP" to "PAGE_UP",
            "KEYCODE_PAGE_DOWN" to "PAGE_DOWN",
            "KEYCODE_DPAD_UP" to "PAGE_UP",
            "KEYCODE_DPAD_DOWN" to "PAGE_DOWN",
            "KEYCODE_VOLUME_UP" to "PAGE_UP",
            "KEYCODE_VOLUME_DOWN" to "PAGE_DOWN",
            "KEYCODE_BACK" to "BACK",
            "KEYCODE_MENU" to "MENU",
            "KEYCODE_ESCAPE" to "BACK"
        )

        /** 纯函数：采样 -> 逻辑名到 keyCode 名的映射（去重、稳定顺序） */
        fun logicalInputKeys(samples: List<KeySample>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (s in samples) {
                val logical = LOGICAL_BY_KEYCODE[s.name] ?: continue
                out.putIfAbsent(logical, s.name)
            }
            return out
        }
    }
}

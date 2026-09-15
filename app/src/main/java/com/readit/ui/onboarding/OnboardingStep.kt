package com.readit.ui.onboarding

/**
 * 首次启动引导的步骤机（§8 Phase 4「引导 + 设置 + 备份」）。
 *
 * 抽成纯枚举是为了能在 JVM 上把「首/末步的边界」跑一遍 ——
 * 引导页的经典 bug 就是首步能点「上一步」、末步点「下一步」不收敛。
 */
enum class OnboardingStep(val id: Int) {

    /** 确认性能档位（把 EAL 的自动判定结果摆给用户看，允许手动覆盖） */
    TIER(0),

    /** 选择刷新模式（未适配 E-Ink 设备只能手动全刷） */
    REFRESH(1),

    /** 按键学习（可跳过，设置页随时可回来） */
    INPUT(2),

    /** 导入第一本书 */
    IMPORT(3);

    val isFirst: Boolean get() = id == 0
    val isLast: Boolean get() = id == entries.size - 1

    /** 末步返回 null */
    fun next(): OnboardingStep? = entries.getOrNull(id + 1)

    /** 首步返回 null */
    fun prev(): OnboardingStep? = entries.getOrNull(id - 1)

    /** 进度文案用的「第几步 / 共几步」 */
    val humanIndex: Int get() = id + 1

    companion object {
        val TOTAL: Int get() = entries.size

        fun of(id: Int): OnboardingStep =
            entries.firstOrNull { it.id == id } ?: TIER
    }
}

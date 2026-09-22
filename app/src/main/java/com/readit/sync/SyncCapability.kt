package com.readit.sync

/**
 * §2.3 系统兼容性分级 → 后台同步策略（未闭环项 A1）。
 *
 * 需求原文（§2.3「ReadIt 适配策略」列）只在三档提到后台同步：
 *
 * | 级别 | API | 适配策略原文 |
 * |---|---|---|
 * | B | 23-25 | 后台同步**降频** |
 * | C | 26-28 | **前台服务转换**；WorkManager / JobScheduler 兼容 |
 * | D | 29-34 | 同步**前台化** |
 *
 * S/A 两档（API 19-22）没有后台同步策略，且 `JobScheduler` 本身是 API 21 才有 ——
 * 与其在 21/22 上做一套没人规定的行为，不如一律只留手动同步，边界更清楚。
 *
 * ## 为什么用 JobScheduler 而不是 WorkManager
 * 体积红线（§3.4 / R12）：Universal <20MB、每 ABI 分包 <16MB，当前 v7a 已 14.97MB，
 * 余量仅约 1MB。`androidx.work:work-runtime` 约 +0.5~0.8MB，会显著挤压内置字体的空间；
 * 而 `JobScheduler` 是平台自带（API 21+），**零依赖、零体积**。
 *
 * 本对象刻意不引用任何 Android API —— 纯函数映射，可跑 JVM 单测。
 */
object SyncCapability {

    /** JobScheduler 任务 id（同一 id 重复 schedule 会替换旧任务） */
    const val JOB_ID = 0x5EAD01

    /** 前台服务通知 id */
    const val NOTIFICATION_ID = 0x5EAD02

    /** 通知渠道 id（API 26+ 必需） */
    const val CHANNEL_ID = "readit_sync"

    /** 定时任务最短周期的对齐宽度（15 分钟，JobScheduler 允许的最小抖动窗口） */
    const val FLEX_MS = 15L * 60L * 1000L

    enum class Mode {
        /** API 19-22：没有 §2.3 依据，且 19/20 无 JobScheduler —— 只手动同步 */
        MANUAL_ONLY,

        /** B 级（API 23-25）：静默后台执行，不起通知，靠拉长间隔「降频」 */
        BACKGROUND,

        /** C/D 级（API 26+）：转前台服务，带常驻通知，避免被系统随时掐掉 */
        FOREGROUND
    }

    fun modeOf(apiLevel: Int): Mode = when {
        apiLevel < 23 -> Mode.MANUAL_ONLY
        apiLevel < 26 -> Mode.BACKGROUND
        else -> Mode.FOREGROUND
    }

    /**
     * 自动同步间隔（小时）；[Mode.MANUAL_ONLY] 返回 0 表示不调度。
     *
     * B 级要求「降频」，所以取最长间隔；C/D 级网络与后台能力更好，取 6 小时。
     */
    fun intervalHours(apiLevel: Int): Int = when (modeOf(apiLevel)) {
        Mode.MANUAL_ONLY -> 0
        Mode.BACKGROUND -> 24
        Mode.FOREGROUND -> 6
    }

    /**
     * 是否应该挂上定时任务。
     *
     * 三个条件缺一不可：档位支持调度（非 [Mode.MANUAL_ONLY]）、用户开了自动同步、
     * WebDAV 三项参数已配齐（注：只有 URL 与用户名算必填，见 `ReadItPrefs.webDavConfigured`）。
     */
    fun shouldSchedule(apiLevel: Int, enabled: Boolean, configured: Boolean): Boolean =
        enabled && configured && modeOf(apiLevel) != Mode.MANUAL_ONLY
}

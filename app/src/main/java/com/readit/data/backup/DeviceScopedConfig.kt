package com.readit.data.backup

/**
 * 「设备属性」配置项的恢复策略（2026-09-23 BOSS 拍板方案②）。
 *
 * ## 为什么需要它
 *
 * [ConfigBackup.Snapshot] 里有两类字段混在一起：
 *  - **账号偏好**：字号 / 字体 / 行距 / 边距 / 反色 / 扫描阈值 / WebDAV 地址…… 换设备后照搬是对的；
 *  - **设备属性**：`perfTier`（性能档位）、`refreshMode`（刷新模式）—— 它们描述的是**这台设备的硬件能力**。
 *
 * 真机实测（小米 Civi2 恢复 KY-01L 的云端备份）暴露了后果：
 *
 * ```
 * 恢复前（本机手工设 STANDARD, manual=true）: tier=A53 + MID
 * 恢复后（被备份里的 AUTO 覆盖）          : tier=A33 + HIGH
 * ```
 *
 * 链路是：备份里的档位来自**源设备** → 覆盖到目标设备 → 目标设备改走自动侦测
 * → 该机 SoC 不在设备库里（`SoC unknown, fallback=A33_CLASS`）→ 落到最保守档 `A33`。
 * 而 `A33` 会收窄渲染路径（如 `docxMode=TEXT_ONLY`），等于**无声降级一台本可跑更高档的机器**。
 * 全程不报错、不崩溃，只是画质与能力悄悄变差。
 *
 * ## 策略
 *
 * **只有当目标设备「从未显式设置过」该项时，才接受备份里的值。**
 * 本机一旦显式设过，一律保留本机现值 —— 用户在本机做出的设备级决策，优先级高于配置文件。
 *
 * ## 「显式设置过」的判据 = SharedPreferences 里**存在这个键**
 *
 * 刻意**不用**「值 != AUTO」来判定：用户在设置页主动选「自动」同样是一次显式决策
 * （意思是「让这台机器自己决定」），不该被判成「没设过」而被备份里的具体档位顶掉。
 * 本项目只有三处会写 `perfTier`：引导页、设置页、配置恢复本身 —— 都是用户动作，
 * 所以「键存在」是个干净可靠的信号，不会因为代码里某处无脑回写默认值而误判。
 *
 * 纯函数、无 Android 依赖，便于 JVM 单测覆盖。
 */
object DeviceScopedConfig {

    /** 键名与 [ConfigBackup.Snapshot] 的字段名保持一致，便于日志与排查时对得上 */
    const val KEY_PERF_TIER = "perfTier"
    const val KEY_REFRESH_MODE = "refreshMode"

    /** 属于「设备属性」的配置键：跨设备恢复时可能有害 */
    val KEYS: Set<String> = setOf(KEY_PERF_TIER, KEY_REFRESH_MODE)

    fun isDeviceScoped(key: String): Boolean = key in KEYS

    /**
     * 是否应保留本机现值（即忽略备份里该字段）。
     *
     * @param localExplicitlySet 本机是否已显式设置过该项（键存在于 SharedPreferences）
     * @return true = 保留本机值；false = 可以接受备份里的值
     */
    fun keepLocal(key: String, localExplicitlySet: Boolean): Boolean =
        isDeviceScoped(key) && localExplicitlySet
}

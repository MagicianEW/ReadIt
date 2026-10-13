package com.readit.data.backup

/**
 * 「设备属性」配置项的恢复策略（2026-09-23 BOSS 拍板方案②）。
 *
 * ## 为什么需要它
 *
 * [ConfigBackup.Snapshot] 里有两类字段混在一起：
 *  - **账号偏好**：字号 / 字体 / 行距 / 边距 / 反色 / 扫描阈值 / WebDAV 地址…… 换设备后照搬是对的；
 *  - **设备属性**：`perfTier`（性能档位）、`refreshMode`（刷新模式）、`renderMode`（文字渲染模式）
 *    —— 它们描述的都是**这台设备的硬件特性**，换设备照搬就会出问题。
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
 * ## 「显式设置过」的判据 = 一个**独立标记键**（`user_set_*`），不是值键是否存在
 *
 * 刻意**不用**「值 != AUTO」来判定：用户在设置页主动选「自动」同样是一次显式决策
 * （意思是「让这台机器自己决定」），不该被判成「没设过」而被备份里的具体档位顶掉。
 *
 * 同样**不能**用 `sp.contains(值键)`：本项目写 `perfTier` 的三处里有「配置恢复本身」，
 * 而恢复若走普通 setter 就等于自己把「显式设置过」点亮 —— 于是**恢复一次就把守卫烧掉**，
 * 第二次起从任何设备导入设备属性全被拒。上面那段 Civi2 的实测事故被推迟到第二次发生，
 * 症状一样、根因更隐蔽。故判据与被判据的值键分离：见 `ReadItPrefs.hasExplicitPerfTier`
 * 与 `setPerfTierFromRestore`。
 *
 * 纯函数、无 Android 依赖，便于 JVM 单测覆盖。
 */
object DeviceScopedConfig {

    /** 键名与 [ConfigBackup.Snapshot] 的字段名保持一致，便于日志与排查时对得上 */
    const val KEY_PERF_TIER = "perfTier"
    const val KEY_REFRESH_MODE = "refreshMode"

    /**
     * 文字渲染模式（平滑 / 锐利）同样是设备属性：
     * 墨水屏（1bit 面板）要锐利才不发虚，LCD 要平滑才不难看。
     * 若把墨水屏的「锐利」搬到 LCD 上，文字会有明显锯齿 —— 与档位同类的跨设备问题。
     */
    const val KEY_RENDER_MODE = "renderMode"

    /** 属于「设备属性」的配置键：跨设备恢复时可能有害 */
    val KEYS: Set<String> = setOf(KEY_PERF_TIER, KEY_REFRESH_MODE, KEY_RENDER_MODE)

    fun isDeviceScoped(key: String): Boolean = key in KEYS

    /**
     * 是否应保留本机现值（即忽略备份里该字段）。
     *
     * @param localExplicitlySet 本机是否已显式设置过该项（独立的 `user_set_*` 标记键为 true）
     * @return true = 保留本机值；false = 可以接受备份里的值
     */
    fun keepLocal(key: String, localExplicitlySet: Boolean): Boolean =
        isDeviceScoped(key) && localExplicitlySet
}

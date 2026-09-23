package com.readit.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云端版本化备份的文件命名（F28）。
 *
 * 备份文件名里带时间戳（`readit_backup_20260922-103000.zip`），版本序完全由名字决定 ——
 * 不需要服务端提供排序、也不需要额外的清单文件。因此「名字 → 时间」「时间 → 名字」
 * 这两件事必须严格互逆，否则会出现列表顺序错乱、或恢复时选错版本这类
 * 「看起来正常但结果不对」的问题。这里做成纯函数以便 JVM 单测。
 *
 * 与 [com.readit.sync.VersionedBackup] 的分工：本文件只管命名，不碰 IO。
 */
object BackupNaming {

    const val PREFIX = "readit_backup_"
    const val SUFFIX = ".zip"

    /** 文件名用固定格式 + 固定 Locale：中文/阿拉伯语环境下 `yyyy` 也可能被换历法 */
    private const val FILE_PATTERN = "yyyyMMdd-HHmmss"

    /** 展示用格式，跟随系统 Locale（只影响给人看的字符串） */
    private const val DISPLAY_PATTERN = "yyyy-MM-dd HH:mm"

    fun isBackup(name: String): Boolean =
        name.length > PREFIX.length + SUFFIX.length &&
            name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** 时间 → 备份文件名（**顺序前缀**：时间越晚字典序越大，服务端按名字排序即是时间排序） */
    fun fileName(at: Long): String =
        PREFIX + SimpleDateFormat(FILE_PATTERN, Locale.US).format(Date(at)) + SUFFIX

    /**
     * 备份文件名 → 时间（毫秒）。不是备份、或时间戳部分不合法时返回 null。
     *
     * `setLenient(false)`：宽松模式会把 `20261340-999999` 这样的越界值硬算成某个日期，
     * 用户就会在列表里看到一个「来自不存在时间」的备份，且能点进去。
     */
    fun parse(name: String): Long? {
        if (!isBackup(name)) return null
        val stamp = name.removePrefix(PREFIX).removeSuffix(SUFFIX)
        val fmt = SimpleDateFormat(FILE_PATTERN, Locale.US)
        fmt.isLenient = false
        return try {
            fmt.parse(stamp)?.time
        } catch (e: Exception) {
            null
        }
    }

    /** 按时间倒序（最新在前）；无法解析的名字一律排到最后，不丢 */
    fun sortedDesc(names: List<String>): List<String> =
        names.sortedWith(
            compareByDescending<String> { parse(it) ?: Long.MIN_VALUE }
                .thenByDescending { it }
        )

    /** 展示文案：解析不出就原样返回文件名，至少让用户能看见它、知道它坏了 */
    fun display(name: String): String {
        val at = parse(name) ?: return name
        return SimpleDateFormat(DISPLAY_PATTERN, Locale.getDefault()).format(Date(at))
    }
}

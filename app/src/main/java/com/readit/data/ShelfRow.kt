package com.readit.data

import java.io.File
import java.util.Locale

/**
 * 书架列表的一行（纯逻辑，无 Android 依赖，JVM 可直接测）。
 *
 * 为什么要从「只有一个书名」扩成三段：书架最该回答的问题是「哪本读了一半、哪本还没开始」，
 * 而这些数据项目里**早就有**（[BookMeta] 的字数/章节数、[ProgressStore] 的进度），
 * 之前只在「长按 → 属性」对话框里露过脸。同类阅读器（Kindle / 微信读书 / KOReader）
 * 的书库列表都把进度与体量直接摊在列表上。
 */
data class ShelfRow(
    val file: File,
    /** 已读百分比；null = 算不出来（没扫过元数据，或还没打开过） */
    val percent: Int? = null,
    /** 副标题（字数 · 章节）；空串 = 没有可展示的副标题，整行隐藏 */
    val metaText: String = ""
) {
    /** 是否显示已读条 */
    val hasProgress: Boolean get() = percent != null
}

/**
 * 紧凑数字：**万** 进位。
 *
 * 书架副标题只有一行，480dp 宽的 600×800 兜底屏上放不下「123,456 字」这种长格式
 * （加上章节数会换行）。中文语境对「万」有共识，比「1.2M」自然。
 */
fun formatCompactCount(n: Long): String = when {
    n < 10_000L -> n.toString()
    n < 100_000_000L -> {
        val v = n / 10_000.0
        // 去掉多余的 .0，避免出现「12.0万」这种假精度
        val s = if (v % 1.0 == 0.0) String.format(Locale.ROOT, "%.0f", v)
        else String.format(Locale.ROOT, "%.1f", v)
        "${s}万"
    }
    else -> {
        val v = n / 100_000_000.0
        val s = if (v % 1.0 == 0.0) String.format(Locale.ROOT, "%.0f", v)
        else String.format(Locale.ROOT, "%.1f", v)
        "${s}亿"
    }
}

/**
 * 拼出书架副标题：「12.3万字 · 86 章」。
 *
 * 拿不到的项直接不写（而不是写「—」）：属性对话框要「字段齐全」，用「—」占位；
 * 列表副标题是给人一眼扫的，多一个「—」只会变长。
 */
fun buildShelfMetaText(meta: BookMeta?): String {
    if (meta == null) return ""
    val parts = ArrayList<String>(2)
    if (meta.charCount > 0L) parts.add("${formatCompactCount(meta.charCount)}字")
    if (meta.chapterCount > 0) parts.add("${meta.chapterCount}章")
    return parts.joinToString(" · ")
}

/**
 * 组装书架一行。
 *
 * @param meta 单书元数据（可能为 null —— 还没扫描过）
 * @param pos 当前阅读位置（null = 还没打开过）
 */
fun buildShelfRow(file: File, meta: BookMeta?, pos: ReadingPosition?): ShelfRow {
    // 元数据还没算出来时算不出百分比：分母（总字数 / 总页数）就存在 meta 里。
    val pct = if (meta == null) null else computeProgressPercent(meta, pos)
    return ShelfRow(
        file = file,
        percent = pct,
        metaText = buildShelfMetaText(meta)
    )
}

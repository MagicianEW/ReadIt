package com.readit.data

import com.readit.data.ReadingPosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 单书属性元数据（书架长按 → 属性 对话框用）。
 *
 * 与进度 / 编码记忆一样按**文件名**索引（bookId = 文件名），改名 / 删除时由
 * [com.readit.data.storage.StorageManager] 一并迁移或清理。
 *
 * 加入书库时间（[addedAt]）在本书**首次被扫到**时写入当前时间。老书追溯不到真实加入时间，
 * 会全部落到「第一次运行本功能的那天」——这是已知的不精确之处，已在需求文档登记
 * （「加入书库时间」无法追溯历史，仅能记录首次扫描时刻）。
 */
data class BookMeta(
    /** 加入书库时间，epoch 毫秒；-1 = 未知 */
    val addedAt: Long = -1L,
    /** 总体字数（抽取出的纯文本字符数）；-1 = 未知（如扫描版 PDF、抽取失败） */
    val charCount: Long = -1L,
    /** 章节数；-1 = 未知 / 不适用 */
    val chapterCount: Int = -1,
    /** 页数（主要是 PDF；文本格式为 -1） */
    val pageCount: Int = -1,
    /** 元数据计算完成的时间，epoch 毫秒 */
    val computedAt: Long = 0L,
    /** 文件指纹：`size:lastModified`，用来判断是否需要重算 */
    val signature: String = ""
) {
    /** 是否已算过（至少有签名） */
    fun isComputed(): Boolean = signature.isNotEmpty()
}

/**
 * 属性对话框的可展示数据：所有字段都已格式化为**最终文案**（含「—」占位）。
 * 由 [buildBookMetaView] 从 [BookMeta] + [ReadingPosition] 生成，纯函数、可单测。
 */
data class BookMetaView(
    val progressText: String,
    val charCountText: String,
    val chapterText: String,
    val addedAtText: String
)

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

/**
 * 算阅读进度百分比：
 * - 文本格式（TXT/EPUB/DOCX）：`charOffset / charCount`
 * - PDF：`(pageIndex + 1) / pageCount`
 *
 * 返回 `null` 表示算不出来（没有可依据的分母，或本书从未打开过且拿不到总量）。
 */
fun computeProgressPercent(meta: BookMeta, pos: ReadingPosition?): Int? {
    if (pos == null) return null
    if (meta.charCount > 0L && pos.charOffset > 0) {
        return (pos.charOffset * 100 / meta.charCount).toInt().coerceIn(0, 100)
    }
    if (meta.pageCount > 0 && pos.pageIndex >= 0) {
        return ((pos.pageIndex + 1) * 100 / meta.pageCount).coerceIn(0, 100)
    }
    return null
}

/**
 * 把 [BookMeta] + 当前阅读位置拼成对话框文案。
 *
 * 规则：
 * - 进度：`未开始`（无进度记录）→ 百分比 → `—`（算不出）
 * - 字数 / 章节：有值则带单位，否则 `—`（「如有」语义：拿不到就不强行编一个数）
 * - 加入时间：格式化，未知则 `—`
 */
fun buildBookMetaView(meta: BookMeta, pos: ReadingPosition?): BookMetaView {
    val pct = computeProgressPercent(meta, pos)
    val progressText = when {
        pos == null || (pos.charOffset == 0 && pos.pageIndex < 0) -> "未开始"
        pct == null -> "—"
        else -> "$pct%"
    }
    val charCountText = if (meta.charCount > 0L) {
        String.format(Locale.ROOT, "%,d 字", meta.charCount)
    } else {
        "—"
    }
    val chapterText = if (meta.chapterCount > 0) {
        "${meta.chapterCount} 章"
    } else {
        "—"
    }
    val addedAtText = if (meta.addedAt >= 0L) {
        DATE_FMT.format(Date(meta.addedAt))
    } else {
        "—"
    }
    return BookMetaView(
        progressText = progressText,
        charCountText = charCountText,
        chapterText = chapterText,
        addedAtText = addedAtText
    )
}

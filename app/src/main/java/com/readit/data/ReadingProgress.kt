package com.readit.data

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * 阅读进度（F09）。
 *
 * TXT / EPUB 文本降级：字符偏移 + 章节序号（章节由 ChapterDetector / EPUB TOC 给出）。
 * EPUB 完整渲染：CFI + spine 下标（章节内位置精度优于字符偏移）。
 */
data class ReadingPosition(
    val charOffset: Int = 0,
    val chapterIndex: Int = 0,
    val updatedAt: Long = 0L,
    /** EPUB CFI，仅 epub.js 路径写入 */
    val cfi: String? = null,
    /** PDF 页码（0-based），仅 PDF 路径写入；-1 表示未使用 */
    val pageIndex: Int = -1
)

object ProgressStore {

    private const val DIR = "progress"
    private val gson = Gson()

    fun save(context: Context, bookId: String, pos: ReadingPosition) {
        val f = file(context, bookId)
        try {
            f.parentFile?.mkdirs()
            val json = gson.toJson(pos)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            // 不再先 delete 目标文件：renameTo 返回 false 时旧实现会把「旧进度已删、
            // 新进度未落盘」变成一个纯数据丢失。POSIX rename 本身就会覆盖，
            // 真失败了还有一次直接覆写的退路。
            if (!tmp.renameTo(f)) {
                ReadItLog.w("progress rename failed, falling back to direct write")
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ReadItLog.w("progress save failed: ${e.message}")
        }
    }

    fun load(context: Context, bookId: String): ReadingPosition? {
        val f = file(context, bookId)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), ReadingPosition::class.java)
        } catch (e: Exception) {
            ReadItLog.w("progress load failed: ${e.message}")
            null
        }
    }

    private fun file(context: Context, bookId: String): File {
        val safe = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, DIR), "$safe.json")
    }

    /**
     * 删掉某本书的进度记录（书被删除时必须调用）。
     *
     * 不删会留下**永久孤儿记录**：`bookId` 是文件名，同名新书进来会直接继承旧进度。
     */
    fun delete(context: Context, bookId: String) {
        runCatching { file(context, bookId).delete() }
            .onFailure { ReadItLog.w("progress delete failed: ${it.message}") }
    }

    /**
     * 重命名书籍时把进度从 [fromId] 搬到 [toId]。
     *
     * 两条纪律：
     *  1. **确认新记录读得回来才删旧的** —— 否则一次失败的写入就把进度彻底弄丢（静默数据丢失）。
     *  2. 两个书名 sanitize 后落到同一个文件时直接返回：`file()` 会把非
     *     `[A-Za-z0-9._-]` 全换成 `_`，两个不同的中文书名完全可能撞到同一条记录。
     */
    fun move(context: Context, fromId: String, toId: String) {
        val src = file(context, fromId)
        if (src.absolutePath == file(context, toId).absolutePath) return
        if (!src.exists()) return

        val pos = load(context, fromId) ?: return
        save(context, toId, pos)
        if (load(context, toId) == null) {
            ReadItLog.w("progress move unverified, keeping old record: $fromId -> $toId")
            return
        }
        src.delete()
    }
}

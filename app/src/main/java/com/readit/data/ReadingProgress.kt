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
}

package com.readit.data

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * 单书属性侧车存储（[BookMeta]）。
 *
 * 与 [ProgressStore] 同范式：目录 `bookmeta/`，文件名按 bookId（= 文件名）sanitize 后拼接。
 * 改名 / 删除时由 [com.readit.data.storage.StorageManager] 调用 [move] / [delete]，
 * 与进度 / 编码 / 书签 / 统计的迁移纪律一致——**确认新记录读得回来才删旧的**。
 */
object BookMetaStore {

    private const val DIR = "bookmeta"
    private val gson = Gson()

    fun save(context: Context, bookId: String, meta: BookMeta) {
        val f = file(context, bookId)
        try {
            f.parentFile?.mkdirs()
            val json = gson.toJson(meta)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                ReadItLog.w("bookmeta rename failed, fallback to direct write")
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ReadItLog.w("bookmeta save failed: ${e.message}")
        }
    }

    fun load(context: Context, bookId: String): BookMeta? {
        val f = file(context, bookId)
        if (!f.exists()) return null
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), BookMeta::class.java)
        } catch (e: Exception) {
            ReadItLog.w("bookmeta load failed: ${e.message}")
            null
        }
    }

    fun delete(context: Context, bookId: String) {
        runCatching { file(context, bookId).delete() }
            .onFailure { ReadItLog.w("bookmeta delete failed: ${it.message}") }
    }

    /**
     * 重命名书籍时把元数据从 [fromId] 搬到 [toId]。
     *
     * 与 [ProgressStore.move] 同纪律：两文件名 sanitize 后落到同一条记录时直接返回；
     * 确认新记录读得回来才删旧的，否则保留旧记录（宁可重复也别丢「加入书库时间」）。
     */
    fun move(context: Context, fromId: String, toId: String) {
        val src = file(context, fromId)
        if (src.absolutePath == file(context, toId).absolutePath) return
        if (!src.exists()) return

        val meta = load(context, fromId) ?: return
        save(context, toId, meta)
        if (load(context, toId) == null) {
            ReadItLog.w("bookmeta move unverified, keeping old record: $fromId -> $toId")
            return
        }
        src.delete()
    }

    /** 目录，供后台扫描层枚举 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 文件指纹：size:lastModified。内容或 mtime 变了就视为需要重算 */
    fun signatureOf(file: File): String = "${file.length()}:${file.lastModified()}"

    fun fileNameFor(bookId: String): String = "${safe(bookId)}.json"

    private fun safe(bookId: String): String = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun file(context: Context, bookId: String): File =
        File(dir(context), fileNameFor(bookId))
}

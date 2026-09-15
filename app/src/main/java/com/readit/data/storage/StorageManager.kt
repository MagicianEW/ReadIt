package com.readit.data.storage

import android.content.Context
import com.readit.core.util.ReadItLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 存储与导入（规范 §5.2，F14）。
 *
 * 导入原子性：先写临时文件 -> 校验（体积 + 可选校验和）-> 重命名入库；失败回滚删除临时文件。
 */
object StorageManager {

    private const val BOOKS_DIR = "books"
    private const val TMP_PREFIX = ".readit_tmp_"
    private const val BUFFER = 64 * 1024

    fun booksDir(context: Context): File {
        val dir = File(context.filesDir, BOOKS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            ReadItLog.w("booksDir mkdirs failed: ${dir.absolutePath}")
        }
        return dir
    }

    fun tmpDir(context: Context): File {
        val dir = File(context.filesDir, "tmp")
        if (!dir.exists() && !dir.mkdirs()) {
            ReadItLog.w("tmpDir mkdirs failed: ${dir.absolutePath}")
        }
        return dir
    }

    /**
     * 原子导入：把 [source] 复制进书籍目录，命名为 [destName]。
     *
     * @param expectedSize 期望体积（>0 时参与校验）
     * @return 入库后的目标文件
     * @throws IOException 校验失败或 IO 错误（临时文件已回滚）
     */
    @Throws(IOException::class)
    fun importAtomic(
        context: Context,
        source: File,
        destName: String,
        expectedSize: Long = -1L
    ): File {
        if (!source.exists()) throw IOException("source not exist: ${source.absolutePath}")
        val dir = booksDir(context)
        val dest = File(dir, destName)
        val tmp = File(dir, TMP_PREFIX + destName + ".part")

        try {
            var copied = 0L
            FileInputStream(source).use { fis ->
                FileOutputStream(tmp).use { fos ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val n = fis.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        copied += n
                    }
                    fos.flush()
                    @Suppress("DEPRECATION")
                    fos.fd.sync()
                }
            }

            if (expectedSize > 0 && copied != expectedSize) {
                throw IOException("size mismatch: expected=$expectedSize actual=$copied")
            }
            if (copied == 0L) throw IOException("empty file")

            // 覆盖已有书时**不要先删**：POSIX rename 本身就会覆盖，而「先删旧文件、
            // 再 rename」一旦 rename 失败，用户就同时失去旧书和新书（同一类静默数据丢失）。
            // 删除只作为 rename 不被允许覆盖时的退路。
            if (!tmp.renameTo(dest)) {
                if (dest.exists() && dest.delete()) {
                    if (!tmp.renameTo(dest)) {
                        throw IOException("rename failed: ${tmp.name} -> ${dest.name}")
                    }
                } else {
                    throw IOException("rename failed: ${tmp.name} -> ${dest.name}")
                }
            }
            ReadItLog.i("importAtomic ok: ${dest.name} (${copied}B)")
            return dest
        } catch (e: Exception) {
            rollback(tmp)
            if (e is IOException) throw e
            throw IOException(e)
        }
    }

    /** 回滚：删除临时文件；若目标已写入一半也一并清理 */
    private fun rollback(tmp: File) {
        runCatching { if (tmp.exists()) tmp.delete() }
            .onFailure { ReadItLog.w("rollback failed: ${tmp.absolutePath}") }
    }

    fun listBooks(context: Context): List<File> {
        val dir = booksDir(context)
        return (dir.listFiles { f -> f.isFile && !f.name.startsWith(TMP_PREFIX) }?.toList()
            ?: emptyList()).sortedBy { it.name }
    }

    /** 清理遗留的临时文件（启动或导入失败后调用） */
    fun purgeTemp(context: Context) {
        val files = booksDir(context).listFiles { f -> f.name.startsWith(TMP_PREFIX) }
        files?.forEach { runCatching { it.delete() } }
    }
}

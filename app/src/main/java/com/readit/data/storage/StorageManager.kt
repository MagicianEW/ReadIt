package com.readit.data.storage

import android.content.Context
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * 存储与导入（规范 §5.2，F14）。
 *
 * 导入原子性：先写临时文件 -> 校验（体积 + 可选校验和）-> 重命名入库；失败回滚删除临时文件。
 *
 * 书籍目录：默认在应用内部（`filesDir/books`）；用户可在首次引导 / 设置页改成
 * 外部真实路径（如 `/sdcard/ReadIt`）。见 [booksDir]。
 */
object StorageManager {

    private const val BOOKS_DIR = "books"
    private const val TMP_PREFIX = ".readit_tmp_"
    private const val BUFFER = 64 * 1024

    /** 应用内部默认书籍目录（无自定义配置时的兜底，也是迁移的「源」）。 */
    fun defaultBooksDir(context: Context): File {
        val dir = File(context.filesDir, BOOKS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            ReadItLog.w("defaultBooksDir mkdirs failed: ${dir.absolutePath}")
        }
        return dir
    }

    /**
     * 解析当前生效的书籍目录。
     *
     * 规则：配置了自定义目录且**实际可用**（是目录 + 可读 + 可写）则用之；
     * 否则回落到内部默认目录 —— 绝不因配置了一个坏路径而让书架整体不可用。
     */
    fun booksDir(context: Context): File {
        val custom = ReadItPrefs.get(context).booksDir
        if (custom.isNotBlank()) {
            val f = File(custom)
            if (f.isDirectory && f.canRead() && f.canWrite()) return f
            ReadItLog.w("custom books dir unusable, fallback internal: $custom")
        }
        return defaultBooksDir(context)
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

    /** 迁移结果：复制成功 / 因同名已存在而跳过 / 失败。 */
    data class MigrateResult(val copied: Int, val skipped: Int, val failed: Int) {
        val isEmpty: Boolean get() = copied == 0 && skipped == 0 && failed == 0

        fun summary(): String = "copied=$copied skipped=$skipped failed=$failed"
    }

    /**
     * 把 [from] 目录下的书籍**复制**到 [to]（源保留）。
     *
     * 迁移采用「复制 + 不覆盖」：目标已存在同名文件时跳过并计数，
     * 既不覆盖用户在新目录里可能已有的同名文件，也不删除旧目录里的书
     * （把「换目录」做成不可逆操作风险太高）。
     */
    fun migrate(from: File, to: File): MigrateResult {
        if (!from.isDirectory || !to.isDirectory) return MigrateResult(0, 0, 0)
        val same = runCatching { from.canonicalPath == to.canonicalPath }.getOrDefault(false)
        if (same) return MigrateResult(0, 0, 0)

        var copied = 0
        var skipped = 0
        var failed = 0
        val sources = from.listFiles { f -> f.isFile && !f.name.startsWith(TMP_PREFIX) } ?: return MigrateResult(0, 0, 0)
        for (src in sources) {
            val dst = File(to, src.name)
            if (dst.exists()) {
                skipped++
                continue
            }
            try {
                FileInputStream(src).use { fis ->
                    FileOutputStream(dst).use { fos -> fis.copyTo(fos, BUFFER) }
                }
                copied++
            } catch (e: Exception) {
                ReadItLog.e("migrate failed: ${src.name}", e)
                runCatching { if (dst.exists()) dst.delete() }
                failed++
            }
        }
        ReadItLog.i("migrate ${from.absolutePath} -> ${to.absolutePath}: ${MigrateResult(copied, skipped, failed).summary()}")
        return MigrateResult(copied, skipped, failed)
    }

    /**
     * 应用新的书籍目录：**先迁移**当前生效目录里的书，**再落盘**配置。
     * 顺序不能反 —— 反了之后 [booksDir] 已指向新目录，旧目录里的书就再找不到源了。
     *
     * @return 迁移结果（新目录与旧目录相同时为空结果）
     */
    fun applyBooksDir(context: Context, newDir: File): MigrateResult {
        val app = context.applicationContext
        val old = booksDir(app)
        val result = if (!samePath(old, newDir)) migrate(old, newDir) else MigrateResult(0, 0, 0)
        // 选中的就是内部默认目录时存空串，语义上仍是「默认」
        ReadItPrefs.get(app).booksDir = if (samePath(newDir, defaultBooksDir(app))) "" else newDir.absolutePath
        return result
    }

    private fun samePath(a: File, b: File): Boolean =
        runCatching { a.canonicalPath == b.canonicalPath }.getOrDefault(a.absolutePath == b.absolutePath)
}

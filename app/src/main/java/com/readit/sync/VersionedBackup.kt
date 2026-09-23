package com.readit.sync

import android.content.Context
import com.readit.core.util.ReadItLog
import com.readit.data.BackupNaming
import com.readit.data.BookmarkStore
import com.readit.data.ProgressStore
import com.readit.data.backup.ConfigBackup
import com.readit.data.prefs.ReadItPrefs
import com.readit.sync.webdav.WebDavClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 云端版本化备份（F28）。
 *
 * ## 与既有「配置导出/导入」的分工
 *
 * [ConfigBackup]（设置页的导出/导入）走 SAF，**只有配置**，用户自己选文件、自己保管。
 * 本类走 WebDAV，**一次打包配置 + 统计 + 进度 + 书签**，按时间戳留多份历史版本，
 * 用来回答「我三天前那台设备上的排版和进度还能找回来吗」。
 *
 * ## 为什么复用书库集合 + 文件名前缀
 *
 * 原因与 [ProgressSync] 完全相同（[WebDavClient] 没有 MKCOL 能力）。备份文件是 `.zip`，
 * 同样落进 `BookType.UNKNOWN`，不会被书籍同步当成书。
 *
 * ## 恢复口径（有意保守）
 *
 * 恢复是「**按备份覆盖同名记录**」，**不会删除**备份里没有的进度 / 书签 / 统计条目。
 * 完整镜像式恢复（先清空再写入）在真机上风险太高：一旦下错版本，用户这几天的
 * 阅读进度会被静默抹掉，而且没有回滚点。宁可少恢复，不可多删除。
 */
class VersionedBackup(
    private val client: WebDavClient,
    private val appVersion: String
) {

    companion object {
        private const val ENTRY_CONFIG = "config.json"
        private const val ENTRY_STATS = "stats/stats.json"
        private const val DIR_PROGRESS = "progress"
        private const val DIR_BOOKMARKS = "bookmarks"

        /** 恢复时单条目解压上限，防 zip bomb 把 1GB 设备的内存吃穿 */
        private const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    }

    /** 远端的一份备份 */
    data class Entry(
        val name: String,
        val href: String,
        val at: Long,
        val size: Long,
        val display: String
    )

    data class RestoreResult(
        val configRestored: Boolean,
        val statsRestored: Boolean,
        val progressRestored: Int,
        val bookmarksRestored: Int
    ) {
        fun summary(): String =
            "配置 ${if (configRestored) "✓" else "—"} · 统计 ${if (statsRestored) "✓" else "—"}" +
                " · 进度 $progressRestored · 书签 $bookmarksRestored"
    }

    /**
     * 打一份备份并上传。**必须在工作线程调用**。
     *
     * @return 远端文件名
     */
    fun backup(context: Context, at: Long = System.currentTimeMillis()): String {
        val name = BackupNaming.fileName(at)
        val zip = File(cacheDir(context), name)
        try {
            zip.outputStream().buffered().use { out ->
                ZipOutputStream(out).use { zos -> writeEntries(context, zos, at) }
            }
            ReadItLog.i("backup packed: $name (${zip.length()}B)")
            client.upload(name, zip, remoteEtag = null, createOnly = false)
            return name
        } finally {
            zip.delete()
        }
    }

    /** 列出远端所有备份，最新在前 */
    fun list(): List<Entry> {
        val backups = client.list().filter { !it.isDirectory && BackupNaming.isBackup(it.displayName) }
        return BackupNaming.sortedDesc(backups.map { it.displayName })
            .mapNotNull { n -> backups.firstOrNull { it.displayName == n } }
            .map { r ->
                Entry(
                    name = r.displayName,
                    href = r.href,
                    at = BackupNaming.parse(r.displayName) ?: 0L,
                    size = r.contentLength,
                    display = BackupNaming.display(r.displayName)
                )
            }
    }

    /** 下载并恢复一份备份。**必须在工作线程调用**。 */
    fun restore(context: Context, entry: Entry): RestoreResult {
        val zip = File(cacheDir(context), "restore_${entry.name}")
        var configRestored = false
        var statsRestored = false
        var progress = 0
        var bookmarks = 0
        try {
            client.download(entry.href, zip)
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    // 注意：continue 必须直接出现在 while 体内，不能包在 run/let 之类的
                    // 内联 lambda 里（Kotlin 会报 "break continue in inline lambdas" 是实验特性）。
                    val safeName = safeEntryName(e.name)
                    if (safeName == null) {
                        ReadItLog.w("backup restore: 跳过可疑条目 ${e.name}")
                        zis.closeEntry()
                        continue
                    }
                    val bytes = readEntry(zis)
                    when {
                        safeName == ENTRY_CONFIG -> {
                            configRestored = restoreConfig(context, bytes)
                        }
                        safeName == ENTRY_STATS -> {
                            statsRestored = writeFile(
                                File(context.filesDir, "stats/stats.json"), bytes
                            )
                        }
                        safeName.startsWith("$DIR_PROGRESS/") -> {
                            val dest = File(ProgressStore.dir(context), safeName.removePrefix("$DIR_PROGRESS/"))
                            if (writeFile(dest, bytes)) progress++
                        }
                        safeName.startsWith("$DIR_BOOKMARKS/") -> {
                            val dest = File(BookmarkStore.dir(context), safeName.removePrefix("$DIR_BOOKMARKS/"))
                            if (writeFile(dest, bytes)) bookmarks++
                        }
                        else -> ReadItLog.w("backup restore: 未知条目 $safeName，跳过")
                    }
                    zis.closeEntry()
                }
            }
        } finally {
            zip.delete()
        }
        val result = RestoreResult(configRestored, statsRestored, progress, bookmarks)
        ReadItLog.i("backup restored: ${entry.name} -> ${result.summary()}")
        return result
    }

    // ------------------------------------------------------------------ 内部

    private fun cacheDir(context: Context): File =
        File(context.cacheDir, "readit_backup").apply { mkdirs() }

    private fun writeEntries(context: Context, zos: ZipOutputStream, at: Long) {
        // 配置：复用 ConfigBackup，保证与设置页导出的 JSON 完全同构（能互相导入）
        val prefs = ReadItPrefs.get(context)
        val snapshot = ConfigBackup.snapshot(prefs, appVersion).copy(exportedAt = at)
        putEntry(zos, ENTRY_CONFIG, ConfigBackup.toJson(snapshot).toByteArray(Charsets.UTF_8))

        // 统计
        val statsFile = File(context.filesDir, ENTRY_STATS)
        if (statsFile.isFile) putEntry(zos, ENTRY_STATS, statsFile.readBytes())

        // 进度 / 书签：一本书一个小 JSON
        putDir(zos, ProgressStore.dir(context), DIR_PROGRESS)
        putDir(zos, BookmarkStore.dir(context), DIR_BOOKMARKS)
    }

    private fun putDir(zos: ZipOutputStream, dir: File, prefix: String) {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        for (f in files) {
            putEntry(zos, "$prefix/${f.name}", f.readBytes())
        }
    }

    private fun putEntry(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }

    /**
     * 条目名安全化。
     *
     * Zip Slip：zip 里的 `../../databases/x` 这类名字如果直接拼到解压目录，
     * 会把文件写到应用私有目录之外。这里只接受**单层相对路径**，
     * 拒绝绝对路径、`..`、以及反斜杠（Windows 风格的路径分隔符在部分解压器上也被当分隔符）。
     */
    private fun safeEntryName(raw: String): String? {
        if (raw.isBlank()) return null
        if (raw.startsWith("/") || raw.contains('\\')) return null
        val parts = raw.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        // 允许 config.json（1 段）与 <dir>/<file>（2 段），再深不接受
        if (parts.size !in 1..2) return null
        return parts.joinToString("/")
    }

    private fun readEntry(zis: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n <= 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) {
                throw IllegalStateException("备份条目过大（>${MAX_ENTRY_BYTES}B），已中止")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun restoreConfig(context: Context, bytes: ByteArray): Boolean {
        val json = bytes.toString(Charsets.UTF_8)
        return try {
            val snapshot = ConfigBackup.parse(json)
            ConfigBackup.restore(ReadItPrefs.get(context), snapshot)
            true
        } catch (e: Exception) {
            // 备份里的配置坏了不该让整份恢复失败：进度与书签仍然值得救回来
            ReadItLog.w("backup restore: 配置项恢复失败（其余项继续）: ${e.message}")
            false
        }
    }

    private fun writeFile(dest: File, bytes: ByteArray): Boolean = try {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
        true
    } catch (e: Exception) {
        ReadItLog.w("backup restore: 写入 ${dest.name} 失败: ${e.message}")
        false
    }
}

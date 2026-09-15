package com.readit.sync

import android.content.Context
import com.readit.core.util.ReadItLog
import com.readit.data.storage.StorageManager
import com.readit.sync.webdav.WebDavClient
import java.io.File

/**
 * WebDAV 双向同步（F13 / R19）。
 *
 * 语义（与 [SyncDecider] 一一对应）：
 *  远端独有  → 下载
 *  本地独有  → 上传（`If-None-Match: *`，绝不覆盖远端已有文件）
 *  仅一侧变  → 变化的一侧流向另一侧
 *  两侧都变  → 记冲突，本次不碰任何一侧
 *
 * 设计取舍：单线程串行、逐文件上报进度。E-Ink 设备带宽/存储都紧，
 * 并发下载只会拉高内存峰值与失败重试的复杂度，没有收益。
 *
 * 所有逐文件失败都被收敛进 [SyncReport.failed]，不中断整轮同步 ——
 * 一本坏书不该让整次同步作废。
 */
class WebDavSync(
    private val client: WebDavClient
) {

    data class SyncReport(
        val downloaded: List<String>,
        val uploaded: List<String>,
        val upToDate: List<String>,
        val conflicts: List<String>,
        val failed: List<Pair<String, String>>,
        val elapsedMs: Long
    ) {
        val isEmpty: Boolean
            get() = downloaded.isEmpty() && uploaded.isEmpty() && conflicts.isEmpty() && failed.isEmpty()

        /** 一行式摘要，直接可用于 Toast / 设置页 summary */
        fun summary(): String = buildString {
            append("下载 ${downloaded.size}")
            append(" · 上传 ${uploaded.size}")
            append(" · 已最新 ${upToDate.size}")
            if (conflicts.isNotEmpty()) append(" · 冲突 ${conflicts.size}")
            if (failed.isNotEmpty()) append(" · 失败 ${failed.size}")
            append(" · ${elapsedMs}ms")
        }
    }

    /**
     * 执行一轮同步。**必须在工作线程调用**（内部是阻塞 IO）。
     */
    fun sync(context: Context, onProgress: ((String) -> Unit)? = null): SyncReport {
        val started = System.currentTimeMillis()
        val booksDir = StorageManager.booksDir(context)
        val downloaded = ArrayList<String>()
        val uploaded = ArrayList<String>()
        val upToDate = ArrayList<String>()
        val conflicts = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()

        val remoteBooks = try {
            client.list().filter { it.isBook }
        } catch (e: Exception) {
            ReadItLog.e("webdav list failed", e)
            return SyncReport(
                emptyList(), emptyList(), emptyList(), emptyList(),
                listOf("*" to (e.message ?: "list failed")),
                System.currentTimeMillis() - started
            )
        }

        val localMap = StorageManager.listBooks(context).associateBy { it.name }
        val remoteNames = remoteBooks.map { it.displayName }.toSet()

        for (r in remoteBooks) {
            val name = r.displayName
            val local = localMap[name]
            onProgress?.invoke(name)
            try {
                if (local == null) {
                    downloadTo(context, r, File(booksDir, name))
                    downloaded.add(name)
                    continue
                }
                val record = SyncStateStore.get(context, name)
                val decision = SyncDecider.both(
                    RemoteMeta(r.etag, r.lastModified, r.contentLength),
                    LocalMeta(local.length(), local.lastModified()),
                    record
                )
                when (decision.action) {
                    SyncAction.DOWNLOAD_UPDATE -> {
                        downloadTo(context, r, local)
                        downloaded.add(name)
                    }
                    SyncAction.UPLOAD_UPDATE -> {
                        uploadLocal(context, name, local, record)
                        uploaded.add(name)
                    }
                    SyncAction.UP_TO_DATE -> {
                        // 刷新基线：服务端可能补上了 ETag，顺手记下来
                        persist(context, name, local, r.href, r.etag ?: record?.etag, r.lastModified ?: record?.lastModified, r.contentLength)
                        upToDate.add(name)
                    }
                    SyncAction.CONFLICT -> {
                        ReadItLog.w("sync conflict on $name: ${decision.reason}")
                        conflicts.add(name)
                    }
                    else -> failed.add(name to "unexpected action ${decision.action}")
                }
            } catch (e: Exception) {
                ReadItLog.w("sync file failed: $name -> ${e.message}")
                failed.add(name to (e.message ?: "error"))
            }
        }

        // 本地上行：远端没有的书
        for ((name, local) in localMap) {
            if (remoteNames.contains(name)) continue
            onProgress?.invoke(name)
            try {
                uploadLocal(context, name, local, null)
                uploaded.add(name)
            } catch (_: WebDavClient.ConflictException) {
                // 远端在我们列举之后被别人建了同名文件 → 保守记冲突，不覆盖
                conflicts.add(name)
            } catch (e: Exception) {
                ReadItLog.w("sync upload failed: $name -> ${e.message}")
                failed.add(name to (e.message ?: "error"))
            }
        }

        val report = SyncReport(
            downloaded, uploaded, upToDate, conflicts, failed,
            System.currentTimeMillis() - started
        )
        ReadItLog.i("webdav sync done: ${report.summary()}")
        return report
    }

    // ------------------------------------------------------------------ 内部

    private fun downloadTo(context: Context, r: WebDavClient.DavResource, dest: File) {
        val record = SyncStateStore.get(context, dest.name)
        val result = client.download(r.href, dest, record?.etag, record?.lastModified)
        if (result.notModified) return
        persist(context, dest.name, dest, r.href, result.etag, result.lastModified, result.size)
    }

    private fun uploadLocal(context: Context, name: String, local: File, record: SyncRecord?) {
        val result = client.upload(name, local, record?.etag)
        persist(context, name, local, client.urlOf(name), result.etag, result.lastModified, local.length())
    }

    private fun persist(
        context: Context,
        name: String,
        local: File,
        href: String,
        etag: String?,
        lastModified: String?,
        remoteSize: Long
    ) {
        SyncStateStore.put(
            context,
            name,
            SyncRecord(
                href = href,
                etag = etag,
                lastModified = lastModified,
                remoteSize = remoteSize,
                localSize = local.length(),
                localLastModified = local.lastModified(),
                version = 1L,
                syncedAt = System.currentTimeMillis()
            )
        )
    }
}

package com.readit.sync

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import com.readit.data.BookmarkFile
import com.readit.data.BookmarkStore
import com.readit.data.Bookmarks
import com.readit.data.ProgressMerge
import com.readit.data.ProgressStore
import com.readit.data.ReadingPosition
import com.readit.sync.webdav.WebDavClient
import java.io.File

/**
 * 进度与书签的跨设备同步（F25）。
 *
 * ## 为什么放在书库集合里、而不是新建一个 WebDAV 集合
 *
 * 新建集合需要 MKCOL，而 [WebDavClient] 只做 PROPFIND/GET/PUT/HEAD（没有建目录能力），
 * 且用户配置的 WebDAV 账号未必有建集合的权限。书库集合是**已经存在且已知可写**的，
 * 所以把侧车文件直接放进去，用文件名前缀区分：
 *
 *  - `readit_progress_<safe>.json`   —— 阅读进度（一本书一个）
 *  - `readit_bookmarks_<safe>.json`  —— 书签（一本书一个）
 *
 * 为什么不会污染书籍同步：[WebDavClient.DavResource.isBook] 按扩展名判定，
 * `.json` 落到 `BookType.UNKNOWN`，书籍同步循环会直接跳过它们；
 * 反过来本类也只认前缀，不会去碰 `.txt/.epub/...`。
 *
 * ## 合并口径
 *
 *  - **进度**：JSON 里自带权威时间戳 `updatedAt`，[ProgressMerge] 按它判胜负
 *    （新者胜、打平保留本地、远端缺时间戳一律不覆盖本地）。
 *  - **书签**：增量收藏，用 [Bookmarks.merge] 做并集，两台设备各加的都能留下。
 *    代价是删除不跨设备传播（不做墓碑），见 [Bookmarks.merge] 的说明。
 *
 * 全程逐文件收敛失败，任一文件出错都只进 [Report.failed]，不中断整轮。
 */
class ProgressSync(private val client: WebDavClient) {

    companion object {
        const val PREFIX_PROGRESS = "readit_progress_"
        const val PREFIX_BOOKMARKS = "readit_bookmarks_"

        private const val SUFFIX = ".json"
        private val gson = Gson()
    }

    data class Report(
        val pulledProgress: List<String>,
        val pushedProgress: List<String>,
        val mergedBookmarks: List<String>,
        val failed: List<Pair<String, String>>,
        val elapsedMs: Long
    ) {
        val isEmpty: Boolean
            get() = pulledProgress.isEmpty() && pushedProgress.isEmpty() &&
                mergedBookmarks.isEmpty() && failed.isEmpty()

        fun summary(): String = buildString {
            append("进度 ↓${pulledProgress.size} ↑${pushedProgress.size}")
            append(" · 书签 ${mergedBookmarks.size}")
            if (failed.isNotEmpty()) append(" · 失败 ${failed.size}")
            append(" · ${elapsedMs}ms")
        }
    }

    /** 必须在工作线程调用（内部是阻塞 IO） */
    fun sync(context: Context): Report {
        val started = System.currentTimeMillis()
        val pulled = ArrayList<String>()
        val pushed = ArrayList<String>()
        val mergedBm = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()

        val remote = try {
            client.list()
        } catch (e: Exception) {
            ReadItLog.e("progress sync list failed", e)
            return Report(
                emptyList(), emptyList(), emptyList(),
                listOf("*" to (e.message ?: "list failed")),
                System.currentTimeMillis() - started
            )
        }

        val remoteProgress = remote.filter { !it.isDirectory && it.displayName.startsWith(PREFIX_PROGRESS) }
        val remoteBookmarks = remote.filter { !it.isDirectory && it.displayName.startsWith(PREFIX_BOOKMARKS) }

        // ---- 进度：远端 → 本地 或 本地 → 远端
        for (r in remoteProgress) {
            val safe = idOf(r.displayName, PREFIX_PROGRESS) ?: continue
            try {
                val remotePos = downloadPosition(context, r, safe)
                when (val d = ProgressMerge.decide(ProgressStore.load(context, safe), remotePos)) {
                    is ProgressMerge.Decision.Identical -> Unit
                    is ProgressMerge.Decision.TakeRemote -> {
                        ProgressStore.save(context, safe, d.pos)
                        pulled.add(safe)
                    }
                    is ProgressMerge.Decision.KeepLocal -> {
                        if (pushProgress(context, safe)) pushed.add(safe)
                    }
                }
            } catch (e: Exception) {
                ReadItLog.w("progress sync failed: $safe -> ${e.message}")
                failed.add(safe to (e.message ?: "error"))
            }
        }

        // ---- 进度：本地有、远端没有 → 上行
        val remoteProgressIds = remoteProgress.mapNotNull { idOf(it.displayName, PREFIX_PROGRESS) }.toSet()
        for (f in listJson(context, ProgressStore.dir(context))) {
            val safe = f.name.removeSuffix(SUFFIX)
            if (safe in remoteProgressIds) continue
            try {
                if (pushProgress(context, safe)) pushed.add(safe)
            } catch (e: Exception) {
                failed.add(safe to (e.message ?: "error"))
            }
        }

        // ---- 书签：并集合并，本地与远端都落成合并后的结果
        val remoteBookmarkIds = remoteBookmarks.mapNotNull { idOf(it.displayName, PREFIX_BOOKMARKS) }.toSet()
        for (r in remoteBookmarks) {
            val safe = idOf(r.displayName, PREFIX_BOOKMARKS) ?: continue
            try {
                if (mergeBookmarks(context, safe, downloadBookmarks(context, r, safe))) mergedBm.add(safe)
            } catch (e: Exception) {
                ReadItLog.w("bookmarks sync failed: $safe -> ${e.message}")
                failed.add(safe to (e.message ?: "error"))
            }
        }
        for (f in listJson(context, BookmarkStore.dir(context))) {
            val safe = f.name.removeSuffix(SUFFIX)
            if (safe in remoteBookmarkIds) continue
            try {
                if (mergeBookmarks(context, safe, null)) mergedBm.add(safe)
            } catch (e: Exception) {
                failed.add(safe to (e.message ?: "error"))
            }
        }

        val report = Report(pulled, pushed, mergedBm, failed, System.currentTimeMillis() - started)
        ReadItLog.i("progress sync done: ${report.summary()}")
        return report
    }

    // ------------------------------------------------------------------ 内部

    /** 远端文件名 → 书标识（`readit_progress_<safe>.json` → `<safe>`）；不合规则返回 null */
    private fun idOf(displayName: String, prefix: String): String? {
        if (!displayName.startsWith(prefix) || !displayName.endsWith(SUFFIX)) return null
        return displayName.removePrefix(prefix).removeSuffix(SUFFIX).takeIf { it.isNotEmpty() }
    }

    private fun listJson(context: Context, dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) } ?: emptyList()

    private fun tmpFile(context: Context, name: String): File {
        val dir = File(context.cacheDir, "readit_sync")
        dir.mkdirs()
        return File(dir, name)
    }

    private fun downloadPosition(context: Context, r: WebDavClient.DavResource, safe: String): ReadingPosition? {
        val tmp = tmpFile(context, "p_$safe")
        return try {
            client.download(r.href, tmp)
            runCatching { gson.fromJson(tmp.readText(Charsets.UTF_8), ReadingPosition::class.java) }.getOrNull()
        } finally {
            tmp.delete()
        }
    }

    private fun downloadBookmarks(context: Context, r: WebDavClient.DavResource, safe: String): BookmarkFile? {
        val tmp = tmpFile(context, "b_$safe")
        return try {
            client.download(r.href, tmp)
            runCatching { gson.fromJson(tmp.readText(Charsets.UTF_8), BookmarkFile::class.java) }.getOrNull()
        } finally {
            tmp.delete()
        }
    }

    /**
     * 上传本地进度文件（**无条件 PUT**）。
     *
     * 不用 `If-Match`：本类刚刚才判定「本地更新」，目的就是要覆盖远端。
     * 万一两台设备同时推，谁后被处理谁覆盖 —— 代价可控，因为时间戳在 JSON 里，
     * 下一轮同步会立刻把被覆盖的那台拉到更新的那一侧（自愈）。
     */
    private fun pushProgress(context: Context, safe: String): Boolean {
        val local = File(ProgressStore.dir(context), "$safe$SUFFIX")
        if (!local.exists()) return false
        client.upload(PREFIX_PROGRESS + "$safe$SUFFIX", local, remoteEtag = null, createOnly = false)
        return true
    }

    private fun pushBookmarks(context: Context, safe: String): Boolean {
        val local = File(BookmarkStore.dir(context), "$safe$SUFFIX")
        if (!local.exists()) return false
        client.upload(PREFIX_BOOKMARKS + "$safe$SUFFIX", local, remoteEtag = null, createOnly = false)
        return true
    }

    /**
     * 把远端书签与本地合并，两侧都落成合并结果。
     *
     * [remote] 为 null（远端还没有这本书的书签）时只做「本地上行」。
     * @return true 表示确实产生了变化（本地写盘或远端上传）
     */
    private fun mergeBookmarks(context: Context, safe: String, remote: BookmarkFile?): Boolean {
        val local = BookmarkStore.load(context, safe)
        val merged = Bookmarks.merge(local, remote ?: BookmarkFile())

        val localBefore = gson.toJson(local)
        val localAfter = gson.toJson(merged)
        var changed = false
        if (localBefore != localAfter) {
            BookmarkStore.save(context, safe, merged)
            changed = true
            ReadItLog.i("bookmarks merged into local: $safe (${local.items.size} + ${remote?.items?.size ?: 0} -> ${merged.items.size})")
        }

        // 远端与合并结果不一致就推上去（远端缺文件时 gson 序列化一个空集合，必然不等）
        val remoteJson = remote?.let { gson.toJson(it) }
        if (remoteJson != localAfter) {
            if (pushBookmarks(context, safe)) changed = true
        }
        return changed
    }
}

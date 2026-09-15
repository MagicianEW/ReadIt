package com.readit.sync

/**
 * 双向同步的决策层（F13 / R19）。
 *
 * 刻意做成**无 Android、无网络依赖的纯函数**：同步最容易出错的不是 IO，
 * 而是「哪一侧改了」的判断。把这层抽出来才能在 JVM 上把边界情况全跑一遍
 * （无 ETag、只有 Last-Modified、两侧都改、记录缺失……）。
 *
 * 判定优先级（R19 明确要求）：ETag > Last-Modified > 体积。
 * 一旦两侧都变且无法确定谁新 → CONFLICT，**不做任何写入**，交人工处理。
 */
enum class SyncAction {
    /** 远端有、本地无 → 下载 */
    DOWNLOAD_NEW,

    /** 远端变了、本地没变 → 覆盖本地 */
    DOWNLOAD_UPDATE,

    /** 本地有、远端无 → 上传新建 */
    UPLOAD_NEW,

    /** 本地变了、远端没变 → 上传覆盖 */
    UPLOAD_UPDATE,

    /** 两侧一致 */
    UP_TO_DATE,

    /** 两侧都变（或缺少基线记录）→ 不写入，上报冲突 */
    CONFLICT
}

/** 本地文件的「可比较指纹」 */
data class LocalMeta(val size: Long, val lastModified: Long)

/** 远端资源的「可比较指纹」（PROPFIND 结果子集） */
data class RemoteMeta(val etag: String?, val lastModified: String?, val size: Long)

/**
 * 上一次同步成功后的基线记录。
 *
 * @param version 客户端版本号，用于完全没有 ETag / Last-Modified 的服务端兜底
 */
data class SyncRecord(
    val href: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val remoteSize: Long = -1L,
    val localSize: Long = -1L,
    val localLastModified: Long = -1L,
    val version: Long = 1L,
    val syncedAt: Long = 0L
)

data class SyncDecision(val action: SyncAction, val reason: String)

object SyncDecider {

    /** 远端独有 */
    fun remoteOnly(): SyncDecision = SyncDecision(SyncAction.DOWNLOAD_NEW, "remote only")

    /** 本地独有 */
    fun localOnly(): SyncDecision = SyncDecision(SyncAction.UPLOAD_NEW, "local only")

    /**
     * 两侧都存在时的判定。
     *
     * @param record null 表示没有同步基线（第一次同步遇到同名文件）—— 无法判断谁新，判冲突
     */
    fun both(remote: RemoteMeta, local: LocalMeta, record: SyncRecord?): SyncDecision {
        if (record == null) {
            return SyncDecision(SyncAction.CONFLICT, "no baseline record; both sides present")
        }
        val localChanged = local.size != record.localSize || local.lastModified != record.localLastModified
        val remoteChanged = remoteChanged(remote, record)

        return when {
            !localChanged && !remoteChanged -> SyncDecision(SyncAction.UP_TO_DATE, "in sync")
            !localChanged -> SyncDecision(SyncAction.DOWNLOAD_UPDATE, "remote changed")
            !remoteChanged -> SyncDecision(SyncAction.UPLOAD_UPDATE, "local changed")
            else -> SyncDecision(SyncAction.CONFLICT, "both sides changed")
        }
    }

    /** ETag 优先；无 ETag 退 Last-Modified；两者皆无退体积（R19 兜底链） */
    fun remoteChanged(remote: RemoteMeta, record: SyncRecord): Boolean {
        if (!record.etag.isNullOrEmpty() && !remote.etag.isNullOrEmpty()) {
            return record.etag != remote.etag
        }
        if (!record.lastModified.isNullOrEmpty() && !remote.lastModified.isNullOrEmpty()) {
            return record.lastModified != remote.lastModified
        }
        if (remote.size >= 0 && record.remoteSize >= 0) {
            return remote.size != record.remoteSize
        }
        // 三条链全断：宁可判「变了」也不静默认为没变
        return true
    }
}

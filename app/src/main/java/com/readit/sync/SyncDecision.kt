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

/**
 * 归一化 ETag：去掉弱校验前缀 `W/` 与首尾双引号，空值 / 空串一律返回 null（=「服务端没给」）。
 *
 * ★ 真机 E2E 踩到的坑：同一个 ETag 值在**不同来源**上的包裹形式不一致 ——
 * 实测 wsgidav/cheroot：
 *  - `PUT` / `GET` 的响应头给 `"ec716e12…-1790127075-43598"`（带双引号，符合 RFC 7232）
 *  - 同一文件的 `PROPFIND <D:getetag>` 给 `ec716e12…-1790127075-43598`（**不带引号**）
 *
 * 直接做字符串比较会把同一个版本判成两个版本，后果是 **每轮同步都把整库重下一遍**，
 * 永远收敛不到「已最新」。而且它不报错、不冲突，只是白耗流量与电量 ——
 * 在墨水瓶这种按流量和电算成本的设备上尤其难被发现。
 *
 * 比较用归一化值；**出站** `If-Match` 仍要用带引号的规范形式（见 WebDavClient.upload）。
 */
fun normalizeEtag(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val strong = if (trimmed.startsWith("W/", ignoreCase = true)) trimmed.substring(2).trim() else trimmed
    val unquoted = if (strong.length >= 2 && strong.startsWith("\"") && strong.endsWith("\"")) {
        strong.substring(1, strong.length - 1)
    } else {
        strong
    }
    return unquoted.ifEmpty { null }
}

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
        val baselineEtag = normalizeEtag(record.etag)
        val remoteEtag = normalizeEtag(remote.etag)
        if (baselineEtag != null && remoteEtag != null) {
            return baselineEtag != remoteEtag
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

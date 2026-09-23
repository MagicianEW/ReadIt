package com.readit.data

/**
 * 多设备进度合并（F25）。
 *
 * ## 为什么进度不能复用 [com.readit.sync.SyncDecider]
 *
 * 书籍文件用「ETag + 本地 mtime + 上次同步基线」三方比较，因为书是**不透明二进制**，
 * 内容变没变只能靠元数据推断。进度不一样：它自己就带着权威时间戳
 * （[ReadingPosition.updatedAt]，由写入方打上），直接比时间戳比猜文件 mtime 准得多，
 * 也不需要维护同步基线（换设备、清数据后基线就废了，时间戳还在 JSON 里）。
 *
 * 本文件刻意不依赖 Android 类型，便于 JVM 单测 —— 冲突合并是最难在真机上复现的一环。
 */
object ProgressMerge {

    sealed class Decision {
        /** 本地更新或两边一致：不动本地 */
        object KeepLocal : Decision()

        /** 远端更新：用远端覆盖本地 */
        data class TakeRemote(val pos: ReadingPosition) : Decision()

        /** 两边完全一致 */
        object Identical : Decision()
    }

    /**
     * 决定两个进度记录谁胜出。
     *
     * 规则（按优先级）：
     *  1. 一侧没有 → 用另一侧
     *  2. 内容完全相同 → [Decision.Identical]
     *  3. `updatedAt` 大者胜（严格大于；**打平时保留本地**，避免无休止来回覆盖）
     *  4. 远端 `updatedAt <= 0`（老版本写的、或字段缺失）**永远不覆盖**本地有时间的记录 ——
     *     这是防「一台旧设备把新进度冲掉」的关键一条
     */
    fun decide(local: ReadingPosition?, remote: ReadingPosition?): Decision {
        if (local == null && remote == null) return Decision.Identical
        if (local == null) return Decision.TakeRemote(remote!!)
        if (remote == null) return Decision.KeepLocal
        if (same(local, remote)) return Decision.Identical

        if (remote.updatedAt <= 0L) return Decision.KeepLocal
        if (local.updatedAt <= 0L) return Decision.TakeRemote(remote)
        return if (remote.updatedAt > local.updatedAt) Decision.TakeRemote(remote)
        else Decision.KeepLocal
    }

    /**
     * 两条进度是不是同一个位置。
     *
     * 只比位置字段，**不比 updatedAt**：同一页在两台设备上各开一次，时间戳不同但
     * 位置一样，属于「一致」，不该触发任何写入（否则每次同步都有 1 个文件在动）。
     */
    fun same(a: ReadingPosition, b: ReadingPosition): Boolean =
        a.charOffset == b.charOffset &&
            a.chapterIndex == b.chapterIndex &&
            a.cfi == b.cfi &&
            a.pageIndex == b.pageIndex
}

/**
 * 「整份 JSON 谁更新」的兜底合并（F25 书签、F28 备份清单等）。
 *
 * 书签没有像进度那样的内嵌时间戳（[BookmarkFile] 里没有整体 mtime 字段），
 * 所以退回到「比内容 → 比时间戳参数」。调用方把已知的写入时间传进来即可。
 */
object JsonFileMerge {

    sealed class Decision {
        object KeepLocal : Decision()
        data class TakeRemote(val content: String) : Decision()
        object Identical : Decision()
    }

    /**
     * @param local 本地内容（null 表示本地没有）
     * @param remote 远端内容（null 表示远端没有）
     * @param localAt / remoteAt 两侧的写入时间；未知传 0
     */
    fun decide(local: String?, remote: String?, localAt: Long, remoteAt: Long): Decision {
        if (local == null && remote == null) return Decision.Identical
        if (local == null) return Decision.TakeRemote(remote!!)
        if (remote == null) return Decision.KeepLocal
        if (local == remote) return Decision.Identical
        if (remoteAt <= 0L) return Decision.KeepLocal
        if (localAt <= 0L) return Decision.TakeRemote(remote)
        return if (remoteAt > localAt) Decision.TakeRemote(remote) else Decision.KeepLocal
    }
}

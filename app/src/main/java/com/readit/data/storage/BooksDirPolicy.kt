package com.readit.data.storage

import java.io.File

/**
 * 书籍保存目录校验策略（纯函数，JVM 可测）。
 *
 * 用户可在首次引导 / 设置页指定书籍保存目录，**确认前必须先校验**，
 * 且要求所选目录为空 —— 避免误把「一整个已有资料的文件夹」当成书籍库，
 * 也避免后续写入与用户既有文件混在一起。
 *
 * 之所以抽成纯函数：目录这段最容易错的不是 IO 而是判断分支
 * （不存在 / 不是目录 / 不可读 / 不可写 / 非空 / 与当前目录相同），
 * 全部在 JVM 上把每个分支跑一遍，真机上就只剩「采集探针」这一层。
 */
object BooksDirPolicy {

    /** 目录的可观测事实：由调用方用 [File] 采集后传入，策略本身不碰磁盘。 */
    data class Probe(
        val exists: Boolean,
        val isDirectory: Boolean,
        val canRead: Boolean,
        val canWrite: Boolean,
        val childCount: Int,
    )

    enum class Verdict {
        OK,
        NOT_EXIST,
        NOT_DIR,
        NOT_READABLE,
        NOT_WRITABLE,
        NOT_EMPTY,
    }

    /**
     * @param isCurrent 所选目录是否等于「当前已生效」的书籍目录。
     *
     * 等于当前目录时**豁免「非空」**：否则用户导入过书之后，
     * 再想重选同一个目录会永远被「非空」卡死（书架里明明已经有书）。
     * 该豁免只针对「新选的目录」，新目录仍必须为空。
     */
    fun evaluate(probe: Probe, isCurrent: Boolean): Verdict = when {
        !probe.exists -> Verdict.NOT_EXIST
        !probe.isDirectory -> Verdict.NOT_DIR
        !probe.canRead -> Verdict.NOT_READABLE
        !probe.canWrite -> Verdict.NOT_WRITABLE
        probe.childCount > 0 && !isCurrent -> Verdict.NOT_EMPTY
        else -> Verdict.OK
    }

    /** 从文件系统采集探针：只读取事实，不做判定。 */
    fun probeOf(dir: File): Probe {
        val exists = dir.exists()
        val isDir = dir.isDirectory
        val canRead = exists && isDir && dir.canRead()
        val canWrite = exists && isDir && dir.canWrite()
        // 不可读时不列目录，childCount 记 0（判定会在 NOT_READABLE 提前收口）
        val children = if (canRead) (dir.listFiles()?.size ?: 0) else 0
        return Probe(exists, isDir, canRead, canWrite, children)
    }
}

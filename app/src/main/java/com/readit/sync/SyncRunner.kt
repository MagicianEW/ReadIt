package com.readit.sync

import android.content.Context
import com.readit.core.util.ReadItLog
import com.readit.sync.webdav.WebDavClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一轮完整同步的编排（F25）。
 *
 * 拆成两步而不是塞进 [WebDavSync] 里：
 *  - [WebDavSync]     只管**书籍文件**（大、二进制、靠 ETag/mtime 判变化）
 *  - [ProgressSync]   只管**侧车 JSON**（小、自带时间戳、可真正做内容合并）
 *
 * 两者的失败语义、冲突策略、体积量级都不一样，混在一个循环里会让
 * 「一本坏书」和「一条坏进度」共用同一套重试逻辑，反而更难查。
 * 这里只负责「顺序执行 + 拼一行摘要」。
 */
object SyncRunner {

    /**
     * 全局互斥：同一时刻只允许一轮同步在跑。
     *
     * 手动「立即同步」（设置页的 io 线程）与 6 小时周期任务（SyncJobService 拉起的前台服务）
     * 是两条独立线程，设置页那个 `syncing` 标志只防「设置页内重复点击」，防不住周期任务
     * 正好同时起跑。两者并发会：
     *  - 同时下载同一本书到书库 → 争抢同一个目标文件（临时文件已唯一化，但 rename 仍会互相顶）；
     *  - 同时读改同一份侧车 JSON → 合并基于半截内容得出结论。
     * 抢不到锁就直接放弃这一轮（返回上一轮结果语义由调用方按「未执行」处理），
     * 宁可少同步一轮，也不要在损坏书库的风险上抢时间。
     */
    private val inFlight = AtomicBoolean(false)

    data class Outcome(
        val books: WebDavSync.SyncReport,
        val sidecar: ProgressSync.Report,
        /** false = 已有另一轮在跑，本次未执行 */
        val executed: Boolean = true
    ) {
        fun summary(): String =
            if (executed) "书[${books.summary()}] 进度[${sidecar.summary()}]" else "已有同步在执行，本次跳过"

        /** 本轮是否真的跑了（调用方据此决定要不要提示「已跳过」） */
        fun didRun(): Boolean = executed
    }

    /**
     * 执行一整轮同步。**必须在工作线程调用**（内部全为阻塞 IO）。
     *
     * 侧车同步放在书籍同步之后：进度文件极小（几百字节），放在后面可以让
     * 用户先看到「书下载完了」，而不是等一堆进度文件先把带宽占满。
     */
    /** 空报告：用于「本轮因并发而未执行」的返回，summary 走 executed=false 分支不读它 */
    private fun emptyBooksReport() = WebDavSync.SyncReport(
        downloaded = emptyList(), uploaded = emptyList(), upToDate = emptyList(),
        conflicts = emptyList(), failed = emptyList(), elapsedMs = 0L
    )

    private fun emptySidecarReport() = ProgressSync.Report(
        pulledProgress = emptyList(), pushedProgress = emptyList(),
        mergedBookmarks = emptyList(), corruptRemote = emptyList(),
        failed = emptyList(), elapsedMs = 0L
    )

    fun run(context: Context, client: WebDavClient): Outcome {
        if (!inFlight.compareAndSet(false, true)) {
            ReadItLog.i("sync skipped: another round already in flight")
            return Outcome(emptyBooksReport(), emptySidecarReport(), executed = false)
        }
        return try {
            val books = WebDavSync(client).sync(context)
            val sidecar = ProgressSync(client).sync(context)
            Outcome(books, sidecar)
        } finally {
            inFlight.set(false)
        }
    }
}

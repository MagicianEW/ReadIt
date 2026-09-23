package com.readit.sync

import android.content.Context
import com.readit.sync.webdav.WebDavClient

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

    data class Outcome(
        val books: WebDavSync.SyncReport,
        val sidecar: ProgressSync.Report
    ) {
        fun summary(): String = "书[${books.summary()}] 进度[${sidecar.summary()}]"
    }

    /**
     * 执行一整轮同步。**必须在工作线程调用**（内部全为阻塞 IO）。
     *
     * 侧车同步放在书籍同步之后：进度文件极小（几百字节），放在后面可以让
     * 用户先看到「书下载完了」，而不是等一堆进度文件先把带宽占满。
     */
    fun run(context: Context, client: WebDavClient): Outcome {
        val books = WebDavSync(client).sync(context)
        val sidecar = ProgressSync(client).sync(context)
        return Outcome(books, sidecar)
    }
}

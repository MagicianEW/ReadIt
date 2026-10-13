package com.readit.doc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import com.readit.core.eal.DocxMode
import com.readit.core.util.ReadItLog
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * DOCX 转换客户端（F04）。
 *
 * 主路径：把活派给独立进程里的 [DocxConvertService]。
 * 兜底路径：服务不可用（被系统限制 / 未注册 / 启动异常）时退回主进程内联转换，
 * 功能不减，只是失去进程隔离 —— 宁可慢一点，也不能打不开书。
 *
 * 线程约束：内部是**阻塞**调用（CountDownLatch），必须在工作线程调用，
 * 不要直接放在 oncreate / 点击回调里。ResultReceiver 传 null，
 * 回调走 Binder 线程池而非常驻主线程 Handler，因此等待方不会被自己卡死。
 */
object DocxConverter {

    data class Converted(
        /** 子集渲染产物（HTML 文本，已注入标题锚点、媒体路径已相对化） */
        val html: String?,
        /** 纯文本产物（TEXT_ONLY 档） */
        val text: String?,
        /** HTML 内相对资源（图片）所在目录，供 WebView baseUrl 使用 */
        val baseDir: File?,
        val tocTitles: List<String>,
        val tocDepths: List<Int>,
        val tocIds: List<Int>,
        val warnings: List<String>,
        /** 实际走通的路径，用于日志与降级提示 */
        val viaService: Boolean
    ) {
        val isEmpty: Boolean
            get() = html.isNullOrEmpty() && text.isNullOrEmpty()
    }

    /** 服务转换超时（大文档 + 低配 SoC 留足余量） */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /**
     * 主进程内联转换的看门狗。
     *
     * 内联转换没有进程隔离兜底：一份病态 docx 能把阅读器自身进程拖死 / 吃光内存 ——
     * 而这正是当初把转换挪进 `:converter` 进程要防的事。所以它也必须有上限，
     * 且超时要**如实上报失败**（让用户能返回），而不是让「正在打开 DOCX…」永远转下去。
     * 取服务超时的 2 倍：内联慢是正常的，只是不能无界。
     */
    const val IN_PROCESS_TIMEOUT_MS = DEFAULT_TIMEOUT_MS * 2

    /**
     * 转换结果。
     *
     * 用 sealed 而不是裸 `Converted?`：调用方需要区分「服务不可用（已在主进程兜过）」与
     * 「文档本身病态（超时，别再在主进程重试）」，这两种失败要给用户不同的提示。
     */
    sealed class Result {
        data class Ok(val converted: Converted) : Result()

        /**
         * @param timedOut true = 病态文档触发了超时；这类**不应**再退回主进程重解析
         * @param reason 面向用户的一句话原因
         */
        data class Failed(val reason: String, val timedOut: Boolean) : Result()
    }

    /**
     * 转换入口。失败返回 [Result.Failed]，调用方提示后退出，不要崩。
     *
     * 两条路径的区别对待（这是本方法最要紧的一条逻辑）：
     *  - **服务不可用**（未注册 / 被系统限制 / 启动异常）→ 退回主进程内联转换。
     *    隔离没了但功能还在，慢一点可以接受。
     *  - **服务超时**（文档病态，30s 没回话）→ **不退回**。原实现在这里也退回，于是把
     *    同一份病态 docx 拿到主进程再解析一遍，而内联路径既无超时也无内存上限：
     *    正是设计要防的 OOM 场景，在超时后 100% 落回阅读器自身进程，直接把 App 杀掉 ——
     *    进程隔离的唯一目的被自己破坏。降级方向在这里恰好是反的。
     */
    fun convert(
        context: Context,
        src: File,
        mode: DocxMode,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Result {
        val via = runCatching { convertViaService(context, src, mode, timeoutMs) }
            .onFailure { ReadItLog.w("docx service path failed: ${it.message}") }
            .getOrNull()

        when (via) {
            is ServiceAttempt.Ok -> return Result.Ok(via.converted)
            is ServiceAttempt.TimedOut -> {
                ReadItLog.w("docx timed out in :converter; NOT retrying in-process (would defeat isolation)")
                return Result.Failed("文档过于复杂，转换超时", timedOut = true)
            }
            // null = startService/绑服务本身抛了异常，同样属于「服务不可用」
            else -> Unit
        }

        ReadItLog.w("docx service unavailable -> in-process conversion (isolation lost, accept slower)")
        return runCatching { convertInProcessGuarded(context, src, mode) }
            .fold(
                onSuccess = { Result.Ok(it) },
                onFailure = {
                    ReadItLog.e("docx in-process conversion failed", it)
                    Result.Failed("文档解析失败：${it.message ?: "未知错误"}", timedOut = false)
                }
            )
    }

    /** 服务路径的三种结局，让「不可用」与「超时」不再混为一谈 */
    private sealed class ServiceAttempt {
        data class Ok(val converted: Converted) : ServiceAttempt()
        /** 服务起不来 / 没注册 / 报失败 —— 可以退回主进程 */
        object Unavailable : ServiceAttempt()
        /** 30s 没回话 —— 文档病态，退回主进程只会把 OOM 搬回阅读器自身进程 */
        object TimedOut : ServiceAttempt()
    }

    // ------------------------------------------------------------ service path

    private fun convertViaService(
        context: Context,
        src: File,
        mode: DocxMode,
        timeoutMs: Long
    ): ServiceAttempt {
        val latch = CountDownLatch(1)
        var code = Int.MIN_VALUE
        // latch 的 countDown/await 已建立 happens-before，可见性无需额外修饰
        var payload: Bundle? = null

        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                code = resultCode
                payload = resultData
                latch.countDown()
            }
        }

        val root = DocxConvertService.cacheRoot(context)
        val intent = Intent(context, DocxConvertService::class.java).apply {
            action = DocxConvertService.ACTION_CONVERT
            putExtra(DocxConvertService.EXTRA_RECEIVER, receiver)
            putExtra(DocxConvertService.EXTRA_SRC, src.absolutePath)
            putExtra(DocxConvertService.EXTRA_MODE, mode.name)
            putExtra(DocxConvertService.EXTRA_CACHE_ROOT, root.absolutePath)
        }
        context.startService(intent)

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            ReadItLog.w("docx service timeout after ${timeoutMs}ms: ${src.name}")
            return ServiceAttempt.TimedOut
        }
        val data = payload ?: return ServiceAttempt.Unavailable
        if (code != DocxConvertService.RESULT_OK) {
            ReadItLog.w("docx service failed: ${data.getString(DocxConvertService.EXTRA_ERROR)}")
            return ServiceAttempt.Unavailable
        }

        val htmlPath = data.getString(DocxConvertService.EXTRA_HTML_PATH)
        val textPath = data.getString(DocxConvertService.EXTRA_TEXT_PATH)
        val baseDir = File(root, DocxConvertService.safeName(src.name))

        val html = htmlPath?.let { File(it).takeIf(File::exists)?.readText(Charsets.UTF_8) }
        val text = textPath?.let { File(it).takeIf(File::exists)?.readText(Charsets.UTF_8) }
        if (html == null && text == null) return ServiceAttempt.Unavailable

        return ServiceAttempt.Ok(
            Converted(
                html = html,
                text = text,
                baseDir = baseDir.takeIf { it.isDirectory },
                tocTitles = data.getStringArray(DocxConvertService.EXTRA_TOC_TITLES)?.toList() ?: emptyList(),
                tocDepths = data.getIntArray(DocxConvertService.EXTRA_TOC_DEPTHS)?.toList() ?: emptyList(),
                tocIds = data.getIntArray(DocxConvertService.EXTRA_TOC_IDS)?.toList() ?: emptyList(),
                warnings = data.getStringArray(DocxConvertService.EXTRA_WARNINGS)?.toList() ?: emptyList(),
                viaService = true
            )
        )
    }

    // ------------------------------------------------------- in-process path

    /**
     * 内联转换 + 看门狗。
     *
     * 这条路径已经在主进程里了，**不能再无界地跑**：没有 `:converter` 的内存隔离，
     * 也没有第二次机会。超时就中断并抛，让 [Result.Failed] 上报 ——
     * 用户至少能回到书架，而不是看着「正在打开 DOCX…」永远转下去。
     * 诚实说明：中断只是给线程发信号，正在进行的 zip 解压**未必**立刻停下，
     * 所以日志里明确记「线程可能仍在收尾」。
     */
    private fun convertInProcessGuarded(
        context: Context,
        src: File,
        mode: DocxMode
    ): Converted {
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "readit-docx-inline").apply { isDaemon = true }
        }
        try {
            val future = exec.submit<Converted> { convertInProcess(context, src, mode) }
            return future.get(IN_PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            ReadItLog.e("docx in-process timeout after ${IN_PROCESS_TIMEOUT_MS}ms; abandoning ${src.name}")
            throw IOException("in-process conversion timed out", e)
        } catch (e: ExecutionException) {
            throw e.cause ?: IOException("in-process conversion failed", e)
        } finally {
            exec.shutdownNow()
        }
    }

    private fun convertInProcess(context: Context, src: File, mode: DocxMode): Converted? {
        if (mode == DocxMode.TEXT_ONLY) {
            val text = DocxParser(mode).parseTextOnly(src)
            return Converted(
                html = null, text = text, baseDir = null,
                tocTitles = emptyList(), tocDepths = emptyList(), tocIds = emptyList(),
                warnings = emptyList(), viaService = false
            )
        }
        val outDir = File(DocxConvertService.cacheRoot(context), DocxConvertService.safeName(src.name))
            .apply { mkdirs() }
        val parsed = DocxParser(mode, outDir).parse(src)
        val toc = DocxHtmlToc.rewrite(parsed.html)
        return Converted(
            html = DocxHtmlToc.relativizeMedia(toc.html),
            text = null,
            baseDir = outDir,
            tocTitles = toc.titles,
            tocDepths = toc.depths,
            tocIds = toc.ids,
            warnings = parsed.warnings,
            viaService = false
        )
    }
}

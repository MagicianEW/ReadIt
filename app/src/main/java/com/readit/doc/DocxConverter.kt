package com.readit.doc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ResultReceiver
import com.readit.core.eal.DocxMode
import com.readit.core.util.ReadItLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
     * 转换入口。失败返回 null，调用方按 [DocxMode] 决定降级展示，不要崩。
     */
    fun convert(
        context: Context,
        src: File,
        mode: DocxMode,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): Converted? {
        val viaService = runCatching { convertViaService(context, src, mode, timeoutMs) }
            .onFailure { ReadItLog.w("docx service path failed: ${it.message}") }
            .getOrNull()
        if (viaService != null) return viaService

        ReadItLog.w("docx falling back to in-process conversion")
        return runCatching { convertInProcess(context, src, mode) }
            .onFailure { ReadItLog.e("docx in-process conversion failed", it) }
            .getOrNull()
    }

    // ------------------------------------------------------------ service path

    private fun convertViaService(
        context: Context,
        src: File,
        mode: DocxMode,
        timeoutMs: Long
    ): Converted? {
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
            return null
        }
        val data = payload ?: return null
        if (code != DocxConvertService.RESULT_OK) {
            ReadItLog.w("docx service failed: ${data.getString(DocxConvertService.EXTRA_ERROR)}")
            return null
        }

        val htmlPath = data.getString(DocxConvertService.EXTRA_HTML_PATH)
        val textPath = data.getString(DocxConvertService.EXTRA_TEXT_PATH)
        val baseDir = File(root, DocxConvertService.safeName(src.name))

        val html = htmlPath?.let { File(it).takeIf(File::exists)?.readText(Charsets.UTF_8) }
        val text = textPath?.let { File(it).takeIf(File::exists)?.readText(Charsets.UTF_8) }
        if (html == null && text == null) return null

        return Converted(
            html = html,
            text = text,
            baseDir = baseDir.takeIf { it.isDirectory },
            tocTitles = data.getStringArray(DocxConvertService.EXTRA_TOC_TITLES)?.toList() ?: emptyList(),
            tocDepths = data.getIntArray(DocxConvertService.EXTRA_TOC_DEPTHS)?.toList() ?: emptyList(),
            tocIds = data.getIntArray(DocxConvertService.EXTRA_TOC_IDS)?.toList() ?: emptyList(),
            warnings = data.getStringArray(DocxConvertService.EXTRA_WARNINGS)?.toList() ?: emptyList(),
            viaService = true
        )
    }

    // ------------------------------------------------------- in-process path

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

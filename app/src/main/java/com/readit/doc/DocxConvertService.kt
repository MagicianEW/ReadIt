package com.readit.doc

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ResultReceiver
import com.readit.core.eal.DocxMode
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * DOCX 转换服务（规范 §3.2.3 / F04），运行在独立进程 `com.readit.eink:converter`。
 *
 * 为什么必须独立进程：DOCX 子集解析是纯 CPU + 堆压力的活（ZipFile 解压 XML、
 * 大文档可能几十 MB 中间字符串）。放在主进程会在 1GB 设备上与渲染抢堆，
 * 一旦解析进程 OOM 被系统杀掉，阅读器本身不受影响 —— 这层的隔离价值就在这。
 *
 * 进程间不能共享内存，因此：
 *  入参走 Intent（源文件路径 / 模式 / cache 根目录）
 *  出参走 ResultReceiver（产物文件路径 + 目录数组 + 告警），产物本身落在 cache 目录用文件传递
 *
 * 降级原则：任何异常都只回 RESULT_FAILED + 错误文案，绝不抛到系统导致进程崩溃。
 */
class DocxConvertService : Service() {

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("readit-docx-converter").also { it.start() }
        handler = Handler(thread.looper)
        ReadItLog.i("DocxConvertService created (pid=${android.os.Process.myPid()})")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != ACTION_CONVERT) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val receiver = intent.getParcelableExtra<ResultReceiver>(EXTRA_RECEIVER)
        val srcPath = intent.getStringExtra(EXTRA_SRC)
        val cacheRoot = intent.getStringExtra(EXTRA_CACHE_ROOT)
        val mode = intent.getStringExtra(EXTRA_MODE)?.let {
            runCatching { DocxMode.valueOf(it) }.getOrNull()
        } ?: DocxMode.SUBSET

        if (srcPath == null || cacheRoot == null) {
            send(receiver, RESULT_FAILED, Bundle().apply { putString(EXTRA_ERROR, "bad request") })
            stopSelf(startId)
            return START_NOT_STICKY
        }

        handler.post {
            val out = Bundle()
            try {
                val src = File(srcPath)
                if (!src.exists()) throw java.io.IOException("source missing: $srcPath")

                val outDir = File(cacheRoot, safeName(src.name)).apply { mkdirs() }
                // 清掉上一轮的产物，避免残留旧图误导渲染
                outDir.listFiles()?.forEach { runCatching { it.delete() } }

                val warnings: List<String>

                if (mode == DocxMode.TEXT_ONLY) {
                    val text = DocxParser(mode).parseTextOnly(src)
                    val f = File(outDir, "content.txt")
                    f.writeText(text, Charsets.UTF_8)
                    out.putString(EXTRA_TEXT_PATH, f.absolutePath)
                    out.putInt(EXTRA_CHARS, text.length)
                    warnings = emptyList()
                } else {
                    val parsed = DocxParser(mode, outDir).parse(src)
                    val toc = DocxHtmlToc.rewrite(parsed.html)
                    val html = DocxHtmlToc.relativizeMedia(toc.html)
                    val f = File(outDir, "content.html")
                    f.writeText(html, Charsets.UTF_8)

                    out.putString(EXTRA_HTML_PATH, f.absolutePath)
                    out.putStringArray(EXTRA_TOC_TITLES, toc.titles.toTypedArray())
                    out.putIntArray(EXTRA_TOC_DEPTHS, toc.depths.toIntArray())
                    out.putIntArray(EXTRA_TOC_IDS, toc.ids.toIntArray())
                    out.putInt(EXTRA_TABLES, parsed.stats.tables)
                    out.putInt(EXTRA_IMAGES, parsed.stats.images)
                    warnings = parsed.warnings
                }

                out.putStringArray(EXTRA_WARNINGS, warnings.toTypedArray())
                send(receiver, RESULT_OK, out)
                ReadItLog.i("docx convert ok: ${src.name} mode=$mode warnings=${warnings.size}")
            } catch (e: Throwable) {
                ReadItLog.e("docx convert failed: $srcPath", e)
                send(
                    receiver,
                    RESULT_FAILED,
                    Bundle().apply { putString(EXTRA_ERROR, e.message ?: e.javaClass.simpleName) }
                )
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun send(receiver: ResultReceiver?, code: Int, data: Bundle) {
        try {
            receiver?.send(code, data)
        } catch (e: Throwable) {
            ReadItLog.w("result send failed: ${e.message}")
        }
    }

    companion object {
        const val ACTION_CONVERT = "com.readit.eink.action.DOCX_CONVERT"

        const val RESULT_OK = 0
        const val RESULT_FAILED = 1

        const val EXTRA_RECEIVER = "readit_docx_receiver"
        const val EXTRA_SRC = "readit_docx_src"
        const val EXTRA_MODE = "readit_docx_mode"
        const val EXTRA_CACHE_ROOT = "readit_docx_cache_root"

        const val EXTRA_HTML_PATH = "readit_docx_html"
        const val EXTRA_TEXT_PATH = "readit_docx_text"
        const val EXTRA_TOC_TITLES = "readit_docx_toc_titles"
        const val EXTRA_TOC_DEPTHS = "readit_docx_toc_depths"
        const val EXTRA_TOC_IDS = "readit_docx_toc_ids"
        const val EXTRA_WARNINGS = "readit_docx_warnings"
        const val EXTRA_TABLES = "readit_docx_tables"
        const val EXTRA_IMAGES = "readit_docx_images"
        const val EXTRA_CHARS = "readit_docx_chars"
        const val EXTRA_ERROR = "readit_docx_error"

        /** 转换产物根目录（两个进程共享同一 app 的 cache 目录） */
        fun cacheRoot(context: Context): File =
            File(context.cacheDir, "docx").apply { mkdirs() }

        fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}

package com.readit.reader.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.readit.core.display.Inversion
import com.readit.core.util.ReadItLog
import com.readit.pdf.PdfBookmark
import com.readit.pdf.PdfCore
import com.readit.pdf.PdfCropper
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * PDF 渲染档视图（规范 §3.2.4，F05/F07）。
 *
 * 职责：
 *  - 持有 [PdfCore]（Pdfium 单页位图渲染）
 *  - 渲染完成后按需裁边（[PdfCropper]，受 pdfCropEnabled 控制）
 *  - 按「等比 fit-center」绘制到画布，纯黑白底
 *
 * 线程模型：位图渲染在工作线程做，渲染完成 post 回主线程换页并 invalidate。
 * 用递增的 renderSeq 丢弃过期结果 —— 用户连点翻页时旧页回来不能覆盖新页。
 *
 * 内存约束：任一时刻只持有 1 张页位图（低内存档 pdfCachedPages=1 的口径），
 * 换页时立即 recycle 上一张。
 */
class PdfRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val core = PdfCore(context)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = true }

    /**
     * 反色用的颜色矩阵滤镜（F27）。
     *
     * 刻意不做逐像素翻转：一页 A4 扫描件动辄几百万像素，CPU 逐像素会把
     * 首屏预算吃穿；交给 Skia 在绘制时做，等于零成本（位图本身也不被改写，
     * 关掉反色立刻恢复原样，不需要重新渲染这一页）。
     */
    private val invertFilter = ColorMatrixColorFilter(ColorMatrix(Inversion.argbMatrix()))
    private val main = Handler(Looper.getMainLooper())

    private var executor: ExecutorService? = null
    private var page: Bitmap? = null
    private var seq = 0

    /** 已成功落到屏幕上的最大序号；看门狗用它区分「还在渲染」与「渲染完了但 seq 已变」 */
    private var completedSeq = 0
    private var renderedWidth = 0
    private var desiredPage = 0

    /** 是否启用裁边（F07）；仅渲染档有效 */
    var cropEnabled: Boolean = true

    /** 反色（黑白置换，F27）；只换滤镜，不重渲染当前页 */
    var inverted: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                paint.colorFilter = if (value) invertFilter else null
                invalidate()
            }
        }

    /** 渲染策略里的缓存页数，仅用于日志/后续预取，当前实现恒为单页常驻 */
    var cachePages: Int = 1

    /**
     * 单页渲染超时（R11 缓解措施之一）。
     *
     * Pdfium 在兜底档 SoC 上可能是数百 ms，但一旦超过这个量级基本就是卡死了
     * （畸形页 / native 死锁）。没有看门狗时表现为「白屏且永远不恢复」，
     * 用户连失败原因都看不到。
     */
    var renderTimeoutMs: Long = 8_000L

    var onPageChanged: ((page: Int, total: Int) -> Unit)? = null

    /** 位图真正就绪（区别于 [onPageChanged]，那是「请求切页」的那一刻） */
    var onPageRendered: ((page: Int) -> Unit)? = null

    var onRenderFailed: ((reason: String) -> Unit)? = null

    /** 单页超过 [renderTimeoutMs] 仍未返回；调用方据此决定是否整体降级 */
    var onRenderTimeout: ((page: Int) -> Unit)? = null

    val isOpen: Boolean get() = core.isOpen
    val pageCount: Int get() = core.pageCount
    val currentPage: Int get() = desiredPage

    /** 书签目录（F06）；Pdfium 的 Outline 为空时由调用方退到 PdfBox */
    fun bookmarks(): List<PdfBookmark> =
        if (core.isOpen) core.bookmarks() else emptyList()

    // ------------------------------------------------------------------ 生命周期

    /** @return false 表示 Pdfium 打不开（调用方应退到文本档） */
    fun open(file: File): Boolean {
        return try {
            core.open(file)
            desiredPage = 0
            renderedWidth = 0
            recyclePage()
            if (width > 0) requestRender(desiredPage)
            true
        } catch (e: Throwable) {
            ReadItLog.e("PdfRenderView open failed", e)
            onRenderFailed?.invoke(e.message ?: "pdf open failed")
            false
        }
    }

    fun goToPage(index: Int) {
        if (!core.isOpen) return
        val target = index.coerceIn(0, (core.pageCount - 1).coerceAtLeast(0))
        if (target == desiredPage && page != null) return
        desiredPage = target
        onPageChanged?.invoke(target, core.pageCount)
        requestRender(target)
    }

    fun next(): Boolean {
        if (!core.isOpen) return false
        if (desiredPage >= core.pageCount - 1) return false
        goToPage(desiredPage + 1)
        return true
    }

    fun prev(): Boolean {
        if (!core.isOpen) return false
        if (desiredPage <= 0) return false
        goToPage(desiredPage - 1)
        return true
    }

    fun release() {
        seq++
        recyclePage()
        runCatching { core.close() }
        executor?.shutdownNow()
        executor = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 视图被移除时释放 native 句柄；重新 addView 会重新 open
        release()
    }

    // ------------------------------------------------------------------ 渲染

    private fun requestRender(index: Int) {
        if (!core.isOpen) return
        val targetWidth = width.takeIf { it > 0 } ?: return
        val mySeq = ++seq
        renderedWidth = targetWidth
        val ex = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        ex.execute {
            val raw = core.renderPage(index, targetWidth)
            val out = raw?.let { if (cropEnabled) crop(it) else it }
            if (raw != null && out !== raw) raw.recycle()
            main.post {
                if (mySeq != seq) {
                    out?.recycle()
                    return@post
                }
                if (out == null) {
                    ReadItLog.w("pdf render returned null at page $index")
                    completedSeq = mySeq
                    onRenderFailed?.invoke("page $index render failed")
                    return@post
                }
                val old = page
                page = out
                old?.recycle()
                completedSeq = mySeq
                invalidate()
                onPageRendered?.invoke(index)
            }
        }

        // R11 看门狗：到点还没拿到这一页的位图就报超时。
        // 只认「自己还是最新请求」的情况 —— 用户连点翻页时旧页的超时不该再打扰上层。
        main.postDelayed({
            if (mySeq == seq && completedSeq < mySeq) {
                ReadItLog.w("pdf render timeout after ${renderTimeoutMs}ms at page $index")
                onRenderTimeout?.invoke(index)
            }
        }, renderTimeoutMs)
    }

    private fun crop(src: Bitmap): Bitmap {
        val c = PdfCropper.detect(src) ?: return src
        return try {
            val cropped = Bitmap.createBitmap(src, c.left, c.top, c.width, c.height)
            ReadItLog.i("pdf crop applied: ${c.describe()}")
            cropped
        } catch (e: Throwable) {
            ReadItLog.w("pdf crop failed: ${e.message}")
            src
        }
    }

    private fun recyclePage() {
        page?.recycle()
        page = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && core.isOpen && w != renderedWidth) requestRender(desiredPage)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Inversion.backColor(inverted))
        val bmp = page ?: return
        if (bmp.isRecycled) return

        val vw = width
        val vh = height
        if (vw <= 0 || vh <= 0) return

        val scale = minOf(vw.toFloat() / bmp.width, vh.toFloat() / bmp.height)
        val dw = (bmp.width * scale).toInt().coerceAtLeast(1)
        val dh = (bmp.height * scale).toInt().coerceAtLeast(1)
        val left = (vw - dw) / 2
        val top = (vh - dh) / 2
        canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), Rect(left, top, left + dw, top + dh), paint)
    }
}

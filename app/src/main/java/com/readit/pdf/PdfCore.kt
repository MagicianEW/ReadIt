package com.readit.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.readit.core.util.ReadItLog
import com.shockwave.pdfium.PdfDocument
import com.shockwave.pdfium.PdfiumCore
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * PDF 渲染档封装（规范 §3.2.4，F05/F06/F07）。
 *
 * 底层为 PdfiumAndroid 1.9.0（上游 2018 年起停止维护，锁定版本，见风险 R22）。
 * 方法签名以 AAR 内 classes.jar 的 javap 输出为准：
 *  - PdfiumCore.getPageCount(doc) / openPage() / getPageWidthPoint() / renderPageBitmap(7 参)
 *  - PdfiumCore.getTableOfContents(doc) → List&lt;PdfDocument.Bookmark&gt;，Bookmark.getPageIdx() 为 long
 *
 * 职责边界：
 *  - 渲染：单页位图（一次只持有当前页；这是 §5.1 的硬要求，不随档位放宽）
 *  - 书签：getTableOfContents() 直接可用；异常时退到 [PdfBoxBookmarks]
 *  - 文本：不在此类，由 [PdfTextExtractor]（PdfBox-Android）承担
 *
 * 注意：本类依赖 native 库，只能在真机 / 模拟器验证；单元测试不覆盖。
 * 可测的几何与判定逻辑分别抽到 [PdfCropper] 与 [ScanThresholds]。
 */
class PdfCore(private val context: Context) : Closeable {

    private val core = PdfiumCore(context)
    private var document: PdfDocument? = null
    private var descriptor: ParcelFileDescriptor? = null

    /**
     * native 句柄的单线程闸门。
     *
     * Pdfium 的 `fpdf_doc_t` / `fpdf_page_t` **不是线程安全的**，而本类的调用方跨线程：
     * `renderPage` 跑在 PdfRenderView 的渲染线程上，`bookmarks()` 与 `close()` 跑在主线程
     * （首开时 loadPdfToc、返回时 release）。二者并发会出现 native use-after-free / SIGSEGV，
     * 而 Kotlin 的 `runCatching` **拦不住信号**。
     *
     * 渲染任务本来就只有一个（单线程 executor），串行化不损失并发度。
     * 用可重入锁：renderPage → pageSize 是嵌套获取。
     */
    private val nativeLock = ReentrantLock()

    /**
     * 单页位图的像素预算（RGB_565 下 = 2 字节/px）。
     *
     * 不封顶时极端宽高比页面会算出荒谬的尺寸：40×3000pt 的长条票据在 1200px 宽视图下
     * 是 1200×90000 = 1.08 亿像素 ≈ 216MB，1GB 设备必 OOM，且该页此后每次翻回都是白页
     * （renderPage 返回 null，页面无重试）。按可用堆对**高度**封顶并等比缩小。
     */
    private val maxBitmapPixels: Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val heapMb = (am?.memoryClass ?: 32).toLong()
        // 单页不超过可用堆的 1/4，且不低于 8MP
        (heapMb * 1024L * 1024L / 4L).coerceIn(8L * 1024 * 1024, 64L * 1024 * 1024)
    }.getOrDefault(16L * 1024 * 1024)

    @Volatile
    var pageCount: Int = 0
        private set

    val isOpen: Boolean get() = document != null

    @Throws(IOException::class)
    fun open(file: File) {
        close()
        // 尺寸缓存属于旧文档，必须同步清掉：close() 在渲染在途时会把关闭转后台，
        // 那条路径上的清理不是同步的。
        pageSizeCache.clear()
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw IOException("pdf open failed: ${e.message}", e)
        }
        descriptor = pfd
        try {
            nativeLock.withLock {
                val doc = core.newDocument(pfd)
                document = doc
                pageCount = core.getPageCount(doc)
            }
            ReadItLog.i("PdfCore opened: ${file.name} pages=$pageCount")
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            descriptor = null
            // 已建好的 native 文档必须在这里关掉：原实现只关 fd、document 仍非 null，
            // 于是 isOpen 对一个没初始化完的文档返回 true。
            runCatching { document?.let { core.closeDocument(it) } }
            document = null
            pageCount = 0
            throw IOException("pdfium load failed: ${e.message}", e)
        }
    }

    /**
     * 每页尺寸缓存（point）。
     *
     * 为什么要缓存：查尺寸必须 `core.openPage(doc, index)`，而 **pdfium-android 1.9.0 的
     * `PdfiumCore` 只暴露 `closeDocument`，根本没有 `closePage`**（已 javap 核对 AAR：
     * `openPage` / `closeDocument` 之外无任何 page 生命周期方法），所以这个绑定层面
     * 根本无法显式释放单页。页面尺寸对同一文档是常量，于是正确的做法不是「开完再关」
     * （做不到），而是**每页只开一次**：翻页来回时不再重复 openPage。
     */
    private val pageSizeCache = HashMap<Int, IntArray>()

    /** 页面尺寸（point，1/72 inch）；返回 [宽, 高]，失败为 [0, 0] */
    fun pageSize(index: Int): IntArray {
        val doc = document ?: return intArrayOf(0, 0)
        if (index < 0 || index >= pageCount) return intArrayOf(0, 0)
        pageSizeCache[index]?.let { return it }
        return try {
            val size = nativeLock.withLock {
                core.openPage(doc, index)
                intArrayOf(core.getPageWidthPoint(doc, index), core.getPageHeightPoint(doc, index))
            }
            if (size[0] > 0 && size[1] > 0) pageSizeCache[index] = size
            size
        } catch (e: Throwable) {
            ReadItLog.w("pageSize($index) failed: ${e.message}")
            intArrayOf(0, 0)
        }
    }

    /**
     * 渲染单页到恰好 [targetWidth] 像素宽的位图（等比缩放）。
     *
     * @param config RGB_565 内存减半（低内存档默认），ARGB_8888 质量更好
     * @return null 表示渲染失败（调用方走文本兜底，不要崩）
     */
    fun renderPage(index: Int, targetWidth: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? {
        val doc = document ?: return null
        if (index < 0 || index >= pageCount || targetWidth <= 0) return null
        return try {
            val size = pageSize(index)
            val pw = size[0]
            val ph = size[1]
            if (pw <= 0 || ph <= 0) return null

            val w = targetWidth.coerceAtLeast(1)
            var h = ((ph.toLong() * w) / pw).toInt().coerceAtLeast(1)
            // 像素封顶：超了等比缩小而不是直接 OOM（见 maxBitmapPixels）
            if (w.toLong() * h > maxBitmapPixels) {
                h = (maxBitmapPixels / w).toInt().coerceAtLeast(1)
                ReadItLog.w("pdf page $index exceeds pixel budget, downscale to ${w}x$h")
            }

            val bitmap = Bitmap.createBitmap(w, h, config)
            nativeLock.withLock { core.renderPageBitmap(doc, bitmap, index, 0, 0, w, h) }
            bitmap
        } catch (e: OutOfMemoryError) {
            ReadItLog.e("pdf render OOM at page $index width=$targetWidth", e)
            null
        } catch (e: Throwable) {
            ReadItLog.e("pdf render failed at page $index", e)
            null
        }
    }

    /** 书签（Pdfium getTableOfContents）。返回扁平化结果，depth 表示层级。 */
    fun bookmarks(): List<PdfBookmark> {
        val doc = document ?: return emptyList()
        return try {
            nativeLock.withLock { flatten(core.getTableOfContents(doc), 0) }
        } catch (e: Throwable) {
            ReadItLog.w("pdfium bookmarks failed: ${e.message}")
            emptyList()
        }
    }

    private fun flatten(nodes: List<PdfDocument.Bookmark>?, depth: Int): List<PdfBookmark> {
        if (nodes.isNullOrEmpty()) return emptyList()
        val out = ArrayList<PdfBookmark>()
        for (n in nodes) {
            if (out.size >= MAX_BOOKMARK_ITEMS) {
                ReadItLog.w("pdfium outline truncated at $MAX_BOOKMARK_ITEMS items")
                return out
            }
            val title = runCatching { n.title }.getOrNull()?.trim().orEmpty()
            val page = runCatching { n.pageIdx.toInt() }.getOrDefault(-1)
            if (title.isNotEmpty()) out.add(PdfBookmark(title, page, depth))
            out.addAll(flatten(runCatching { n.children }.getOrNull(), depth + 1))
            if (out.size >= MAX_BOOKMARK_ITEMS) return out
        }
        return out
    }

    override fun close() {
        // 不在调用线程上无限等：release() 是从主线程进来的，此时渲染线程可能正卡在
        // 一页畸形 PDF 的 native 调用里。等不到就把关闭交给后台线程收尾 ——
        // 宁可晚点释放（泄漏一次文档），也不能阻塞主线程，也不能与渲染并发操作同一 fpdf_doc_t。
        val held = try {
            nativeLock.tryLock(CLOSE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!held) {
            ReadItLog.w("pdf close: render in flight, deferring to background")
            Thread({ nativeLock.withLock { closeNow() } }, "readit-pdf-close").start()
            return
        }
        try {
            closeNow()
        } finally {
            nativeLock.unlock()
        }
    }

    private fun closeNow() {
        pageSizeCache.clear()
        runCatching { document?.let { core.closeDocument(it) } }
        document = null
        pageCount = 0
        runCatching { descriptor?.close() }
        descriptor = null
    }

    companion object {
        /**
         * Pdfium Outline 条数上限。
         *
         * 与兜底路径 [com.readit.pdf.PdfBookmarks] 的 `MAX_ITEMS = 500` 对齐：
         * 畸形 PDF 可能有几万条 Outline，而 getTableOfContents + 建表都发生在主线程，
         * 不封顶就是开书即 ANR。
         */
        const val MAX_BOOKMARK_ITEMS = 500

        /** close() 等待在途渲染的最长时间；超了转后台收尾 */
        private const val CLOSE_WAIT_MS = 400L
    }
}

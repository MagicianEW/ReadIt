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

/**
 * PDF 渲染档封装（规范 §3.2.4，F05/F06/F07）。
 *
 * 底层为 PdfiumAndroid 1.9.0（上游 2018 年起停止维护，锁定版本，见风险 R22）。
 * 方法签名以 AAR 内 classes.jar 的 javap 输出为准：
 *  - PdfiumCore.getPageCount(doc) / openPage() / getPageWidthPoint() / renderPageBitmap(7 参)
 *  - PdfiumCore.getTableOfContents(doc) → List&lt;PdfDocument.Bookmark&gt;，Bookmark.getPageIdx() 为 long
 *
 * 职责边界：
 *  - 渲染：单页位图（一次只持有当前页，配合 [RenderStrategy.pdfCachedPages] 控制缓存）
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

    @Volatile
    var pageCount: Int = 0
        private set

    val isOpen: Boolean get() = document != null

    @Throws(IOException::class)
    fun open(file: File) {
        close()
        val pfd = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            throw IOException("pdf open failed: ${e.message}", e)
        }
        descriptor = pfd
        try {
            val doc = core.newDocument(pfd)
            document = doc
            pageCount = core.getPageCount(doc)
            ReadItLog.i("PdfCore opened: ${file.name} pages=$pageCount")
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            descriptor = null
            throw IOException("pdfium load failed: ${e.message}", e)
        }
    }

    /** 页面尺寸（point，1/72 inch）；返回 [宽, 高]，失败为 [0, 0] */
    fun pageSize(index: Int): IntArray {
        val doc = document ?: return intArrayOf(0, 0)
        if (index < 0 || index >= pageCount) return intArrayOf(0, 0)
        return try {
            core.openPage(doc, index)
            intArrayOf(core.getPageWidthPoint(doc, index), core.getPageHeightPoint(doc, index))
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
            val h = ((ph.toLong() * w) / pw).toInt().coerceAtLeast(1)

            val bitmap = Bitmap.createBitmap(w, h, config)
            core.renderPageBitmap(doc, bitmap, index, 0, 0, w, h)
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
            flatten(core.getTableOfContents(doc), 0)
        } catch (e: Throwable) {
            ReadItLog.w("pdfium bookmarks failed: ${e.message}")
            emptyList()
        }
    }

    private fun flatten(nodes: List<PdfDocument.Bookmark>?, depth: Int): List<PdfBookmark> {
        if (nodes.isNullOrEmpty()) return emptyList()
        val out = ArrayList<PdfBookmark>()
        for (n in nodes) {
            val title = runCatching { n.title }.getOrNull()?.trim().orEmpty()
            val page = runCatching { n.pageIdx.toInt() }.getOrDefault(-1)
            if (title.isNotEmpty()) out.add(PdfBookmark(title, page, depth))
            out.addAll(flatten(runCatching { n.children }.getOrNull(), depth + 1))
        }
        return out
    }

    override fun close() {
        runCatching { document?.let { core.closeDocument(it) } }
        document = null
        pageCount = 0
        runCatching { descriptor?.close() }
        descriptor = null
    }
}

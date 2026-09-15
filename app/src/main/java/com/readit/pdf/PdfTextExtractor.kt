package com.readit.pdf

import com.readit.core.util.ReadItLog
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * PDF 文本提取（规范 §3.2.4，P0-C 门禁 C1）。
 *
 * 文本能力由 PdfBox-Android 承担（barteksc PdfiumAndroid 不暴露文本提取 API）。
 * 关键约束：按页流式提取，避免一次性 getText() 全文档导致 1GB 设备 OOM。
 */
class PdfTextExtractor {

    /**
     * @param tempFileOnly true 时把 PdfBox 的解码缓冲落到临时文件而非内存（低内存档位用）
     * @param tempDir      `tempFileOnly` 时的临时目录，一般为 `context.cacheDir`；
     *                     不传则用 JVM 默认临时目录（Android 上不一定可写，故建议显式传入）
     */
    constructor(tempFileOnly: Boolean = false, tempDir: File? = null) {
        this.tempFileOnly = tempFileOnly
        this.tempDir = tempDir
    }

    private val tempFileOnly: Boolean
    private val tempDir: File?

    @Volatile
    private var document: PDDocument? = null

    val pageCount: Int get() = document?.numberOfPages ?: 0

    @Throws(IOException::class)
    fun open(file: File): PdfTextExtractor {
        close()
        document = try {
            if (tempFileOnly) {
                // 早期版本这里两个分支都写 PDDocument.load(file)（`if (x) A else A`），
                // 低内存档的「缓冲落盘」从来没有生效过 —— 门禁口径形同虚设。
                // 正确做法是用 MemoryUsageSetting 把随机访问缓冲放到 scratch 文件。
                val mus = MemoryUsageSetting.setupTempFileOnly()
                tempDir?.let { d ->
                    if (!d.exists()) d.mkdirs()
                    mus.setTempDir(d)
                }
                PDDocument.load(file, mus)
            } else {
                PDDocument.load(file)
            }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException("pdf open failed: ${e.message}")
        }
        ReadItLog.i(
            "PDF opened: ${file.name}, pages=${document?.numberOfPages} " +
                "tempFileOnly=$tempFileOnly"
        )
        return this
    }

    /** 提取指定页（0-based）文本 */
    @Throws(IOException::class)
    fun extractPage(pageIndex: Int): String {
        val doc = document ?: throw IOException("document not opened")
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return ""
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }
        return runCatching { stripper.getText(doc) }.getOrElse { e ->
            ReadItLog.w("extract page $pageIndex failed: ${e.message}")
            ""
        }
    }

    /** 按页迭代，回调返回每页文本；用于流式阅读与内存受控场景 */
    @Throws(IOException::class)
    fun forEachPage(from: Int = 0, to: Int = Int.MAX_VALUE, onPage: (Int, String) -> Unit) {
        val doc = document ?: throw IOException("document not opened")
        val last = minOf(to, doc.numberOfPages - 1)
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        for (i in from..last) {
            stripper.startPage = i + 1
            stripper.endPage = i + 1
            val text = try {
                stripper.getText(doc)
            } catch (e: Exception) {
                ReadItLog.w("page $i extraction failed: ${e.message}")
                ""
            }
            onPage(i, text)
        }
    }

    /**
     * 扫描版检测（3 页采样）：返回 0.0~1.0 的文本覆盖率估计。
     * 判定口径：字符数过少 且 存在大面积图像 → 视为扫描版。
     */
    fun textCoverage(samplePages: Int = 3): Float {
        val doc = document ?: return 0f
        val total = doc.numberOfPages
        if (total == 0) return 0f
        val step = (total / samplePages).coerceAtLeast(1)
        var hit = 0
        var cnt = 0
        for (i in 0 until total step step) {
            if (cnt >= samplePages) break
            val text = try {
                extractPage(i)
            } catch (e: Exception) {
                ""
            }
            val trimmed = text.trim()
            // 阈值：每页有效字符 > 20 视为有文本层
            if (trimmed.length > MIN_TEXT_PER_PAGE) hit++
            cnt++
        }
        return if (cnt == 0) 0f else hit.toFloat() / cnt
    }

    /** true = 判定为文本版（可走 text/reflow 路径） */
    fun isTextBased(samplePages: Int = 3, threshold: Float = 0.5f): Boolean =
        textCoverage(samplePages) >= threshold

    // ---------------------------------------------------------------- 扫描版检测

    /**
     * 扫描版检测（规范 §3.2.4 / F08）：
     * 3 页采样 + 「文本层字符数」与「整页大图占比」双信号 + 阈值可调。
     *
     * 判定为扫描版的充要条件：
     *  平均每页文本字符数 &lt; thresholds.minCharsPerPage
     *  且 含整页大图的采样页占比 ≥ thresholds.minImagePageRatio
     *
     * 只保证「大概率」，不承诺 100%；调用方必须提供「强制导入」入口。
     */
    fun detectScan(thresholds: ScanThresholds = ScanThresholds.DEFAULT): ScanVerdict {
        val t = thresholds.sanitized()
        val doc = document
            ?: return ScanVerdict(false, 0, 0f, 0f, 0f, "document not opened")
        val total = doc.numberOfPages
        if (total == 0) return ScanVerdict(false, 0, 0f, 0f, 0f, "empty document")

        val samples = sampleIndices(total, t.samplePages)
        var chars = 0
        var imagePages = 0
        var maxAreaRatio = 0f

        for (i in samples) {
            chars += extractPage(i).trim().length
            val ratio = biggestImageAreaRatio(doc, i)
            if (ratio >= t.minImageAreaRatio) imagePages++
            if (ratio > maxAreaRatio) maxAreaRatio = ratio
        }

        val avgChars = chars.toFloat() / samples.size
        val imageRatio = imagePages.toFloat() / samples.size
        val lowText = avgChars < t.minCharsPerPage
        val enoughImages = imageRatio >= t.minImagePageRatio
        val scanned = lowText && enoughImages

        val reason = when {
            scanned -> "low text (${"%.0f".format(avgChars)}<${t.minCharsPerPage}) + full-page images (${"%.2f".format(imageRatio)})"
            !lowText -> "text layer present (${"%.0f".format(avgChars)} chars/page)"
            else -> "low text but no full-page image (${"%.2f".format(imageRatio)})"
        }
        val verdict = ScanVerdict(scanned, samples.size, avgChars, imageRatio, maxAreaRatio, reason)
        ReadItLog.i("scan detect: ${verdict.describe()}")
        return verdict
    }

    /** 均匀采样页索引；采样数 ≥ 总页数时返回全部页 */
    internal fun sampleIndices(total: Int, samplePages: Int): List<Int> {
        val n = samplePages.coerceAtLeast(1)
        if (total <= n) return (0 until total).toList()
        if (n == 1) return listOf(total / 2)
        return (0 until n).map { it * (total - 1) / (n - 1) }.distinct()
    }

    /**
     * 该页最大图像的「像素面积 / 页面点面积」比值。
     * 整页扫描图的像素数远大于页面点数，比值通常 ≫ 1；小图标/水印则 ≪ 1。
     */
    private fun biggestImageAreaRatio(doc: PDDocument, pageIndex: Int): Float {
        return try {
            val page: PDPage = doc.getPage(pageIndex)
            val resources = page.resources ?: return 0f
            val pageArea = (page.mediaBox.width * page.mediaBox.height).toFloat()
            if (pageArea <= 0f) return 0f
            var best = 0f
            for (name in resources.xObjectNames) {
                val obj = runCatching { resources.getXObject(name) }.getOrNull() ?: continue
                if (obj !is PDImageXObject) continue
                val area = (obj.width.toLong() * obj.height.toLong()).toFloat()
                val ratio = area / pageArea
                if (ratio > best) best = ratio
            }
            best
        } catch (e: Throwable) {
            ReadItLog.w("image scan failed at page $pageIndex: ${e.message}")
            0f
        }
    }

    fun close() {
        try {
            document?.close()
        } catch (e: Exception) {
            ReadItLog.w("pdf close failed: ${e.message}")
        }
        document = null
    }

    companion object {
        /** 单页有效字符低于该值视为无文本层（可配置） */
        const val MIN_TEXT_PER_PAGE = 20

        /** 一次抽取全部文本的兜底实现（仅高内存档位使用） */
        @Throws(IOException::class)
        fun extractAll(file: File): String {
            PDDocument.load(file).use { doc ->
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                return stripper.getText(doc)
            }
        }
    }
}

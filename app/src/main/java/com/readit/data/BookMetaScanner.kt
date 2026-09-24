package com.readit.data

import android.content.Context
import com.readit.core.text.ChapterDetector
import com.readit.core.text.EncodingDetector
import com.readit.core.util.ReadItLog
import com.readit.doc.DocxParser
import com.readit.epub.EpubParser
import com.readit.epub.EpubTextExtractor
import com.readit.pdf.PdfCore
import com.readit.pdf.PdfTextExtractor
import com.readit.data.storage.StorageManager
import java.io.File

/**
 * 书库扫描器：按格式算出 [BookMeta] 的 charCount / chapterCount / pageCount，
 * 用 `size:lastModified` 签名做增量判定（内容没变就跳过），全程后台、不阻塞 UI。
 *
 * 设计要点（对照需求与墨水屏弱 SoC 约束）：
 * - **增量**：[ensure] 命中签名就直接返回缓存，只对「没算过 / 文件变了」的书算。
 * - **不阻塞**：调用方在后台线程跑（书架进页面用 [scanLibrary] 触发）；对话框里也走后台
 *   `ensure` 再回主线程刷新，避免 PDF / DOCX 解析卡住界面。
 * - **可降级**：任何格式解析抛错都回落到「只留 addedAt + 签名」的空元数据，绝不让一本书
 *   因为某一格式解析失败而让整个扫描炸掉。
 * - **加入书库时间**：[ensure] 时发现旧记录就沿用其 addedAt，只有首次出现才写当前时间
 *   —— 老书的 addedAt 全部落到「第一次运行本功能的那天」（已知不精确，见文档登记）。
 */
object BookMetaScanner {

    /** 取某本书的元数据：命中缓存则直接返回，否则后台算完落盘再返回 */
    fun ensure(context: Context, file: File, force: Boolean = false): BookMeta {
        val bookId = file.name
        val existing = BookMetaStore.load(context, bookId)
        val sig = BookMetaStore.signatureOf(file)

        if (!force && existing != null && existing.signature == sig && existing.isComputed()) {
            return existing
        }

        val computed = runCatching { compute(file, context) }.getOrDefault(BookMeta())
        val meta = computed.copy(
            addedAt = existing?.addedAt?.takeIf { it >= 0L } ?: System.currentTimeMillis(),
            signature = sig,
            computedAt = System.currentTimeMillis()
        )
        BookMetaStore.save(context, bookId, meta)
        return meta
    }

    /** 后台全库扫描：只对没算过 / 签名变化的书算，已算过的直接跳过 */
    fun scanLibrary(context: Context) {
        val books = runCatching { StorageManager.listBooks(context) }.getOrDefault(emptyList())
        for (f in books) {
            runCatching { ensure(context, f) }
                .onFailure { ReadItLog.w("scan book failed: ${f.name}") }
        }
    }

    // ---------------------------------------------------------------- 格式分发

    private fun compute(file: File, context: Context): BookMeta {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".txt") -> computeTxt(file)
            name.endsWith(".epub") -> computeEpub(file)
            name.endsWith(".docx") -> computeDocx(file)
            name.endsWith(".pdf") -> computePdf(file, context)
            else -> BookMeta()
        }
    }

    /** 纯计算（无 Android 依赖），开放为 internal 供单测直接覆盖 */
    internal fun computeTxt(file: File): BookMeta {
        val charset = EncodingDetector.detectName(file) ?: Charsets.UTF_8.name()
        val text = EncodingDetector.readWith(file, charset)
        val chapters = ChapterDetector.detect(text)
        val chapterCount = if (ChapterDetector.isReliable(chapters, text.length)) chapters.size else -1
        return BookMeta(charCount = text.length.toLong(), chapterCount = chapterCount)
    }

    internal fun computeEpub(file: File): BookMeta {
        val book = EpubParser.parse(file)
        val result = EpubTextExtractor().extract(file, book)
        val charCount = result.text.length.toLong()
        val chapterCount = if (book.toc.isNotEmpty()) book.toc.size else -1
        return BookMeta(charCount = charCount, chapterCount = chapterCount)
    }

    internal fun computeDocx(file: File): BookMeta {
        val text = DocxParser().parseTextOnly(file)
        val stats = DocxParser().parse(file).stats
        val chapterCount = if (stats.headings > 0) stats.headings else -1
        return BookMeta(charCount = text.length.toLong(), chapterCount = chapterCount)
    }

    private fun computePdf(file: File, context: Context): BookMeta {
        val core = PdfCore(context)
        val pageCount = try {
            core.open(file)
            core.pageCount
        } catch (e: Exception) {
            ReadItLog.w("pdf pageCount failed: ${file.name} (${e.message})")
            -1
        } finally {
            runCatching { core.close() }
        }

        var charCount = -1L
        if (pageCount > 0) {
            val ex = PdfTextExtractor()
            runCatching {
                ex.open(file)
                if (ex.isTextBased()) {
                    var total = 0
                    ex.forEachPage { _, text -> total += text.length }
                    charCount = total.toLong()
                }
            }.onFailure { ReadItLog.w("pdf text extract failed: ${file.name}") }
            runCatching { ex.close() }
        }
        return BookMeta(charCount = charCount, pageCount = pageCount)
    }
}

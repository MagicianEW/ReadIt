package com.readit.pdf

import com.readit.core.util.ReadItLog
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * PDF 书签条目（F06）。
 *
 * @param pageIndex 目标页（0-based）；-1 表示书签存在但目标页无法解析
 */
data class PdfBookmark(
    val title: String,
    val pageIndex: Int,
    val depth: Int = 0
)

/**
 * PdfBox-Android 书签解析（规范 §3.2.4「并保留 PdfBox-Android 书签解析作为兜底」）。
 *
 * Pdfium 的 getTableOfContents() 在 Outline 结构异常时可能整体返回空，
 * 这条兜底逐项容错，且完全跑在 JVM 上，可单元测试。
 */
object PdfBoxBookmarks {

    /** 单文档最多解析书签条目数，防止畸形 Outline 撑爆内存 */
    const val MAX_ITEMS = 500

    fun read(file: File, maxItems: Int = MAX_ITEMS): List<PdfBookmark> {
        return try {
            PDDocument.load(file).use { doc ->
                val root = doc.documentCatalog?.documentOutline ?: return emptyList()
                val pageIndex = HashMap<PDPage, Int>()
                for (i in 0 until doc.numberOfPages) {
                    pageIndex[doc.getPage(i)] = i
                }
                val out = ArrayList<PdfBookmark>()
                walk(root.firstChild, 0, doc, pageIndex, out, maxItems)
                ReadItLog.i("pdfbox bookmarks: ${out.size}")
                out
            }
        } catch (e: Throwable) {
            ReadItLog.w("pdfbox bookmarks failed: ${e.message}")
            emptyList()
        }
    }

    private fun walk(
        first: PDOutlineItem?,
        depth: Int,
        doc: PDDocument,
        pageIndex: Map<PDPage, Int>,
        out: MutableList<PdfBookmark>,
        max: Int
    ) {
        var item = first
        while (item != null && out.size < max) {
            // 局部 val，避免 smart cast 被可变捕获挡住
            val current: PDOutlineItem = item
            val title = runCatching { current.title }.getOrNull()?.trim().orEmpty()
            if (title.isNotEmpty()) {
                out.add(PdfBookmark(title, pageIndexOf(current, doc, pageIndex), depth))
            }
            walk(runCatching { current.firstChild }.getOrNull(), depth + 1, doc, pageIndex, out, max)
            item = runCatching { current.nextSibling }.getOrNull()
        }
    }

    private fun pageIndexOf(
        item: PDOutlineItem,
        doc: PDDocument,
        pageIndex: Map<PDPage, Int>
    ): Int {
        return try {
            val page = item.findDestinationPage(doc) ?: return -1
            pageIndex[page] ?: -1
        } catch (e: Throwable) {
            -1
        }
    }
}

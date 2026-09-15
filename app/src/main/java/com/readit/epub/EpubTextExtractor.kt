package com.readit.epub

import android.util.Xml
import com.readit.core.util.ReadItLog
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.ZipFile

/**
 * EPUB 文本抽取降级路径（规范 §3.2.2 降级项，P2）。
 *
 * 触发条件：WebView 能力分级不通过（Chromium < 55 或特性探测失败）。
 * 产物：按 spine 顺序拼接的纯文本 + 每章节字符偏移，交给 TxtCanvasView 渲染。
 *
 * 降级口径：
 *  - 保留：目录、章节顺序、段落换行、粗体/斜体以外的基础文本
 *  - 丢失：图片、复杂排版、CSS 样式、内嵌字体
 *  - 进度口径与规范一致：章节 + 字符偏移
 *
 * 主路径用 XmlPullParser；遇到非良构 XHTML（真实书常见 &nbsp; / 未闭合标签）
 * 自动退化为正则剥离，绝不崩溃。
 */
class EpubTextExtractor {

    data class Result(
        val text: String,
        /** spineIndex -> 该章节在 text 中的起始字符偏移 */
        val spineOffsets: IntArray,
        /** 与 book.toc 一一对应的字符偏移；-1 表示未命中 */
        val tocOffsets: IntArray,
        val warnings: List<String>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Result) return false
            return text == other.text &&
                spineOffsets.contentEquals(other.spineOffsets) &&
                tocOffsets.contentEquals(other.tocOffsets) &&
                warnings == other.warnings
        }

        override fun hashCode(): Int =
            (text.hashCode() * 31 + spineOffsets.contentHashCode()) * 31 + tocOffsets.contentHashCode()
    }

    @Throws(java.io.IOException::class)
    fun extract(file: File, book: EpubParser.Book): Result {
        val warnings = ArrayList<String>()
        val sb = StringBuilder()
        val spineOffsets = IntArray(book.spine.size) { -1 }

        val zip = ZipFile(file)
        try {
            book.spine.forEachIndexed { index, item ->
                if (item.href.isBlank()) {
                    warnings.add("spine[$index] '${item.idref}' has no href")
                    return@forEachIndexed
                }
                spineOffsets[index] = sb.length
                val text = runCatching {
                    val stream = EpubParser.open(zip, item.href.substringBefore('#'))
                    stream.use { toPlainText(readAll(it), warnings) }
                }.getOrElse {
                    warnings.add("spine[$index] read failed -> ${it.message}")
                    ""
                }
                if (text.isBlank()) {
                    warnings.add("spine[$index] empty: ${item.href}")
                }
                sb.append(text)
                if (!text.endsWith('\n')) sb.append('\n')
            }
        } finally {
            runCatching { zip.close() }
        }

        val tocOffsets = IntArray(book.toc.size) { i ->
            val e = book.toc[i]
            if (e.spineIndex in spineOffsets.indices) spineOffsets[e.spineIndex] else -1
        }

        val text = collapseBlankLines(sb.toString()).trim()
        ReadItLog.i(
            "epub text extracted: chars=${text.length} spine=${book.spine.size} " +
                "toc=${book.toc.size} warnings=${warnings.size}"
        )
        return Result(text, spineOffsets, tocOffsets, warnings.distinct())
    }

    // ------------------------------------------------------------------ XHTML → 纯文本

    private fun toPlainText(source: String, warnings: MutableList<String>): String =
        try {
            parseWellFormed(source)
        } catch (e: Exception) {
            warnings.add("xhtml not well-formed -> regex fallback (${e.javaClass.simpleName})")
            stripByRegex(source)
        }

    private fun parseWellFormed(source: String): String {
        val sb = StringBuilder()
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(source))
        }
        var skipDepth = 0
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase(Locale.ROOT)
                    if (n in SKIP_TAGS) {
                        skipDepth++
                    } else if (skipDepth == 0 && n in BLOCK_TAGS) {
                        newLine(sb)
                    }
                }
                XmlPullParser.TEXT -> if (skipDepth == 0) appendRaw(sb, p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name.lowercase(Locale.ROOT)
                    if (n in SKIP_TAGS) {
                        skipDepth = (skipDepth - 1).coerceAtLeast(0)
                    } else if (skipDepth == 0 && n in BLOCK_TAGS) {
                        newLine(sb)
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun stripByRegex(html: String): String {
        var s = html
        s = Regex("<(script|style)\\b[^>]*>.*?</\\1>", RegexOption.IGNORE_CASE)
            .replace(s) { "" }
        s = Regex("<(script|style)\\b[^>]*/?>", RegexOption.IGNORE_CASE).replace(s, "")
        s = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<hr\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("</?(p|div|h[1-6]|li|ul|ol|blockquote|tr|table|pre|dd|dt|dl|" +
            "section|article|figure|figcaption|header|footer)\\b[^>]*>", RegexOption.IGNORE_CASE)
            .replace(s, "\n")
        s = Regex("<[^>]+>").replace(s, "")
        s = decodeEntities(s)
        return s
    }

    private fun decodeEntities(s: String): String {
        var out = s
        out = out.replace("&nbsp;", " ")
        out = out.replace("&amp;", "&")
        out = out.replace("&lt;", "<")
        out = out.replace("&gt;", ">")
        out = out.replace("&quot;", "\"")
        out = out.replace("&apos;", "'")
        out = NUMERIC_ENT.replace(out) { m ->
            val body = m.groupValues[1]
            val v = if (body.startsWith("x", true)) {
                body.substring(1).toIntOrNull(16)
            } else {
                body.toIntOrNull(10)
            }
            v?.toChar()?.toString() ?: m.value
        }
        return out
    }

    private fun appendRaw(sb: StringBuilder, raw: String?) {
        if (raw.isNullOrEmpty()) return
        var pendingSpace = false
        for (c in raw) {
            when {
                c == '\n' || c == '\r' || c == ' ' || c == '\t' || c == ' ' -> pendingSpace = true
                else -> {
                    if (pendingSpace && sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
                    pendingSpace = false
                    sb.append(c)
                }
            }
        }
        if (pendingSpace && sb.isNotEmpty() && sb.last() != '\n') sb.append(' ')
    }

    private fun newLine(sb: StringBuilder) {
        if (sb.isEmpty()) return
        if (sb.last() != '\n') sb.append('\n')
    }

    private fun collapseBlankLines(s: String): String {
        val out = StringBuilder(s.length)
        var newlineRun = 0
        for (c in s) {
            if (c == '\n') {
                newlineRun++
                if (newlineRun <= 2) out.append(c)
            } else {
                newlineRun = 0
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun readAll(input: InputStream): String {
        val bytes = input.readBytes()
        return String(bytes, charsetOf(bytes))
    }

    /** XHTML 编码判定：BOM → XML 声明 → meta charset → UTF-8 兜底 */
    internal fun charsetOf(bytes: ByteArray): java.nio.charset.Charset {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return Charsets.UTF_8
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE

        val head = String(bytes, 0, bytes.size.coerceAtMost(2048), Charsets.ISO_8859_1)
        val decl = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9_\\-]+)[\"']").find(head)
            ?.groupValues?.getOrNull(1)
        val meta = Regex("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)").find(head)
            ?.groupValues?.getOrNull(1)
        val name = decl ?: meta
        if (!name.isNullOrBlank()) {
            return runCatching { java.nio.charset.Charset.forName(name) }.getOrNull() ?: Charsets.UTF_8
        }
        return Charsets.UTF_8
    }

    private companion object {
        val SKIP_TAGS = setOf("script", "style", "head")
        val BLOCK_TAGS = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6",
            "li", "ul", "ol", "dl", "dt", "dd",
            "blockquote", "pre", "table", "tr",
            "section", "article", "aside", "figure", "figcaption",
            "header", "footer", "main", "hr", "br"
        )
        val NUMERIC_ENT = Regex("&#(x?[0-9a-fA-F]+);")
    }
}

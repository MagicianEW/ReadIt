package com.readit.epub

import android.util.Xml
import com.readit.core.util.ReadItLog
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
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
        // 每章首行（去空白后的锚点），用于事后把偏移**重算**到最终文本上。
        // 原因见下面 realignOffsets 的注释。
        val anchors = arrayOfNulls<String>(book.spine.size)
        var totalChars = 0

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
                    stream.use { toPlainText(readAll(it, index, warnings), warnings) }
                }.getOrElse {
                    warnings.add("spine[$index] read failed -> ${it.message}")
                    ""
                }
                if (text.isBlank()) {
                    warnings.add("spine[$index] empty: ${item.href}")
                } else {
                    anchors[index] = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                sb.append(text)
                if (!text.endsWith('\n')) sb.append('\n')

                // 总量护栏：EPUB 侧没有 DOCX 那套进程隔离兜底，zip bomb 或超大书
                // 会在阅读器自身进程把堆吃穿。超了就停，并把已抽到的部分交出去
                // （有内容总比 OOM 崩掉好），同时如实告知截断。
                totalChars += text.length
                if (totalChars >= MAX_TOTAL_CHARS) {
                    warnings.add("正文超过 ${MAX_TOTAL_CHARS} 字符，已截断（后续章节未载入）")
                    return@forEachIndexed
                }
            }
        } finally {
            runCatching { zip.close() }
        }

        val text = collapseBlankLines(sb.toString()).trim()
        realignOffsets(spineOffsets, anchors, text, warnings)

        val tocOffsets = IntArray(book.toc.size) { i ->
            val e = book.toc[i]
            if (e.spineIndex in spineOffsets.indices) spineOffsets[e.spineIndex] else -1
        }

        ReadItLog.i(
            "epub text extracted: chars=${text.length} spine=${book.spine.size} " +
                "toc=${book.toc.size} warnings=${warnings.size}"
        )
        return Result(text, spineOffsets, tocOffsets, warnings.distinct())
    }

    /**
     * 把每章的字符偏移**重算**到最终文本上。
     *
     * 原实现在累积缓冲上记 `spineOffsets[index] = sb.length`，最后却拿
     * `collapseBlankLines(...).trim()` 之后的新串去渲染 —— 中间被折叠掉的空行和
     * 开头的 `trim()` 让偏移随章节推进**单调漂移**，于是目录跳转越到后面越不准
     * （走正则兜底的书从第一章就开始偏，因为标签替换后开头必有空白）。
     * 现有测试只断言「偏移单调递增」，那是恒真的，测不出这个分歧。
     *
     * 做法：记下每章首行作为锚点，在最终文本里从上一章的位置往后找。
     * `collapseBlankLines` 只压缩连续换行、`trim()` 只动首尾空白，都不会删掉非空行，
     * 所以锚点必然还能定位到。
     */
    private fun realignOffsets(
        spineOffsets: IntArray,
        anchors: Array<String?>,
        finalText: String,
        warnings: MutableList<String>
    ) {
        var cursor = 0
        for (i in anchors.indices) {
            val anchor = anchors[i] ?: continue
            val at = finalText.indexOf(anchor, cursor)
            if (at >= 0) {
                spineOffsets[i] = at
                cursor = at
            } else {
                // 锚点找不到（极端的空白折叠）：保守地标 -1，让调用方 filter 掉这条目录项，
                // 好过给一个指到别的章节的偏移
                spineOffsets[i] = -1
                warnings.add("spine[$i] anchor not found after normalisation -> 目录项已略过")
            }
        }
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

    /**
     * 读一个章节的 XHTML，**带体积上限**。
     *
     * 原实现是 `input.readBytes()`：无上限。单章解压出来几百 MB 的 zip bomb，
     * 或者一本几十万字的书，都会在阅读器自身进程 OOM —— EPUB 侧没有 DOCX 的
     * `:converter` 进程隔离兜底，这是降级路径独有的暴露面。
     * 超限时截断并如实告警（书仍能读，只是这一章没读完）。
     */
    private fun readAll(input: InputStream, index: Int, warnings: MutableList<String>): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            if (total + n > MAX_CHAPTER_BYTES) {
                out.write(buf, 0, MAX_CHAPTER_BYTES - total)
                total = MAX_CHAPTER_BYTES
                truncated = true
                break
            }
            out.write(buf, 0, n)
            total += n
        }
        if (truncated) {
            warnings.add("spine[$index] 超过 ${MAX_CHAPTER_BYTES / 1024}KB，已截断")
        }
        val bytes = out.toByteArray()
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
        /** 单章 XHTML 的字节上限（8MB）：超出即截断，防 zip bomb / 超大章节 */
        const val MAX_CHAPTER_BYTES = 8 * 1024 * 1024

        /** 全书正文字符上限（400 万，够 200 万字的书还有余量）：与 PDF 文本档同一量级 */
        const val MAX_TOTAL_CHARS = 4_000_000

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

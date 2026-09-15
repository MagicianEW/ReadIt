package com.readit.doc

import android.util.Xml
import com.readit.core.eal.DocxMode
import com.readit.core.util.ReadItLog
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * DOCX 内置 OOXML 子集解析器原型（规范 §3.2.3，P0-C 门禁 C2）。
 *
 * 不使用 Apache POI（依赖 Java SE 专属类，Android 上抛 NoClassDefFoundError）。
 * 仅依赖平台内置 java.util.zip.ZipFile + XmlPullParser。
 *
 * 输入清单（v1.6 修正补入 numbering.xml）：
 *  - [Content_Types].xml  主文档部件
 *  - word/_rels 关系部件（样式 / 编号 / 图片）
 *  - word/document.xml    正文
 *  - word/numbering.xml   有序列表编号还原（缺失则编号全丢）
 *  - word/media 目录      内嵌图片
 *  - word/styles.xml      标题识别
 *
 * 支持子集：段落、标题、粗体、斜体、下划线、删除线、有序/无序列表、简单表格、内嵌图片、超链接。
 * 降级项：文本框、公式、艺术字、页眉页脚、批注、修订、浮动图片 —— 记录 warning，绝不崩溃。
 */
class DocxParser(
    private val mode: DocxMode = DocxMode.SUBSET,
    private val mediaOutDir: File? = null
) {

    data class Stats(
        val paragraphs: Int,
        val headings: Int,
        val listItems: Int,
        val tables: Int,
        val images: Int,
        val skippedBlocks: Int
    )

    data class Result(
        val html: String,
        val warnings: List<String>,
        val stats: Stats,
        val mediaFiles: List<File>
    )

    @Throws(IOException::class)
    fun parse(file: File): Result {
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)
            val names = zip.entries().toList().map { it.name }.toSet()

            val mainPart = resolveMainPart(zip, names)
            val rels = parseRelationships(zip, relsPath(mainPart))
            val numbering = parseNumbering(zip)
            if (numbering.isEmpty()) {
                ReadItLog.w("numbering.xml missing -> ordered list numbers will fall back to bullets")
            }
            if (mediaOutDir != null && !mediaOutDir.exists()) mediaOutDir.mkdirs()

            val s = State(numbering, rels, names)
            zip.getInputStream(ZipEntry(mainPart)).use { input -> parseDocument(input, s) }
            extractImages(zip, s)

            return Result(
                html = wrapHtml(s),
                warnings = s.warnings.distinct(),
                stats = s.stats(),
                mediaFiles = s.extractedMedia
            )
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            ReadItLog.e("DOCX parse failed", e)
            throw IOException("docx parse failed: ${e.message}")
        } finally {
            try {
                zip?.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /** 兜底：纯文本抽取（1GB 设备 / TEXT_ONLY 档位） */
    @Throws(IOException::class)
    fun parseTextOnly(file: File): String {
        ZipFile(file).use { zip ->
            val names = zip.entries().toList().map { it.name }.toSet()
            val mainPart = resolveMainPart(zip, names)
            val sb = StringBuilder()
            zip.getInputStream(ZipEntry(mainPart)).use { input ->
            val xpp = newParser(input)
            var inPara = false
            while (true) {
                if (xpp.eventType == XmlPullParser.END_DOCUMENT) break
                when (xpp.eventType) {
                    XmlPullParser.START_TAG -> if (xpp.name == "p") inPara = true
                    XmlPullParser.TEXT -> if (inPara) sb.append(xpp.text)
                    XmlPullParser.END_TAG -> if (xpp.name == "p") {
                        inPara = false
                        sb.append('\n')
                    }
                }
                if (safeNext(xpp) == XmlPullParser.END_DOCUMENT) break
            }
            }
            return sb.toString()
        }
    }

    // ---------------------------------------------------------------- parts

    private fun relsPath(part: String): String {
        val dir = part.substringBeforeLast('/', "")
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val file = part.substringAfterLast('/')
        return "${prefix}_rels/$file.rels"
    }

    private fun resolveMainPart(zip: ZipFile, names: Set<String>): String {
        parseRelationships(zip, "_rels/.rels").values
            .firstOrNull { it.type.endsWith("/officeDocument") }?.target?.trimStart('/')?.let {
                if (names.contains(it)) return it
            }
        return when {
            names.contains("word/document.xml") -> "word/document.xml"
            else -> names.firstOrNull { it.startsWith("word/") && it.endsWith(".xml") }
                ?: throw IOException("main document part not found")
        }
    }

    private data class Rel(val target: String, val type: String)

    private fun parseRelationships(zip: ZipFile, path: String): Map<String, Rel> {
        val out = LinkedHashMap<String, Rel>()
        val entry = zip.getEntry(path) ?: return out
        zip.getInputStream(entry).use { input ->
            val xpp = newParser(input)
            while (true) {
                if (xpp.eventType == XmlPullParser.END_DOCUMENT) break
                if (xpp.eventType == XmlPullParser.START_TAG && xpp.name == "Relationship") {
                    val id = xpp.getAttributeValue(null, "Id")
                    val type = xpp.getAttributeValue(null, "Type") ?: ""
                    val raw = xpp.getAttributeValue(null, "Target") ?: ""
                    val target = if (raw.startsWith("/")) raw.trimStart('/') else relativize(path, raw)
                    if (id != null) out[id] = Rel(target, type)
                }
                if (safeNext(xpp) == XmlPullParser.END_DOCUMENT) break
            }
        }
        return out
    }

    private fun relativize(relsPath: String, target: String): String {
        val baseDir = relsPath.substringBeforeLast('/', "")
        val base = if (baseDir.endsWith("_rels")) baseDir.removeSuffix("_rels") else "$baseDir/"
        val parts = (base + target).split('/').toMutableList()
        val resolved = ArrayList<String>()
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> Unit
                p == ".." -> if (resolved.isNotEmpty()) resolved.removeAt(resolved.size - 1)
                else -> resolved.add(p)
            }
        }
        return resolved.joinToString("/")
    }

    // ------------------------------------------------------------ numbering

    private data class LevelDef(val numFmt: String, val lvlText: String, val start: Int)

    private fun parseNumbering(zip: ZipFile): Map<Int, Map<Int, LevelDef>> {
        val entry = zip.getEntry("word/numbering.xml") ?: return emptyMap()
        val abstractMap = HashMap<Int, MutableMap<Int, LevelDef>>()
        val numMap = HashMap<Int, Int>()
        val out = LinkedHashMap<Int, Map<Int, LevelDef>>()

        zip.getInputStream(entry).use { input ->
            val xpp = newParser(input)
            var curAbstract: Int? = null
            var curNumId: Int? = null
            var curLevel: Int? = null
            var numFmt = "decimal"
            var lvlText = ""
            var start = 1

            while (true) {
                if (xpp.eventType == XmlPullParser.END_DOCUMENT) break
                when (xpp.eventType) {
                    XmlPullParser.START_TAG -> when (xpp.name) {
                        "abstractNum" -> curAbstract = attrInt(xpp, "abstractNumId")
                        "num" -> curNumId = attrInt(xpp, "numId")
                        "abstractNumId" -> {
                            val a = attrInt(xpp, "val")
                            val n = curNumId
                            if (n != null && a != null) numMap[n] = a
                        }
                        "lvl" -> {
                            curLevel = attrInt(xpp, "ilvl")
                            numFmt = "decimal"
                            lvlText = ""
                            start = 1
                        }
                        "numFmt" -> numFmt = attr(xpp, "val") ?: "decimal"
                        "lvlText" -> lvlText = attr(xpp, "val") ?: ""
                        "start" -> start = attrInt(xpp, "val") ?: 1
                    }
                    XmlPullParser.END_TAG -> when (xpp.name) {
                        "lvl" -> {
                            val a = curAbstract
                            val l = curLevel
                            if (a != null && l != null) {
                                abstractMap.getOrPut(a) { LinkedHashMap() }[l] =
                                    LevelDef(numFmt, lvlText, start)
                            }
                            curLevel = null
                        }
                        "abstractNum" -> curAbstract = null
                        "num" -> curNumId = null
                    }
                }
                if (safeNext(xpp) == XmlPullParser.END_DOCUMENT) break
            }
        }
        for ((numId, absId) in numMap) abstractMap[absId]?.let { out[numId] = it }
        ReadItLog.i("numbering: ${out.size} num definitions")
        return out
    }

    // ------------------------------------------------------------- document

    private class State(
        val numbering: Map<Int, Map<Int, LevelDef>>,
        val rels: Map<String, Rel>,
        val names: Set<String>
    ) {
        val out = StringBuilder()
        val warnings = ArrayList<String>()
        val extractedMedia = ArrayList<File>()
        val mediaRefs = ArrayList<String>()
        val counters = HashMap<String, Int>()

        var paragraphs = 0
        var headings = 0
        var listItems = 0
        var tables = 0
        var images = 0
        var skipped = 0

        val runs = ArrayList<String>()
        var styleId: String? = null
        var numId: Int? = null
        var ilvl: Int = 0
        var bold = false
        var ital = false
        var under = false
        var strike = false
        var href: String? = null

        var inTable = false
        var inCell = false
        val cellText = StringBuilder()
        val row = ArrayList<String>()
        val rows = ArrayList<List<String>>()

        var skipDepth = 0
        var inT = false
        val textBuffer = StringBuilder()

        fun stats() = Stats(paragraphs, headings, listItems, tables, images, skipped)
    }

    private fun parseDocument(input: InputStream, s: State) {
        val xpp = newParser(input)
        while (true) {
            if (xpp.eventType == XmlPullParser.END_DOCUMENT) break
            when (xpp.eventType) {
                XmlPullParser.START_TAG -> onStart(xpp, s)
                XmlPullParser.TEXT -> if (s.skipDepth == 0 && s.inT) s.textBuffer.append(xpp.text)
                XmlPullParser.END_TAG -> onEnd(xpp, s)
            }
            if (safeNext(xpp) == XmlPullParser.END_DOCUMENT) break
        }
    }

    private fun onStart(xpp: XmlPullParser, s: State) {
        when (xpp.name) {
            // txbxContent / AlternateContent：明确不支持的块
            "AlternateContent" -> {
                s.skipDepth++
                s.skipped++
                s.warnings.add("不支持的兼容块（AlternateContent）已跳过")
            }
            "txbxContent" -> {
                s.skipDepth++
                s.skipped++
                s.warnings.add("文本框内容已跳过（不支持）")
            }
            "tbl" -> if (s.skipDepth == 0) {
                s.inTable = true
                s.rows.clear()
            }
            "tr" -> if (s.skipDepth == 0 && s.inTable) s.row.clear()
            "tc" -> if (s.skipDepth == 0 && s.inTable) {
                s.inCell = true
                s.cellText.setLength(0)
            }
            "p" -> if (s.skipDepth == 0) {
                s.runs.clear()
                s.styleId = null
                s.numId = null
                s.ilvl = 0
                s.href = null
                s.bold = false
                s.ital = false
                s.under = false
                s.strike = false
            }
            "t" -> if (s.skipDepth == 0) {
                // 只接收 <w:t> 内部的文本，标签之间的缩进空白不进正文
                s.inT = true
                s.textBuffer.setLength(0)
            }
            "pStyle" -> s.styleId = attr(xpp, "val")
            "ilvl" -> s.ilvl = attrInt(xpp, "val") ?: 0
            "numId" -> s.numId = attrInt(xpp, "val")
            "b" -> s.bold = true
            "i" -> s.ital = true
            "u" -> s.under = true
            "strike", "dstrike" -> s.strike = true
            "hyperlink" -> s.href = attr(xpp, "r:id")?.let { s.rels[it]?.target }
            "br" -> if (s.skipDepth == 0) s.runs.add("<br/>")
            "tab" -> if (s.skipDepth == 0) s.runs.add("&nbsp;&nbsp;&nbsp;&nbsp;")
            "blip" -> if (s.skipDepth == 0) handleImage(xpp, s)
            "object", "pict" -> {
                s.skipped++
                s.warnings.add("不支持的嵌入对象已跳过")
            }
        }
    }

    private fun onEnd(xpp: XmlPullParser, s: State) {
        when (xpp.name) {
            "txbxContent" -> if (s.skipDepth > 0) s.skipDepth--
            "AlternateContent" -> if (s.skipDepth > 0) s.skipDepth--
            "tc" -> if (s.inCell) {
                s.row.add(s.cellText.toString().trim())
                s.inCell = false
            }
            "tr" -> if (s.inTable && s.row.isNotEmpty()) {
                s.rows.add(ArrayList(s.row))
                s.row.clear()
            }
            "tbl" -> if (s.inTable && s.skipDepth == 0) {
                s.inTable = false
                s.tables++
                emitTable(s)
            }
            "p" -> if (s.skipDepth == 0) flushParagraph(s)
            "r" -> if (s.skipDepth == 0) {
                // run 属性（b/i/u/strike）必须持续到 run 结束，不能在每个空元素结束时复位
                s.bold = false
                s.ital = false
                s.under = false
                s.strike = false
                s.href = null
            }
            "t" -> {
                if (s.inT) {
                    val text = s.textBuffer.toString()
                    s.textBuffer.setLength(0)
                    s.inT = false
                    if (s.skipDepth == 0) {
                        val marked = markRuns(escapeHtml(text), s)
                        if (s.inCell) s.cellText.append(marked) else s.runs.add(marked)
                    }
                }
            }
            "hyperlink" -> s.href = null
        }
    }

    private fun markRuns(html: String, s: State): String {
        if (html.isEmpty()) return html
        var out = html
        if (s.bold) out = "<b>$out</b>"
        if (s.ital) out = "<i>$out</i>"
        if (s.under) out = "<u>$out</u>"
        if (s.strike) out = "<s>$out</s>"
        s.href?.let { out = "<a href=\"$it\">$out</a>" }
        return out
    }

    private fun flushParagraph(s: State) {
        val body = s.runs.joinToString("")
        if (body.isBlank() && s.numId == null) return
        s.paragraphs++

        val style = s.styleId.orEmpty()
        val list = buildListTag(s)

        val html = when {
            list != null -> {
                s.listItems++
                "<${list.first}${list.second}>$body</${list.first}>"
            }
            style.equals("Title", true) || style.startsWith("Heading1") -> {
                s.headings++
                "<h1>$body</h1>"
            }
            style.startsWith("Heading2") -> {
                s.headings++
                "<h2>$body</h2>"
            }
            style.startsWith("Heading") -> {
                s.headings++
                "<h3>$body</h3>"
            }
            body.isBlank() -> "<p>&nbsp;</p>"
            else -> "<p>$body</p>"
        }
        s.out.append(html).append('\n')
    }

    /** @return (tag, attrs) 或 null 表示非列表项 */
    private fun buildListTag(s: State): Pair<String, String>? {
        val numId = s.numId ?: return null
        if (mode == DocxMode.TEXT_ONLY) return null
        val levels = s.numbering[numId]
        val def = levels?.get(s.ilvl)
        val bullet = def?.numFmt == "bullet" || def == null
        return if (bullet) {
            if (levels == null) {
                s.warnings.add("编号定义缺失（numbering.xml），列表按项目符号降级")
            }
            "li" to " class=\"readit-bullet\""
        } else {
            val key = "$numId:${s.ilvl}"
            val seq = (s.counters[key] ?: 0) + 1
            s.counters[key] = seq
            "li" to " class=\"readit-num readit-lvl${s.ilvl}\" data-num=\"$seq\""
        }
    }

    private fun emitTable(s: State) {
        if (s.rows.isEmpty()) return
        if (mode == DocxMode.TEXT_ONLY) {
            s.rows.forEach { row ->
                s.out.append("<p>").append(escapeHtml(row.joinToString(" | "))).append("</p>\n")
            }
            return
        }
        s.out.append("<table border=\"1\" style=\"border-collapse:collapse;width:auto\">\n")
        for (row in s.rows) {
            s.out.append("<tr>")
            for (cell in row) s.out.append("<td style=\"padding:4px\">").append(cell).append("</td>")
            s.out.append("</tr>\n")
        }
        s.out.append("</table>\n")
        s.warnings.add("表格按简单表格渲染（合并单元格/框线不还原）")
    }

    private fun handleImage(xpp: XmlPullParser, s: State) {
        s.images++
        if (mode != DocxMode.SUBSET_WITH_IMAGE) {
            s.warnings.add("图片已省略（当前档位不支持图片）")
            return
        }
        val rid = attr(xpp, "r:embed") ?: attr(xpp, "embed") ?: return
        val target = s.rels[rid]?.target ?: return
        s.mediaRefs.add(target)
        s.runs.add("<img src=\"$IMG_SCHEME$target\" alt=\"image\"/>")
    }

    private fun extractImages(zip: ZipFile, s: State) {
        val dir = mediaOutDir ?: return
        for (target in s.mediaRefs) {
            try {
                val entry = zip.getEntry(target) ?: continue
                val dest = File(dir, target.substringAfterLast('/'))
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                s.extractedMedia.add(dest)
            } catch (e: Exception) {
                ReadItLog.w("extract image failed: $target")
            }
        }
    }

    private fun wrapHtml(s: State): String {
        if (mode == DocxMode.TEXT_ONLY) {
            return "<html><head><meta charset=\"utf-8\"></head><body><pre>\n${s.out}\n</pre></body></html>"
        }
        val body = s.out.toString()
            .replace(
                "(?s)((?:<li class=\"readit-bullet\"[^>]*>.*?</li>\\s*)+)".toRegex()
            ) { "<ul>${it.value}</ul>" }
            .replace(
                "(?s)((?:<li class=\"readit-num[^>]*>.*?</li>\\s*)+)".toRegex()
            ) { "<ol>${it.value}</ol>" }
        return "<html><head><meta charset=\"utf-8\"><style>" +
            "body{font-family:serif;line-height:1.5;margin:12px}" +
            "h1{font-size:1.4em}h2{font-size:1.2em}h3{font-size:1.1em}" +
            ".readit-bullet{list-style:disc inside;margin-left:8px}" +
            ".readit-num{list-style:decimal inside;margin-left:8px}" +
            ".readit-lvl1{margin-left:28px}" +
            "</style></head><body>\n" + body + "</body></html>"
    }

    // --------------------------------------------------------------- helpers

    private fun newParser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setInput(input, "UTF-8")
        return p
    }

    private fun safeNext(p: XmlPullParser): Int = try {
        p.next()
    } catch (e: Exception) {
        ReadItLog.w("xml truncated/stopped: ${e.message}")
        XmlPullParser.END_DOCUMENT
    }

    /**
     * 取属性：先按原始名取，再按 local name 兜底。
     * 必须兜底——命名空间处理开启时 r:embed 会被解析成 (ns, "embed")，
     * 用 "r:embed" 直接取会拿到 null，导致图片引用丢失。
     */
    private fun attr(p: XmlPullParser, name: String): String? {
        p.getAttributeValue(null, name)?.let { return it }
        val local = name.substringAfter(':')
        for (i in 0 until p.attributeCount) {
            val attrName = p.getAttributeName(i)
            if (attrName == name || attrName.substringAfter(':') == local) {
                return p.getAttributeValue(i)
            }
        }
        return null
    }

    private fun attrInt(p: XmlPullParser, name: String): Int? =
        attr(p, name)?.trim()?.toIntOrNull()

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        const val IMG_SCHEME = "readit-media://"
    }
}

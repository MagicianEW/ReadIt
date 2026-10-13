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

            val s = State(numbering, rels, names, parseStyleHeadings(zip, names))
            warnMissingParts(s)
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
        val names: Set<String>,
        /**
         * styleId → 标题层级（1-based，null 表示不是标题）。
         *
         * 来自 `word/styles.xml` 的 `w:outlineLvl`，外加对样式**名字**的识别
         * （"Heading 1" / "标题 1" / "Title"）。原实现只对 `pStyle` 字符串做
         * `startsWith("Heading")` 前缀匹配，于是中文 Word 生成的文档（标题样式
         * 存成 `w:val="1"`）一个标题都识别不出 → 目录全空且无任何提示。
         */
        val styleHeadings: Map<String, Int> = emptyMap()
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
        /** 段落级 `w:outlineLvl`（0-based，-1 = 未设置）；优先于 styleId 的推断 */
        var outlineLevel = -1
        /** run 级 `w:sz`（半磅，null = 未设置） */
        var runHalfPt: Int? = null

        /**
         * 表格嵌套深度。0 = 不在表格内。
         *
         * 原来只有一个 `inTable` 布尔量，于是内层 `<w:tbl>` 会 `rows.clear()` 抹掉外层
         * 已收集的行、内层 `<w:tc>` 的 `cellText.setLength(0)` 抹掉外层单元格文字，
         * 而内层 `</w:tbl>` 把 inTable 置 false 后外层的闭合标签全部走空分支 ——
         * **嵌套表格会静默吞掉外层表格的全部内容**，且降级提示里也不提。
         * 改用深度计数后，内层内容并入外层单元格文本，不再丢字。
         */
        var tableDepth = 0
        val inTable: Boolean get() = tableDepth > 0
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
            // 表格用**深度**而非布尔：内层 tbl 不再清空外层已收集的行，
            // 内层 tc 也不再清空外层单元格文字（见 State.tableDepth 的说明）
            "tbl" -> if (s.skipDepth == 0) {
                s.tableDepth++
                if (s.tableDepth == 1) s.rows.clear()
                else s.warnings.add("嵌套表格已简化（内层并入外层单元格文本）")
            }
            "tr" -> if (s.skipDepth == 0 && s.tableDepth == 1) s.row.clear()
            "tc" -> if (s.skipDepth == 0 && s.tableDepth == 1) {
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
                s.outlineLevel = -1
            }
            "t" -> if (s.skipDepth == 0) {
                // 只接收 <w:t> 内部的文本，标签之间的缩进空白不进正文
                s.inT = true
                s.textBuffer.setLength(0)
            }
            "pStyle" -> s.styleId = attr(xpp, "val")
            "outlineLvl" -> if (s.skipDepth == 0) s.outlineLevel = attrInt(xpp, "val") ?: -1
            "ilvl" -> s.ilvl = attrInt(xpp, "val") ?: 0
            "numId" -> s.numId = attrInt(xpp, "val")
            // b/i/u/strike 必须看 w:val：Word「关闭继承加粗」时写的是 <w:b w:val="0"/>，
            // 只看元素是否出现会把它一律渲染成加粗/下划线。
            "b" -> s.bold = toggleOn(attr(xpp, "val"))
            "i" -> s.ital = toggleOn(attr(xpp, "val"))
            "u" -> s.under = toggleOn(attr(xpp, "val"))
            "strike", "dstrike" -> s.strike = toggleOn(attr(xpp, "val"))
            // w:sz 单位是半磅。原先完全忽略，DOCX 自带的字号层级被拍平成一个大小。
            "sz" -> if (s.skipDepth == 0) {
                attrInt(xpp, "val")?.takeIf { it in 2..400 }?.let { s.runHalfPt = it }
            }
            "hyperlink" -> s.href = attr(xpp, "r:id")?.let { safeHref(s.rels[it]?.target) }
            "br" -> if (s.skipDepth == 0) s.runs.add("<br/>")
            "tab" -> if (s.skipDepth == 0) s.runs.add("&nbsp;&nbsp;&nbsp;&nbsp;")
            "blip" -> if (s.skipDepth == 0) handleImage(xpp, s)
            "object", "pict" -> {
                s.skipped++
                s.warnings.add("不支持的嵌入对象已跳过")
            }
        }
    }

    /**
     * 带 `w:val` 的开关元素取值。
     *
     * `w:val="0"` / `"false"` / `"off"` / `"none"` 都是**关闭**（Word 用它们表示
     * 「显式不要继承」）；缺 `w:val`、或为 `true`/`1`，才是打开。
     */
    private fun toggleOn(valAttr: String?): Boolean {
        val v = valAttr?.trim()?.lowercase() ?: return true
        return v !in OFF_VALUES
    }

    private fun onEnd(xpp: XmlPullParser, s: State) {
        when (xpp.name) {
            "txbxContent" -> if (s.skipDepth > 0) s.skipDepth--
            "AlternateContent" -> if (s.skipDepth > 0) s.skipDepth--
            // 单元格只在**顶层表格**收口：内层 tc 的文本已经并进外层 cellText，
            // 这里再 push 一行就会造出一条错位的行
            "tc" -> if (s.inCell && s.tableDepth == 1) {
                s.row.add(s.cellText.toString().trim())
                s.inCell = false
            }
            "tr" -> if (s.tableDepth == 1 && s.row.isNotEmpty()) {
                s.rows.add(ArrayList(s.row))
                s.row.clear()
            }
            "tbl" -> if (s.tableDepth > 0) {
                s.tableDepth--
                // 只有退回 0 层才 emit：内层单独 emit 会让表格结构错乱
                if (s.tableDepth == 0) {
                    s.tables++
                    emitTable(s)
                }
            }
            "p" -> if (s.skipDepth == 0) flushParagraph(s)
            "r" -> if (s.skipDepth == 0) {
                // run 属性（b/i/u/strike/sz）必须持续到 run 结束，不能在每个空元素结束时复位
                s.bold = false
                s.ital = false
                s.under = false
                s.strike = false
                s.href = null
                s.runHalfPt = null
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
        // w:sz（半磅）→ 行内 font-size。不还原的话 DOCX 自带的字号层级被拍平成一个大小，
        // 排版观感与原文差别明显。用内联样式而不是 class：产物 HTML 没有配套 CSS。
        s.runHalfPt?.let { half ->
            val pt = half / 2f
            if (pt in 4f..96f) out = """<span style="font-size:${trimNum(pt)}pt">$out</span>"""
        }
        // rels 的 Target 来自书内 XML，属**不可信输入**：产物 HTML 开在开着 JS 又无 CSP 的
        // WebView 里（见 DocxWebView），不转义则 Target="x\" onerror=\"..." 可直接注入事件属性。
        // 正文文本走 escapeHtml，这里属性还要多转义引号，故用 escapeAttr。
        s.href?.let { safe -> out = "<a href=\"${escapeAttr(safe)}\">$out</a>" }
        return out
    }

    /** 去掉 ".0" 尾巴，CSS 里 `14.0pt` 与 `14pt` 等价但后者更干净 */
    private fun trimNum(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.1f", v)

    /**
     * 段落 → 标题层级（1-based）；null 表示不是标题。
     *
     * 判定顺序：段落自己的 `w:outlineLvl` > `styles.xml` 里的样式定义 > 样式名启发式。
     * 三条都留是因为真实 docx 三种都见过：英文 Word 走 `Heading1` styleId、
     * 中文 Word 走 `w:val="1"` 的数字样式名、LibreOffice 直接给段落 `w:outlineLvl`。
     * 原实现只有第二条里最窄的 `startsWith("Heading")` 前缀匹配，
     * 中文 Word 生成的文档因此**一个标题都识别不出** → 目录全空且无 warning。
     */
    private fun headingLevelOf(s: State, style: String): Int? {
        if (s.outlineLevel in 0..8) return s.outlineLevel + 1
        s.styleHeadings[style]?.let { return it }
        if (style.equals("Title", true) || style.equals("标题", true)) return 1
        // 样式 id / 名字形如 "Heading3" / "heading 3" / "标题3" / "3"
        val m = Regex("""(?i)^(?:heading|标题)\s*([1-9])$""").find(style) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun flushParagraph(s: State) {
        val body = s.runs.joinToString("")
        if (body.isBlank() && s.numId == null) return
        s.paragraphs++

        val style = s.styleId.orEmpty()
        val list = buildListTag(s)
        val level = headingLevelOf(s, style)

        val html = when {
            list != null -> {
                s.listItems++
                "<${list.first}${list.second}>$body</${list.first}>"
            }
            level != null -> {
                s.headings++
                // 只映射到 h1/h2/h3：更深的层级并入 h3，避免 HTML 里造出无意义的 h7+
                val tag = if (level <= 1) "h1" else if (level == 2) "h2" else "h3"
                "<$tag>$body</$tag>"
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
        // 同 markRuns：Target 不可信，拼进 src 前必须转义属性
        s.runs.add("<img src=\"${escapeAttr(IMG_SCHEME + target)}\" alt=\"image\"/>")
    }

    private fun extractImages(zip: ZipFile, s: State) {
        val dir = mediaOutDir ?: return
        for (target in s.mediaRefs) {
            try {
                val entry = zip.getEntry(target) ?: continue
                // 同名不同目录的图片（media/image1.png 与 media/sub/image1.png）若只取 basename
                // 会互相覆盖，读者看到错图。补上目录层级：sub/image1.png。
                val rel = target.trimStart('/')
                val dest = File(dir, rel)
                // zip slip：Target 来自书内 XML，`../../..` 能把文件写到 media 目录之外
                // （本应用私有目录、乃至应用沙盒内别处）。规范化后必须仍在 dir 之内。
                if (!dest.canonicalPath.startsWith(dir.canonicalPath + File.separator)) {
                    s.warnings.add("图片路径越界已跳过")
                    ReadItLog.w("extract image blocked (zip slip): $target")
                    continue
                }
                dest.parentFile?.mkdirs()
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

    /**
     * 解析 `word/styles.xml` → styleId 到标题层级（1-based）的映射。
     *
     * 只取 `w:style` 的 `w:pPr/w:outlineLvl`（OOXML 里 0-based，9 = 正文不设标题）。
     * 读它是因为**中文 Word 生成的 docx 习惯把标题样式存成数字 id**（`w:val="1"`、
     * `w:val="2"`…），只对 `pStyle` 字符串做 `startsWith("Heading")` 前缀匹配时，
     * 这类文档一个标题都识别不出来 → 目录全空，而降级提示里也不会说明原因。
     * 解析失败一律退化为空表，由 [headingLevelOf] 的样式名启发式兜底。
     */
    private fun parseStyleHeadings(zip: ZipFile, names: Set<String>): Map<String, Int> {
        val entry = zip.getEntry("word/styles.xml") ?: return emptyMap()
        val out = HashMap<String, Int>()
        zip.getInputStream(entry).use { input ->
            val xpp = newParser(input)
            var curId: String? = null
            var depthInStyle = 0
            while (true) {
                if (xpp.eventType == XmlPullParser.END_DOCUMENT) break
                when (xpp.eventType) {
                    XmlPullParser.START_TAG -> when (xpp.name) {
                        "style" -> {
                            curId = attr(xpp, "styleId")
                            depthInStyle = 0
                        }
                        "pPr" -> if (curId != null) depthInStyle++
                        "outlineLvl" -> {
                            val id = curId
                            val lvl = attrInt(xpp, "val")
                            // 0-based → 1-based；9 是 OOXML 的「正文」哨兵值
                            if (id != null && lvl != null && lvl in 0..8) out[id] = lvl + 1
                        }
                    }
                    XmlPullParser.END_TAG -> when (xpp.name) {
                        "pPr" -> if (depthInStyle > 0) depthInStyle--
                        "style" -> curId = null
                    }
                }
                if (safeNext(xpp) == XmlPullParser.END_DOCUMENT) break
            }
        }
        return out
    }

    /**
     * 诚实告知「这些部件我根本没读」。
     *
     * KDoc 早就承诺会为页眉页脚 / 脚注 / 批注 / 修订记录 warning，但代码里一条都没有：
     * `parse()` 只打开主部件、rels、numbering 与 media。用户打开一份带页眉脚注的文档，
     * 会静默缺一大块内容，而提示框还在告诉他「只简化了表格和图片」——
     * 这比不提示更糟。判定方式是看 zip 里**有没有**这些部件，不解析内容。
     */
    private fun warnMissingParts(s: State) {
        val names = s.names
        if (names.any { it.startsWith("word/header") && it.endsWith(".xml") }) {
            s.warnings.add("页眉已省略")
        }
        if (names.any { it.startsWith("word/footer") && it.endsWith(".xml") }) {
            s.warnings.add("页脚已省略")
        }
        if (names.contains("word/footnotes.xml")) s.warnings.add("脚注/尾注已省略")
        if (names.contains("word/endnotes.xml")) s.warnings.add("尾注已省略")
        if (names.contains("word/comments.xml")) s.warnings.add("批注已省略")
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

    /**
     * HTML **属性值**转义：比 [escapeHtml] 多一层引号。
     *
     * 用于把不可信值（rels 的 `Target`）拼进 `href="..."` / `src="..."`。
     * 只转义 `& < >` 是不够的 —— 少了引号，`x" onerror="alert(1)` 这类载荷就能闭合属性并注入事件。
     * 单引号一并转义，防止将来落到单引号属性里时同样被闭合。
     */
    private fun escapeAttr(s: String): String = escapeHtml(s)
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * 只保留可安全放进 `href` 的外链。
     *
     * 转义只能防「逃出属性」，防不住「href 本身就是 javascript:」——
     * 转义后的 `javascript:alert(1)` 仍是可执行链接，点击即跑。
     * 产品上 DOCX 外链不可用（WebView 禁网 + 不外跳导航），一律当无链接处理最省事。
     */
    private fun safeHref(target: String?): String? {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return null
        val scheme = t.substringBefore(':', "").lowercase()
        if (scheme.isNotEmpty() && scheme !in ALLOWED_HREF_SCHEMES) return null
        return t
    }

    companion object {
        const val IMG_SCHEME = "readit-media://"

        /** 允许出现在 DOCX 外链 href 里的 scheme；其余（javascript:/data:/vbscript:…）一律丢弃 */
        private val ALLOWED_HREF_SCHEMES = setOf("http", "https", "mailto", "tel")

        /** `w:val` 里表示「关闭」的取值（b/i/u/strike 等开关元素） */
        private val OFF_VALUES = setOf("0", "false", "off", "none")
    }
}

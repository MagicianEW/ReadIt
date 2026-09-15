package com.readit.epub

import android.util.Xml
import com.readit.core.util.ReadItLog
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB 容器解析（规范 §3.2.2，P2 F03 / F10）。
 *
 * 只依赖平台内置 java.util.zip.ZipFile + XmlPullParser，用于：
 *  - epub.js 完整渲染路径：提供目录（无需等待 JS 回调，首屏即可展示目录）
 *  - 文本抽取降级路径：提供 spine 顺序与章节偏移
 *
 * 覆盖范围：
 *  - META-INF/container.xml → OPF 路径
 *  - OPF：manifest / spine / metadata(dc:title)
 *  - 目录：EPUB3 nav XHTML（优先）→ EPUB2 NCX（回退）→ spine 兜底
 *  - META-INF/encryption.xml 存在即判定为加密（DRM），不支持
 *
 * 绝不因坏文件崩溃：任何异常都降级为 warnings + 可用子集。
 */
object EpubParser {

    private const val NS_EPUB_OPS = "http://www.idpf.org/2007/ops"
    private const val NS_CONTAINER = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val MEDIA_NCX = "application/x-dtbncx+xml"
    private const val MEDIA_OEBPS = "application/oebps-package+xml"

    // ------------------------------------------------------------------ 模型

    data class ManifestItem(
        val id: String,
        /** 相对 zip 根的路径（已解析） */
        val href: String,
        val mediaType: String,
        val properties: String
    )

    data class SpineItem(
        val index: Int,
        val idref: String,
        /** 相对 zip 根的路径（已解析）；idref 找不到 manifest 项时为空串 */
        val href: String,
        val linear: Boolean
    )

    data class TocEntry(
        val title: String,
        /** 相对 zip 根的路径，可能带 #fragment */
        val href: String,
        val depth: Int,
        /** 命中的 spine 下标，-1 表示不在 spine 内 */
        val spineIndex: Int
    )

    enum class TocSource { NAV_XHTML, NCX, SPINE, NONE }

    data class Book(
        val title: String,
        val opfPath: String,
        val manifest: List<ManifestItem>,
        val spine: List<SpineItem>,
        val toc: List<TocEntry>,
        val tocSource: TocSource,
        val encrypted: Boolean,
        val warnings: List<String>
    ) {
        /** 目录条目在纯文本中的章节序号（降级路径用） */
        fun tocIndexOf(href: String): Int {
            val base = href.substringBefore('#')
            return spine.indexOfFirst { it.href.equals(base, ignoreCase = true) }
        }
    }

    // ------------------------------------------------------------------ 入口

    @Throws(java.io.IOException::class)
    fun parse(file: File): Book {
        val warnings = ArrayList<String>()
        val zip = ZipFile(file)
        try {
            val names = LinkedHashSet<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) names.add(entries.nextElement().name)

            val encrypted = names.any { it.equals("META-INF/encryption.xml", ignoreCase = true) }
            if (encrypted) warnings.add("encryption.xml present -> DRM encrypted, not supported")

            val opfPath = resolveOpfPath(zip, names, warnings)
            val (manifest, spineRefs, title, tocId, navId) = parseOpf(zip, opfPath, warnings)
            val manifestById = manifest.associateBy { it.id }
            val manifestByHref = manifest.associateBy { it.href.substringBefore('#') }

            val spine = ArrayList<SpineItem>()
            spineRefs.forEachIndexed { index, ref ->
                val item = manifestById[ref.idref]
                if (item == null) {
                    warnings.add("spine itemref '${ref.idref}' has no manifest entry")
                }
                spine.add(
                    SpineItem(
                        index = index,
                        idref = ref.idref,
                        href = item?.href.orEmpty(),
                        linear = ref.linear
                    )
                )
            }

            var toc: List<TocEntry> = emptyList()
            var source = TocSource.NONE

            val navItem = navId?.let { manifestById[it] }
            if (navItem != null) {
                val parsed = runCatching {
                    parseNavXhtml(open(zip, navItem.href), parentOf(navItem.href), warnings)
                }.getOrElse {
                    warnings.add("nav xhtml parse failed -> ${it.message}")
                    null
                }
                if (!parsed.isNullOrEmpty()) {
                    toc = parsed
                    source = TocSource.NAV_XHTML
                }
            }

            if (toc.isEmpty()) {
                val ncxItem = tocId?.let { manifestById[it] }
                    ?: manifest.firstOrNull { it.mediaType == MEDIA_NCX }
                    ?: names.firstOrNull { it.endsWith(".ncx", ignoreCase = true) }
                        ?.let { found -> ManifestItem(id = "__ncx", href = found, mediaType = MEDIA_NCX, properties = "") }
                if (ncxItem != null) {
                    val parsed = runCatching {
                        parseNcx(open(zip, ncxItem.href), parentOf(ncxItem.href), warnings)
                    }.getOrElse {
                        warnings.add("ncx parse failed -> ${it.message}")
                        null
                    }
                    if (!parsed.isNullOrEmpty()) {
                        toc = parsed
                        source = TocSource.NCX
                    }
                }
            }

            if (toc.isEmpty() && spine.isNotEmpty()) {
                toc = spine.map {
                    TocEntry(
                        title = "${it.index + 1}",
                        href = it.href,
                        depth = 0,
                        spineIndex = it.index
                    )
                }
                source = TocSource.SPINE
                warnings.add("no toc document -> fall back to spine order")
            }

            // 目录 href → spine 下标
            val tocResolved = toc.map { e ->
                val base = e.href.substringBefore('#')
                var idx = spine.indexOfFirst { it.href.equals(base, ignoreCase = true) }
                if (idx < 0) {
                    // 某些书的目录 href 编码不一致，退一步按文件名匹配
                    val tail = base.substringAfterLast('/')
                    if (tail.isNotEmpty()) {
                        idx = spine.indexOfFirst { it.href.substringAfterLast('/').equals(tail, ignoreCase = true) }
                    }
                }
                if (idx < 0 && manifestByHref.containsKey(base)) {
                    warnings.add("toc target not in spine: ${e.href}")
                }
                e.copy(spineIndex = idx)
            }

            ReadItLog.i(
                "epub parsed: title=$title opf=$opfPath manifest=${manifest.size} " +
                    "spine=${spine.size} toc=${tocResolved.size} src=$source encrypted=$encrypted"
            )
            return Book(
                title = title,
                opfPath = opfPath,
                manifest = manifest,
                spine = spine,
                toc = tocResolved,
                tocSource = source,
                encrypted = encrypted,
                warnings = warnings.distinct()
            )
        } finally {
            runCatching { zip.close() }
        }
    }

    // ------------------------------------------------------------------ container.xml

    private fun resolveOpfPath(zip: ZipFile, names: Set<String>, warnings: ArrayList<String>): String {
        val container = names.firstOrNull { it.equals("META-INF/container.xml", ignoreCase = true) }
        if (container == null) {
            warnings.add("META-INF/container.xml missing -> scan for *.opf")
        } else {
            runCatching {
                zip.getInputStream(ZipEntry(container)).use { input ->
                    val p = newParser(input)
                    while (p.next() != XmlPullParser.END_DOCUMENT) {
                        when (p.eventType) {
                            XmlPullParser.START_TAG -> if (p.name == "rootfile") {
                                val fullPath = p.attr("", "full-path")
                                    ?: p.attr(NS_CONTAINER, "full-path")
                                val type = p.attr("", "media-type")
                                    ?: p.attr(NS_CONTAINER, "media-type")
                                    ?: MEDIA_OEBPS
                                if (!fullPath.isNullOrBlank() && type == MEDIA_OEBPS) return fullPath
                            }
                        }
                    }
                }
            }.onFailure {
                warnings.add("container.xml parse failed -> ${it.message}")
            }
        }
        val fallback = names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
        return fallback ?: throw java.io.IOException("no OPF found in epub")
    }

    // ------------------------------------------------------------------ OPF

    private data class OpfResult(
        val manifest: List<ManifestItem>,
        val spine: List<SpineRef>,
        val title: String,
        val tocId: String?,
        val navId: String?
    )

    private data class SpineRef(val idref: String, val linear: Boolean)

    /** NCX navPoint 树节点（仅解析期使用） */
    private class NcxNode(val parent: NcxNode?) {
        var label: StringBuilder? = null
        var src: String? = null
        val children: MutableList<NcxNode> = ArrayList()
    }

    private fun parseOpf(
        zip: ZipFile,
        opfPath: String,
        warnings: ArrayList<String>
    ): OpfResult {
        val base = parentOf(opfPath)
        val manifest = ArrayList<ManifestItem>()
        val spineRefs = ArrayList<SpineRef>()
        var title = ""
        var tocId: String? = null
        var navId: String? = null

        zip.getInputStream(ZipEntry(opfPath)).use { input ->
            val p = newParser(input)
            var inTitle = false
            var inManifest = false
            var inSpine = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "title" -> inTitle = true
                        "manifest" -> inManifest = true
                        "spine" -> {
                            inSpine = true
                            val t = p.attr("", "toc") ?: p.attr(NS_EPUB_OPS, "toc")
                            if (!t.isNullOrBlank()) tocId = t
                        }
                        "item" -> if (inManifest) {
                            val id = p.attr("", "id").orEmpty()
                            val href = p.attr("", "href").orEmpty()
                            val type = p.attr("", "media-type").orEmpty()
                            val props = p.attr("", "properties").orEmpty()
                            if (href.isNotBlank()) {
                                manifest.add(
                                    ManifestItem(
                                        id = id,
                                        href = normalize(base, href),
                                        mediaType = type,
                                        properties = props
                                    )
                                )
                                if (props.split(Regex("\\s+")).contains("nav")) navId = id
                            }
                        }
                        "itemref" -> if (inSpine) {
                            val idref = p.attr("", "idref").orEmpty()
                            if (idref.isNotBlank()) {
                                val linear = (p.attr("", "linear").orEmpty()).equals("no", ignoreCase = true).not()
                                spineRefs.add(SpineRef(idref, linear))
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inTitle && title.isEmpty()) {
                        title = p.text?.trim().orEmpty()
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "title" -> inTitle = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
            }
        }
        if (spineRefs.isEmpty()) warnings.add("empty spine")
        return OpfResult(manifest, spineRefs, title, tocId, navId)
    }

    // ------------------------------------------------------------------ EPUB3 nav

    private fun parseNavXhtml(
        input: InputStream,
        base: String,
        warnings: ArrayList<String>
    ): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        input.use {
            val p = newParser(it)
            var navLevel = 0
            var inNav = false
            var olDepth = 0
            var pendingHref: String? = null
            var titleBuf: StringBuilder? = null

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when {
                        !inNav && p.name == "nav" -> {
                            val type = p.attr(NS_EPUB_OPS, "type") ?: p.attr("", "type").orEmpty()
                            if (type.equals("toc", ignoreCase = true)) {
                                inNav = true
                                navLevel = 0
                            }
                        }
                        inNav && p.name == "nav" -> navLevel++
                        inNav && p.name == "ol" -> olDepth++
                        inNav && p.name == "a" -> {
                            val href = p.attr("", "href")
                            if (pendingHref == null && !href.isNullOrBlank()) {
                                pendingHref = href
                                titleBuf = StringBuilder()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inNav) titleBuf?.append(p.text)
                    XmlPullParser.END_TAG -> if (inNav) when (p.name) {
                        "a" -> {
                            val t = titleBuf?.toString()?.trim().orEmpty()
                            val h = pendingHref
                            if (!h.isNullOrBlank()) {
                                out.add(
                                    TocEntry(
                                        title = t.ifEmpty { h.substringBefore('#').substringAfterLast('/') },
                                        href = normalize(base, h),
                                        depth = (olDepth - 1).coerceAtLeast(0),
                                        spineIndex = -1
                                    )
                                )
                            }
                            pendingHref = null
                            titleBuf = null
                        }
                        "ol" -> olDepth--
                        "nav" -> if (navLevel == 0) {
                            inNav = false
                        } else {
                            navLevel--
                        }
                    }
                }
            }
        }
        if (out.isEmpty()) warnings.add("nav document has no toc entries")
        return out
    }

    // ------------------------------------------------------------------ EPUB2 NCX

    private fun parseNcx(
        input: InputStream,
        base: String,
        warnings: ArrayList<String>
    ): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        input.use {
            val p = newParser(it)
            // navPoint 可无限嵌套：内层的 <navLabel>/<content> 是独立节点，
            // 必须建树后再按文档序展开，否则父节点会被子节点覆盖或顺序倒置。
            val root = NcxNode(null)
            val stack = ArrayList<NcxNode>().apply { add(root) }
            var inLabel = false
            var inText = false

            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "navPoint" -> {
                            val node = NcxNode(stack.last())
                            stack.last().children.add(node)
                            stack.add(node)
                        }
                        "navLabel" -> {
                            inLabel = true
                            stack.last().label = StringBuilder()
                        }
                        "text" -> if (inLabel) inText = true
                        "content" -> {
                            val s = p.attr("", "src")
                            val top = stack.last()
                            if (top.src == null && !s.isNullOrBlank()) top.src = s
                        }
                    }
                    XmlPullParser.TEXT -> if (inText) stack.last().label?.append(p.text)
                    XmlPullParser.END_TAG -> when (p.name) {
                        "text" -> inText = false
                        "navLabel" -> inLabel = false
                        "navPoint" -> if (stack.size > 1) stack.removeAt(stack.size - 1)
                    }
                }
            }

            fun flatten(node: NcxNode, depth: Int) {
                node.children.forEach { child ->
                    val h = child.src
                    if (!h.isNullOrBlank()) {
                        val t = child.label?.toString()?.trim().orEmpty()
                        out.add(
                            TocEntry(
                                title = t.ifEmpty { h.substringBefore('#').substringAfterLast('/') },
                                href = normalize(base, h),
                                depth = depth,
                                spineIndex = -1
                            )
                        )
                    }
                    flatten(child, depth + 1)
                }
            }
            flatten(root, 0)
        }
        if (out.isEmpty()) warnings.add("ncx has no navPoint")
        return out
    }

    // ------------------------------------------------------------------ 工具

    internal fun newParser(input: InputStream): XmlPullParser =
        Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, null)
        }

    internal fun open(zip: ZipFile, path: String): InputStream {
        val direct = runCatching { zip.getInputStream(ZipEntry(path)) }.getOrNull()
        if (direct != null) return direct
        val encoded = path.replace(" ", "%20")
        return zip.getInputStream(ZipEntry(encoded))
    }

    private fun XmlPullParser.attr(ns: String, name: String): String? =
        getAttributeValue(ns, name)

    internal fun parentOf(path: String): String {
        val i = path.lastIndexOf('/')
        return if (i >= 0) path.substring(0, i) else ""
    }

    /**
     * 解析相对路径：合并 base、折叠 ./ 与 ../、保留 #fragment。
     * 只做百分号解码，不做 '+' → 空格（文件名允许 '+'）。
     */
    internal fun normalize(base: String, relative: String): String {
        val hash = relative.indexOf('#')
        val rawPath = if (hash >= 0) relative.substring(0, hash) else relative
        val frag = if (hash >= 0) relative.substring(hash) else ""
        val decoded = percentDecode(rawPath)
        val segs = ArrayList<String>()
        if (!decoded.startsWith("/") && base.isNotEmpty()) segs.addAll(base.split('/'))
        segs.addAll(decoded.split('/'))
        val out = ArrayList<String>()
        for (s in segs) {
            when {
                s.isEmpty() || s == "." -> Unit
                s == ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(s)
            }
        }
        return out.joinToString("/") + frag
    }

    private fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    sb.append(v.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

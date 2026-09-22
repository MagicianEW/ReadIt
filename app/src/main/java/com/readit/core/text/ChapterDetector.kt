package com.readit.core.text

/**
 * TXT 章节检测（规范 §3.2.1，F02）。
 *
 * 目标：基准语料识别率 > 90%；无章节时由调用方按长度分页（TxtPager 兜底）。
 * 设计原则：宁可少判（漏检按正文处理），不可误判正文为标题。
 */
object ChapterDetector {

    data class Chapter(
        val index: Int,
        val title: String,
        /** 标题行在全文中的字符偏移 */
        val startOffset: Int,
        /** 正文起始偏移（标题行之后） */
        val bodyOffset: Int
    ) {
        fun contains(offset: Int, next: Chapter?): Boolean =
            offset >= startOffset && (next == null || offset < next.startOffset)
    }

    /** 标题行最大长度：超过则认为是正文段落 */
    private const val MAX_TITLE_LEN = 40

    /** 中文数字 */
    private const val CN = "零〇一二三四五六七八九十百千两"

    private val CN_CHAPTER = Regex("^\\s*第\\s*[${CN}0-9]+\\s*[章节節回卷篇部集幕]\\s*[:：、.．·\\-]*\\s*.*$")
    private val CN_VOLUME = Regex("^\\s*[卷篇部集]\\s*[${CN}0-9]+\\s*[:：、.．·\\-]*\\s*.*$")
    private val EN_CHAPTER = Regex("^\\s*(?i)(chapter|part|book|section)\\s+([0-9]+|[$CN]+)\\s*[:：.．\\-]*\\s*.*$")
    private val EN_NUM = Regex("^\\s*[0-9]+\\s*\\.\\s+\\S.{0,38}$")
    private val CN_NUM = Regex("^\\s*[0-9]+\\s*[、．]\\s*\\S.{0,38}$")
    private val SPECIAL = Regex("^\\s*(序言|序|前言|引子|楔子|后记|後記|尾声|尾聲|附录|附錄|番外|结束语).{0,20}$")

    /**
     * 小节号：`1.1 起手式`、`20.1 断剑`。
     *
     * 与 EN_NUM 分开写，是因为 `1.1` 里的点号后面紧跟数字，EN_NUM 要求点号后是空白，
     * 会把整类小节标题全漏掉（基准实测漏检占召回缺口的 ~1/3）。
     */
    private val SECTION_NUM = Regex("^\\s*[0-9]+(?:\\.[0-9]+)+\\s+\\S.{0,38}$")

    /**
     * 目录页引导行的指纹：成串的点号。
     *
     * 网络 TXT 常在正文前塞一页目录（`第一章 少年············12`），
     * 这些行在字面上完全符合章节样式，不拦就是整页目录被当成章节（基准实测误判主因）。
     */
    private val TOC_DOTS = Regex("[.．·・⋯…]{3,}")

    /**
     * 编号 + 纯数字尾巴：`2023. 12`、`1998. 7`。
     *
     * 这类行在字面上完全符合 EN_NUM，但「数字. 数字」是日期/编号碎片，不是章节标题。
     * 只拦「尾巴是纯数字」这一种，不动 `1. 起始` 这类正常编号标题。
     */
    private val NUM_ONLY_TAIL = Regex("^\\s*[0-9]+\\s*[.．、]\\s*[0-9]+\\s*$")

    /** 网络 TXT 常见的标题包裹符，判定时先行剥离 */
    private val WRAPPERS = listOf(
        '【' to '】', '「' to '」', '『' to '』', '《' to '》', '〈' to '〉',
        '（' to '）', '(' to ')', '[' to ']', '{' to '}', '<' to '>', '"' to '"'
    )

    /** 全角数字 -> ASCII；全角空格 -> 半角。第３章 与 第3章 是同一个意思 */
    private fun normalize(s: String): String {
        var out = s
        var changed = false
        // 仅在确实存在全角字符时才新建字符串，避免每行都做一次拷贝
        for (i in 0 until out.length) {
            val c = out[i]
            if (c in '０'..'９' || c == '　' || c == '．' || c == '：') { changed = true; break }
        }
        if (!changed) return out
        val sb = StringBuilder(out.length)
        for (c in out) {
            sb.append(
                when (c) {
                    in '０'..'９' -> (c.code - '０'.code + '0'.code).toChar()
                    '　' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** 剥离成对包裹符（【】「」《》（）等），只剥两端成对的部分 */
    private fun unwrap(s: String): String {
        var cur = s
        var stripped = true
        while (stripped && cur.length >= 2) {
            stripped = false
            for ((open, close) in WRAPPERS) {
                if (cur.first() == open && cur.last() == close) {
                    cur = cur.substring(1, cur.length - 1).trim()
                    stripped = true
                    break
                }
            }
        }
        return cur
    }

    /**
     * @param text 全文
     * @param maxChapters 上限保护，避免超长文档产生巨量伪章节
     * @return 章节列表（按 offset 升序）；无章节时返回空列表
     */
    fun detect(text: String, maxChapters: Int = 2000): List<Chapter> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Chapter>()
        var offset = 0
        var lineStart = 0
        val len = text.length

        while (offset <= len) {
            val nl = text.indexOf('\n', offset)
            val lineEnd = if (nl < 0) len else nl
            val line = text.substring(lineStart, lineEnd).trim()
            if (line.isNotEmpty() && isHeading(line)) {
                val bodyOffset = skipBlank(text, lineEnd)
                out.add(
                    Chapter(
                        index = out.size,
                        title = line,
                        startOffset = lineStart,
                        bodyOffset = bodyOffset
                    )
                )
                if (out.size >= maxChapters) break
            }
            if (nl < 0) break
            offset = nl + 1
            lineStart = offset
        }
        return out
    }

    /** 判断单行是否为标题 */
    fun isHeading(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty() || s.length > MAX_TITLE_LEN) return false
        // 行尾以句号/逗号结尾的，基本是正文
        if (s.endsWith("。") || s.endsWith("，") || s.endsWith(",")) return false
        // 目录页引导行：成串点号 + 页码，不是正文标题
        if (TOC_DOTS.containsMatchIn(s)) return false

        val core = normalize(unwrap(s))
        // 「2023. 12」这类编号碎片：尾巴是纯数字，不是标题
        if (NUM_ONLY_TAIL.matches(core)) return false
        if (core.isEmpty()) return false
        return CN_CHAPTER.matches(core) ||
            CN_VOLUME.matches(core) ||
            EN_CHAPTER.matches(core) ||
            EN_NUM.matches(core) ||
            SECTION_NUM.matches(core) ||
            CN_NUM.matches(core) ||
            SPECIAL.matches(core)
    }

    /** 章节过密（疑似误判）时，调用方应回退为按长度分页 */
    fun isReliable(chapters: List<Chapter>, textLength: Int, minAvgChars: Int = 500): Boolean {
        if (chapters.isEmpty()) return false
        return textLength / chapters.size >= minAvgChars
    }

    private fun skipBlank(text: String, from: Int): Int {
        var i = from
        while (i < text.length && (text[i] == '\n' || text[i] == '\r' || text[i] == ' ' || text[i] == '　')) i++
        return i
    }
}

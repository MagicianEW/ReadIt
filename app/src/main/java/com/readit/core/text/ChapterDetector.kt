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
        return CN_CHAPTER.matches(s) ||
            CN_VOLUME.matches(s) ||
            EN_CHAPTER.matches(s) ||
            EN_NUM.matches(s) ||
            CN_NUM.matches(s) ||
            SPECIAL.matches(s)
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

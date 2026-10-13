package com.readit.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F02：章节检测识别率（目标 > 90%）。
 */
class ChapterDetectorTest {

    /** 基准语料：24 个真实章节标题（覆盖中英文与常见编号写法） */
    private val headingLines = listOf(
        "第一章 少年出山",
        "第二章 无名心法",
        "第3章 江湖再见",
        "第十章 断桥残雪",
        "第 十二 章 风雪夜归",
        "卷一 苍穹之始",
        "卷二 长夜将至",
        "Chapter 1 The Beginning",
        "Chapter 12 The Return",
        "Part 3 A New Hope",
        "1. 初入江湖",
        "2. 拜师学艺",
        "3、初露锋芒",
        "4、声名鹊起",
        "序言",
        "前言",
        "引子",
        "楔子",
        "后记",
        "尾声",
        "附录 名词表",
        "番外 十年之后",
        "第二十一章 大结局",
        "第一百章 破碎虚空"
    )

    /** 干扰正文（不应被判为标题） */
    private val noiseLines = listOf(
        "他缓缓抬起头，看向远处的山脊，风从谷口吹过来，带着雪的味道。",
        "这是一段很长的正文，用来验证检测器不会把普通段落误判成章节标题。",
        "“你来了。”那人说道，声音沙哑得像砂纸擦过木头。",
        "The quick brown fox jumps over the lazy dog, again and again and again.",
        "他今年二十岁，家中排行第三，所以村里人都叫他三郎。",
        "第一章的内容其实在第二页才真正开始，这句话本身是正文。"
    )

    @Test
    fun `recall on benchmark corpus is above 90 percent`() {
        val hit = headingLines.count { ChapterDetector.isHeading(it) }
        val rate = hit.toFloat() / headingLines.size
        println("[CHAPTER] recall = $hit/${headingLines.size} = ${"%.2f".format(rate)}")
        headingLines.filter { !ChapterDetector.isHeading(it) }.forEach {
            println("[CHAPTER] MISSED: $it")
        }
        assertTrue("识别率需 > 90%，实际 ${"%.2f".format(rate)}", rate > 0.90f)
    }

    @Test
    fun `noise paragraphs are not treated as headings`() {
        val falsePositives = noiseLines.filter { ChapterDetector.isHeading(it) }
        println("[CHAPTER] false positives = ${falsePositives.size}")
        falsePositives.forEach { println("[CHAPTER] FP: $it") }
        assertEquals("正文误判需为 0", 0, falsePositives.size)
    }

    @Test
    fun `detect returns ordered chapters with offsets`() {
        val sb = StringBuilder()
        headingLines.forEach { title ->
            sb.append(title).append('\n')
            sb.append("正文：").append("字数填充".repeat(30)).append("\n\n")
        }
        val text = sb.toString()
        val chapters = ChapterDetector.detect(text)

        assertEquals(headingLines.size, chapters.size)
        assertTrue("章节需按 offset 升序", chapters.zipWithNext().all { (a, b) -> a.startOffset < b.startOffset })
        assertEquals("第一章 少年出山", chapters.first().title)
        assertTrue("首个章节应在正文之前", chapters.first().bodyOffset > chapters.first().startOffset)

        // 偏移量可回查
        chapters.forEach { ch ->
            assertEquals(
                "offset 应指向标题行",
                ch.title,
                text.substring(ch.startOffset, text.indexOf('\n', ch.startOffset)).trim()
            )
        }
    }

    @Test
    fun `no chapter means length based fallback`() {
        val plain = "没有任何标题的一整段小说正文。\n".repeat(50)
        val chapters = ChapterDetector.detect(plain)
        assertTrue("无章节时 detect 应返回空", chapters.isEmpty())
        assertFalse("应判定为不可靠，交由长度分页兜底",
            ChapterDetector.isReliable(chapters, plain.length))
    }

    @Test
    fun `dense false chapters are marked unreliable`() {
        val text = "1. 行\n".repeat(1000)
        val chapters = ChapterDetector.detect(text)
        assertFalse("过密章节应判定为不可靠", ChapterDetector.isReliable(chapters, text.length))
    }

    // ------------------------------------------------------------ 上限截断（必须对用户可见）

    @Test
    fun `truncation flag is set when more headings remain past the cap`() {
        val sb = StringBuilder()
        repeat(30) { i ->
            sb.append("第").append(i + 1).append("章 标题\n")
            sb.append("正文".repeat(20)).append("\n")
        }
        val r = ChapterDetector.detectDetailed(sb.toString(), maxChapters = 10)
        assertEquals("必须按上限停", 10, r.chapters.size)
        assertTrue("后面还有内容，必须标记为被截断", r.truncated)
    }

    @Test
    fun `truncation flag stays false when the cap hit heading is the very last line`() {
        // 唯一能证明「没有下文」的情形：触发上限的那一行就是全文最后一行。
        val text = (1 until 5).joinToString("\n") { "第${it + 1}章 标题\n正文正文正文" } +
            "\n第5章 标题"
        val r = ChapterDetector.detectDetailed(text, maxChapters = 5)
        assertEquals(5, r.chapters.size)
        assertFalse("上限正好落在全文末尾，不算被截断", r.truncated)
    }

    @Test
    fun `truncation flag is set when the cap hit heading still has body text after it`() {
        // 反过来这一条才是常态：第 2000 个标题后面还有正文（哪怕后面再没有标题），
        // 用户看到的目录就是残缺的，必须提示。
        val sb = StringBuilder()
        repeat(5) { i ->
            sb.append("第").append(i + 1).append("章 标题\n")
            sb.append("正文".repeat(20)).append("\n")
        }
        val r = ChapterDetector.detectDetailed(sb.toString(), maxChapters = 5)
        assertEquals(5, r.chapters.size)
        assertTrue("后面还有正文就必须提示", r.truncated)
    }

    @Test
    fun `truncation flag stays false for short texts`() {
        val r = ChapterDetector.detectDetailed("第1章 标题\n正文\n", maxChapters = 2000)
        assertFalse(r.truncated)
        assertEquals(1, r.chapters.size)
    }

    @Test
    fun `detect keeps returning the bare chapter list`() {
        val text = "第1章 甲\n正文\n第2章 乙\n正文\n"
        assertEquals(2, ChapterDetector.detect(text).size)
        assertEquals(ChapterDetector.detect(text), ChapterDetector.detectDetailed(text).chapters)
    }
}

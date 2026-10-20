package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 书架列表行的组装逻辑（纯函数）。
 *
 * 守的是「副标题该显示什么、什么时候不显示」：
 * 拿不到的项**不写占位符**（属性对话框要字段齐全所以用「—」，列表副标题是给人一眼扫的，
 * 多一个「—」只会把行撑长）。
 */
class ShelfRowTest {

    @Test
    fun `compact count switches to wan at ten thousand`() {
        assertEquals("9999", formatCompactCount(9_999L))
        assertEquals("1万", formatCompactCount(10_000L))
        assertEquals("1.2万", formatCompactCount(12_345L))
        assertEquals("12.3万", formatCompactCount(123_456L))
        assertEquals("100万", formatCompactCount(1_000_000L))
        assertEquals("1亿", formatCompactCount(100_000_000L))
    }

    @Test
    fun `compact count drops fake precision`() {
        // 「12.0万」是假精度，中文语境里没意义
        assertEquals("20万", formatCompactCount(200_000L))
        assertEquals("3万", formatCompactCount(30_000L))
    }

    @Test
    fun `meta text combines chars and chapters`() {
        val meta = BookMeta(charCount = 123_456L, chapterCount = 86)
        assertEquals("12.3万字 · 86章", buildShelfMetaText(meta))
    }

    @Test
    fun `meta text omits unknown fields instead of placeholding them`() {
        // 只有字数
        assertEquals("5000字", buildShelfMetaText(BookMeta(charCount = 5_000L)))
        // 只有章节
        assertEquals("12章", buildShelfMetaText(BookMeta(chapterCount = 12)))
        // 都没有 -> 空串（整行隐藏）
        assertEquals("", buildShelfMetaText(BookMeta()))
        assertEquals("", buildShelfMetaText(null))
    }

    @Test
    fun `row has no percent before the book was ever opened`() {
        val row = buildShelfRow(File("a.txt"), BookMeta(charCount = 1000L), null)
        assertNull(row.percent)
        assertTrue(row.metaText.isNotEmpty())
    }

    @Test
    fun `row percent comes from char offset for text books`() {
        val row = buildShelfRow(
            File("a.txt"),
            BookMeta(charCount = 1000L),
            ReadingPosition(charOffset = 250)
        )
        assertEquals(25, row.percent)
        assertTrue(row.hasProgress)
    }

    @Test
    fun `row percent comes from page index for pdf`() {
        val row = buildShelfRow(
            File("a.pdf"),
            BookMeta(pageCount = 200),
            ReadingPosition(pageIndex = 49)
        )
        assertEquals(25, row.percent)
    }

    @Test
    fun `row has no percent when metadata was never computed`() {
        // 元数据还没算出来：分母不存在，算不出百分比
        val row = buildShelfRow(File("a.txt"), null, ReadingPosition(charOffset = 100))
        assertNull(row.percent)
        assertEquals("", row.metaText)
    }

    @Test
    fun `percent never exceeds bounds`() {
        val row = buildShelfRow(
            File("a.txt"),
            BookMeta(charCount = 100L),
            ReadingPosition(charOffset = 999_999)
        )
        assertEquals(100, row.percent)
    }
}

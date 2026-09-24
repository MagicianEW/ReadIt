package com.readit.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookMetaScannerTest {

    @Test
    fun `TXT 算字数等于解码后文本长度`() {
        val content = "第一章 序\n这是一段正文，用来统计字数。\n第二章 发展\n更多正文内容。".repeat(20)
        val f = File.createTempFile("bm_", ".txt").apply {
            writeText(content, Charsets.UTF_8)
        }
        try {
            val meta = BookMetaScanner.computeTxt(f)
            assertEquals(content.length.toLong(), meta.charCount)
            // 章节数要么识别到 >=1，要么判定不可靠返回 -1，二者皆合法（不崩即可）
            assertTrue("chapterCount 非法: ${meta.chapterCount}", meta.chapterCount == -1 || meta.chapterCount >= 1)
        } finally {
            f.delete()
        }
    }
}

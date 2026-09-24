package com.readit.data

import com.readit.data.ReadingPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BookMetaTest {

    // ---------------------------------------------------------------- 进度百分比

    @Test
    fun `文本格式按 charOffset 算进度`() {
        val meta = BookMeta(charCount = 1000)
        val pos = ReadingPosition(charOffset = 250)
        assertEquals(25, computeProgressPercent(meta, pos))
    }

    @Test
    fun `文本格式进度夹断到 100`() {
        val meta = BookMeta(charCount = 100)
        val pos = ReadingPosition(charOffset = 999)
        assertEquals(100, computeProgressPercent(meta, pos))
    }

    @Test
    fun `PDF 按 pageIndex 算进度`() {
        val meta = BookMeta(pageCount = 10)
        val pos = ReadingPosition(pageIndex = 4) // 第 5 页（0-based）
        assertEquals(50, computeProgressPercent(meta, pos))
    }

    @Test
    fun `无进度记录返回 null`() {
        assertNull(computeProgressPercent(BookMeta(charCount = 100), null))
    }

    @Test
    fun `没有分母时返回 null（算不出）`() {
        // 扫描版 PDF：charCount=-1、pageCount=-1，即使有 pageIndex 也算不出百分比
        val meta = BookMeta(charCount = -1, pageCount = -1)
        val pos = ReadingPosition(pageIndex = 3)
        assertNull(computeProgressPercent(meta, pos))
    }

    // ---------------------------------------------------------------- 对话框文案

    @Test
    fun `未开始显示未开始`() {
        val vm = buildBookMetaView(BookMeta(charCount = 100), null)
        assertEquals("未开始", vm.progressText)
    }

    @Test
    fun `算不出进度显示破折号`() {
        val vm = buildBookMetaView(BookMeta(charCount = -1, pageCount = -1), ReadingPosition(pageIndex = 3))
        assertEquals("—", vm.progressText)
    }

    @Test
    fun `字数带千分位与单位`() {
        val vm = buildBookMetaView(BookMeta(charCount = 123456), null)
        assertEquals("123,456 字", vm.charCountText)
    }

    @Test
    fun `字数未知显示破折号`() {
        val vm = buildBookMetaView(BookMeta(charCount = -1), null)
        assertEquals("—", vm.charCountText)
    }

    @Test
    fun `章节有值带章字、未知显示破折号`() {
        assertEquals("12 章", buildBookMetaView(BookMeta(chapterCount = 12), null).chapterText)
        assertEquals("—", buildBookMetaView(BookMeta(chapterCount = -1), null).chapterText)
    }

    @Test
    fun `加入时间格式化、未知显示破折号`() {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val t = 1_700_000_000_000L
        val vm = buildBookMetaView(BookMeta(addedAt = t), null)
        assertEquals(fmt.format(java.util.Date(t)), vm.addedAtText)
        assertEquals("—", buildBookMetaView(BookMeta(addedAt = -1), null).addedAtText)
    }

    // ---------------------------------------------------------------- 文件指纹

    @Test
    fun `签名随 size 变化`() {
        val f = File.createTempFile("bmt", ".txt")
        f.writeText("hello")
        val s1 = BookMetaStore.signatureOf(f)
        f.writeText("hello world!!") // 内容变长 -> size 变
        val s2 = BookMetaStore.signatureOf(f)
        assert(s1 != s2) { "size 变了签名应变: $s1 vs $s2" }
        f.delete()
    }
}

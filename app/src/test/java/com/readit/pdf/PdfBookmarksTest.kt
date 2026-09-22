package com.readit.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F06 书签解析（PdfBox 兜底路径）。
 *
 * 这条路径的价值在于「Pdfium 的 getTableOfContents() 整体返空时还能救回来」，
 * 所以它自己必须是**逐项容错**的：单条书签的目标页解析不出来，只该让那一条 pageIndex=-1，
 * 不该连累整份大纲。这里把四种形态都钉住：平铺 / 嵌套 / 无大纲 / 目标页丢失。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfBookmarksTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_bm_test").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("fixtures/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    @Test
    fun `flat outline keeps order and page indices`() {
        val items = PdfBoxBookmarks.read(fixture("readit_outline_flat.pdf"))
        println("[BM flat] $items")
        assertEquals(3, items.size)
        assertEquals("第一章 山下的少年", items[0].title)
        assertEquals(0, items[0].pageIndex)
        assertEquals("第二章 断剑", items[1].title)
        assertEquals(2, items[1].pageIndex)
        assertEquals("第三章 雨夜", items[2].title)
        assertEquals(4, items[2].pageIndex)
        assertTrue("平铺大纲 depth 应全为 0", items.all { it.depth == 0 })
    }

    @Test
    fun `nested outline preserves depth`() {
        val items = PdfBoxBookmarks.read(fixture("readit_outline_nested.pdf"))
        println("[BM nested] $items")
        assertEquals(5, items.size)
        assertEquals(0, items[0].depth)
        assertEquals("第一卷", items[0].title)
        assertTrue("子条目 depth 应为 1", items[1].depth == 1 && items[2].depth == 1)
        assertEquals(0, items[3].depth)
        assertTrue("子条目 pageIndex 应解析成功", items.all { it.pageIndex >= 0 })
    }

    @Test
    fun `missing outline yields empty list not crash`() {
        val items = PdfBoxBookmarks.read(fixture("readit_outline_none.pdf"))
        assertTrue("无大纲应返回空列表", items.isEmpty())
    }

    @Test
    fun `broken destination degrades to minus one instead of dropping the whole outline`() {
        val items = PdfBoxBookmarks.read(fixture("readit_outline_brokendest.pdf"))
        println("[BM broken] $items")
        assertTrue("不能因为一条坏目标就把整份大纲丢掉", items.size >= 1)
        assertTrue(
            "目标页解析失败的条目应得到 pageIndex=-1（实测 $items）",
            items.any { it.pageIndex == -1 }
        )
    }

    @Test
    fun `maxItems caps runaway outline`() {
        val all = PdfBoxBookmarks.read(fixture("readit_outline_nested.pdf"), maxItems = 2)
        assertTrue("maxItems 应生效（实测 ${all.size}）", all.size <= 2)
    }
}

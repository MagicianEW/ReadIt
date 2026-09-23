package com.readit.core.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InversionTest {

    @Test
    fun `默认是白底黑字`() {
        assertEquals(Inversion.BLACK, Inversion.fontColor(false))
        assertEquals(Inversion.WHITE, Inversion.backColor(false))
        assertEquals("#000000", Inversion.cssFont(false))
        assertEquals("#ffffff", Inversion.cssBack(false))
    }

    @Test
    fun `反色后黑底白字`() {
        assertEquals(Inversion.WHITE, Inversion.fontColor(true))
        assertEquals(Inversion.BLACK, Inversion.backColor(true))
        assertEquals("#ffffff", Inversion.cssFont(true))
        assertEquals("#000000", Inversion.cssBack(true))
    }

    @Test
    fun `前后景色永远互补`() {
        for (inv in booleanArrayOf(false, true)) {
            assertNotEquals(Inversion.fontColor(inv), Inversion.backColor(inv))
        }
    }

    @Test
    fun `纯黑纯白互翻`() {
        assertEquals(Inversion.WHITE, Inversion.invertArgb(Inversion.BLACK))
        assertEquals(Inversion.BLACK, Inversion.invertArgb(Inversion.WHITE))
    }

    @Test
    fun `反色两次回到原值`() {
        val samples = intArrayOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF123456.toInt(),
            0x80000000.toInt(), 0x00000000.toInt(), 0xFF7F7F7F.toInt()
        )
        for (s in samples) {
            assertEquals(s, Inversion.invertArgb(Inversion.invertArgb(s)))
        }
    }

    @Test
    fun `只翻 RGB 不动 alpha`() {
        // 半透明红 0x80FF0000 → RGB 取补 = 0x0000FFFF（青），alpha 0x80 原样保留
        val halfTransparentRed = 0x80FF0000.toInt()
        val out = Inversion.invertArgb(halfTransparentRed)
        assertEquals(0x80, out ushr 24)
        assertEquals(0x0000FFFF, out and 0x00FFFFFF)
    }

    @Test
    fun `全透明像素反色后仍然全透明`() {
        // 这是最容易翻车的一类：alpha 一起取反 → 透明变不透明，盖住正文
        val out = Inversion.invertArgb(0x00000000)
        assertEquals(0, out ushr 24)
        assertEquals(0x00FFFFFF, out and 0x00FFFFFF)
    }

    @Test
    fun `颜色矩阵形状与关键位正确`() {
        val m = Inversion.argbMatrix()
        assertEquals(20, m.size)
        // RGB 三行斜率 -1、偏移 255；alpha 行保持 1
        assertEquals(-1f, m[0])
        assertEquals(255f, m[4])
        assertEquals(-1f, m[6])
        assertEquals(255f, m[9])
        assertEquals(-1f, m[12])
        assertEquals(255f, m[14])
        assertEquals(1f, m[18])
        assertEquals(0f, m[19])
    }

    @Test
    fun `颜色矩阵与逐像素反色结果一致`() {
        // 手工按矩阵算一遍，确认两条路径（Skia 绘制 / 逐像素）语义一致
        val m = Inversion.argbMatrix()
        fun apply(r: Int, g: Int, b: Int, a: Int): IntArray {
            fun channel(row: Int, vararg v: Int): Int {
                var acc = m[row * 5 + 4]
                v.forEachIndexed { i, x -> acc += m[row * 5 + i] * x }
                return acc.toInt().coerceIn(0, 255)
            }
            return intArrayOf(channel(0, r, g, b, a), channel(1, r, g, b, a), channel(2, r, g, b, a), channel(3, r, g, b, a))
        }
        for (px in intArrayOf(0xFF336699.toInt(), Inversion.BLACK, Inversion.WHITE, 0x8012AB34.toInt())) {
            val a = px ushr 24 and 0xFF
            val r = px ushr 16 and 0xFF
            val g = px ushr 8 and 0xFF
            val b = px and 0xFF
            val got = apply(r, g, b, a)
            val expect = Inversion.invertArgb(px)
            assertEquals(expect ushr 24 and 0xFF, got[3])
            assertEquals(expect ushr 16 and 0xFF, got[0])
            assertEquals(expect ushr 8 and 0xFF, got[1])
            assertEquals(expect and 0xFF, got[2])
        }
    }

    @Test
    fun `DOCX 样式只在反色时注入`() {
        assertTrue(Inversion.docxCss(false).isEmpty())
        val css = Inversion.docxCss(true)
        assertTrue(css.contains("background:#000000"))
        assertTrue(css.contains("color:#ffffff"))
        // 图片必须留在外面：反成负片会让照片/图表读不出来
        assertTrue(!css.contains("img"))
    }
}

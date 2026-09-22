package com.readit.reader.txt

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 恢复起始位置的时序回归（F09）。
 *
 * 打开顺序固定是：`setDocument(text, savedOffset)` **先于**首次 `onSizeChanged()`。
 * 前者执行时 View 还没被测量，`pager` 仍然是 null。若此时就去算
 * `pager?.pageOfOffset(offset) ?: 0`，偏移会被静默吞成 0，随后首次分页
 * 又拿「第 0 页偏移」当 keep —— 结果就是**每次打开都从第 1 页开始**，
 * 而进度写入一切正常（日志里 `start page=0`，看不出任何异常）。
 *
 * 这个用例就是把「先 setDocument、后 layout」的顺序钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
class TxtCanvasViewTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun longText(chars: Int): String {
        // 用换行分段的重复正文，保证页数足够多
        val para = "这是一段用于分页测试的中文正文内容，长度需要足够长以避免只有一页。"
        val sb = StringBuilder(chars)
        while (sb.length < chars) sb.append(para).append('\n')
        return sb.toString()
    }

    /**
     * 用**项目口径的小屏**（480×800，E-Ink 兜底尺寸）而不是 1080×2039。
     *
     * Robolectric 底下没有真实字形，Paint 的字体度量是 stub 值，每页容量会离谱地大；
     * 用大屏时整篇 12 万字会装进 1 页，「恢复到非首页」这类断言就永远测不出来
     * （曾经靠旧行高公式碰巧成立，行高一改就露馅）。小屏能把容量压回十几页量级。
     */
    private fun layerOut(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
    }

    private fun layerOutSmall(v: View) = layerOut(v, 480, 800)

    @Test
    fun `start offset survives being applied before the first layout`() {
        val text = longText(120_000)
        // 目标取正文末段：无论运行环境的字体度量如何（Robolectric 底下没有真实字形，
        // 每页容量会偏大），末段都必定落在非首页，测试因此不依赖具体分页数值。
        val target = (text.length * 9) / 10
        val v = TxtCanvasView(ctx)

        // 1) 未测量：此时还没有分页，任何 offset 都只能先挂起
        v.setDocument(text, startOffset = target)
        assertEquals("未测量时还没有分页信息", 1, v.totalPages())
        assertEquals(0, v.currentPage())

        // 2) 首次布局：挂起的偏移必须在这里被消费
        layerOutSmall(v)

        assertTrue(
            "布局后应真正分页（>1 页）pages=${v.totalPages()} width=${v.width} height=${v.height}",
            v.totalPages() > 1
        )
        assertTrue(
            "必须恢复到非首页，而不是退回第 0 页 page=${v.currentPage()} " +
                "offset=${v.currentOffset()} target=$target pages=${v.totalPages()}",
            v.currentPage() > 0
        )
        assertTrue("恢复到的页首偏移必须 > 0", v.currentOffset() > 0)
        assertTrue(
            "恢复到的页必须包含目标偏移（页首 <= 目标）",
            v.currentOffset() <= target
        )
    }

    @Test
    fun `offset zero still lands on the first page`() {
        val v = TxtCanvasView(ctx)
        v.setDocument(longText(60_000), startOffset = 0)
        layerOutSmall(v)
        assertEquals(0, v.currentPage())
        assertEquals(0, v.currentOffset())
    }

    @Test
    fun `relayout for font change keeps the current page instead of jumping home`() {
        val text = longText(120_000)
        val target = (text.length * 9) / 10
        val v = TxtCanvasView(ctx)
        v.setDocument(text, startOffset = target)
        layerOutSmall(v)
        assertTrue("前置条件：应恢复到非首页", v.currentPage() > 0)
        assertTrue(v.currentOffset() > 0)

        // 改字号会触发重新分页（等价于 rebuild()）
        v.fontSizeSp = 22f
        layerOutSmall(v)

        assertTrue(
            "重新分页后不应回到第 0 页 page=${v.currentPage()} offset=${v.currentOffset()}",
            v.currentPage() > 0
        )
        assertTrue("重新分页后偏移不应归零", v.currentOffset() > 0)
    }
}

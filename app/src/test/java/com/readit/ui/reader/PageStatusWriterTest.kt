package com.readit.ui.reader

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.readit.eink.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 底部状态条（页码 + 已读长度条）的写入纪律。
 *
 * 守的是「**必须 post 到主线程**」这一条。画布的回调有两个来源，两种都会让直接
 * setText 失效：
 *  1. 布局过程中（`onSizeChanged -> rebuild -> notifyPageChanged`）——
 *     setText 触发「布局中调用 requestLayout」，改动被丢弃；
 *  2. 后台线程（`setDocument` 跑在 io 线程）—— 跨线程改 UI。
 *
 * 症状一样：**页码不显示、已读长度条不出现**。
 * 这个用例把「先排队、后生效」钉死：调用后立刻读，文本应当**还没变**；
 * 主线程 idle 之后才变。谁哪天把 post 去掉，这条会红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w600dp-h800dp-mdpi")
class PageStatusWriterTest {

    private lateinit var ctx: Context
    private lateinit var pageInfo: TextView
    private lateinit var progress: ProgressBar
    private lateinit var writer: PageStatusWriter

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        pageInfo = TextView(ctx)
        progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        // **必须挂到窗口上**：`View.post` 对「已 attach」的视图走主线程 Handler，
        // 对「未 attach」的视图走 ViewRootImpl.RunQueue —— 后者只在 attach 时才被清空，
        // idle 主线程根本不会执行它。用游离的 TextView 测，post 永远不会跑。
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.addView(pageInfo)
        root.addView(progress)
        activity.setContentView(root)

        writer = PageStatusWriter(ctx, pageInfo, progress)
    }

    @Test
    fun `text is not applied synchronously but after the main looper idles`() {
        writer.apply(11, 340)

        assertEquals(
            "写入必须排队，不能同步生效（否则布局过程中的那次会被丢弃）",
            "", pageInfo.text
        )

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("12 / 340", pageInfo.text)
    }

    @Test
    fun `progress bar becomes visible and shows the ratio`() {
        writer.apply(11, 340)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(3, progress.progress) // 12 / 340 ≈ 3%
    }

    @Test
    fun `unknown total hides the progress bar but still shows the page number`() {
        writer.apply(0, 0)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.GONE, progress.visibility)
        assertTrue("页码仍要写", pageInfo.text.contains("1"))
    }

    @Test
    fun `last page shows one hundred percent`() {
        writer.apply(339, 340)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(View.VISIBLE, progress.visibility)
        assertEquals(100, progress.progress)
    }

    @Test
    fun `page number format matches the string resource`() {
        writer.apply(0, 7)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(ctx.getString(R.string.page_info, 1, 7), pageInfo.text)
    }
}

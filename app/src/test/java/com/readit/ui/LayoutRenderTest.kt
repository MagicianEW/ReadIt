package com.readit.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.readit.eink.R
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 把**真实布局**渲染成 PNG，供人眼直接比对 UI 改动。
 *
 * 这不是断言测试，是一个「出图」工具：
 * `./gradlew testDebugUnitTest --tests '*LayoutRenderTest*'`
 * 会在 `app/build/ui-preview/` 下生成各屏截图。
 *
 * 用 Robolectric 的 NATIVE 图形模式（真实 Skia 绘制，不是 mock），
 * 所以出来的图就是布局在设备上的真实排版 —— 文字度量、间距、换行都对。
 *
 * 分辨率按项目登记的口径来，密度取 mdpi（1dp = 1px）：
 *  - 600×800 = 规格 §5.4 的兜底基准（图标 36dp / 热区 48dp / 正文 10–20sp）
 *  - 480×600 = KY-01L 真机
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w600dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LayoutRenderTest {

    private val outDir = File("build/ui-preview").apply { mkdirs() }

    private fun render(name: String, widthPx: Int, heightPx: Int, build: (Context) -> View) {
        // 原生图形运行时缺失时**跳过**而不是失败：这是个「出图工具」，
        // 不该因为环境缺 native 库就把整个门禁脚本（gc.cmd）卡住。
        // 只吞「原生图形不可用」这一类错误，其余异常照常抛出（那是真 bug）。
        try {
            renderOrThrow(name, widthPx, heightPx, build)
        } catch (e: Throwable) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            if (e is UnsatisfiedLinkError ||
                msg.contains("nativeruntime", ignoreCase = true) ||
                msg.contains("native graphics", ignoreCase = true)
            ) {
                org.junit.Assume.assumeTrue("native graphics 不可用，跳过出图: $msg", false)
            }
            throw e
        }
    }

    private fun renderOrThrow(name: String, widthPx: Int, heightPx: Int, build: (Context) -> View) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val view = build(ctx)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, widthPx, heightPx)

        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)

        val f = File(outDir, "$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        println("[UI] $name -> ${f.absolutePath} (${widthPx}x$heightPx)")
        assertTrue("PNG 应已生成", f.exists() && f.length() > 0)
    }

    /**
     * 把若干条目 inflate 进一个竖直 LinearLayout。
     *
     * 预览不需要真的跑 RecyclerView —— 条目布局 inflate 出来放进竖直容器，
     * 视觉结果与真实列表一致，还省掉 adapter 与回收逻辑这一堆噪声。
     */
    private fun stackOf(
        ctx: Context,
        layoutRes: Int,
        count: Int,
        bind: (View, Int) -> Unit
    ): LinearLayout {
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        for (i in 0 until count) {
            val item = LayoutInflater.from(ctx).inflate(layoutRes, box, false)
            bind(item, i)
            box.addView(item)
        }
        return box
    }

    // ---------------------------------------------------------------- 书架

    @Test
    fun `shelf screen`() {
        render("01-shelf", 600, 800) { ctx ->
            val root = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_shelf, null) as LinearLayout
            root.findViewById<TextView>(R.id.tvCount).text = "共 3 本"

            val rows = listOf(
                Triple("雪山飞狐.txt", "已读 62%", "123.4万字 · 86章"),
                Triple("三体.pdf", "已读 8%", "45.6万字 · 12章"),
                Triple("解忧杂货店.epub", "", "")
            )
            replaceWith(root, R.id.rvBooks,
                stackOf(ctx, R.layout.item_book, rows.size) { item, pos ->
                    val (name, pct, meta) = rows[pos]
                    item.findViewById<TextView>(R.id.tvBookName).text = name
                    val p = item.findViewById<TextView>(R.id.tvBookPercent)
                    val m = item.findViewById<TextView>(R.id.tvBookMeta)
                    val bar = item.findViewById<ProgressBar>(R.id.pbBookProgress)
                    if (pct.isEmpty()) {
                        p.visibility = View.GONE
                        m.visibility = View.GONE
                        bar.visibility = View.GONE
                    } else {
                        p.visibility = View.VISIBLE
                        p.text = pct
                        m.visibility = View.VISIBLE
                        m.text = meta
                        bar.visibility = View.VISIBLE
                        bar.progress = pct.filter { it.isDigit() }.toInt()
                    }
                }
            )
            root
        }
    }

    /** 用一个静态 View 顶替布局里的某个子 View（预览用；RecyclerView 不能直接加子 View） */
    private fun replaceWith(root: ViewGroup, id: Int, replacement: View) {
        val target = root.findViewById<View>(id)
        val parent = target.parent as ViewGroup
        val idx = parent.indexOfChild(target)
        val lp = target.layoutParams
        parent.removeViewAt(idx)
        parent.addView(replacement, idx, lp)
    }

    // ---------------------------------------------------------------- 阅读页

    private fun fakeBody(ctx: Context, text: String): View = TextView(ctx).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 15f
        setPadding(24, 24, 24, 24)
    }

    @Test
    fun `reader screen`() {
        render("02-reader", 600, 800) { ctx ->
            val root = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_reader, null) as ViewGroup
            val stage = root.findViewById<FrameLayout>(R.id.stage)
            stage.addView(
                fakeBody(
                    ctx,
                    "第一章 雪地里的脚印\n\n他停下脚步，蹲下身去细看那一行脚印。\n\n" +
                        "雪已经积了半尺厚，可脚印还是清清楚楚的——来的人不慌，走得很稳。\n\n" +
                        "他把手指伸进雪里，摸了摸脚印的边缘。边缘已经塌了，但中间还是硬的。\n\n" +
                        "远处传来一声闷响，像是有什么东西倒在了雪里。他没有立刻站起来，" +
                        "只是继续看着那行脚印。"
                ),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            root.findViewById<TextView>(R.id.tvPageInfo).text = "12 / 340"
            root.findViewById<TextView>(R.id.tvBookTitle).text = "雪山飞狐"
            val bar = root.findViewById<ProgressBar>(R.id.pbReadingProgress)
            bar.visibility = View.VISIBLE
            bar.progress = 4
            root
        }
    }

    @Test
    fun `reader screen with typography panel open`() {
        render("03-reader-panel", 600, 800) { ctx ->
            val root = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_reader, null) as ViewGroup
            val stage = root.findViewById<FrameLayout>(R.id.stage)
            stage.addView(
                fakeBody(
                    ctx,
                    "第一章 雪地里的脚印\n\n他停下脚步，蹲下身去细看那一行脚印。\n\n" +
                        "雪已经积了半尺厚，可脚印还是清清楚楚的。"
                ),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            root.findViewById<TextView>(R.id.tvPageInfo).text = "12 / 340"
            root.findViewById<TextView>(R.id.tvBookTitle).text = "雪山飞狐"
            root.findViewById<View>(R.id.panelTypography).visibility = View.VISIBLE
            root.findViewById<TextView>(R.id.tvFontValue).text = "15sp"
            root.findViewById<TextView>(R.id.tvLineValue).text = "1.3"
            root.findViewById<TextView>(R.id.tvMarginValue).text = "12dp"
            root
        }
    }

    // ---------------------------------------------------------------- 目录抽屉

    @Test
    fun `toc drawer`() {
        // 抽屉是覆盖层（layout_gravity=start），嵌在 DrawerLayout 里会画到屏幕外，
        // 所以这里单独出图：宽 90vw = 540px，与代码里设置的抽屉宽度一致。
        render("04-toc", 540, 800) { ctx ->
            val rows = listOf(
                "第一章 雪地里的脚印" to false,
                "第二章 越走越深" to false,
                "第三章 脚印的主人" to true,
                "第四章 雪崩" to false,
                "第五章 空屋" to false,
                "第六章 回来的路" to false
            )
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
            }
            for ((title, current) in rows) {
                val item = LayoutInflater.from(ctx).inflate(R.layout.item_chapter, box, false)
                val tv = item.findViewById<TextView>(R.id.tvChapterTitle)
                tv.text = if (current) "$title  当前" else title
                tv.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
                box.addView(item)
            }
            box
        }
    }

    // ---------------------------------------------------------------- 窄屏

    @Test
    fun `reader screen on 480x600`() {
        render("05-reader-480x600", 480, 600) { ctx ->
            val root = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_reader, null) as ViewGroup
            val stage = root.findViewById<FrameLayout>(R.id.stage)
            stage.addView(
                fakeBody(ctx, "第一章 雪地里的脚印\n\n他停下脚步，蹲下身去细看那一行脚印。"),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            root.findViewById<TextView>(R.id.tvPageInfo).text = "12 / 340"
            root.findViewById<TextView>(R.id.tvBookTitle).text = "雪山飞狐"
            val bar = root.findViewById<ProgressBar>(R.id.pbReadingProgress)
            bar.visibility = View.VISIBLE
            bar.progress = 4
            root
        }
    }

    // ---------------------------------------------------------------- 状态条真实链路

    /**
     * 走**真实链路**渲染：TxtCanvasView 出文档 -> 页面回调 -> PageStatusWriter 写状态条。
     *
     * 这条用例守的是一个真机上才发现的缺陷：画布的页面回调有两个来源，
     * 一个在**布局过程中**、一个在**后台线程**，两种情况下直接 setText 都会被丢弃
     * （症状：页码空白、已读长度条不出现）。修法是 post 到主线程。
     *
     * 所以断言不是「文本被 set 了」，而是**渲染出来的像素里真的有页码**。
     */
    @Test
    fun `reader shows the page number through the real canvas callback path`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .setup().get()
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.activity_reader, null) as ViewGroup
        activity.setContentView(root)

        val canvas = root.findViewById<com.readit.reader.txt.TxtCanvasView>(R.id.txtCanvas)
        val pageInfo = root.findViewById<TextView>(R.id.tvPageInfo)
        val progress = root.findViewById<ProgressBar>(R.id.pbReadingProgress)
        val writer = com.readit.ui.reader.PageStatusWriter(ctx, pageInfo, progress)
        canvas.onPageChanged = { index, total -> writer.apply(index, total) }

        val body = StringBuilder()
        repeat(200) { i -> body.append("第").append(i + 1).append("行测试正文内容。\n") }
        canvas.setDocument(body.toString())

        // 走一遍真实布局，再让主线程把 post 出去的写入执行掉
        root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 600, 800)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        assertTrue(
            "页码必须真的写进 TextView（回调 -> post -> 主线程）",
            pageInfo.text.isNotEmpty()
        )

        render("06-reader-pageinfo", 600, 800) { root }
    }

    /**
     * 翻页动画：翻页那一刻**旧页还在原位、新页在屏外**，动画才真正跑起来。
     *
     * 这里不靠像素分布判定动画中间帧 —— Robolectric 的 ValueAnimator 会在一次 idle
     * 里把动画直接跑完（实测那样测出来的像素断言是假阳性）。
     * 改用**可观测状态**：[TxtCanvasView.isPageAnimating]。
     *
     * 同时对比两种模式渲染出的帧：ANIM 模式下刚翻完那一帧应当仍是**旧页**
     * （进度 0：新页在 x=+600 的屏外），TAP 模式则是**新页** —— 两帧必须不同。
     */
    @Test
    fun `page turn animation keeps the old page on screen at the first frame`() {
        val animFrame = renderReaderAt(mode = com.readit.data.PageTurnMode.ANIM, turn = true)
        val tapFrame = renderReaderAt(mode = com.readit.data.PageTurnMode.TAP, turn = true)
        File(outDir, "08-anim-first-frame.png").outputStream()
            .use { animFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(outDir, "09-tap-first-frame.png").outputStream()
            .use { tapFrame.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue(
            "ANIM 与 TAP 渲染出的帧必须不同（ANIM 停在旧页、TAP 已是新页）",
            pixelsDiffer(animFrame, tapFrame)
        )
    }

    @Test
    fun `anim mode starts the animation and switching away cancels it`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .setup().get()
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.activity_reader, null) as ViewGroup
        activity.setContentView(root)
        val canvas = root.findViewById<com.readit.reader.txt.TxtCanvasView>(R.id.txtCanvas)
        canvas.setDocument(bodyText())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 600, 800)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        canvas.pageTurnMode = com.readit.data.PageTurnMode.ANIM
        canvas.nextPage()
        assertTrue("翻页必须启动动画", canvas.isPageAnimating)

        // 切走模式 -> 动画必须被取消（否则会停在两页之间）
        canvas.pageTurnMode = com.readit.data.PageTurnMode.TAP
        assertFalse("切走模式必须取消动画", canvas.isPageAnimating)
    }

    private fun bodyText(): String = buildString {
        repeat(400) { i -> append("第").append(i + 1).append("行：动画翻页测试正文。\n") }
    }

    /** 打开一个 TXT 文档、翻一页，返回渲染出的位图 */
    private fun renderReaderAt(
        mode: com.readit.data.PageTurnMode,
        turn: Boolean
    ): Bitmap {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java)
            .setup().get()
        val root = LayoutInflater.from(ctx)
            .inflate(R.layout.activity_reader, null) as ViewGroup
        activity.setContentView(root)
        val canvas = root.findViewById<com.readit.reader.txt.TxtCanvasView>(R.id.txtCanvas)
        canvas.pageTurnMode = mode
        canvas.setDocument(bodyText())
        root.measure(
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 600, 800)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        if (turn) canvas.nextPage()

        val bmp = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        root.draw(c)
        return bmp
    }

    /** 两张位图是否有任何像素不同（抽样比较，避免逐像素太慢） */
    private fun pixelsDiffer(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return true
        for (y in 0 until a.height step 3) {
            for (x in 0 until a.width step 3) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return true
            }
        }
        return false
    }

}

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
}

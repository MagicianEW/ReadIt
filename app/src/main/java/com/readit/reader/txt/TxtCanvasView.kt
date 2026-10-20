package com.readit.reader.txt

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.readit.core.display.Inversion
import com.readit.core.display.RenderMode
import com.readit.core.display.ScreenProfile
import com.readit.core.util.ReadItLog
import com.readit.data.PageTurnMode
import kotlin.math.max

/**
 * TXT 自绘渲染（规范 §3.2.1，F01）。
 *
 * 特点：
 *  - Canvas 逐行绘制，无 WebView、无 StaticLayout 全量布局，低配 SoC 也能 <4s 首屏
 *  - 分页容量按屏幕尺寸 + 字号实时计算，与 TxtPager 共用一套口径
 *  - E-Ink：不做动画，翻页只走 invalidate()
 *
 * ## 翻页方式（[PageTurnMode]）
 *
 * [TAP] / [PageTurnMode.SWIPE] 的**手势判定**在 ReaderActivity 里（它同时管灯手势），
 * 本类只负责两件与渲染有关的事：
 *  - [PageTurnMode.SCROLL]：把分页切成一条**连续行流**，纵向拖动阅读；
 *  - [PageTurnMode.ANIM]：翻页时带横向滑动动画。
 *
 * 墨水屏上这两种都不启用（见 [com.readit.data.resolvePageTurnMode]）：
 * 一次刷新就是一次全屏重绘，动画过程会留下一串残影。
 */
class TxtCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 翻页方式。
     *
     * 由 ReaderActivity 在打开时按 [com.readit.data.resolvePageTurnMode] 解析后下发
     * （墨水屏强制 [PageTurnMode.TAP]）。本类只关心 [PageTurnMode.SCROLL] 与
     * [PageTurnMode.ANIM] 两种需要改渲染的表现；点按 / 滑动手势判定不在这里。
     */
    var pageTurnMode: PageTurnMode = PageTurnMode.TAP
        set(value) {
            if (field != value) {
                field = value
                // 切模式要复位滚动位置与动画，否则会停在半个页中间
                resetScrollState()
                cancelPageAnim()
                invalidate()
            }
        }

    /**
     * 文字渲染模式（[RenderMode]）：
     * - `SMOOTH`（默认）：抗锯齿开启，LCD / 高灰阶屏上边缘最顺；
     * - `SHARP`：**点对点**。关抗锯齿 + 关亚像素 + 字号吸附整像素 + 基线吸附整像素。
     *   1bit 墨水屏面板上没有中间灰可抖，笔画边缘就不会发灰发糊（用户反馈的「发虚」）。
     *
     * 只在 SHARP 下吸附字号，SMOOTH 保持原有（可为小数）行为 —— 既不影响既有观感，
     * 也让两种模式的差异可被量化对比（实测见 2026-09-24 的真机灰阶直方图）。
     */
    var renderMode: RenderMode = RenderMode.SMOOTH
        set(value) {
            if (field != value) {
                field = value
                applyRenderMode()
                rebuild()
                invalidate()
            }
        }

    var fontSizeSp: Float = 14f
        set(value) {
            if (field != value) {
                field = value
                applyTextSize()
                rebuild()
                invalidate()
            }
        }

    /** 字体（null = 系统默认）。换字体必须重排：字形宽度变了，每页行数和每行列数都会变。 */
    var typeface: Typeface? = null
        set(value) {
            if (field !== value) {
                field = value
                paint.typeface = value
                rebuild()
                invalidate()
            }
        }

    var lineSpacing: Float = 1.3f
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidate()
            }
        }

    var marginDp: Int = 12
        set(value) {
            if (field != value) {
                field = value
                rebuild()
                invalidate()
            }
        }

    /**
     * 反色（黑白置换，F27）。
     *
     * 只改颜色，**不 rebuild**：颜色不影响换行与分页，重排会白白丢掉当前页
     * 与恢复偏移的状态（还会多一次全量分页的计算）。
     */
    var inverted: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                paint.color = Inversion.fontColor(value)
                invalidate()
            }
        }

    /** 页码变化回调（index 为 0-based） */
    var onPageChanged: ((Int, Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Inversion.fontColor(false)
        textSize = fontSizeSp * resources.displayMetrics.scaledDensity
    }

    /** 按 [renderMode] 设置画笔：抗锯齿 / 亚像素 / 字体微调，并重算字号 */
    private fun applyRenderMode() {
        val sharp = renderMode == RenderMode.SHARP
        // 抗锯齿是「发虚」的主因：笔画边缘被算成中间灰，1bit 面板上只能抖动出来
        paint.isAntiAlias = !sharp
        // 亚像素按 RGB 子像素排列补色：单色面板上只会变成脏边，必须关
        paint.isSubpixelText = false
        // 字体微调把字干对齐到像素网格，锐利模式下尤其明显
        paint.hinting = if (sharp) Paint.HINTING_ON else Paint.HINTING_OFF
        applyTextSize()
    }

    private fun applyTextSize() {
        val raw = fontSizeSp * resources.displayMetrics.scaledDensity
        paint.textSize =
            if (renderMode == RenderMode.SHARP) ScreenProfile.snapTextSizePx(fontSizeSp, resources.displayMetrics.scaledDensity)
            else raw
    }

    private var fullText: String = ""
    private var pager: TxtPager? = null
    private var pageIndex = 0
    private var charsPerLine = 20
    private var linesPerPage = 20

    /**
     * 待恢复的起始偏移（**必须挂起到首次分页建立之后才能用**）。
     *
     * 打开流程是 `ReaderActivity.onCreate() -> openTxt() -> setDocument(text, savedOffset)`，
     * 此时 View 还没被测量，`rebuild()` 直接 return、`pager` 仍是 null。
     * 早期实现直接就写 `pageIndex = pager?.pageOfOffset(startOffset) ?: 0` ->
     * pager 为 null 时静默取 0，起始位置被吃掉；随后首次 `onSizeChanged()` 里
     * `keep = currentOffset()` 拿到的是第 0 页的偏移，于是**每次打开 TXT 都从第 1 页开始**，
     * 阅读进度写入正常、却永远读不回来（日志里 `start page=0`，看不出异常）。
     *
     * 现在把偏移挂起，等首次真正分页时消费一次。
     */
    private var restoreOffset = 0
    private var restorePending = false

    fun setDocument(text: String, startOffset: Int = 0) {
        fullText = text
        restoreOffset = startOffset.coerceAtLeast(0)
        restorePending = true
        invalidateLineCache()
        cancelPageAnim()
        resetScrollState()
        // 已测量（同一 View 换书 / 换字号）时 rebuild 会立刻消费掉这个偏移；
        // 未测量时留给首次 onSizeChanged()。
        rebuild()
        ReadItLog.i(
            "TxtCanvasView document ready: ${text.length} chars, " +
                "startOffset=$restoreOffset start page=$pageIndex " +
                "pending=$restorePending pages=${totalPages()}"
        )
        notifyPageChanged()
        invalidate()
    }

    // ---------------------------------------------------------------- 连续滚动（SCROLL 模式）

    /**
     * 滚动位置。
     *
     * - [scrollPage]：当前**首个可见行**所在的页
     * - [scrollLine]：该页内的行序号
     * - [scrollPixel]：行内像素偏移（0..行高），让滚动是平滑的而不是一行一跳
     *
     * 为什么不用「绝对行号」：那需要全书各页的行数累加表，
     * 得把整本书渲染一遍才算得出来（10MB 的书就是一次全量排版）。
     * 这里改成**增量式**：向上滚出当前页就退到上一页的最后一行，
     * 向下滚出当前页就进到下一页的第一行 —— 每步 O(1)，内存 O(1)。
     */
    private var scrollPage = 0
    private var scrollLine = 0
    private var scrollPixel = 0f

    /**
     * 视觉行流：一页拆成**实际会画出来**的行。
     *
     * 与 [onDraw] 的绘制口径必须完全一致（同样的换行切分 + 同样的 breakText 宽度），
     * 否则滚动时行号会错位。而且这里比 onDraw 多带一个**字符偏移** ——
     * 滚动位置落在页中间时，进度要记的��是页首偏移，而是这一行真正的起始字符。
     */
    private data class Line(val text: String, val offset: Int)

    private var lineCachePage = -1
    private var lineCache: List<Line> = emptyList()

    /** 取某页的视觉行流（带缓存；排版一变缓存即失效） */
    private fun linesOf(pageIdx: Int): List<Line> {
        if (pageIdx == lineCachePage) return lineCache
        val p = pager?.page(pageIdx) ?: return emptyList()
        val density = resources.displayMetrics.density
        val maxWidth = contentWidthPx(density)
        val out = ArrayList<Line>()
        var base = p.start
        for (para in p.text.split('\n')) {
            if (para.isEmpty()) {
                out.add(Line("", base))
                base += 1
                continue
            }
            var start = 0
            while (start < para.length) {
                val count = paint.breakText(para, start, para.length, true, maxWidth, null)
                if (count <= 0) break
                val end = (start + count).coerceAtMost(para.length)
                out.add(Line(para.substring(start, end), base + start))
                start = end
            }
            base += para.length + 1 // +1 是被 split 吃掉的换行符
        }
        lineCachePage = pageIdx
        lineCache = out
        return out
    }

    private fun invalidateLineCache() {
        lineCachePage = -1
        lineCache = emptyList()
    }

    private fun resetScrollState() {
        scrollPage = pageIndex
        scrollLine = 0
        scrollPixel = 0f
    }

    // ---------------------------------------------------------------- 翻页动画（ANIM 模式）

    /** 动画的起始页；-1 = 没在动画 */
    private var animFromPage = -1
    private var animProgress = 1f
    private var animDir = 0
    private var pageAnim: ValueAnimator? = null

    private fun cancelPageAnim() {
        pageAnim?.cancel()
        pageAnim = null
        animFromPage = -1
        animProgress = 1f
        animDir = 0
    }

    /**
     * 启动翻页动画。
     *
     * 动画期间画两页：旧页向外滑、新页向内滑。[onDraw] 据此绘制。
     * 动画被 [cancelPageAnim] 打断是安全的（换书、换字号、连点翻页都会调它）——
     * 旧实现里「动画到一半换页」会让画面停在两页之间。
     */
    private fun startPageAnim(from: Int, dir: Int) {
        cancelPageAnim()
        animFromPage = from
        animDir = dir
        animProgress = 0f
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    animFromPage = -1
                    invalidate()
                }
            })
            start()
        }
    }

    /**
     * 画布上是否真的装了一篇正文。
     *
     * 异步打开路径（PDF 扫描检测 / DOCX `:converter` 转换）期间 `mode` 还是 TXT，
     * 但画布是空的。`ProgressPolicy` 靠这个标志区分「进度确实是 0」与「文档还没来」，
     * 否则 onPause 会把 0 当进度写回去，覆盖掉上次读到的位置。
     */
    val hasDocument: Boolean get() = pager != null && fullText.isNotEmpty()

    /**
     * 当前阅读位置（字符偏移）。
     *
     * 滚动模式下**不能**返回页首偏移 —— 那样进度会粗到「一页一跳」，
     * 用户滚了半页也看不出进度变了。这里返回首个可见行真正的起始字符。
     */
    fun currentOffset(): Int {
        val p = pager ?: return 0
        if (pageTurnMode != PageTurnMode.SCROLL) return p.page(pageIndex)?.start ?: 0
        val lines = linesOf(scrollPage)
        val idx = scrollLine.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        return lines.getOrNull(idx)?.offset ?: (p.page(scrollPage)?.start ?: 0)
    }

    fun currentPage(): Int = if (pageTurnMode == PageTurnMode.SCROLL) scrollPage else pageIndex

    /**
     * 用户正拖着排版滑条（字号 / 行距 / 边距）。
     *
     * 拖动过程中 `rebuild()` 每一格都会跑一次，而 rebuild 末尾的 [notifyPageChanged]
     * 要拿总页数去刷页码 —— [totalPages] 走的是 [TxtPager.exactPageCount]，
     * 那是一次**全书**分页。1000 万字的书单次几百毫秒，拖动过程就是整机冻结。
     *
     * 所以拖动期间改用 [TxtPager.estimatedPageCount]（O(1)，按容量折算）：
     * 总页数在拖动中显示的是估算值，松手后 [endInteractiveReflow] 立刻补一次精确值，
     * 页码会从「约 312 页」跳到「318 页」——这是一次可见的收敛，好过拖不动。
     */
    private var interactiveReflow = false

    /** 滑条按下：进入估算模式 */
    fun beginInteractiveReflow() {
        interactiveReflow = true
    }

    /** 滑条松手：回到精确模式，并立刻把真实总页数刷到 UI 上（只精确分页这一次） */
    fun endInteractiveReflow() {
        if (!interactiveReflow) return
        interactiveReflow = false
        notifyPageChanged()
    }

    fun totalPages(): Int {
        val p = pager ?: return 1
        return if (interactiveReflow) p.estimatedPageCount() else p.exactPageCount()
    }

    /**
     * 取某偏移处的一小段正文，用于书签标签（F25）。
     *
     * 从偏移往后扫：先跳过行首空白，遇到换行就停，凑够 [max] 个字符收工。
     * 目的只是让用户在书签列表里认出「这是哪一段」，不需要精确到字 ——
     * 所以不处理省略号，也不做两端对齐。
     */
    fun snippetAt(offset: Int, max: Int = 18): String {
        if (fullText.isEmpty()) return ""
        val sb = StringBuilder(max)
        var i = offset.coerceIn(0, fullText.length)
        while (i < fullText.length && sb.length < max) {
            val c = fullText[i]
            if (sb.isEmpty() && c.isWhitespace()) {
                i++
                continue
            }
            if (c == '\n' || c == '\r') break
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    fun nextPage(): Boolean {
        val next = pageIndex + 1
        val p = pager?.page(next) ?: return false
        if (p.start >= fullText.length) return false
        // 连续滚动模式下「下一页」= 往下滚一屏
        if (pageTurnMode == PageTurnMode.SCROLL) {
            return scrollByPage(1)
        }
        val from = pageIndex
        pageIndex = next
        if (pageTurnMode == PageTurnMode.ANIM) startPageAnim(from, 1)
        notifyPageChanged()
        invalidate()
        return true
    }

    fun prevPage(): Boolean {
        if (pageIndex == 0) return false
        if (pageTurnMode == PageTurnMode.SCROLL) {
            return scrollByPage(-1)
        }
        val from = pageIndex
        pageIndex--
        if (pageTurnMode == PageTurnMode.ANIM) startPageAnim(from, -1)
        notifyPageChanged()
        invalidate()
        return true
    }

    fun goToOffset(offset: Int) {
        val p = pager ?: return
        pageIndex = p.pageOfOffset(offset)
        // 定位后滚动位置要跟着回到该页首，否则模式切换时会停在半页中间
        scrollPage = pageIndex
        scrollLine = 0
        scrollPixel = 0f
        cancelPageAnim()
        notifyPageChanged()
        invalidate()
    }

    // ---------------------------------------------------------------- 滚动操作（SCROLL 模式）

    /**
     * 纵向滚动。
     *
     * @param deltaPx 正数 = 往后看（内容向上移），负数 = 往前看
     * @return 是否真的滚动了（到头 / 非滚动模式时返回 false）
     */
    fun scrollBy(deltaPx: Float): Boolean {
        if (pageTurnMode != PageTurnMode.SCROLL) return false
        if (pager == null || fullText.isEmpty()) return false
        val lineHeight = lineHeightPx(paint.fontMetrics)
        if (lineHeight <= 0f || deltaPx == 0f) return false

        val before = scrollPage * 1000000L + scrollLine * 1000L + scrollPixel.toLong()
        scrollPixel += deltaPx

        // 行内像素偏移归位成整行，再逐行走
        while (scrollPixel >= lineHeight) {
            scrollPixel -= lineHeight
            if (!advanceLine()) {
                scrollPixel = 0f
                break
            }
        }
        while (scrollPixel < 0f) {
            scrollPixel += lineHeight
            if (!retreatLine()) {
                scrollPixel = 0f
                break
            }
        }
        val after = scrollPage * 1000000L + scrollLine * 1000L + scrollPixel.toLong()
        if (before == after) return false

        // 页变了要同步 pageIndex：进度、页码、目录高亮都看它
        if (scrollPage != pageIndex) {
            pageIndex = scrollPage
            notifyPageChanged()
        }
        invalidate()
        return true
    }

    /** 往下走一行；到本页末尾则进下一页。@return 是否还能继续 */
    private fun advanceLine(): Boolean {
        val lines = linesOf(scrollPage)
        scrollLine++
        if (scrollLine < lines.size) return true
        val total = pager?.exactPageCount() ?: 1
        if (scrollPage + 1 >= total) {
            scrollLine = (lines.size - 1).coerceAtLeast(0)
            return false
        }
        scrollPage++
        scrollLine = 0
        return true
    }

    /** 往上退一行；到本页开头则退到上一页末尾。@return 是否还能继续 */
    private fun retreatLine(): Boolean {
        scrollLine--
        if (scrollLine >= 0) return true
        if (scrollPage == 0) {
            scrollLine = 0
            return false
        }
        scrollPage--
        scrollLine = (linesOf(scrollPage).size - 1).coerceAtLeast(0)
        return true
    }

    /** 滚动一屏（硬件翻页键用） */
    private fun scrollByPage(dir: Int): Boolean {
        val density = resources.displayMetrics.density
        val marginPx = marginDp * density
        val lineHeight = lineHeightPx(paint.fontMetrics)
        val screen = (height - 2 * marginPx).coerceAtLeast(lineHeight)
        // 一次滚一屏减去一点重叠，翻页时不会漏看行
        val delta = dir * (screen - lineHeight)
        val moved = scrollBy(delta)
        if (moved) {
            if (scrollPage != pageIndex) {
                pageIndex = scrollPage
                notifyPageChanged()
            }
        }
        return moved
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    /**
     * 行高（分页与绘制**唯一**的口径）。
     *
     * 必须用 ascent/descent（hhea，稳定在 1em 上下），不能用 `fm.top/bottom`：后者来自 OS/2，
     * CJK 字体为容纳全角/竖排字形会给到 3em 以上 —— 换上内置思源宋体后实测行高被算成 ~120px，
     * 一页只剩 2 行。
     *
     * `.coerceIn(textSize, textSize*3)` 同样不能只出现在一处：以前 [rebuild] 带钳位、
     * [onDraw] 不带，于是用户字体（hhea 跨度 > 1.5em 常见）把行距拉满时，
     * 分页按 3em 算行数、绘制按真实值（更大）画，可用行数变少，超出 `bottomLimit` 的行
     * 被 break 掉 —— 那些字符**既不在本页也不在任何后续页**（下一页从 pager 算出的字符
     * 边界开始，已越过它们），即静默丢字。抽成一处，两个口径不可能再漂移。
     */
    private fun lineHeightPx(fm: Paint.FontMetrics): Float =
        ((fm.descent - fm.ascent) * lineSpacing)
            .coerceIn(paint.textSize, paint.textSize * 3f)
            .let { if (it.isNaN() || it <= 0f) paint.textSize else it }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val marginPx = marginDp * density
        val maxWidth = contentWidthPx(density)
        val lineHeight = lineHeightPx(paint.fontMetrics)
        val usable = (height - 2 * marginPx).coerceAtLeast(lineHeight)

        // 中文字符宽度作为基准（中英混排时偏保守，避免溢出）。
        // 样本是**多字**串，所以必须除以样本字数换算成单字宽度——早期漏了这一步，
        // 每行字数被低估约 6 倍（真机 charsPerLine=7），一页只排到实际容量的 1/5。
        val charW = paint.measureText(MEASURE_SAMPLE).coerceAtLeast(1f)
        charsPerLine = TxtLayout.charsPerLine(maxWidth, charW, MEASURE_SAMPLE.length)
        linesPerPage = TxtLayout.linesPerPage(usable, lineHeight)

        // 保底：600x800 @14sp 约 18-22 行（规范 §2.2）
        linesPerPage = max(linesPerPage, 1)

        // 首次分页时用挂起的恢复偏移；之后（换字号/行距/边距）沿用当前页偏移，保持阅读位置。
        val keep = if (restorePending) restoreOffset else currentOffset()
        pager = TxtPager(fullText, charsPerLine, linesPerPage)
        // 排版变了（字号/行距/边距/字体）→ 视觉行的切分全变了，行缓存必须作废
        invalidateLineCache()
        cancelPageAnim()
        if (fullText.isNotEmpty()) {
            pageIndex = pager!!.pageOfOffset(keep)
            restorePending = false
        }
        // 滚动位置回到该页首（keep 是字符偏移，落到页首即可）
        scrollPage = pageIndex
        scrollLine = 0
        scrollPixel = 0f
        ReadItLog.d("TxtCanvasView rebuild: ${width}x$height charsPerLine=$charsPerLine linesPerPage=$linesPerPage")

        // ------------------------------------------------------------------
        // 必须在这里回调一次（P5 真机冒烟修的 P1 缺陷）。
        //
        // 生命周期是：ReaderActivity.openTxt() -> setDocument() 时 View 还没被测量，
        // rebuild() 直接 return，pager 仍是 null -> notifyPageChanged() 算出
        // totalPages()==1，页码先显示成 "1 / 1"；
        // 之后首次 onSizeChanged() 才真正算出正确分页，但以前这里**没有回调**，
        // 于是页码一直停在 "1 / 1"，必须手动翻一页才会被 nextPage() 的
        // notifyPageChanged() 纠正（用户看到的就是「首屏页码不对，翻页后自己好了」）。
        // 字号/行距/边距变更同样走 rebuild()，一并靠这次回调刷新总页数。
        //
        // onSizeChanged 先于首帧 onDraw，所以不会闪。
        // ------------------------------------------------------------------
        notifyPageChanged()
    }

    @SuppressLint("DrawAllocation")
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Inversion.backColor(inverted))
        if (pager == null || fullText.isEmpty()) return

        when {
            // 动画优先：动画期间画两页（旧的滑出、新的滑入）
            animFromPage >= 0 -> drawAnimated(canvas)
            // 连续滚动：把分页接成一条行流
            pageTurnMode == PageTurnMode.SCROLL -> drawScrolled(canvas)
            else -> drawPage(canvas, pageIndex)
        }
    }

    /**
     * 画一页（[PageTurnMode.TAP] / [PageTurnMode.SWIPE] 的常规路径，
     * 也被动画路径复用）。
     */
    private fun drawPage(canvas: Canvas, idx: Int) {
        val page = pager?.page(idx) ?: return
        val density = resources.displayMetrics.density
        val sharp = renderMode == RenderMode.SHARP
        // 锐利模式：起点也吸附到整像素。字级（textSize）吸附了但基线停在半像素上，
        // 字形仍会跨在两个像素之间 —— 半像素偏移是抗锯齿之外第二个发虚来源。
        val marginPx = if (sharp) kotlin.math.round(marginDp * density) else marginDp * density
        val fm = paint.fontMetrics
        val lineHeight = lineHeightPx(fm)
        var y = marginPx - fm.ascent
        val maxWidth = contentWidthPx(density)
        val bottomLimit = height - marginPx

        for (para in page.text.split('\n')) {
            if (y > bottomLimit) break
            if (para.isEmpty()) {
                y += lineHeight
                continue
            }
            var start = 0
            while (start < para.length) {
                if (y > bottomLimit) break
                val count = paint.breakText(para, start, para.length, true, maxWidth, null)
                // count <= 0 时 end == start，`start = end` 不推进 → 这个 while 永远出不来，
                // 主线程 100% 卡死。maxWidth 已有下限保护，这里再加一道保险：
                // 宁可少画一个字符，也不能让整页画不完。
                if (count <= 0) break
                val end = (start + count).coerceAtMost(para.length)
                canvas.drawText(para, start, end, marginPx, if (sharp) kotlin.math.round(y) else y, paint)
                y += lineHeight
                start = end
            }
        }
    }

    /**
     * 连续行流绘制（[PageTurnMode.SCROLL]）。
     *
     * 与 [drawPage] 的关键差别：**画满一屏就接着画下一页**，页与页之间没有缝。
     * 分页是按字符容量切的，一页里真正的视觉行数可能多于（也可能少于）一屏能放下的行数；
     * 按页画时多出来的行会被 `bottomLimit` 截断丢掉（那是「静默丢字」的来源之一），
     * 连续行流则把溢出部分顺延到下一屏，一个字都不会少。
     */
    private fun drawScrolled(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val sharp = renderMode == RenderMode.SHARP
        val marginPx = if (sharp) kotlin.math.round(marginDp * density) else marginDp * density
        val fm = paint.fontMetrics
        val lineHeight = lineHeightPx(fm)
        val bottomLimit = height - marginPx
        var y = marginPx - fm.ascent - scrollPixel

        var pageIdx = scrollPage
        var lineIdx = scrollLine
        val total = pager?.exactPageCount() ?: 1
        var guard = 0
        // 每屏大约几十行，200 圈足够画满并留出余量
        while (y <= bottomLimit && guard++ < 400) {
            val lines = linesOf(pageIdx)
            if (lineIdx >= lines.size) {
                pageIdx++
                lineIdx = 0
                if (pageIdx >= total) break
                continue
            }
            val line = lines[lineIdx]
            if (line.text.isNotEmpty()) {
                canvas.drawText(
                    line.text, marginPx,
                    if (sharp) kotlin.math.round(y) else y, paint
                )
            }
            y += lineHeight
            lineIdx++
        }
    }

    /**
     * 翻页动画绘制（[PageTurnMode.ANIM]）。
     *
     * 旧页向外滑、新页向内滑；[animDir] 决定方向（+1 下一页 / -1 上一页）。
     * 动画结束后 [animFromPage] 被置回 -1，只画当前页。
     */
    private fun drawAnimated(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) {
            drawPage(canvas, pageIndex)
            return
        }
        val p = animProgress.coerceIn(0f, 1f)

        canvas.save()
        canvas.translate((1f - p) * w * animDir, 0f)
        drawPage(canvas, pageIndex)
        canvas.restore()

        canvas.save()
        canvas.translate(-p * w * animDir, 0f)
        drawPage(canvas, animFromPage)
        canvas.restore()
    }

    /**
     * 正文可用宽度，**带下限**。
     *
     * `width - 2*marginPx` 在 `marginDp` 越界时（配置备份/恢复可写入任意值；读取侧原先
     * 无 clamp）会 <= 0，而 `Paint.breakText` 传非正宽度会返回 0 —— 于是分页与绘制双双空转。
     * 下限与最小可用正文宽度一致，窄屏/分屏压缩 width 时也不会踩到。
     */
    private fun contentWidthPx(density: Float): Float =
        (width - 2 * marginDp * density).coerceAtLeast(density * 40f)

    private fun notifyPageChanged() {
        onPageChanged?.invoke(pageIndex, totalPages())
    }

    companion object {
        /** 翻页动画时长。220ms：短到不拖沓，长到能看出「翻过去」而不是「闪一下」 */
        private const val ANIM_DURATION_MS = 220L

        // 纯中文字符样本：用它测宽度再除以字数，得到保守的单字宽度。
        // 不要在这里混入半角字符（会把基准拉窄，中文行就会溢出）。
        private const val MEASURE_SAMPLE = "汉字基准宽度测量示例"
    }
}

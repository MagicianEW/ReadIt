package com.readit.reader.txt

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.readit.core.util.ReadItLog
import kotlin.math.max

/**
 * TXT 自绘渲染（规范 §3.2.1，F01）。
 *
 * 特点：
 *  - Canvas 逐行绘制，无 WebView、无 StaticLayout 全量布局，低配 SoC 也能 <4s 首屏
 *  - 分页容量按屏幕尺寸 + 字号实时计算，与 TxtPager 共用一套口径
 *  - E-Ink：不做动画，翻页只走 invalidate()
 */
class TxtCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var fontSizeSp: Float = 14f
        set(value) {
            if (field != value) {
                field = value
                paint.textSize = value * resources.displayMetrics.scaledDensity
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

    /** 页码变化回调（index 为 0-based） */
    var onPageChanged: ((Int, Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        textSize = fontSizeSp * resources.displayMetrics.scaledDensity
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

    /**
     * 画布上是否真的装了一篇正文。
     *
     * 异步打开路径（PDF 扫描检测 / DOCX `:converter` 转换）期间 `mode` 还是 TXT，
     * 但画布是空的。`ProgressPolicy` 靠这个标志区分「进度确实是 0」与「文档还没来」，
     * 否则 onPause 会把 0 当进度写回去，覆盖掉上次读到的位置。
     */
    val hasDocument: Boolean get() = pager != null && fullText.isNotEmpty()

    fun currentOffset(): Int = pager?.page(pageIndex)?.start ?: 0

    fun currentPage(): Int = pageIndex

    fun totalPages(): Int = pager?.exactPageCount() ?: 1

    fun nextPage(): Boolean {
        val next = pageIndex + 1
        val p = pager?.page(next) ?: return false
        if (p.start >= fullText.length) return false
        pageIndex = next
        notifyPageChanged()
        invalidate()
        return true
    }

    fun prevPage(): Boolean {
        if (pageIndex == 0) return false
        pageIndex--
        notifyPageChanged()
        invalidate()
        return true
    }

    fun goToOffset(offset: Int) {
        val p = pager ?: return
        pageIndex = p.pageOfOffset(offset)
        notifyPageChanged()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val marginPx = marginDp * density
        val maxWidth = (width - 2 * marginPx).coerceAtLeast(density * 40)
        val fm = paint.fontMetrics
        // 行高必须用 ascent/descent（hhea，稳定在 1em 上下），不能用 fm.top/bottom：
        // 后者来自 OS/2，CJK 字体为了容纳全角/竖排字形会给到 3em 以上 —— 换上内置思源宋体后
        // 实测行高被算成 ~120px，一页只剩 2 行。这里与 onDraw() 的行距口径也统一了
        // （以前两处公式不同，分页和绘制会算出不同的行高）。
        val lineHeight = ((fm.descent - fm.ascent) * lineSpacing)
            .coerceIn(paint.textSize, paint.textSize * 3f)
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
        if (fullText.isNotEmpty()) {
            pageIndex = pager!!.pageOfOffset(keep)
            restorePending = false
        }
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
        canvas.drawColor(0xFFFFFFFF.toInt())
        val page = pager?.page(pageIndex) ?: return
        val density = resources.displayMetrics.density
        val marginPx = marginDp * density
        val fm = paint.fontMetrics
        val lineHeight = (fm.descent - fm.ascent) * lineSpacing
        var y = marginPx - fm.ascent
        val maxWidth = width - 2 * marginPx
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
                val end = (start + count).coerceAtMost(para.length)
                canvas.drawText(para, start, end, marginPx, y, paint)
                y += lineHeight
                start = end
            }
        }
    }

    private fun notifyPageChanged() {
        onPageChanged?.invoke(pageIndex, totalPages())
    }

    companion object {
        // 纯中文字符样本：用它测宽度再除以字数，得到保守的单字宽度。
        // 不要在这里混入半角字符（会把基准拉窄，中文行就会溢出）。
        private const val MEASURE_SAMPLE = "汉字基准宽度测量示例"
    }
}

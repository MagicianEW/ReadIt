package com.readit.ui.reader

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.os.Handler
import android.os.Looper
import com.readit.core.eal.Eal
import com.readit.core.eal.RefreshModeManager
import com.readit.core.light.LightGesture
import com.readit.core.light.ScreenLight
import com.readit.core.input.InputMapper
import com.readit.core.text.ChapterDetector
import com.readit.core.text.Fonts
import com.readit.core.text.UserFonts
import com.readit.core.text.EncodingDetector
import com.readit.core.display.ChromeInversion
import com.readit.core.display.Inversion
import com.readit.core.display.RenderMode
import com.readit.core.display.ScreenProfile
import com.readit.core.display.ScreenProfileDetector
import com.readit.core.util.Metrics
import com.readit.core.util.ReadItLog
import com.readit.data.Bookmark
import com.readit.data.BookmarkFile
import com.readit.data.BookmarkStore
import com.readit.data.Bookmarks
import com.readit.data.ProgressPolicy
import com.readit.data.ProgressStore
import com.readit.data.ReadMode
import com.readit.data.ReadingPosition
import com.readit.data.TocEntry
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.stats.ReadingStats
import com.readit.data.stats.ReadingStatsStore
import com.readit.doc.DocxConverter
import com.readit.eink.R
import com.readit.epub.EpubParser
import com.readit.epub.EpubTextExtractor
import com.readit.pdf.PdfBoxBookmarks
import com.readit.pdf.PdfOpenError
import com.readit.pdf.PdfTextExtractor
import com.readit.reader.docx.DocxWebView
import com.readit.reader.epub.EpubWebView
import com.readit.reader.pdf.PdfRenderView
import com.readit.reader.txt.TxtCanvasView
import com.readit.web.WebViewCapability
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 阅读页（P1 TXT / P2 EPUB / P3 DOCX + PDF）。
 *
 * 六条渲染路径共用同一套目录、输入与进度机制：
 *  - TXT        ：TxtCanvasView
 *  - EPUB_FULL  ：WebView + epub.js（能力分级通过）
 *  - EPUB_TEXT  ：EPUB 文本抽取 + TxtCanvasView（降级）
 *  - DOCX_HTML  ：独立进程转换出的 HTML + WebView（子集渲染）
 *  - DOCX_TEXT  ：DOCX 纯文本 + TxtCanvasView（兜底档 / 转换失败降级）
 *  - PDF_RENDER ：Pdfium 页面位图 + 裁边
 *  - PDF_TEXT   ：PdfBox 文本抽取 + TxtCanvasView（渲染档不可用时的最后兜底）
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var stage: FrameLayout
    private lateinit var canvas: TxtCanvasView
    private lateinit var drawer: DrawerLayout

    /** 按需创建：打开 TXT 时不该白白构造 WebView / 加载 native 库 */
    private var epubView: EpubWebView? = null
    private var docxView: DocxWebView? = null
    private var pdfView: PdfRenderView? = null

    private lateinit var tocList: RecyclerView
    private lateinit var pageInfo: TextView
    private lateinit var panel: LinearLayout
    private lateinit var fontSpinner: Spinner
    private lateinit var btnEncoding: Button

    /** 当前打开的文件；改编码要按它重读，不能只靠 onCreate 的局部变量 */
    private lateinit var bookFile: File
    private var fontIds: List<String> = Fonts.builtinIds()

    /**
     * Spinner 的 `onItemSelected` 在 `setAdapter()` 后会被立刻回调一次（position 0），
     * 随后 `setSelection()` 再回调真正的当前项。不设闸的话，打开面板这一下就会把字体
     * 改成列表第一项——用户只是想看看，结果字体被悄悄改了。
     */
    private var fontSpinnerReady = false
    private lateinit var prefs: ReadItPrefs

    private val io = Executors.newSingleThreadExecutor()

    private var entries: List<TocEntry> = emptyList()
    private var bookId: String = ""
    private var mode = ReadMode.TXT

    /** 当前 DOCX 的转换产物目录：打包字体要拷到这里才能被 @font-face 的相对路径命中 */
    private var docxBaseDir: File? = null
    private var downX = 0f
    private var downY = 0f
    private var downStageX = 0f
    private var downStageY = 0f

    /**
     * 触摸接管判定（P5 真机冒烟修复）。
     *
     * 只有「按下点落在 stage（正文舞台）之内」的手势才由本 Activity 按三分区语义消费；
     * 落在 stage 之外（底部工具栏 目录/排版/刷新/设置、页码栏、目录抽屉）的一律交还
     * 给子 View，否则按钮永远收不到点击。
     */
    private var downInStage = false
    private val stageRect = Rect()
    private val stageLoc = IntArray(2)

    // ---------------------------------------------------------------- §7 指标埋点

    /** 打开动作的起点，用于「首帧 / 首屏」类指标 */
    private var openStartedAt = 0L

    // ---------------------------------------------------------------- §F26 阅读统计

    /**
     * 本次「可见阅读时段」的起点（单调时钟），onResume 起、onPause 结。
     *
     * 用 [Metrics.now] 而不是墙上时钟：统计的是**读了多久**，不该被系统改时间影响。
     * 前后台切换会切成若干段，段内累加进 [sessionMs]。
     */
    private var sessionStartedAt = 0L

    /** 本次打开累计的可见阅读时长（跨多次前后台切换累加） */
    private var sessionMs = 0L

    /** 本次打开累计的翻页次数，只在 [logNav] 里 +1（所有渲染路径都走那里） */
    private var sessionTurns = 0

    /**
     * 本次打开的统计是否已落盘。
     *
     * 统计口径是「一次打开 = openCount +1」，所以一个 Activity 实例**只能落一次**；
     * onPause(isFinishing) 与 onDestroy 都可能触发，靠这个闸门防重复。
     */
    private var statsFlushed = false

    /**
     * 自绘画布最近一次回调里的总页数。
     *
     * TxtCanvasView 没有公开 pageCount 访问器，只有 `onPageChanged(index, total)`
     * 这一个出口，顺手接住即可（不额外从 pager 反推，免得渲染中读坏状态）。
     */
    private var canvasTotalPages = 0

    /** 一次用户导航（翻页 / 目录跳转）的起点，页面回调时结算后清零 */
    private var navStartedAt = 0L

    /**
     * 本次导航是不是「目录跳转」。
     *
     * 自绘画布只有「页面已可见」这一个回调，翻页与跳转走的是同一条结算路径；
     * 不带这个标记的话，目录跳转会被记成 `pageTurn`，`tocJump` 永久缺失
     * （EPD106 实测：TXT 目录跳转打出的是 `pageTurn file=chapter_long.txt ms=10`）。
     */
    private var navIsTocJump = false

    /**
     * 本次打开最终要展示的视图，由 [showOnly] 记录。
     *
     * 为什么要这东西：Activity 是「一本书一个实例」，但视图回调不是——打开 PDF 时
     * 自绘画布还没设过文档，仅因为一次布局就会回调 `onPageChanged(0, 1)`，被
     * [notePageShown] 当成「首帧」结算掉。实测（EPD106）：打开 real_text.pdf 打出
     * `metrics: firstFrame file=real_text.pdf mode=TXT ms=205`——mode 还是上一个
     * 实例遗留的 TXT，205ms 是空画布的假数字，而 Pdfium 真正的首屏**永远不再记录**
     * （`firstFrameLogged` 已被假样本吃掉）。只认「当前打开路径指定的那个视图」即可根治。
     */
    private var pendingView: View? = null

    private var firstFrameLogged = false

    /**
     * 页面已可见。
     *
     * 首次回调 = 「首帧」，之后若处在一次导航里 = 「翻页 / 跳转耗时」。
     * 自绘画布（TXT / DOCX_TEXT / EPUB_TEXT / PDF_TEXT）与 Pdfium 渲染档共用这一处，
     * 因为两者都只有在页面真的显示出来时才回调。
     *
     * [src] 必须是本次打开路径指定的视图（见 [pendingView]），否则一律忽略：
     * 空画布的布局回调、上一次打开的视图的余波都不算「页面已可见」。
     */
    private fun notePageShown(src: View) {
        if (src !== pendingView) {
            ReadItLog.d("page shown ignored: src=${src.javaClass.simpleName} pending=${pendingView?.javaClass?.simpleName}")
            return
        }
        if (!firstFrameLogged && openStartedAt > 0L) {
            firstFrameLogged = true
            Metrics.logFirstFrame(bookId, mode.name, openStartedAt)
            return
        }
        if (navStartedAt > 0L) {
            logNav(navStartedAt)
            navStartedAt = 0L
        }
    }

    /**
     * 按发起方结算一次导航耗时：目录跳转记 `tocJump`，翻页记 `pageTurn`。
     *
     * 两条 WebView 路径（EPUB_FULL / DOCX_HTML）没有页面回调，由发起处直接调用。
     */
    private fun logNav(startedAt: Long) {
        if (navIsTocJump) {
            Metrics.logTocJump(bookId, mode.name, startedAt)
        } else {
            Metrics.logPageTurn(bookId, mode.name, startedAt)
            // F26：翻页计数就挂在这里 —— 自绘画布的页面回调与两条 WebView 路径
            // （EPUB_FULL / DOCX_HTML）最终都走 logNav，挂别处必然漏一条路径。
            sessionTurns++
        }
    }

    /** 用户发起一次导航的起点；[tocJump] 区分目录跳转与翻页，决定结算时记哪条指标 */
    private fun beginNav(tocJump: Boolean = false) {
        navStartedAt = Metrics.now()
        navIsTocJump = tocJump
    }

    // 屏幕灯手势状态（详见 lightGesture* 一组方法）
    private val lightHandler = Handler(Looper.getMainLooper())
    private var lightLongPress: Runnable? = null
    private var lightLongPressFired = false
    private var lightSwipeConsumed = false
    private var lightSwipeBaseLevel = 0
    private var lightSwipeSteps = 0
    private var lightSwipeDirty = false

    /** 把 stage 的真实屏幕矩形同步到 [stageRect]，并在 [stageLoc] 留下左上角屏幕坐标 */
    private fun syncStageRect() {
        stage.getLocationOnScreen(stageLoc)
        stageRect.set(
            stageLoc[0],
            stageLoc[1],
            stageLoc[0] + stage.width,
            stageLoc[1] + stage.height
        )
    }

    /** DOCX HTML 路径的进度锚点：最后一次跳转/点击到的标题序号 */
    private var lastDocxHeading = 0

    /** PDF 文本兜底的幂等闸门，避免两条降级路径重复触发 */
    private var pdfFallbackStarted = false

    /** 连续渲染超时计数（R11）；任意一页渲染成功即清零 */
    private var pdfTimeoutStreak = 0

    private val tocAdapter = TocAdapter { entry -> jumpTo(entry) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        prefs = ReadItPrefs.get(this)
        stage = findViewById(R.id.stage)
        canvas = findViewById(R.id.txtCanvas)
        drawer = findViewById(R.id.drawer)
        tocList = findViewById(R.id.rvToc)
        pageInfo = findViewById(R.id.tvPageInfo)
        panel = findViewById(R.id.panelTypography)

        val path = intent.getStringExtra(EXTRA_PATH) ?: run {
            finish()
            return
        }
        val file = File(path)
        bookId = file.name
        bookFile = file
        // §7 的四个时限都以「打开动作」为起点，必须在任何 open* 之前取
        openStartedAt = Metrics.now()
        btnEncoding = findViewById(R.id.btnEncoding)

        // 目录宽度 90vw（600×800 兜底口径）
        val tocWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        tocList.layoutParams.width = tocWidth
        tocList.layoutManager = LinearLayoutManager(this)
        tocList.adapter = tocAdapter

        // 屏幕灯：把持久化的开关/亮度作用到当前窗口（首次进入也生效）
        restoreLight()

        canvas.fontSizeSp = prefs.fontSizeSp
        canvas.lineSpacing = prefs.lineSpacing
        canvas.marginDp = prefs.marginDp
        // 渲染模式：AUTO 在这里解析成实际生效值（疑似墨水屏 -> SHARP）。
        // 必须**先于** fontSizeSp 生效相关的重排，避免多一次 rebuild。
        canvas.renderMode = resolvedRenderMode()
        val openTf = UserFonts.typefaceFor(this, fontId())
        canvas.typeface = openTf
        ReadItLog.i("font at open: id=${fontId()} typeface=${openTf ?: "null(keep default)"}")
        canvas.inverted = prefs.invertEnabled
        canvas.onPageChanged = { index, total ->
            // F26：画布不公开 pageCount，从唯一出口接住总页数
            if (total > 0) canvasTotalPages = total
            // TalkBack 读不到自绘正文（见 R18 结论），至少给出可定位的方位信息
            canvas.contentDescription = getString(R.string.a11y_canvas, index + 1, total)
            if (mode != ReadMode.EPUB_FULL && mode != ReadMode.PDF_RENDER && mode != ReadMode.DOCX_HTML) {
                pageInfo.text = getString(R.string.page_info, index + 1, total)
            }
            // TXT / DOCX_TEXT / EPUB_TEXT / PDF_TEXT 都走自绘画布，页面回调即「已可见」
            notePageShown(canvas)
        }

        val saved = ProgressStore.load(this, bookId)
        when (file.extension.lowercase()) {
            "epub" -> openEpub(file, saved)
            "txt", "text" -> openTxt(file, saved)
            "docx" -> openDocx(file, saved)
            "pdf" -> openPdf(file, saved)
            else -> {
                Toast.makeText(
                    this,
                    getString(R.string.format_unsupported, file.extension.uppercase()),
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return
            }
        }
        syncEncodingButton()

        findViewById<Button>(R.id.btnToc).setOnClickListener {
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.toc_empty), Toast.LENGTH_SHORT).show()
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }
        findViewById<Button>(R.id.btnBookmark).setOnClickListener { showBookmarks() }
        findViewById<Button>(R.id.btnTypography).setOnClickListener {
            if (panel.visibility != View.VISIBLE) {
                // 每次展开都重扫一次字体目录：用户可能刚把 .ttf 丢进去
                refreshFontOptions()
            }
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            syncTypographyPanelForMode()
        }
        // F15 常驻入口：随时改编码，不依赖自动检测是否猜错
        findViewById<Button>(R.id.btnEncoding).setOnClickListener {
            if (::bookFile.isInitialized) askEncoding(bookFile, currentTextPosition())
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            RefreshModeManager.requestFullRefresh(activeView())
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, com.readit.ui.settings.SettingsActivity::class.java))
        }

        bindTypographyPanel()

        // 开屏就把持久化的反色作用到界面 chrome —— 否则用户带着反色偏好进来，
        // 正文是黑的、工具栏是白的，只有拨一次开关才一致。
        ChromeInversion.apply(chromeViews(), prefs.invertEnabled)
    }

    // ------------------------------------------------------------------ 视图创建

    private fun ensureEpubView(): EpubWebView {
        epubView?.let { return it }
        val v = EpubWebView(this)
        v.setInverted(prefs.invertEnabled)
        stage.addView(v, fullStageParams())
        epubView = v
        return v
    }

    private fun ensureDocxView(): DocxWebView {
        docxView?.let { return it }
        val v = DocxWebView(this)
        v.setInverted(prefs.invertEnabled)
        v.onLoaded = { notePageShown(v) }
        stage.addView(v, fullStageParams())
        docxView = v
        return v
    }

    private fun ensurePdfView(): PdfRenderView {
        pdfView?.let { return it }
        val v = PdfRenderView(this)
        v.inverted = prefs.invertEnabled
        v.renderMode = resolvedRenderMode()
        stage.addView(v, fullStageParams())
        pdfView = v
        return v
    }

    private fun fullStageParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    /** 只让当前路径的视图可见，避免 WebView / 位图视图在后台白白占内存 */
    private fun showOnly(target: View?) {
        // 同时声明「本次打开指定的视图」：只有它的回调才算页面已可见（见 notePageShown）
        pendingView = target
        // 也让按钮显隐跟上 mode。原先只在 onCreate（此时 mode 还是默认 TXT）与展开排版面板时
        // 同步，结果 PDF_RENDER / EPUB_FULL 下「编码」按钮一直亮着（epd106 实测截图）。
        syncEncodingButton()
        canvas.visibility = if (target === canvas) View.VISIBLE else View.GONE
        epubView?.visibility = if (target === epubView) View.VISIBLE else View.GONE
        docxView?.visibility = if (target === docxView) View.VISIBLE else View.GONE
        pdfView?.visibility = if (target === pdfView) View.VISIBLE else View.GONE
    }

    /**
     * F15 的编码入口只在「自绘文本」模式下有意义。
     *
     * EPUB_FULL / DOCX_HTML 的编码由各自的解析器负责（走 CSS/HTML meta），
     * PDF_RENDER 只出位图——这三条路径给个编码按钮只会让人困惑。
     */
    private fun syncEncodingButton() {
        val usesCanvas = when (mode) {
            ReadMode.TXT, ReadMode.EPUB_TEXT, ReadMode.DOCX_TEXT, ReadMode.PDF_TEXT -> true
            ReadMode.EPUB_FULL, ReadMode.DOCX_HTML, ReadMode.PDF_RENDER -> false
        }
        btnEncoding.visibility = if (usesCanvas) View.VISIBLE else View.GONE
    }

    /** 当前正文位置，改编码后要把用户还在这页上 */
    private fun currentTextPosition() =
        ReadingPosition(charOffset = if (::canvas.isInitialized) canvas.currentOffset() else 0)

    // ------------------------------------------------------------------ TXT

    /**
     * TXT 打开（F01 / F15）。
     *
     * 编码是这里唯一的变量，处理顺序是「记忆 → 自动检测 → 手动选择」：
     *  - 用户以前为这本书选过编码 → 直接用，不再打扰；失效则清掉回退；
     *  - 没选过 → 自动检测；检测失败或**解出来是乱码**（同样两者都要管，否则
     *    `MANUAL_CANDIDATES` 永远没人调用）→ 弹手动选择列表。
     *
     * **整段搬进 [io] 线程**：整本 `readText` + 全文章节扫描 + 首次分页都是 O(全书) 的
     * 工作量，一本 200MB 的 GBK 书在 256MB 堆的墨水屏上会直接 OOM，中等大小的 TXT 也会
     * 阻塞主线程到 ANR。PDF 与 DOCX 的打开本来就走 `io`，这里是唯一的例外，属于路径间
     * 的不一致而不是设计。
     *
     * mode / showOnly 仍在主线程同步做完：它们决定「本次打开的视图是谁」，
     * 埋点与编码按钮显隐都依赖它，先定下来才不会中途读到上一次的残留状态。
     */
    private fun openTxt(file: File, saved: ReadingPosition?) {
        mode = ReadMode.TXT
        showOnly(canvas)

        val remembered = prefs.charsetFor(bookId)
        io.execute {
            if (remembered != null) {
                try {
                    val text = EncodingDetector.readWith(file, remembered)
                    val chapters = detectChapters(text)
                    runOnUiThread { applyTxtContent(file, text, chapters, saved, remembered) }
                    return@execute
                } catch (e: Exception) {
                    // 记忆失效（书被换掉、编码名不被平台支持）→ 清掉，回退到自动检测
                    ReadItLog.w("remembered charset unusable: $remembered ${e.message}")
                    prefs.setCharsetFor(bookId, null)
                }
            }

            val detected = EncodingDetector.detect(file)
            val text = tryRead(file, detected?.name())
            val chapters = detectChapters(text.orEmpty())
            val garbled = text == null || EncodingDetector.looksGarbled(text)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyTxtContent(file, text ?: "", chapters, saved, detected?.name())
                if (garbled) askEncoding(file, saved)
            }
        }
    }

    /**
     * 章节检测（纯计算，可在 IO 线程跑）。
     *
     * 逐行 substring + 每行最多 7 次正则匹配，全书扫描是打开路径上最贵的一段，
     * 必须与 UI 线程解耦。不可靠时返回空列表，由调用方走纯分页。
     *
     * [DetectResult.truncated] 会穿透到 UI：目录撞到 2000 章上限时必须让用户知道
     * 「后面还有，只是没做出来」，否则表现为「这本书的目录就这么长」。
     */
    private fun detectChapters(text: String): ChapterDetector.DetectResult {
        val r = ChapterDetector.detectDetailed(text)
        if (!ChapterDetector.isReliable(r.chapters, text.length)) {
            return ChapterDetector.DetectResult(emptyList(), r.truncated)
        }
        return r
    }

    /** 目录被章节数上限截断时的统一提示（TEXT/DOCX/PDF 三条打开路径共用） */
    private fun warnChaptersTruncated() {
        Toast.makeText(
            this,
            getString(R.string.toc_truncated, ChapterDetector.DEFAULT_MAX_CHAPTERS),
            Toast.LENGTH_LONG
        ).show()
    }

    /** 按指定编码整本读取；失败返回 null（不打扰用户，由调用方决定何时提示） */
    private fun tryRead(file: File, charsetName: String?): String? = try {
        EncodingDetector.readWith(file, charsetName ?: "UTF-8")
    } catch (e: Exception) {
        ReadItLog.w("read failed with ${charsetName ?: "UTF-8"}: ${e.message}")
        null
    }

    /** 把文本交给画布。 [detected] 由 IO 线程预先算好；`charsetName` 为 null 表示自动检测结果。 */
    private fun applyTxtContent(
        file: File,
        text: String,
        detected: ChapterDetector.DetectResult,
        saved: ReadingPosition?,
        charsetName: String?
    ) {
        entries = detected.chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
        tocAdapter.submit(entries)

        canvas.setDocument(text, saved?.charOffset ?: 0)
        ReadItLog.i(
            "open txt: ${file.name} chars=${text.length} chapters=${entries.size} " +
                "charset=${charsetName ?: "auto"}"
        )
        if (detected.truncated) warnChaptersTruncated()
        RefreshModeManager.requestFullRefresh(canvas)
    }

    /**
     * F15 手动选择编码。
     *
     * 选完先验证：仍乱码就再弹一次让用户继续试（中文书常见的 GBK/GB18030/BIG5
     * 互相之间肉眼难分，一次选中率不高）；验证通过才写进 prefs，避免把一次
     * 误操作固化成永久乱码。
     *
     * 试读同样在 [io] 线程：每个候选编码都要**整本**读一遍，主线程做这件事会卡住界面。
     */
    private fun askEncoding(file: File, saved: ReadingPosition?) {
        val items = EncodingDetector.MANUAL_CANDIDATES.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.encoding_pick_title)
            .setItems(items) { _, which ->
                val name = items[which]
                io.execute {
                    val text = tryRead(file, name)
                    val garbled = text != null && EncodingDetector.looksGarbled(text)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        when {
                            text == null -> toast(getString(R.string.read_failed))
                            garbled -> {
                                toast(getString(R.string.encoding_still_garbled, name))
                                askEncoding(file, saved)
                            }
                            else -> {
                                prefs.setCharsetFor(bookId, name)
                                applyTxtContent(file, text, detectChapters(text), saved, name)
                                toast(getString(R.string.encoding_applied, name))
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ EPUB

    /**
     * EPUB 打开（F03）。
     *
     * [EpubParser.parse] 要解 zip + 解析 container/OPF/NCX/toc，是 I/O + XML 的双重开销，
     * 放在主线程上等于开书即卡；而降级路径的 [EpubTextExtractor] 更要**逐章**读进内存
     * （zip bomb 或超大章节能在阅读器自身进程 OOM/ANR —— EPUB 侧没有 DOCX 那套进程隔离兜底）。
     * 两段都搬进 [io]，只有「建视图 / 设内容」这类必须上主线程的部分留在主线程。
     */
    private fun openEpub(file: File, saved: ReadingPosition?) {
        Toast.makeText(this, getString(R.string.epub_opening), Toast.LENGTH_SHORT).show()
        io.execute {
            var book: EpubParser.Book? = null
            var errMsg = ""
            try {
                book = EpubParser.parse(file)
            } catch (e: Exception) {
                errMsg = e.message ?: ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val b = book
                if (b == null) {
                    ReadItLog.e("epub parse failed: $errMsg")
                    Toast.makeText(
                        this,
                        getString(R.string.epub_open_failed, errMsg),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@runOnUiThread
                }
                if (b.warnings.isNotEmpty()) ReadItLog.w("epub warnings: ${b.warnings}")
                if (b.encrypted) {
                    Toast.makeText(this, getString(R.string.epub_drm), Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                val report = WebViewCapability.probe(this)
                val tier = Eal.strategy(this)
                ReadItLog.i("epub route: webview=${report.level} tier=$tier")
                if (report.supportsEpubJs) {
                    openEpubFull(file, b, saved)
                } else {
                    openEpubTextAsync(file, b, saved)
                }
            }
        }
    }

    private fun openEpubFull(file: File, book: EpubParser.Book, saved: ReadingPosition?) {
        mode = ReadMode.EPUB_FULL
        val ev = ensureEpubView()
        showOnly(ev)

        // href 一并带上：单个 XHTML 承载多章的书里，相邻目录项的 spineIndex 相同，
        // 只按下标跳会全部落到该文件开头（要靠 href 里的 #fragment 区分）。
        entries = book.toc.map {
            TocEntry(title = it.title, depth = it.depth, spineIndex = it.spineIndex, href = it.href)
        }
        tocAdapter.submit(entries)
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.epub_no_toc), Toast.LENGTH_SHORT).show()
        }

        ev.callback = object : EpubWebView.Callback {
            override fun onRendered(href: String, elapsedMs: Long) {
                // 每次章节渲染都会回调，日志按「本次渲染耗时」表述（不是"首章"）
                ReadItLog.i("epub chapter rendered in ${elapsedMs}ms href=$href")
                notePageShown(ev)
                RefreshModeManager.requestFullRefresh(ev)
            }

            override fun onLocation(cfi: String, href: String, spineIndex: Int, page: Int, total: Int) {
                if (total > 0) {
                    pageInfo.text = getString(R.string.page_info, page, total)
                } else {
                    pageInfo.text = entries.firstOrNull { it.spineIndex == spineIndex }?.title.orEmpty()
                }
            }

            override fun onError(message: String) {
                ReadItLog.e("epub runtime error: $message")
                Toast.makeText(
                    this@ReaderActivity,
                    getString(R.string.epub_open_failed, message),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        // 「正在打开 EPUB…」由 openEpub 统一弹（解析阶段就要给反馈），这里不再重复一次
        ev.open(file, saved?.cfi, fontPercent(), Fonts.cssFamily(fontId()), prefs.invertEnabled)
    }

    /** 降级文本路径：抽取在 IO 线程，落内容回主线程 */
    private fun openEpubTextAsync(file: File, book: EpubParser.Book, saved: ReadingPosition?) {
        io.execute {
            var result: EpubTextExtractor.Result? = null
            var errMsg = ""
            try {
                result = EpubTextExtractor().extract(file, book)
            } catch (e: Exception) {
                ReadItLog.e("epub text extract failed", e)
                errMsg = e.message ?: ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val r = result
                if (r == null) {
                    Toast.makeText(
                        this,
                        getString(R.string.epub_open_failed, errMsg),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@runOnUiThread
                }
                mode = ReadMode.EPUB_TEXT
                showOnly(canvas)

                val offsets = r.tocOffsets
                entries = book.toc.mapIndexed { i, e ->
                    TocEntry(
                        title = e.title,
                        depth = e.depth,
                        offset = offsets.getOrElse(i) { -1 },
                        spineIndex = e.spineIndex,
                        href = e.href
                    )
                }.filter { it.offset >= 0 }
                tocAdapter.submit(entries)

                canvas.setDocument(r.text, saved?.charOffset ?: 0)
                Toast.makeText(this, getString(R.string.epub_degraded), Toast.LENGTH_LONG).show()
                ReadItLog.i("epub text path: chars=${r.text.length} toc=${entries.size}")
            }
        }
    }

    // ------------------------------------------------------------------ DOCX

    /**
     * DOCX 转换在 :converter 独立进程完成，因此这里必须异步等待，
     * 不能阻塞主线程（大文档 + 低配 SoC 可能数秒）。
     */
    private fun openDocx(file: File, saved: ReadingPosition?) {
        val docxMode = Eal.strategy(this).docxMode
        Toast.makeText(this, getString(R.string.docx_opening), Toast.LENGTH_SHORT).show()

        io.execute {
            when (val outcome = DocxConverter.convert(applicationContext, file, docxMode)) {
                is DocxConverter.Result.Failed -> runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    ReadItLog.e("docx convert failed: ${outcome.reason} timedOut=${outcome.timedOut}")
                    Toast.makeText(
                        this,
                        if (outcome.timedOut) outcome.reason
                        else getString(R.string.docx_open_failed, file.name),
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
                is DocxConverter.Result.Ok -> {
                    val converted = outcome.converted
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (converted.isEmpty) {
                            ReadItLog.e("docx convert produced nothing: ${file.name} mode=$docxMode")
                            Toast.makeText(
                                this,
                                getString(R.string.docx_open_failed, file.name),
                                Toast.LENGTH_LONG
                            ).show()
                            finish()
                            return@runOnUiThread
                        }
                        ReadItLog.i("docx route: mode=$docxMode viaService=${converted.viaService}")
                        if (converted.text != null) {
                            openDocxText(converted.text, saved)
                        } else {
                            openDocxHtml(converted, saved)
                        }
                        notifyDocxDegrade(converted.warnings)
                    }
                }
            }
        }
    }

    private fun openDocxText(text: String, saved: ReadingPosition?) {
        mode = ReadMode.DOCX_TEXT
        showOnly(canvas)
        val detected = detectChapters(text)
        entries = detected.chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
        tocAdapter.submit(entries)
        if (detected.truncated) warnChaptersTruncated()
        canvas.setDocument(text, saved?.charOffset ?: 0)
        ReadItLog.i("docx text path: chars=${text.length}")
    }

    private fun openDocxHtml(converted: DocxConverter.Converted, saved: ReadingPosition?) {
        val html = converted.html ?: return openDocxText("", saved)
        mode = ReadMode.DOCX_HTML

        val dv = ensureDocxView()
        showOnly(dv)

        entries = converted.tocTitles.mapIndexed { i, title ->
            TocEntry(
                title = title,
                depth = converted.tocDepths.getOrElse(i) { 0 },
                offset = converted.tocIds.getOrElse(i) { i }
            )
        }
        tocAdapter.submit(entries)
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.docx_no_toc), Toast.LENGTH_SHORT).show()
        }

        lastDocxHeading = (saved?.chapterIndex ?: 0).coerceAtLeast(0)
        docxBaseDir = converted.baseDir
        dv.setFontFamily(Fonts.cssFamily(fontId()), docxFontFaceSrc(converted.baseDir))
        dv.setFontPercent(fontPercent())
        dv.load(html, converted.baseDir)
        // 就绪判定在 DocxWebView 内部（onPageFinished 之前会挂起），这里直接调用即可。
        // 早期版本用 dv.post{} 抢在 onPageFinished 之前就 evaluateJavascript，
        // 在空文档上静默无效 → 恢复进度丢失。
        if (lastDocxHeading > 0) dv.scrollToHeading(lastDocxHeading)
        RefreshModeManager.requestFullRefresh(dv)
    }

    /** F04 降级提示：哪些复杂元素被简化，一次性说清楚，可关闭 */
    private fun notifyDocxDegrade(warnings: List<String>) {
        val shown = warnings.distinct().take(MAX_DEGRADE_LINES)
        if (shown.isEmpty()) return
        ReadItLog.w("docx degraded: $shown")
        val detail = shown.joinToString("\n") { "• $it" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.docx_degraded_title))
            .setMessage(getString(R.string.docx_degraded_detail, detail))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    // ------------------------------------------------------------------ PDF

    private fun openPdf(file: File, saved: ReadingPosition?) {
        // 已强制导入过的书不再重复检测（用户已明确知情）
        if (prefs.isForceImport(bookId)) {
            openPdfRender(file, saved)
            return
        }
        Toast.makeText(this, getString(R.string.pdf_opening), Toast.LENGTH_SHORT).show()

        io.execute {
            var encrypted = false
            val verdict = try {
                val ex = newPdfTextExtractor()
                try {
                    ex.open(file)
                    ex.detectScan(prefs.scanThresholds())
                } finally {
                    ex.close()
                }
            } catch (e: Throwable) {
                ReadItLog.w("pdf scan detect failed: ${e.message}")
                encrypted = PdfOpenError.isEncrypted(e)
                null
            }
            // 加密 PDF：渲染档与文本档都读不了，通用降级会连弹两条错误提示且都不对，
            // 这里直接按 §9.3 边界场景 5 给「提示不支持」，不再往下走。
            if (encrypted) {
                ReadItLog.i("pdf encrypted: ${file.name} -> 提示不支持（§9.3 边界场景 5）")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle(R.string.pdf_encrypted_title)
                        .setMessage(R.string.pdf_encrypted_message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.action_ok) { _, _ -> finish() }
                        .show()
                }
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (verdict?.scanned == true) {
                    showScannedPrompt(verdict.sampledPages, file, saved)
                } else {
                    verdict?.let { ReadItLog.i("pdf scan verdict: ${it.describe()}") }
                    openPdfRender(file, saved)
                }
            }
        }
    }

    /** F08：不承诺 100% 检出，必须给出「继续导入」出口 */
    private fun showScannedPrompt(sampledPages: Int, file: File, saved: ReadingPosition?) {
        AlertDialog.Builder(this)
            .setTitle(R.string.pdf_scanned_title)
            .setMessage(getString(R.string.pdf_scanned_message, sampledPages))
            .setCancelable(false)
            .setPositiveButton(R.string.pdf_force_import) { _, _ ->
                prefs.markForceImport(bookId)
                openPdfRender(file, saved)
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> finish() }
            .show()
    }

    private fun openPdfRender(file: File, saved: ReadingPosition?) {
        val pv = ensurePdfView()
        pv.cropEnabled = prefs.pdfCropEnabled
        // 注意：Eal.strategy(this).pdfCachedPages 故意**不**下发。PdfRenderView 恒为单页常驻
        // （§5.1 硬要求），曾经存在的 `pv.cachePages` 属性没有任何读取方，
        // 只会让自审表误以为档位在驱动缓存页数。
        pdfTimeoutStreak = 0
        // 先定 mode 再 showOnly：showOnly 会顺带校准编码入口显隐（见 syncEncodingButton）
        mode = ReadMode.PDF_RENDER
        showOnly(pv)

        pv.onPageChanged = { page, total ->
            pageInfo.text = getString(R.string.page_info, page + 1, total)
        }
        pv.onPageRendered = {
            pdfTimeoutStreak = 0
            // Pdfium 渲染档的首帧口径与自绘画布一致：页面真的回调出来了
            notePageShown(pv)
        }
        pv.onRenderTimeout = { page ->
            // R11：单页超时先提示、允许用户翻页绕开。
            //
            // 「连续超时就整本退文本档」这条兜底**不能套用到扫描版 PDF 上**：
            // 慢的恰恰是大图扫描页，而这正是超时预算最紧的一类书；退到文本档后
            // 扫描书没有文本层 → `fallbackToPdfText` 末尾 `finish()`，用户看到
            // 「疑似扫描版→继续导入」、读几页后被弹退出，**重开复现、该书永久打不开**。
            // 降级方向对扫描书恰好是错的。所以扫描版只提示不降级。
            ReadItLog.w("pdf render timeout: page=$page streak=${pdfTimeoutStreak + 1}")
            pdfTimeoutStreak++
            val scannedBook = prefs.isForceImport(bookId)
            when {
                mode != ReadMode.PDF_RENDER -> Unit
                scannedBook -> Toast.makeText(
                    this,
                    getString(R.string.pdf_page_timeout, page + 1),
                    Toast.LENGTH_SHORT
                ).show()
                pdfTimeoutStreak >= PDF_TIMEOUT_STREAK_LIMIT ->
                    fallbackToPdfText(file, "render timeout x$pdfTimeoutStreak")
                else -> Toast.makeText(
                    this,
                    getString(R.string.pdf_page_timeout, page + 1),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        pv.onRenderFailed = { reason ->
            ReadItLog.e("pdf render failed: $reason")
            // 整本打不开（页数未知）才值得退到文本档；
            // 单页畸形在长文档里很常见，为一页把整本退成无页码的纯文本是过度降级。
            if (mode == ReadMode.PDF_RENDER && pv.pageCount <= 0) {
                fallbackToPdfText(file, reason)
            } else if (mode == ReadMode.PDF_RENDER) {
                Toast.makeText(
                    this,
                    getString(R.string.pdf_page_failed, pv.currentPage + 1),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (!pv.open(file)) {
            // open() 内部已经回调过 onRenderFailed，这里只兜住「回调没来」的情况；
            // fallbackToPdfText 自带幂等闸门，不会被触发两次。
            fallbackToPdfText(file, "pdfium unavailable")
            return
        }

        entries = loadPdfToc(file, pv)
        tocAdapter.submit(entries)
        ReadItLog.i("open pdf: ${file.name} pages=${pv.pageCount} toc=${entries.size} crop=${prefs.pdfCropEnabled}")

        val startPage = saved?.pageIndex?.takeIf { it >= 0 } ?: 0
        pv.post { pv.goToPage(startPage) }
    }

    /** Pdfium 书签优先；整体为空时退到 PdfBox 逐项解析（F06） */
    private fun loadPdfToc(file: File, pv: PdfRenderView): List<TocEntry> {
        val fromPdfium = try {
            pv.bookmarks()
        } catch (e: Throwable) {
            ReadItLog.w("pdfium toc failed: ${e.message}")
            emptyList()
        }
        val used = if (fromPdfium.isNotEmpty()) {
            fromPdfium
        } else {
            ReadItLog.i("pdfium toc empty -> pdfbox fallback")
            PdfBoxBookmarks.read(file)
        }
        return used.map { TocEntry(title = it.title, depth = it.depth, pageIndex = it.pageIndex) }
    }

    /**
     * 最后兜底：Pdfium 完全不可用时改读文本层。
     * 页码信息在此路径下丢失（如实记录在日志里），只为「至少能读」。
     */
    private fun fallbackToPdfText(file: File, reason: String) {
        // 幂等闸门：onRenderFailed 与 open() 返回值两条路可能都想降级，只允许发生一次
        if (pdfFallbackStarted) return
        pdfFallbackStarted = true

        Toast.makeText(
            this,
            getString(
                if (reason.contains("timeout")) R.string.pdf_timeout_fallback
                else R.string.pdf_render_failed
            ),
            Toast.LENGTH_LONG
        ).show()
        ReadItLog.w("pdf text fallback: $reason")

        io.execute {
            var text: String? = null
            var encrypted = false
            try {
                val ex = newPdfTextExtractor()
                try {
                    ex.open(file)
                    val sb = StringBuilder()
                    ex.forEachPage { _, pageText ->
                        if (sb.length < MAX_PDF_TEXT_CHARS) sb.append(pageText).append('\n')
                    }
                    text = sb.toString()
                } finally {
                    ex.close()
                }
            } catch (e: Throwable) {
                ReadItLog.e("pdf text fallback failed", e)
                // 降级路径**也要**查加密。PdfOpenError 的注释早就点出了这个 bug：
                // 加密 PDF 在 Pdfium 与 PdfBox 都读不了，只在扫描检测阶段查的话，
                // 用户会依次看到「PDF 渲染失败」+「没有可提取的文本层」两条误导提示，
                // 真实原因永不出现。
                encrypted = PdfOpenError.isEncrypted(e)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (encrypted) {
                    ReadItLog.i("pdf encrypted (detected on degrade path) -> 提示不支持")
                    AlertDialog.Builder(this)
                        .setTitle(R.string.pdf_encrypted_title)
                        .setMessage(R.string.pdf_encrypted_message)
                        .setCancelable(false)
                        .setPositiveButton(R.string.action_ok) { _, _ -> finish() }
                        .show()
                    return@runOnUiThread
                }
                if (text.isNullOrBlank()) {
                    // 文本兜底为空 = 这本书没有文本层（扫描版）。
                    // 此时**不能** finish()：用户刚被「疑似扫描版 → 继续导入」放进来，
                    // 再被「没有可提取的文本层」弹出去，且重开复现 —— 该书永久打不开。
                    // 正确出口是留在渲染档（哪怕部分页会超时提示），让用户至少能翻。
                    ReadItLog.w("pdf text fallback empty -> keep render mode (likely scan)")
                    Toast.makeText(this, getString(R.string.pdf_no_text_keep_render), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                mode = ReadMode.PDF_TEXT
                syncTypographyPanelForMode()
                showOnly(canvas)
                val detected = detectChapters(text)
                entries = detected.chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
                tocAdapter.submit(entries)
                if (detected.truncated) warnChaptersTruncated()
                canvas.setDocument(text, 0)
            }
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onPause() {
        super.onPause()
        // 位置来源按当前模式取；「有没有值」交给 ProgressPolicy 判定。
        // 关键：不要再无条件 save —— 异步打开（PDF 扫描检测 / DOCX 转换）期间 mode 仍是 TXT
        // 而画布是空的，无条件写回会把上次真实进度清零（静默数据丢失）。
        val decision = ProgressPolicy.decide(
            ProgressPolicy.Input(
                mode = mode,
                now = System.currentTimeMillis(),
                canvasHasDocument = canvas.hasDocument,
                canvasOffset = canvas.currentOffset(),
                canvasChapterIndex = currentChapterIndex(),
                epubCfi = epubView?.currentCfi(),
                epubSpineIndex = epubView?.currentSpineIndex() ?: 0,
                pdfPage = pdfView?.currentPage ?: -1,
                pdfPageCount = pdfView?.pageCount ?: 0,
                docxHeading = lastDocxHeading
            )
        )
        when (decision) {
            is ProgressPolicy.Decision.Save -> {
                ProgressStore.save(this, bookId, decision.pos)
                ReadItLog.i("progress saved: mode=$mode ${decision.pos}")
            }
            is ProgressPolicy.Decision.Skip -> {
                // 文档尚未就绪，保留磁盘上的旧进度（否则会清零）
                ReadItLog.i("progress save skipped: mode=$mode（位置来源尚无效）")
            }
        }

        // F26：结算本次「可见时段」。只累加、不落盘 —— 落盘统一在 flushStats()，
        // 因为统计口径是「一次打开 = openCount +1」，中途写盘会把它记成多次打开。
        if (sessionStartedAt > 0L) {
            sessionMs += (Metrics.now() - sessionStartedAt).coerceAtLeast(0L)
            sessionStartedAt = 0L
        }
        // 返回键退出时 onPause 先到、且 isFinishing 已为 true；这里补一次，
        // 避免只依赖 onDestroy（进程被系统回收时 onDestroy 可能根本不执行）。
        if (isFinishing) flushStats()
    }

    @Override
    override fun onResume() {
        super.onResume()
        // F26：新的一段可见时段开始（首次进入也走这里，onCreate 后紧跟 onResume）
        if (!statsFlushed) sessionStartedAt = Metrics.now()
    }

    /**
     * 把本次打开的阅读统计落盘（F26）。
     *
     * 一个 Activity 实例只落一次，保证 openCount 与实际「打开」次数一致。
     * 过短会话（误触、看错书）由 [ReadingStats.apply] 自行丢弃，这里不重复判。
     */
    private fun flushStats() {
        if (statsFlushed) return
        statsFlushed = true
        if (bookId.isEmpty()) return
        // 收尾当前时段（onDestroy 路径下 onPause 可能已经收过）
        if (sessionStartedAt > 0L) {
            sessionMs += (Metrics.now() - sessionStartedAt).coerceAtLeast(0L)
            sessionStartedAt = 0L
        }
        val session = ReadingStats.Session(
            bookId = bookId,
            title = if (::bookFile.isInitialized) bookFile.nameWithoutExtension else bookId,
            durationMs = sessionMs,
            pageTurns = sessionTurns,
            charOffset = if (::canvas.isInitialized) canvas.currentOffset() else 0,
            totalPages = canvasTotalPages,
            endedAt = System.currentTimeMillis()
        )
        ReadingStatsStore.record(this, session)
    }

    override fun onDestroy() {
        // F26：先结算统计再拆视图 —— flushStats 需要读 canvas 的当前位置
        flushStats()
        super.onDestroy()
        io.shutdownNow()
        epubView?.let { ev ->
            runCatching { stage.removeView(ev) }
            runCatching { ev.destroy() }
        }
        epubView = null
        docxView?.let { dv ->
            runCatching { stage.removeView(dv) }
            runCatching { dv.stopLoading(); dv.destroy() }
        }
        docxView = null
        pdfView?.let { pv ->
            runCatching { stage.removeView(pv) }
            runCatching { pv.release() }
        }
        pdfView = null
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawers()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------ 工具

    private fun activeView(): View = when (mode) {
        ReadMode.EPUB_FULL -> epubView ?: canvas
        ReadMode.DOCX_HTML -> docxView ?: canvas
        ReadMode.PDF_RENDER -> pdfView ?: canvas
        else -> canvas
    }

    private fun fontPercent(): Int =
        (prefs.fontSizeSp / BASE_FONT_SP * 100).roundToInt().coerceIn(MIN_FONT_PERCENT, MAX_FONT_PERCENT)

    /**
     * 把 prefs 里的渲染偏好（可能是 AUTO）解析成实际生效的 [RenderMode]。
     *
     * AUTO 每次取用都重新判定，不落盘：换设备 / 换 ROM 后「是否疑似墨水屏」的结论会变，
     * 把旧结论写死进配置反而会锁死错误结果。判定逻辑与判据见 [ScreenProfile]。
     */
    private fun resolvedRenderMode(): RenderMode =
        ScreenProfile.resolveRenderMode(prefs.renderMode, ScreenProfileDetector.einkLikely(this))

    /**
     * 按当前档位构造 PDF 文本抽取器：LOW 档把 PdfBox 的解码缓冲落到 `cacheDir`
     * 下的 scratch 文件，避免 1GB 设备在大文档上 OOM（§5.1）。
     */
    private fun newPdfTextExtractor(): PdfTextExtractor {
        val strategy = Eal.strategy(this)
        return PdfTextExtractor(tempFileOnly = strategy.lowMemory, tempDir = cacheDir)
    }

    private fun currentChapterIndex(): Int {
        val off = canvas.currentOffset()
        return entries.indexOfLast { it.offset >= 0 && it.offset <= off }.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ F25 书签

    /**
     * 当前阅读位置，字段口径与 onPause 写进度时**完全一致**（CFI / 页码 / 字符偏移三选一）。
     *
     * 刻意与 [ProgressPolicy] 复用同一套字段含义：书签与进度指的是同一处位置。
     * 这里若另起一套坐标，「加书签时进度记第 1 页、书签记第 10 页」这类不一致
     * 会在真机上表现为「跳书签跳错页」，而且极难复现。
     */
    private fun currentPositionForBookmark(): ReadingPosition {
        val now = System.currentTimeMillis()
        return when (mode) {
            ReadMode.EPUB_FULL -> ReadingPosition(
                chapterIndex = epubView?.currentSpineIndex() ?: 0,
                updatedAt = now,
                cfi = epubView?.currentCfi()
            )
            ReadMode.PDF_RENDER -> {
                val page = pdfView?.currentPage ?: -1
                ReadingPosition(chapterIndex = page.coerceAtLeast(0), updatedAt = now, pageIndex = page)
            }
            ReadMode.DOCX_HTML -> ReadingPosition(chapterIndex = lastDocxHeading, updatedAt = now)
            else -> ReadingPosition(
                charOffset = if (::canvas.isInitialized) canvas.currentOffset() else 0,
                chapterIndex = currentChapterIndex(),
                updatedAt = now
            )
        }
    }

    /** 书签标签：位置（页码 / 节号 / 章号）+ 正文片段，让用户在列表里能认出是哪一段 */
    private fun bookmarkLabelFor(pos: ReadingPosition): String {
        val where = when {
            mode == ReadMode.PDF_RENDER && pos.pageIndex >= 0 -> {
                val total = pdfView?.pageCount ?: 0
                if (total > 0) getString(R.string.page_info, pos.pageIndex + 1, total) else ""
            }
            mode == ReadMode.EPUB_FULL -> getString(R.string.bookmark_epub_section, pos.chapterIndex + 1)
            mode == ReadMode.DOCX_HTML -> getString(R.string.bookmark_docx_heading, pos.chapterIndex + 1)
            else -> if (::canvas.isInitialized && canvas.hasDocument) {
                getString(R.string.page_info, canvas.currentPage() + 1, canvas.totalPages())
            } else ""
        }
        // 正文片段只有自绘画布拿得到；EPUB / PDF 的正文在 WebView / Pdfium 内部，不硬取
        val canvasMode = mode == ReadMode.TXT || mode == ReadMode.EPUB_TEXT ||
            mode == ReadMode.DOCX_TEXT || mode == ReadMode.PDF_TEXT
        val snippet = if (canvasMode && ::canvas.isInitialized) canvas.snippetAt(pos.charOffset) else ""
        return listOf(where, snippet).filter { it.isNotBlank() }.joinToString(" · ")
    }

    private fun showBookmarks() {
        val file = BookmarkStore.load(this, bookId)
        val list = Bookmarks.sorted(file)
        val currentKey = Bookmarks.keyOfPosition(currentPositionForBookmark())
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.bookmarks_title, list.size))

        if (list.isEmpty()) {
            builder.setMessage(R.string.bookmarks_empty)
        } else {
            val labels = list.mapIndexed { i, bm ->
                // 当前位置那条加个星标：不用点进去就知道「这一页已经有了」
                val text = bm.label.ifBlank { getString(R.string.bookmark_untitled, i + 1) }
                if (Bookmarks.keyOf(bm) == currentKey) "★ $text" else text
            }.toTypedArray()
            builder.setItems(labels) { _, which -> jumpToBookmark(list[which]) }
        }

        // 当前位置已有书签时主按钮变成「删书签」，比让用户先进列表再找少一步
        val already = Bookmarks.has(file, currentKey)
        builder.setPositiveButton(if (already) R.string.bookmark_remove_here else R.string.bookmark_add) { _, _ ->
            if (already) removeBookmarkAt(currentKey) else addBookmark()
        }
        builder.setNegativeButton(R.string.action_cancel, null)
        builder.show()
    }

    private fun addBookmark() {
        val pos = currentPositionForBookmark()
        val bm = Bookmark.fromPosition(pos, bookmarkLabelFor(pos), System.currentTimeMillis())
        val next = Bookmarks.add(BookmarkStore.load(this, bookId), bm)
        BookmarkStore.save(this, bookId, next)
        Toast.makeText(this, getString(R.string.bookmark_added), Toast.LENGTH_SHORT).show()
        ReadItLog.i("bookmark added: $bookId key=${Bookmarks.keyOf(bm)} label=${bm.label}")
    }

    private fun removeBookmarkAt(key: String) {
        val before = BookmarkStore.load(this, bookId)
        val after = Bookmarks.remove(before, key)
        if (after === before) return
        BookmarkStore.save(this, bookId, after)
        Toast.makeText(this, getString(R.string.bookmark_removed), Toast.LENGTH_SHORT).show()
    }

    /**
     * 跳到一条书签。
     *
     * 导航协议与 [jumpTo] 完全一致：`beginNav` 发起 → 有页面回调的路径
     * （自绘画布 / Pdfium）自动结算，WebView 两条路径就地结算。
     * 这样书签跳转同样进 §7 指标，也不会漏掉 F26 的翻页计数。
     */
    private fun jumpToBookmark(bm: Bookmark) {
        beginNav(tocJump = true)
        val cfi = bm.cfi
        val dispatched: Boolean = when (mode) {
            ReadMode.EPUB_FULL ->
                if (cfi != null && epubView != null) {
                    epubView?.displayCfi(cfi); true
                } else false
            ReadMode.PDF_RENDER ->
                if (bm.pageIndex >= 0 && pdfView != null) {
                    pdfView?.goToPage(bm.pageIndex); true
                } else false
            ReadMode.DOCX_HTML ->
                if (docxView != null) {
                    lastDocxHeading = bm.chapterIndex
                    docxView?.scrollToHeading(bm.chapterIndex); true
                } else false
            else ->
                if (::canvas.isInitialized && canvas.hasDocument) {
                    canvas.goToOffset(bm.charOffset); true
                } else false
        }
        if (!dispatched) {
            navStartedAt = 0L
            Toast.makeText(this, getString(R.string.bookmark_not_jumpable), Toast.LENGTH_SHORT).show()
            return
        }
        RefreshModeManager.requestFullRefresh(activeView())
        if (mode == ReadMode.EPUB_FULL || mode == ReadMode.DOCX_HTML) {
            logNav(navStartedAt)
            navStartedAt = 0L
        }
    }

    private fun jumpTo(entry: TocEntry) {
        // F10 / §7：目录跳转 <500ms。自绘画布与 Pdfium 会在页面回调时自动结算，
        // WebView 两条路径（EPUB_FULL / DOCX_HTML）没有这个回调，直接在此处收摊。
        beginNav(tocJump = true)
        val webLike = mode == ReadMode.EPUB_FULL || mode == ReadMode.DOCX_HTML
        // 参数有效 + 视图就绪才真发跳转；否则只弹 toast，那种情况不该记指标，
        // 不然 `tocJump` 里会混进一堆「0ms」的假样本。
        fun ok(valid: Boolean, act: () -> Unit): Boolean =
            if (valid) { act(); true } else { toastTocEmpty(); false }
        val dispatched: Boolean = when (mode) {
            ReadMode.EPUB_FULL ->
                // 有 href 就用 href（含 #fragment 精度），退回 spine 下标
                ok(entry.href != null || entry.spineIndex >= 0) {
                    val href = entry.href
                    if (!href.isNullOrBlank()) epubView?.displayHref(href) else epubView?.displaySpine(entry.spineIndex)
                }
            ReadMode.PDF_RENDER ->
                ok(entry.pageIndex >= 0 && pdfView != null) { pdfView?.goToPage(entry.pageIndex) }
            ReadMode.DOCX_HTML -> ok(entry.offset >= 0 && docxView != null) {
                lastDocxHeading = entry.offset
                docxView?.scrollToHeading(entry.offset)
            }
            else -> ok(entry.offset >= 0) { canvas.goToOffset(entry.offset) }
        }
        drawer.closeDrawers()
        RefreshModeManager.requestFullRefresh(activeView())
        // 自绘画布 / Pdfium 会在页面回调里结算；WebView 两条路径没有回调，就地收摊。
        if (webLike || !dispatched) {
            if (dispatched) logNav(navStartedAt)
            navStartedAt = 0L
        }
    }

    private fun toastTocEmpty() {
        Toast.makeText(this, getString(R.string.toc_empty), Toast.LENGTH_SHORT).show()
    }

    /**
     * DOCX 的打包字体 落地点：把 asset 拷到转换产物目录，返回 CSS 侧可用的相对 URL。
     *
     * `loadDataWithBaseURL("file://<baseDir>/", ...)` 之后，HTML 里一切相对 URL 都以
     * 该目录为基准——图片就是这么命中的，字体同理。系统族名没有打包文件，返回 null。
     * 拷贝是幂等的（已存在且非空就跳过），同一本书反复打开不会反复拷贝。
     */
    private fun docxFontFaceSrc(baseDir: File?): String? {
        val asset = Fonts.assetPath(fontId()) ?: return null
        if (baseDir == null) return null
        val dest = File(baseDir, asset)
        return try {
            if (!dest.exists() || dest.length() <= 0L) {
                dest.parentFile?.mkdirs()
                assets.open(asset).use { input ->
                    dest.outputStream().use { out -> input.copyTo(out) }
                }
                ReadItLog.i("docx font staged: $asset")
            }
            asset
        } catch (e: Exception) {
            ReadItLog.w("docx font stage failed: $asset ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 排版

    /** 当前生效字体 id（已按「字体文件还在不在」校正过） */
    private fun fontId(): String = UserFonts.currentId(this)

    /** 应用字体：TXT 换 Typeface，EPUB/DOCX 换 CSS font-family */
    private fun applyFontFamily(id: String) {
        prefs.fontFamily = id
        val tf = UserFonts.typefaceFor(this, id)
        canvas.typeface = tf
        // 字体有没有真的拿到，肉眼看不出来（缺衬线字体的设备上 serif 会静默回退成无衬线），
        // 只能靠日志定位：这里打实际解析结果，不是打用户选了什么。
        ReadItLog.i("font applied: id=$id typeface=${tf ?: "null(keep default)"}")
        val css = Fonts.cssFamily(id)
        epubView?.setFontFamily(css)
        docxView?.setFontFamily(css, docxFontFaceSrc(docxBaseDir))
        RefreshModeManager.requestFullRefresh(activeView())
    }

    /** 重建字体下拉：内置 4 款 + 字体目录里扫到的 .ttf/.otf */
    private fun refreshFontOptions() {
        // 展开面板时顺带校准编码入口的显隐：PDF / DOCX 的异步兜底可能在这之后才定 mode
        syncEncodingButton()
        val userFonts = UserFonts.scan(this)
        fontIds = Fonts.builtinIds() + userFonts.map { it.id }

        val labels = ArrayList<String>(fontIds.size)
        labels.addAll(resources.getStringArray(R.array.font_builtin_labels))
        userFonts.forEach { labels.add(getString(R.string.font_user_label, it.displayName())) }

        fontSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        fontSpinnerReady = false
        fontSpinner.setSelection(fontIds.indexOf(fontId()).coerceAtLeast(0))
        fontSpinner.post { fontSpinnerReady = true }
    }

    private fun bindTypographyPanel() {
        fontSpinner = findViewById(R.id.spFontFamily)
        refreshFontOptions()
        fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!fontSpinnerReady) return
                val picked = fontIds.getOrNull(position) ?: return
                if (picked != fontId()) applyFontFamily(picked)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val sbFont = findViewById<SeekBar>(R.id.sbFontSize)
        val sbLine = findViewById<SeekBar>(R.id.sbLineSpacing)
        val sbMargin = findViewById<SeekBar>(R.id.sbMargin)
        val tvFont = findViewById<TextView>(R.id.tvFontValue)
        val tvLine = findViewById<TextView>(R.id.tvLineValue)
        val tvMargin = findViewById<TextView>(R.id.tvMarginValue)

        // 600×800：10-20sp（规范 §5.4）。范围一律引用 Fonts 的常量，
        // 不在这里另写一份数字 —— 滑条范围与 ReadItPrefs 的 clamp 范围必须是同一个，
        // 否则会出现「拖不出来但备份能写进来」的越界值（见 Fonts.clampLineSpacing）。
        sbFont.max = Fonts.MAX_FONT_SP.toInt() - Fonts.MIN_FONT_SP.toInt()
        sbFont.progress = (prefs.fontSizeSp.toInt() - Fonts.MIN_FONT_SP.toInt())
            .coerceIn(0, sbFont.max)
        sbLine.max = LINE_SPACING_STEPS
        sbLine.progress = ((prefs.lineSpacing - Fonts.MIN_LINE_SPACING) * LINE_SPACING_STEPS).toInt()
            .coerceIn(0, LINE_SPACING_STEPS)
        sbMargin.max = Fonts.MAX_MARGIN_DP
        sbMargin.progress = prefs.marginDp.coerceIn(Fonts.MIN_MARGIN_DP, Fonts.MAX_MARGIN_DP)

        fun syncLabels() {
            tvFont.text = "${prefs.fontSizeSp.toInt()}sp"
            tvLine.text = String.format("%.1f", prefs.lineSpacing)
            tvMargin.text = "${prefs.marginDp}dp"
        }
        syncLabels()

        // 三个滑条共用同一套拖动期策略：拖动中总页数用估算（否则每一格都做一次全书精确分页，
        // 见 TxtCanvasView.beginInteractiveReflow），松手再补一次精确值。
        sbFont.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onStartTrackingTouch(seekBar: SeekBar) = canvas.beginInteractiveReflow()
            override fun onStopTrackingTouch(seekBar: SeekBar) = canvas.endInteractiveReflow()
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.fontSizeSp = (value + Fonts.MIN_FONT_SP.toInt()).toFloat()
                canvas.fontSizeSp = prefs.fontSizeSp
                if (mode == ReadMode.EPUB_FULL) epubView?.setFontPercent(fontPercent())
                if (mode == ReadMode.DOCX_HTML) docxView?.setFontPercent(fontPercent())
                syncLabels()
            }
        })
        sbLine.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onStartTrackingTouch(seekBar: SeekBar) = canvas.beginInteractiveReflow()
            override fun onStopTrackingTouch(seekBar: SeekBar) = canvas.endInteractiveReflow()
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.lineSpacing = Fonts.MIN_LINE_SPACING + value / LINE_SPACING_STEPS.toFloat()
                canvas.lineSpacing = prefs.lineSpacing
                syncLabels()
            }
        })
        sbMargin.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onStartTrackingTouch(seekBar: SeekBar) = canvas.beginInteractiveReflow()
            override fun onStopTrackingTouch(seekBar: SeekBar) = canvas.endInteractiveReflow()
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.marginDp = value
                canvas.marginDp = value
                syncLabels()
            }
        })

        // F27 反色：即时生效，不必退出阅读页再进
        val swInvert = findViewById<Switch>(R.id.swInvert)
        swInvert.isChecked = prefs.invertEnabled
        swInvert.setOnCheckedChangeListener { _, checked ->
            applyInversion(checked)
            toast(getString(if (checked) R.string.invert_on_toast else R.string.invert_off_toast))
        }
    }

    /**
     * 排版面板「适用性」同步（Civi2 真机反馈：PDF 下拖字号滑条毫无反应，像坏了）。
     *
     * PDF 原版渲染是**固定版式**，字号 / 行距 / 边距都不生效 → 三个滑条全部禁用；
     * EPUB_FULL / DOCX_HTML 支持字号（各自 WebView 字号 API），但行距 / 边距
     * 只参与 TxtCanvasView 自绘分页 → 这两个滑条仅在画布模式可用。
     * 值标签保留——显示的是用户当前偏好，换到支持的路径时照常生效。
     * 反色开关不受影响（F27 对四条渲染路径都有效）。
     *
     * 调用时机：面板每次展开（工具栏按钮与中屏菜单两处切换点）、
     * 以及 PDF 渲染回落到文本模式时（届时滑条要恢复可用）。
     */
    private fun syncTypographyPanelForMode() {
        // 字号：除 PDF 原版渲染（真·固定版式）外都支持——EPUB_FULL 走 themes.fontSize
        // + 规格化注入（见 reader.html applyFontSize），DOCX_HTML 走 WebView textZoom。
        findViewById<SeekBar>(R.id.sbFontSize).isEnabled = mode != ReadMode.PDF_RENDER
        // 行距 / 边距：只参与 TxtCanvasView 的自绘分页，两条 WebView 路径不适用。
        val canvasMode = mode == ReadMode.TXT || mode == ReadMode.EPUB_TEXT ||
            mode == ReadMode.DOCX_TEXT || mode == ReadMode.PDF_TEXT
        findViewById<SeekBar>(R.id.sbLineSpacing).isEnabled = canvasMode
        findViewById<SeekBar>(R.id.sbMargin).isEnabled = canvasMode
        findViewById<View>(R.id.tvTypographyHint).visibility =
            if (canvasMode) View.GONE else View.VISIBLE
    }

    /**
     * 应用反色（F27）。四条渲染路径各有各的载体，这里做统一入口：
     * 自绘画布改 Paint 颜色、Pdfium 挂颜色矩阵滤镜、两条 WebView 注入样式，
     * 都不需要重新分页或重新渲染当前页；最后补一次整屏刷新，
     * 否则墨水屏上会留下半屏旧色（残影）。
     *
     * **界面 chrome 也要一起反**（排版面板 / 工具栏 / 页码条 / 目录抽屉）——
     * 只反正文的话用户看到的是「黑底白字正文 + 一整条白底黑字面板」，
     * 正是本模式要避免的割裂。这些容器没有各自的反色实现，统一走
     * [ChromeInversion] 的硬件层滤镜（与 PDF 位图同一套 ColorMatrix）。
     */
    private fun applyInversion(enabled: Boolean, persist: Boolean = true) {
        if (persist) prefs.invertEnabled = enabled
        canvas.inverted = enabled
        pdfView?.inverted = enabled
        epubView?.setInverted(enabled)
        docxView?.setInverted(enabled)
        ChromeInversion.apply(chromeViews(), enabled)
        ReadItLog.i("invert applied: enabled=$enabled mode=$mode chrome=${chromeViews().size}")
        RefreshModeManager.requestFullRefresh(activeView())
    }

    /**
     * 需要参与反色的 chrome 容器。
     *
     * **不能**包含 `stage` 或根 `drawer`：它们是内容视图的父级，而内容视图
     * （自绘画布 / Pdfium / 两条 WebView）各自已实现反色，叠加会变成二次反色。
     */
    private fun chromeViews(): List<View> = listOfNotNull(
        panel,
        findViewById<View>(R.id.barButtons),
        pageInfo,
        tocList
    )

    private open class SimpleSeekBarListener : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {}
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }

    // ------------------------------------------------------------------ 输入

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 用屏幕坐标（rawX/rawY）与 stage 的屏幕矩形比对，避免坐标系（窗口/父容器）混淆。
                // 目录抽屉打开时它**覆盖在 stage 之上**，必须排除，否则抽屉里的条目点不动。
                syncStageRect()
                downInStage = InputMapper.shouldHandleGesture(
                    stageContainsTouch = stageRect.contains(ev.rawX.toInt(), ev.rawY.toInt()),
                    drawerOpen = drawer.isDrawerOpen(GravityCompat.START)
                )
                downX = ev.x
                downY = ev.y
                downStageX = ev.rawX - stageLoc[0]
                downStageY = ev.rawY - stageLoc[1]
                lightGestureDown()
            }
            MotionEvent.ACTION_MOVE -> {
                if (downInStage && lightGestureMove(ev.rawX - stageLoc[0], ev.rawY - stageLoc[1], ev.x, ev.y)) {
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                // 长按开关灯 / 竖划调亮度都算「已消费」，必须抢在翻页判定之前返回，
                // 否则长按抬起时会被当成一次点击 → 误翻到上一页。
                if (downInStage && lightGestureUp()) return true
                if (downInStage) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) < SWIPE_MIN && abs(dy) < SWIPE_MIN) {
                        // 点击：按 stage 内的相对 x 三分区（左=上一页 / 中=菜单 / 右=下一页）
                        syncStageRect()
                        handleAction(InputMapper.actionForTouch(ev.rawX - stageLoc[0], stage.width))
                        return true
                    } else if (abs(dx) > SWIPE_MIN && abs(dx) > abs(dy)) {
                        handleAction(if (dx < 0) InputMapper.Action.NEXT_PAGE else InputMapper.Action.PREV_PAGE)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                downInStage = false
                lightGestureCancel()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ 屏幕灯手势

    private fun lightGeometry() = LightGesture.Geometry(
        widthPx = stage.width,
        heightPx = stage.height,
        density = resources.displayMetrics.density
    )

    private fun lightOn(): Boolean = ScreenLight.isOn(this, prefs.lightOn)

    private fun lightGestureDown() {
        lightLongPressFired = false
        lightSwipeConsumed = false
        lightSwipeDirty = false
        lightSwipeBaseLevel = prefs.lightLevel
        lightSwipeSteps = 0
        if (!downInStage) return
        val g = lightGeometry()
        if (LightGesture.canToggleAt(g, downStageX, downStageY, ScreenLight.canToggle(this))) {
            cancelLightLongPress()
            val r = Runnable {
                lightLongPressFired = true
                toggleScreenLight()
            }
            lightLongPress = r
            lightHandler.postDelayed(r, LightGesture.LONG_PRESS_MS)
        }
    }

    /** @return true 表示这次移动被灯手势消费掉了（调用方应直接 return true） */
    private fun lightGestureMove(x: Float, y: Float, viewX: Float, viewY: Float): Boolean {
        if (LightGesture.movedBeyondSlop(downX, downY, viewX, viewY)) cancelLightLongPress()
        if (lightLongPressFired) return true
        val g = lightGeometry()
        val dx = x - downStageX
        val dy = y - downStageY
        if (abs(dy) <= LightGesture.SWIPE_MIN_PX || abs(dy) <= abs(dx)) return false
        if (!LightGesture.canBrightnessAt(g, downStageX, lightOn())) return false
        val steps = (-dy / g.stepPx).toInt()
        if (steps != lightSwipeSteps || !lightSwipeDirty) {
            lightSwipeSteps = steps
            lightSwipeDirty = true
            applyLightLevel(lightSwipeBaseLevel + steps * ScreenLight.LEVEL_STEP, announce = false)
        }
        lightSwipeConsumed = true
        return true
    }

    /** @return true 表示这次抬手属于灯手势，不应再走翻页判定 */
    private fun lightGestureUp(): Boolean {
        cancelLightLongPress()
        val consumed = lightLongPressFired || lightSwipeConsumed
        if (lightSwipeConsumed && lightSwipeDirty) {
            toast(getString(R.string.light_level_toast, prefs.lightLevel))
        }
        lightLongPressFired = false
        lightSwipeConsumed = false
        return consumed
    }

    private fun lightGestureCancel() {
        cancelLightLongPress()
        lightLongPressFired = false
        lightSwipeConsumed = false
    }

    private fun cancelLightLongPress() {
        lightLongPress?.let { lightHandler.removeCallbacks(it) }
        lightLongPress = null
    }

    private fun toggleScreenLight() {
        if (!ScreenLight.canToggle(this)) {
            toast(getString(R.string.light_toggle_unsupported))
            return
        }
        val on = !lightOn()
        prefs.lightOn = on
        ScreenLight.setOn(this, on)
        ScreenLight.applyToWindow(window, on, prefs.lightLevel)
        toast(getString(if (on) R.string.light_on_toast else R.string.light_off_toast))
    }

    private fun applyLightLevel(level: Int, announce: Boolean) {
        val v = LightGesture.clampLevel(level)
        prefs.lightLevel = v
        ScreenLight.setLevel(this, v)
        ScreenLight.applyToWindow(window, lightOn(), v)
        if (announce) toast(getString(R.string.light_level_toast, v))
    }

    /** 进入/回到前台时把持久化的灯状态作用到窗口 */
    private fun restoreLight() {
        ScreenLight.applyToWindow(window, lightOn(), prefs.lightLevel)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val userMap = InputMapper.LEARNABLE_KEYS.mapNotNull { k ->
            prefs.getKeyMap(k)?.let { k to InputMapper.Action.from(it) }
        }.toMap()
        val action = InputMapper.actionForKey(keyCode, userMap)
        return when (action) {
            null -> {
                Toast.makeText(this, getString(R.string.key_not_mapped), Toast.LENGTH_SHORT).show()
                false
            }
            InputMapper.Action.BACK -> {
                super.onKeyDown(keyCode, event)
            }
            else -> {
                handleAction(action)
                true
            }
        }
    }

    private fun handleAction(action: InputMapper.Action) {
        when (action) {
            InputMapper.Action.PREV_PAGE -> if (!prevPage()) {
                Toast.makeText(this, getString(R.string.at_beginning), Toast.LENGTH_SHORT).show()
            }
            InputMapper.Action.NEXT_PAGE -> if (!nextPage()) {
                Toast.makeText(this, getString(R.string.at_end), Toast.LENGTH_SHORT).show()
            }
            InputMapper.Action.MENU -> {
                panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                syncTypographyPanelForMode()
            }
            InputMapper.Action.BACK -> finish()
            InputMapper.Action.NONE -> Unit
        }
        RefreshModeManager.requestRefresh(activeView())
    }

    /**
     * 翻页（§7 TXT 翻页 <500ms / <300ms）。
     *
     * 自绘画布与 Pdfium 在页面回调里结算耗时；两条 WebView 路径没有页面回调，
     * 只能在这里就地收摊，否则 `navStartedAt` 会残留到下个动作，算出个假数字。
     */
    private fun nextPage(): Boolean = measureNav { nextPageInner() }

    private fun prevPage(): Boolean = measureNav { prevPageInner() }

    private fun measureNav(action: () -> Boolean): Boolean {
        beginNav()
        val moved = action()
        if (mode == ReadMode.EPUB_FULL || mode == ReadMode.DOCX_HTML) {
            Metrics.logPageTurn(bookId, mode.name, navStartedAt)
            navStartedAt = 0L
        }
        return moved
    }

    private fun nextPageInner(): Boolean = when (mode) {
        ReadMode.EPUB_FULL -> {
            epubView?.next(); true
        }
        ReadMode.PDF_RENDER -> pdfView?.next() ?: false
        // DOCX 是整篇滚动，翻页语义交给 WebView 自身滚动条；这里保守返回 true 不弹「已到末尾」
        ReadMode.DOCX_HTML -> {
            docxView?.let { v ->
                val range = v.contentHeight - v.height
                v.scrollTo(0, (v.scrollY + v.height * 0.9f).toInt().coerceAtMost(range.coerceAtLeast(0)))
            }
            true
        }
        else -> canvas.nextPage()
    }

    private fun prevPageInner(): Boolean = when (mode) {
        ReadMode.EPUB_FULL -> {
            epubView?.prev(); true
        }
        ReadMode.PDF_RENDER -> pdfView?.prev() ?: false
        ReadMode.DOCX_HTML -> {
            docxView?.let { v ->
                v.scrollTo(0, (v.scrollY - v.height * 0.9f).toInt().coerceAtLeast(0))
            }
            true
        }
        else -> canvas.prevPage()
    }

    // ------------------------------------------------------------------ 目录

    private class TocAdapter(private val onClick: (TocEntry) -> Unit) :
        RecyclerView.Adapter<TocAdapter.Holder>() {

        private val items = ArrayList<TocEntry>()

        fun submit(list: List<TocEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_chapter, parent, false),
                onClick
            )

        override fun onBindViewHolder(h: Holder, pos: Int) = h.bind(items[pos])
        override fun getItemCount(): Int = items.size

        class Holder(view: View, private val onClick: (TocEntry) -> Unit) :
            RecyclerView.ViewHolder(view) {
            private val tv = view.findViewById<TextView>(R.id.tvChapterTitle)
            fun bind(entry: TocEntry) {
                tv.text = entry.title
                val indent = (8 + entry.depth.coerceAtMost(4) * 12) * itemView.resources.displayMetrics.density
                tv.setPadding(indent.toInt(), tv.paddingTop, tv.paddingRight, tv.paddingBottom)
                itemView.setOnClickListener { onClick(entry) }
                itemView.contentDescription =
                    itemView.context.getString(R.string.a11y_chapter_item, entry.title)
            }
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        private const val SWIPE_MIN = 40f
        private const val BASE_FONT_SP = 14f
        private const val MIN_FONT_PERCENT = 70
        private const val MAX_FONT_PERCENT = 200

        /** 行距滑条格数：把 [Fonts.MIN_LINE_SPACING, Fonts.MAX_LINE_SPACING] 切成这么多档 */
        private const val LINE_SPACING_STEPS = 10

        /** 降级提示最多列几条，避免 600×800 上弹窗被撑爆 */
        private const val MAX_DEGRADE_LINES = 6

        /** 连续这么多页渲染超时就认为本机带不动 Pdfium 渲染档，整本退文本档（R11） */
        private const val PDF_TIMEOUT_STREAK_LIMIT = 3

        /** PDF 文本兜底的内存护栏（1GB 设备） */
        private const val MAX_PDF_TEXT_CHARS = 400_000
    }
}

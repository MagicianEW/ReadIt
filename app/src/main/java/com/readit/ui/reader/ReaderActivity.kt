package com.readit.ui.reader

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.readit.core.eal.Eal
import com.readit.core.eal.RefreshModeManager
import com.readit.core.input.InputMapper
import com.readit.core.text.ChapterDetector
import com.readit.core.text.EncodingDetector
import com.readit.core.util.ReadItLog
import com.readit.data.ProgressPolicy
import com.readit.data.ProgressStore
import com.readit.data.ReadMode
import com.readit.data.ReadingPosition
import com.readit.data.TocEntry
import com.readit.data.prefs.ReadItPrefs
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
    private lateinit var prefs: ReadItPrefs

    private val io = Executors.newSingleThreadExecutor()

    private var entries: List<TocEntry> = emptyList()
    private var bookId: String = ""
    private var mode = ReadMode.TXT
    private var downX = 0f
    private var downY = 0f

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

        // 目录宽度 90vw（600×800 兜底口径）
        val tocWidth = (resources.displayMetrics.widthPixels * 0.9f).toInt()
        tocList.layoutParams.width = tocWidth
        tocList.layoutManager = LinearLayoutManager(this)
        tocList.adapter = tocAdapter

        canvas.fontSizeSp = prefs.fontSizeSp
        canvas.lineSpacing = prefs.lineSpacing
        canvas.marginDp = prefs.marginDp
        canvas.onPageChanged = { index, total ->
            // TalkBack 读不到自绘正文（见 R18 结论），至少给出可定位的方位信息
            canvas.contentDescription = getString(R.string.a11y_canvas, index + 1, total)
            if (mode != ReadMode.EPUB_FULL && mode != ReadMode.PDF_RENDER && mode != ReadMode.DOCX_HTML) {
                pageInfo.text = getString(R.string.page_info, index + 1, total)
            }
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

        findViewById<Button>(R.id.btnToc).setOnClickListener {
            if (entries.isEmpty()) {
                Toast.makeText(this, getString(R.string.toc_empty), Toast.LENGTH_SHORT).show()
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }
        findViewById<Button>(R.id.btnTypography).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            RefreshModeManager.requestFullRefresh(activeView())
        }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, com.readit.ui.settings.SettingsActivity::class.java))
        }

        bindTypographyPanel()
    }

    // ------------------------------------------------------------------ 视图创建

    private fun ensureEpubView(): EpubWebView {
        epubView?.let { return it }
        val v = EpubWebView(this)
        stage.addView(v, fullStageParams())
        epubView = v
        return v
    }

    private fun ensureDocxView(): DocxWebView {
        docxView?.let { return it }
        val v = DocxWebView(this)
        stage.addView(v, fullStageParams())
        docxView = v
        return v
    }

    private fun ensurePdfView(): PdfRenderView {
        pdfView?.let { return it }
        val v = PdfRenderView(this)
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
        canvas.visibility = if (target === canvas) View.VISIBLE else View.GONE
        epubView?.visibility = if (target === epubView) View.VISIBLE else View.GONE
        docxView?.visibility = if (target === docxView) View.VISIBLE else View.GONE
        pdfView?.visibility = if (target === pdfView) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ TXT

    private fun openTxt(file: File, saved: ReadingPosition?) {
        mode = ReadMode.TXT
        showOnly(canvas)

        val text = loadText(file)
        val chapters = ChapterDetector.detect(text).takeIf { ChapterDetector.isReliable(it, text.length) }
            ?: emptyList()
        entries = chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
        tocAdapter.submit(entries)

        canvas.setDocument(text, saved?.charOffset ?: 0)
        ReadItLog.i("open txt: ${file.name} chars=${text.length} chapters=${entries.size}")
    }

    // ------------------------------------------------------------------ EPUB

    private fun openEpub(file: File, saved: ReadingPosition?) {
        val book = try {
            EpubParser.parse(file)
        } catch (e: Exception) {
            ReadItLog.e("epub parse failed", e)
            Toast.makeText(this, getString(R.string.epub_open_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (book.warnings.isNotEmpty()) ReadItLog.w("epub warnings: ${book.warnings}")

        if (book.encrypted) {
            Toast.makeText(this, getString(R.string.epub_drm), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val report = WebViewCapability.probe(this)
        val tier = Eal.strategy(this)
        ReadItLog.i("epub route: webview=${report.level} tier=$tier")

        if (report.supportsEpubJs) {
            openEpubFull(file, book, saved)
        } else {
            openEpubText(file, book, saved)
        }
    }

    private fun openEpubFull(file: File, book: EpubParser.Book, saved: ReadingPosition?) {
        mode = ReadMode.EPUB_FULL
        val ev = ensureEpubView()
        showOnly(ev)

        entries = book.toc.map { TocEntry(title = it.title, depth = it.depth, spineIndex = it.spineIndex) }
        tocAdapter.submit(entries)
        if (entries.isEmpty()) {
            Toast.makeText(this, getString(R.string.epub_no_toc), Toast.LENGTH_SHORT).show()
        }

        ev.callback = object : EpubWebView.Callback {
            override fun onRendered(href: String, elapsedMs: Long) {
                // 每次章节渲染都会回调，日志按「本次渲染耗时」表述（不是"首章"）
                ReadItLog.i("epub chapter rendered in ${elapsedMs}ms href=$href")
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
        Toast.makeText(this, getString(R.string.epub_opening), Toast.LENGTH_SHORT).show()
        ev.open(file, saved?.cfi, fontPercent())
    }

    private fun openEpubText(file: File, book: EpubParser.Book, saved: ReadingPosition?) {
        mode = ReadMode.EPUB_TEXT
        showOnly(canvas)

        val result = try {
            EpubTextExtractor().extract(file, book)
        } catch (e: Exception) {
            ReadItLog.e("epub text extract failed", e)
            Toast.makeText(this, getString(R.string.epub_open_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        entries = book.toc.mapIndexed { i, e ->
            TocEntry(
                title = e.title,
                depth = e.depth,
                offset = result.tocOffsets.getOrElse(i) { -1 },
                spineIndex = e.spineIndex
            )
        }.filter { it.offset >= 0 }
        tocAdapter.submit(entries)

        canvas.setDocument(result.text, saved?.charOffset ?: 0)
        Toast.makeText(this, getString(R.string.epub_degraded), Toast.LENGTH_LONG).show()
        ReadItLog.i("epub text path: chars=${result.text.length} toc=${entries.size}")
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
            val converted = DocxConverter.convert(applicationContext, file, docxMode)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (converted == null || converted.isEmpty) {
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

    private fun openDocxText(text: String, saved: ReadingPosition?) {
        mode = ReadMode.DOCX_TEXT
        showOnly(canvas)
        val chapters = ChapterDetector.detect(text).takeIf { ChapterDetector.isReliable(it, text.length) }
            ?: emptyList()
        entries = chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
        tocAdapter.submit(entries)
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
        val strategy = Eal.strategy(this)
        val pv = ensurePdfView()
        pv.cropEnabled = prefs.pdfCropEnabled
        pv.cachePages = strategy.pdfCachedPages
        pdfTimeoutStreak = 0
        showOnly(pv)
        mode = ReadMode.PDF_RENDER

        pv.onPageChanged = { page, total ->
            pageInfo.text = getString(R.string.page_info, page + 1, total)
        }
        pv.onPageRendered = { pdfTimeoutStreak = 0 }
        pv.onRenderTimeout = { page ->
            // R11：单页超时先提示、允许用户翻页绕开；连续超时说明这台机器带不动
            // Pdfium 渲染档（兜底档 SoC 上的畸形页/慢文档），整本退到文本档比一直白屏有用。
            ReadItLog.w("pdf render timeout: page=$page streak=${pdfTimeoutStreak + 1}")
            pdfTimeoutStreak++
            if (mode == ReadMode.PDF_RENDER && pdfTimeoutStreak >= PDF_TIMEOUT_STREAK_LIMIT) {
                fallbackToPdfText(file, "render timeout x$pdfTimeoutStreak")
            } else if (mode == ReadMode.PDF_RENDER) {
                Toast.makeText(
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
            val text = try {
                val ex = newPdfTextExtractor()
                try {
                    ex.open(file)
                    val sb = StringBuilder()
                    ex.forEachPage { _, pageText ->
                        if (sb.length < MAX_PDF_TEXT_CHARS) sb.append(pageText).append('\n')
                    }
                    sb.toString()
                } finally {
                    ex.close()
                }
            } catch (e: Throwable) {
                ReadItLog.e("pdf text fallback failed", e)
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (text.isNullOrBlank()) {
                    Toast.makeText(this, getString(R.string.pdf_no_text), Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                mode = ReadMode.PDF_TEXT
                showOnly(canvas)
                val chapters = ChapterDetector.detect(text)
                    .takeIf { ChapterDetector.isReliable(it, text.length) } ?: emptyList()
                entries = chapters.map { TocEntry(title = it.title, depth = 0, offset = it.startOffset) }
                tocAdapter.submit(entries)
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
    }

    override fun onDestroy() {
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
     * 按当前档位构造 PDF 文本抽取器：LOW 档把 PdfBox 的解码缓冲落到 `cacheDir`
     * 下的 scratch 文件，避免 1GB 设备在大文档上 OOM（§5.1）。
     */
    private fun newPdfTextExtractor(): PdfTextExtractor {
        val strategy = Eal.strategy(this)
        return PdfTextExtractor(tempFileOnly = strategy.lowMemory, tempDir = cacheDir)
    }

    private fun loadText(file: File): String {
        val charset = EncodingDetector.detect(file)
        return try {
            if (charset != null) file.readText(charset) else file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ReadItLog.e("read text failed", e)
            Toast.makeText(this, getString(R.string.read_failed), Toast.LENGTH_LONG).show()
            ""
        }
    }

    private fun currentChapterIndex(): Int {
        val off = canvas.currentOffset()
        return entries.indexOfLast { it.offset >= 0 && it.offset <= off }.coerceAtLeast(0)
    }

    private fun jumpTo(entry: TocEntry) {
        when (mode) {
            ReadMode.EPUB_FULL -> if (entry.spineIndex >= 0) {
                epubView?.displaySpine(entry.spineIndex)
            } else {
                Toast.makeText(this, getString(R.string.toc_empty), Toast.LENGTH_SHORT).show()
            }
            ReadMode.PDF_RENDER -> if (entry.pageIndex >= 0) {
                pdfView?.goToPage(entry.pageIndex)
            } else {
                Toast.makeText(this, getString(R.string.toc_empty), Toast.LENGTH_SHORT).show()
            }
            ReadMode.DOCX_HTML -> if (entry.offset >= 0) {
                lastDocxHeading = entry.offset
                docxView?.scrollToHeading(entry.offset)
            }
            else -> if (entry.offset >= 0) {
                canvas.goToOffset(entry.offset)
            }
        }
        drawer.closeDrawers()
        RefreshModeManager.requestFullRefresh(activeView())
    }

    // ------------------------------------------------------------------ 排版

    private fun bindTypographyPanel() {
        val sbFont = findViewById<SeekBar>(R.id.sbFontSize)
        val sbLine = findViewById<SeekBar>(R.id.sbLineSpacing)
        val sbMargin = findViewById<SeekBar>(R.id.sbMargin)
        val tvFont = findViewById<TextView>(R.id.tvFontValue)
        val tvLine = findViewById<TextView>(R.id.tvLineValue)
        val tvMargin = findViewById<TextView>(R.id.tvMarginValue)

        // 600×800：10-20sp（规范 §5.4）
        sbFont.max = 20 - MIN_FONT
        sbFont.progress = (prefs.fontSizeSp.toInt() - MIN_FONT).coerceIn(0, sbFont.max)
        sbLine.max = 10
        sbLine.progress = ((prefs.lineSpacing - 1.0f) * 10).toInt().coerceIn(0, 10)
        sbMargin.max = 24
        sbMargin.progress = prefs.marginDp.coerceIn(0, 24)

        fun syncLabels() {
            tvFont.text = "${prefs.fontSizeSp.toInt()}sp"
            tvLine.text = String.format("%.1f", prefs.lineSpacing)
            tvMargin.text = "${prefs.marginDp}dp"
        }
        syncLabels()

        sbFont.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.fontSizeSp = (value + MIN_FONT).toFloat()
                canvas.fontSizeSp = prefs.fontSizeSp
                if (mode == ReadMode.EPUB_FULL) epubView?.setFontPercent(fontPercent())
                syncLabels()
            }
        })
        sbLine.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.lineSpacing = 1.0f + value / 10f
                canvas.lineSpacing = prefs.lineSpacing
                syncLabels()
            }
        })
        sbMargin.setOnSeekBarChangeListener(object : SimpleSeekBarListener() {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                prefs.marginDp = value
                canvas.marginDp = value
                syncLabels()
            }
        })
    }

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
            }
            MotionEvent.ACTION_UP -> {
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
            MotionEvent.ACTION_CANCEL -> downInStage = false
        }
        return super.dispatchTouchEvent(ev)
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
            }
            InputMapper.Action.BACK -> finish()
            InputMapper.Action.NONE -> Unit
        }
        RefreshModeManager.requestRefresh(activeView())
    }

    private fun nextPage(): Boolean = when (mode) {
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

    private fun prevPage(): Boolean = when (mode) {
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
        private const val MIN_FONT = 10
        private const val SWIPE_MIN = 40f
        private const val BASE_FONT_SP = 14f
        private const val MIN_FONT_PERCENT = 70
        private const val MAX_FONT_PERCENT = 200

        /** 降级提示最多列几条，避免 600×800 上弹窗被撑爆 */
        private const val MAX_DEGRADE_LINES = 6

        /** 连续这么多页渲染超时就认为本机带不动 Pdfium 渲染档，整本退文本档（R11） */
        private const val PDF_TIMEOUT_STREAK_LIMIT = 3

        /** PDF 文本兜底的内存护栏（1GB 设备） */
        private const val MAX_PDF_TEXT_CHARS = 400_000
    }
}

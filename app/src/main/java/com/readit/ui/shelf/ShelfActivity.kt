package com.readit.ui.shelf

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.readit.core.util.ReadItLog
import com.readit.data.BookMetaScanner
import com.readit.data.BookMetaStore
import com.readit.data.ProgressStore
import com.readit.data.ShelfRow
import com.readit.data.buildBookMetaView
import com.readit.data.buildShelfRow
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.stats.ReadingStats
import com.readit.data.stats.ReadingStatsStore
import com.readit.data.storage.BookRename
import com.readit.data.storage.StorageManager
import com.readit.core.util.Metrics
import com.readit.eink.R
import com.readit.ui.onboarding.OnboardingActivity
import com.readit.ui.reader.ReaderActivity
import com.readit.ui.settings.SettingsActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 书架（P1）。600×800 兜底：按钮最小热区 48dp，列表项 48dp。
 */
class ShelfActivity : AppCompatActivity() {

    /** 后台元数据扫描串行执行器：避免反复进书架时叠加多个全库扫描线程 */
    private val metaScanExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "readit-metascan") }

    private lateinit var adapter: BookAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shelf)

        val list = findViewById<RecyclerView>(R.id.rvBooks)
        val empty = findViewById<TextView>(R.id.tvEmpty)
        adapter = BookAdapter(onClick = { file -> openBook(file) }, onLongClick = { file -> showBookMenu(file) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<Button>(R.id.btnImport).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnStats).setOnClickListener { showStats() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 首次启动引导（Phase 4）：把档位/刷新/按键/导入一次配好，
        // 免得用户对着空书架不知道从哪开始。完成或跳过都会写下 onboarding_done。
        if (!ReadItPrefs.get(this).onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        refresh(empty)

        // §7 启动时间 <4s：冷启动到书架首帧（列表数据已提交）
        Metrics.logBoot("shelfReady")
    }

    override fun onResume() {
        super.onResume()
        refresh(findViewById(R.id.tvEmpty))
    }

    private fun refresh(empty: TextView) {
        val books = StorageManager.listBooks(this)
        // 先只提交书名：列表要立刻出现，不能等 IO
        adapter.submitFiles(books)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvCount).text = getString(R.string.shelf_count, books.size)

        // 后台补副标题（进度 / 字数 / 章节）：读进度与元数据都是文件 IO，不能在主线程做
        metaScanExecutor.submit {
            publishRows(books)
            // 增量扫描：只对没算过 / 文件变了的书算元数据（全库扫描但非阻塞、可缓存）
            val updated = runCatching { BookMetaScanner.scanLibrary(this) }.getOrDefault(0)
            // 只有真的重算过才再刷一次副标题 —— 每次 resume 都读两遍整库元数据是白花的
            if (updated > 0) publishRows(books)
        }
    }

    /** 把书架行（含副标题）送到主线程刷新。row 的组装在后台完成。 */
    private fun publishRows(books: List<File>) {
        val rows = books.map {
            buildShelfRow(it, BookMetaStore.load(this, it.name), ProgressStore.load(this, it.name))
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            adapter.submitRows(rows)
        }
    }

    private fun openBook(file: File) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, file.absolutePath))
    }

    // ---------------------------------------------------------------- 书架管理：重命名 / 删除

    /** 长按一本书 → 选重命名 / 删除 / 属性。点按仍然是「打开」，不改动原有习惯。 */
    private fun showBookMenu(file: File) {
        val actions = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_delete),
            getString(R.string.action_properties)
        )
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> askRename(file)
                    1 -> confirmDelete(file)
                    2 -> showBookProperties(file)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 长按 → 属性：对话框显示 阅读进度 / 总体字数 / 章节数 / 加入书库时间。
     *
     * 元数据在后台线程算（PDF / DOCX / EPUB 解析可能慢），先填「计算中…」，
     * 算完回主线程填真实值。已算过且文件没变的本书走 [BookMetaScanner.ensure] 命中缓存，
     * 基本瞬间返回。
     */
    private fun showBookProperties(file: File) {
        val view = layoutInflater.inflate(R.layout.dialog_book_properties, null)
        val computing = getString(R.string.prop_computing)
        val vProgress = bindPropRow(view, R.id.row_progress, R.string.prop_progress, computing)
        val vChars = bindPropRow(view, R.id.row_chars, R.string.prop_chars, computing)
        val vChapters = bindPropRow(view, R.id.row_chapters, R.string.prop_chapters, computing)
        val vAdded = bindPropRow(view, R.id.row_added, R.string.prop_added, computing)

        AlertDialog.Builder(this)
            .setTitle(R.string.prop_title)
            .setView(view)
            .setPositiveButton(R.string.action_confirm, null)
            .show()

        metaScanExecutor.submit {
            val meta = runCatching { BookMetaScanner.ensure(this, file) }.getOrDefault(
                com.readit.data.BookMeta()
            )
            val pos = ProgressStore.load(this, file.name)
            val vm = buildBookMetaView(meta, pos)
            runOnUiThread {
                vProgress.text = vm.progressText
                vChars.text = vm.charCountText
                vChapters.text = vm.chapterText
                vAdded.text = vm.addedAtText
            }
        }
    }

    /** 给属性对话框的一行设好标签，初值填 [initial]，返回值 TextView 供后续更新 */
    private fun bindPropRow(container: View, includeId: Int, labelRes: Int, initial: String): TextView {
        val inc = container.findViewById<View>(includeId)
        inc.findViewById<TextView>(R.id.tvLabel).setText(labelRes)
        val value = inc.findViewById<TextView>(R.id.tvValue)
        value.text = initial
        return value
    }

    /**
     * 删除是**不可撤销**的（外部存储上的普通文件没有系统回收站），所以：
     * 二次确认写清后果，失败时明确报错并保留条目，绝不静默当成删掉了。
     */
    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, file.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val ok = StorageManager.deleteBook(this, file)
                val msg = if (ok) R.string.delete_ok else R.string.delete_failed
                Toast.makeText(this, getString(msg, file.name), Toast.LENGTH_SHORT).show()
                refresh(findViewById(R.id.tvEmpty))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun askRename(file: File) {
        val (base, ext) = BookRename.splitExt(file.name)
        val view = layoutInflater.inflate(R.layout.dialog_rename_book, null)
        val edit = view.findViewById<EditText>(R.id.etBookName)
        val extNote = view.findViewById<TextView>(R.id.tvExtNote)
        val error = view.findViewById<TextView>(R.id.tvNameError)

        edit.setText(base)
        edit.setSelection(base.length)
        if (ext.isEmpty()) {
            extNote.visibility = View.GONE
        } else {
            extNote.text = getString(R.string.rename_ext_note, ext)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(view)
            .setPositiveButton(R.string.action_confirm, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        // 校验不通过时对话框必须留着让用户接着改，所以不能用 setPositiveButton 的默认关闭行为，
        // 要等 show() 之后拿按钮自己接管点击。
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val res = BookRename.decide(file.name, edit.text.toString()) { name ->
                    File(file.parentFile, name).exists()
                }
                when (res.reason) {
                    BookRename.Reason.OK -> {
                        val dest = StorageManager.renameBook(this, file, res.newFileName!!)
                        if (dest == null) {
                            showNameError(error, getString(R.string.rename_failed))
                        } else {
                            Toast.makeText(this, getString(R.string.rename_ok, dest.name), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            refresh(findViewById(R.id.tvEmpty))
                        }
                    }
                    // 名字没变就当用户放弃，直接关掉，不报错
                    BookRename.Reason.UNCHANGED -> dialog.dismiss()
                    BookRename.Reason.EMPTY -> showNameError(error, getString(R.string.rename_err_empty))
                    BookRename.Reason.ILLEGAL_CHAR -> showNameError(error, getString(R.string.rename_err_illegal))
                    BookRename.Reason.TOO_LONG -> showNameError(error, getString(R.string.rename_err_long))
                    BookRename.Reason.EXISTS -> showNameError(error, getString(R.string.rename_err_exists))
                }
            }
        }
        dialog.show()
    }

    /** 就地显示校验错误（E-Ink 上不用 toast / error 弹窗，它们会一闪而过且盖住输入框） */
    private fun showNameError(tv: TextView, msg: String) {
        tv.text = msg
        tv.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- F26 阅读统计

    /**
     * 统计入口：全局累计 + 读得最多的书。
     *
     * E-Ink 上另开一个页面反而更重（新建 Activity + 刷新），一个对话框足够；
     * 数据全部来自 [ReadingStatsStore]，这里只做「纯函数分档 → 字符串资源」的映射。
     */
    private fun showStats() {
        val file = ReadingStatsStore.load(this)
        if (file.totalOpenCount == 0 && file.books.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.stats_title)
                .setMessage(R.string.stats_empty)
                .setPositiveButton(R.string.action_confirm, null)
                .show()
            return
        }

        val sb = StringBuilder()
        sb.append(
            getString(
                R.string.stats_total_fmt,
                durationText(file.totalMs),
                file.totalOpenCount,
                file.totalPageTurns
            )
        )
        val top = ReadingStats.topBooks(file, TOP_N)
        if (top.isNotEmpty()) {
            sb.append("\n\n").append(getString(R.string.stats_top_title))
            top.forEach { (id, st) ->
                // 书名优先用统计里记的 title（重命名会同步搬家），空则退回 bookId
                val name = st.title.ifBlank { id }
                sb.append("\n· ")
                    .append(getString(R.string.stats_book_fmt, name, durationText(st.totalMs), st.openCount))
            }
        }
        if (file.firstReadAt > 0L) {
            sb.append("\n\n").append(getString(R.string.stats_since_fmt, dateText(file.firstReadAt)))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.stats_title)
            .setMessage(sb.toString())
            .setPositiveButton(R.string.action_confirm, null)
            .setNeutralButton(R.string.stats_clear) { _, _ -> confirmClearStats() }
            .show()
    }

    private fun confirmClearStats() {
        AlertDialog.Builder(this)
            .setTitle(R.string.stats_clear)
            .setMessage(R.string.stats_clear_confirm)
            .setPositiveButton(R.string.stats_clear) { _, _ ->
                ReadingStatsStore.clear(this)
                Toast.makeText(this, getString(R.string.stats_cleared), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 时长文案：把纯函数分好的档映射到字符串资源（纯函数不碰资源，这一层才碰） */
    private fun durationText(ms: Long): String = when (val d = ReadingStats.duration(ms)) {
        is ReadingStats.Duration.HoursMinutes -> getString(R.string.stats_dur_hours, d.hours, d.minutes)
        is ReadingStats.Duration.MinutesSeconds -> getString(R.string.stats_dur_minutes, d.minutes, d.seconds)
        is ReadingStats.Duration.Seconds -> getString(R.string.stats_dur_seconds, d.seconds)
    }

    private fun dateText(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(at))

    private fun pickFile() {
        // API 19+ 均可用 SAF；返回值走 SAF URI
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/epub+zip", "application/pdf"))
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            ReadItLog.w("SAF not available: ${e.message}")
            Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri: Uri = data?.data ?: return
        val name = queryName(uri) ?: "book_${System.currentTimeMillis()}.txt"
        try {
            val tmp = File(StorageManager.tmpDir(this), "import_${System.currentTimeMillis()}.part")
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: throw java.io.IOException("cannot open uri")
            StorageManager.importAtomic(this, tmp, name, tmp.length())
            tmp.delete()
            Toast.makeText(this, getString(R.string.import_ok, name), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            ReadItLog.e("import failed", e)
            Toast.makeText(this, getString(R.string.import_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private class BookAdapter(
        private val onClick: (File) -> Unit,
        private val onLongClick: (File) -> Unit
    ) : RecyclerView.Adapter<BookAdapter.Holder>() {

        private val items = ArrayList<ShelfRow>()

        /** 先只出书名（列表秒开），副标题等后台算完再补 */
        fun submitFiles(list: List<File>) {
            items.clear()
            items.addAll(list.map { ShelfRow(it) })
            notifyDataSetChanged()
        }

        /** 带副标题的完整行 */
        fun submitRows(list: List<ShelfRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false),
                onClick,
                onLongClick
            )

        override fun onBindViewHolder(h: Holder, pos: Int) = h.bind(items[pos])
        override fun getItemCount(): Int = items.size

        class Holder(
            view: View,
            private val onClick: (File) -> Unit,
            private val onLongClick: (File) -> Unit
        ) : RecyclerView.ViewHolder(view) {
            private val tv = view.findViewById<TextView>(R.id.tvBookName)
            private val tvPercent = view.findViewById<TextView>(R.id.tvBookPercent)
            private val tvMeta = view.findViewById<TextView>(R.id.tvBookMeta)
            private val pb = view.findViewById<ProgressBar>(R.id.pbBookProgress)

            fun bind(row: ShelfRow) {
                val file = row.file
                tv.text = file.name

                // 进度百分比：算不出来就不显示这一格（不写「—」占位）
                val pct = row.percent
                if (pct == null) {
                    tvPercent.visibility = View.GONE
                } else {
                    tvPercent.visibility = View.VISIBLE
                    tvPercent.text = itemView.context.getString(R.string.shelf_meta_progress, pct)
                }

                // 副标题：字数 / 章节，没有就整行隐藏
                if (row.metaText.isEmpty()) {
                    tvMeta.visibility = View.GONE
                } else {
                    tvMeta.visibility = View.VISIBLE
                    tvMeta.text = row.metaText
                }

                // 已读条：无进度就不画（一条 0 宽的黑杠看着像脏点）
                if (pct == null) {
                    pb.visibility = View.GONE
                } else {
                    pb.visibility = View.VISIBLE
                    pb.progress = pct
                }

                itemView.setOnClickListener { onClick(file) }
                itemView.setOnLongClickListener {
                    onLongClick(file)
                    true
                }
                // TalkBack：只报文件名不够，要说明这一项是「可打开」的，
                // 以及长按还能改名 / 删除（否则这两个功能对读屏用户等于不存在）
                val spoken = if (pct == null) {
                    itemView.context.getString(R.string.a11y_open_book, file.name)
                } else {
                    itemView.context.getString(
                        R.string.a11y_open_book_progress,
                        file.name,
                        itemView.context.getString(R.string.shelf_meta_progress, pct)
                    )
                }
                itemView.contentDescription = spoken
            }
        }
    }

    companion object {
        private const val REQ_PICK = 1001

        /** 排行榜取前几本：600×800 上一屏放得下的条数 */
        private const val TOP_N = 5
    }
}

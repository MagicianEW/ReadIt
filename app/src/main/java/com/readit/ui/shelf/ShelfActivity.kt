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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.BookRename
import com.readit.data.storage.StorageManager
import com.readit.core.util.Metrics
import com.readit.eink.R
import com.readit.ui.onboarding.OnboardingActivity
import com.readit.ui.reader.ReaderActivity
import com.readit.ui.settings.SettingsActivity
import java.io.File

/**
 * 书架（P1）。600×800 兜底：按钮最小热区 48dp，列表项 48dp。
 */
class ShelfActivity : AppCompatActivity() {

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
        adapter.submit(books)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openBook(file: File) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, file.absolutePath))
    }

    // ---------------------------------------------------------------- 书架管理：重命名 / 删除

    /** 长按一本书 → 选重命名 / 删除。点按仍然是「打开」，不改动原有习惯。 */
    private fun showBookMenu(file: File) {
        val actions = arrayOf(getString(R.string.action_rename), getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> askRename(file)
                    1 -> confirmDelete(file)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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

        private val items = ArrayList<File>()

        fun submit(list: List<File>) {
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
            fun bind(file: File) {
                tv.text = file.name
                itemView.setOnClickListener { onClick(file) }
                itemView.setOnLongClickListener {
                    onLongClick(file)
                    true
                }
                // TalkBack：只报文件名不够，要说明这一项是「可打开」的，
                // 以及长按还能改名 / 删除（否则这两个功能对读屏用户等于不存在）
                itemView.contentDescription =
                    itemView.context.getString(R.string.a11y_open_book, file.name)
            }
        }
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}

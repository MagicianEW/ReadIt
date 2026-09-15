package com.readit.ui.shelf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.StorageManager
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
        adapter = BookAdapter { file -> openBook(file) }
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

    private class BookAdapter(private val onClick: (File) -> Unit) :
        RecyclerView.Adapter<BookAdapter.Holder>() {

        private val items = ArrayList<File>()

        fun submit(list: List<File>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false),
                onClick
            )

        override fun onBindViewHolder(h: Holder, pos: Int) = h.bind(items[pos])
        override fun getItemCount(): Int = items.size

        class Holder(view: View, private val onClick: (File) -> Unit) : RecyclerView.ViewHolder(view) {
            private val tv = view.findViewById<TextView>(R.id.tvBookName)
            fun bind(file: File) {
                tv.text = file.name
                itemView.setOnClickListener { onClick(file) }
                // TalkBack：只报文件名不够，要说明这一项是「可打开」的
                itemView.contentDescription =
                    itemView.context.getString(R.string.a11y_open_book, file.name)
            }
        }
    }

    companion object {
        private const val REQ_PICK = 1001
    }
}

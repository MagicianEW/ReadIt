package com.readit.ui.storage

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.readit.data.storage.BooksDirPolicy
import com.readit.eink.R
import java.io.File

/**
 * 书籍目录选择器：应用内文件夹浏览器（不依赖 SAF 树 URI，直接产出真实路径）。
 *
 * 交互：进入子目录 / 返回上级 / 新建文件夹 / 「选择此目录」。
 * 选择时走 [BooksDirPolicy] 校验（存在 / 是目录 / 可读 / 可写 / 为空），
 * 不通过则给出具体原因、不返回结果。
 *
 * 权限：API ≤ 29 申请 `WRITE_EXTERNAL_STORAGE`；API 30+ 引导去系统设置
 * 授予「所有文件访问」（[StoragePermission]）。
 */
class DirPickerActivity : AppCompatActivity() {

    private lateinit var tvPath: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnUp: Button
    private lateinit var adapter: ArrayAdapter<String>
    private val dirs = ArrayList<File>()

    private var current: File = File("/")
    private var loaded = false

    /** 当前已生效的目录路径：用于「非空」豁免（重选当前目录不算非空错误）。 */
    private var currentConfigured: String = ""

    private val requestPerm =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // READ 决定能否枚举子目录（缺它 listFiles() 返回 null，列表会静默变空），
            // 所以两个都要到手才算通过。
            if (grants.values.all { it }) loadDirs() else toast(getString(R.string.books_dir_need_perm))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dir_picker)
        title = getString(R.string.dir_picker_title)

        currentConfigured = intent.getStringExtra(EXTRA_CURRENT).orEmpty()
        tvPath = findViewById(R.id.tvDirPath)
        tvEmpty = findViewById(R.id.tvDirEmpty)
        btnUp = findViewById(R.id.btnDirUp)

        adapter = ArrayAdapter(this, R.layout.item_dir, R.id.tvDirName, ArrayList<String>())
        findViewById<ListView>(R.id.lvDirs).apply {
            adapter = this@DirPickerActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> if (pos in dirs.indices) enter(dirs[pos]) }
        }

        btnUp.setOnClickListener { current.parentFile?.let { enter(it) } }
        findViewById<Button>(R.id.btnDirNew).setOnClickListener { newFolder() }
        findViewById<Button>(R.id.btnDirSelect).setOnClickListener { confirmSelection() }

        current = initialDir()
        renderPath()
        ensureAccessThenLoad()
    }

    override fun onResume() {
        super.onResume()
        // 从「所有文件访问」设置页返回后可能已授权
        if (!loaded && StoragePermission.hasAccess(this)) loadDirs()
    }

    // ------------------------------------------------------------------ 权限

    private fun ensureAccessThenLoad() {
        if (StoragePermission.hasAccess(this)) {
            loadDirs()
            return
        }
        val runtime = StoragePermission.runtimePermissions()
        if (runtime.isNotEmpty()) {
            requestPerm.launch(runtime)
        } else {
            val intent = StoragePermission.allFilesAccessIntent(this)
            if (intent != null) {
                toast(getString(R.string.books_dir_need_all_files))
                runCatching { startActivity(intent) }
                    .onFailure { toast(getString(R.string.books_dir_need_perm)) }
            } else {
                toast(getString(R.string.books_dir_need_perm))
            }
        }
    }

    // ------------------------------------------------------------------ 浏览

    private fun initialDir(): File {
        val ext = Environment.getExternalStorageDirectory()
        return if (ext != null && ext.isDirectory) ext else filesDir
    }

    private fun enter(target: File) {
        current = target
        renderPath()
        loadDirs()
    }

    private fun loadDirs() {
        loaded = true
        dirs.clear()
        val children = current.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        if (children != null) dirs.addAll(children.sortedBy { it.name.lowercase() })
        adapter.clear()
        adapter.addAll(dirs.map { it.name })
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (dirs.isEmpty()) View.VISIBLE else View.GONE
        btnUp.isEnabled = current.parentFile != null
    }

    private fun renderPath() {
        tvPath.text = current.absolutePath
    }

    private fun newFolder() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(getString(R.string.dir_picker_new_default))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dir_picker_new_title)
            .setView(input)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/') || name.contains('\\')) {
                    toast(getString(R.string.dir_picker_create_failed))
                } else {
                    val f = File(current, name)
                    when {
                        f.isDirectory -> {
                            toast(getString(R.string.dir_picker_exists))
                            enter(f)
                        }
                        f.mkdirs() -> {
                            toast(getString(R.string.dir_picker_created, name))
                            enter(f)
                        }
                        else -> toast(getString(R.string.dir_picker_create_failed))
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ 校验并返回

    private fun confirmSelection() {
        val verdict = BooksDirPolicy.evaluate(
            BooksDirPolicy.probeOf(current),
            isCurrent = isCurrentDir(current)
        )
        if (verdict == BooksDirPolicy.Verdict.OK) {
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_DIR, current.absolutePath))
            finish()
        } else {
            toast(getString(verdictMessage(verdict)))
        }
    }

    private fun isCurrentDir(dir: File): Boolean {
        if (currentConfigured.isBlank()) return false
        val cfg = File(currentConfigured)
        return runCatching { cfg.canonicalPath == dir.canonicalPath }
            .getOrDefault(cfg.absolutePath == dir.absolutePath)
    }

    private fun verdictMessage(v: BooksDirPolicy.Verdict): Int = when (v) {
        BooksDirPolicy.Verdict.NOT_EXIST -> R.string.books_dir_not_exist
        BooksDirPolicy.Verdict.NOT_DIR -> R.string.books_dir_not_dir
        BooksDirPolicy.Verdict.NOT_READABLE -> R.string.books_dir_not_readable
        BooksDirPolicy.Verdict.NOT_WRITABLE -> R.string.books_dir_not_writable
        BooksDirPolicy.Verdict.NOT_EMPTY -> R.string.books_dir_not_empty
        BooksDirPolicy.Verdict.OK -> R.string.books_dir_ok
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_DIR = "extra_dir"

        /** 当前已生效目录（用于「非空」豁免） */
        const val EXTRA_CURRENT = "extra_current"

        /** 统一拉起入口。 */
        fun intent(context: Context, currentDir: String): Intent =
            Intent(context, DirPickerActivity::class.java).putExtra(EXTRA_CURRENT, currentDir)
    }
}

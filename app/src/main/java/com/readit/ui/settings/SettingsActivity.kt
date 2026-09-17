package com.readit.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.readit.core.eal.DeviceRepository
import com.readit.core.eal.Eal
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.core.eal.RefreshModeManager
import com.readit.core.input.InputMapper
import com.readit.core.util.ReadItLog
import com.readit.data.backup.ConfigBackup
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.StorageManager
import com.readit.eink.BuildConfig
import com.readit.eink.R
import com.readit.eink.ui.MainActivity
import com.readit.sync.SyncStateStore
import com.readit.sync.WebDavSync
import com.readit.sync.webdav.DavUrl
import com.readit.sync.webdav.WebDavClient
import com.readit.ui.diag.DeviceCollectActivity
import com.readit.ui.input.KeyLearnDialog
import com.readit.ui.onboarding.OnboardingActivity
import com.readit.ui.storage.DirPickerActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * 设置（P1 性能/输入；P3 增补 PDF 裁边、扫描版阈值、WebDAV 同步）。
 * 使用 PreferenceFragmentCompat，天生可滚动，满足 600×800 不溢出验收。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private val io = Executors.newSingleThreadExecutor()
        private val main = Handler(Looper.getMainLooper())
        private var syncing = false

        /** 设备配置导入（F18）：SAF 选 JSON → [DeviceRepository.importProfiles] */
        private val pickProfileJson =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importDeviceProfiles(uri)
            }

        /** 配置备份导出（Phase 4） */
        private val createConfigJson =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) exportConfig(uri)
            }

        /** 配置备份导入（Phase 4） */
        private val pickConfigJson =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importConfig(uri)
            }

        /** 书籍目录选择器（与首次引导共用） */
        private val pickBooksDir =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode != android.app.Activity.RESULT_OK) return@registerForActivityResult
                val path = result.data?.getStringExtra(DirPickerActivity.EXTRA_DIR)
                    ?: return@registerForActivityResult
                applyBooksDir(path)
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_readit, rootKey)
            val prefs = ReadItPrefs.get(requireContext())

            bindStorage(prefs)
            bindPerformance(prefs)
            bindReading(prefs)
            bindSync(prefs)
            bindBackup()
            bindInput()
            bindAbout()
        }

        // ------------------------------------------------------------ 存储

        private fun bindStorage(prefs: ReadItPrefs) {
            findPreference<Preference>("books_dir")?.let { p ->
                p.summary = booksDirLabel(prefs)
                p.setOnPreferenceClickListener {
                    pickBooksDir.launch(
                        DirPickerActivity.intent(
                            requireContext(),
                            StorageManager.booksDir(requireContext()).absolutePath
                        )
                    )
                    true
                }
            }
        }

        private fun booksDirLabel(prefs: ReadItPrefs): String {
            val dir = StorageManager.booksDir(requireContext())
            return if (prefs.booksDir.isBlank()) {
                getString(R.string.books_dir_current_default, dir.absolutePath)
            } else {
                getString(R.string.books_dir_current, dir.absolutePath)
            }
        }

        /** 应用新目录：迁移在 IO 线程，结果回主线程提示并刷新 summary。 */
        private fun applyBooksDir(path: String) {
            val ctx = context ?: return
            val appCtx = ctx.applicationContext
            io.execute {
                val msg: String = try {
                    val r = StorageManager.applyBooksDir(appCtx, File(path))
                    if (r.copied > 0 || r.skipped > 0 || r.failed > 0) {
                        appCtx.getString(R.string.books_dir_migrated, r.copied, r.skipped, r.failed)
                    } else {
                        appCtx.getString(R.string.books_dir_applied, path)
                    }
                } catch (e: Exception) {
                    ReadItLog.e("apply books dir failed", e)
                    appCtx.getString(R.string.books_dir_apply_failed, e.message ?: "")
                }
                main.post {
                    toast(msg)
                    findPreference<Preference>("books_dir")?.summary =
                        booksDirLabel(ReadItPrefs.get(appCtx))
                }
            }
        }

        // ------------------------------------------------------------ 性能与显示

        private fun bindPerformance(prefs: ReadItPrefs) {
            val tier = findPreference<ListPreference>("perf_tier")
            tier?.value = prefs.perfTier.key
            tier?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            tier?.setOnPreferenceChangeListener { _, newValue ->
                prefs.perfTier = PerfTier.from(newValue as? String)
                // 档位是 EAL 检测阶段的输入，而 Eal 进程内做了缓存 —— 不 reload 的话
                // 用户在设置页切了档位却要重启应用才生效（F20「六档组合正确切换」）。
                val r = Eal.reload(requireContext())
                toast(getString(R.string.pref_perf_tier_applied, r.socClass.key, r.ramClass.key))
                findPreference<Preference>("eal_summary")?.summary = Eal.describe()
                true
            }

            val refresh = findPreference<ListPreference>("refresh_mode")
            refresh?.value = prefs.refreshMode.key
            refresh?.summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            refresh?.setOnPreferenceChangeListener { _, newValue ->
                val mode = RefreshMode.from(newValue as? String)
                prefs.refreshMode = mode
                RefreshModeManager.apply(mode)
                true
            }

            findPreference<Preference>("device_profile_import")?.setOnPreferenceClickListener {
                pickProfileJson.launch(arrayOf("*/*"))
                true
            }
        }

        /**
         * 导入外部设备配置 JSON（F18「内置 + 手动导入」）。
         *
         * SAF 只给 URI，[DeviceRepository.importProfiles] 需要 File，所以先落一份临时文件；
         * 解析失败会抛 IOException，由这里统一转成用户可读的提示。
         */
        private fun importDeviceProfiles(uri: android.net.Uri) {
            val ctx = context ?: return
            val appCtx = ctx.applicationContext
            io.execute {
                var msg: String
                var ok = false
                try {
                    val tmp = File(appCtx.cacheDir, "device_profiles_import.json")
                    appCtx.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { out -> input.copyTo(out) }
                    } ?: throw java.io.IOException("cannot open uri")
                    val count = DeviceRepository.importProfiles(appCtx, tmp)
                    tmp.delete()
                    ok = true
                    msg = appCtx.getString(R.string.import_profiles_ok, count)
                } catch (e: Exception) {
                    ReadItLog.e("import device profiles failed", e)
                    msg = appCtx.getString(R.string.import_profiles_failed, e.message ?: "")
                }
                main.post {
                    // Eal.reload 内部会反射调用厂商 EPD SDK（RefreshModeManager.apply），
                    // 必须在主线程执行 —— 不能放在上面的工作线程里。
                    if (ok) Eal.reload(appCtx)
                    toast(msg)
                    findPreference<Preference>("eal_summary")?.summary = Eal.describe()
                }
            }
        }

        // ------------------------------------------------------------ 阅读与解析

        private fun bindReading(prefs: ReadItPrefs) {
            val crop = findPreference<SwitchPreferenceCompat>("pdf_crop")
            crop?.isChecked = prefs.pdfCropEnabled
            crop?.setOnPreferenceChangeListener { _, v ->
                prefs.pdfCropEnabled = v as? Boolean ?: true
                true
            }

            val sample = findPreference<EditTextPreference>("scan_sample_pages")
            sample?.text = prefs.scanSamplePages.toString()
            sample?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            sample?.setOnPreferenceChangeListener { _, v ->
                val n = (v as? String)?.trim()?.toIntOrNull()
                if (n == null || n !in 1..10) {
                    toast(getString(R.string.pref_scan_sample_pages_summary))
                    false
                } else {
                    prefs.scanSamplePages = n
                    true
                }
            }

            val chars = findPreference<EditTextPreference>("scan_min_chars")
            chars?.text = prefs.scanMinCharsPerPage.toString()
            chars?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            chars?.setOnPreferenceChangeListener { _, v ->
                val n = (v as? String)?.trim()?.toIntOrNull()
                if (n == null || n !in 0..2000) {
                    toast(getString(R.string.pref_scan_min_chars_summary))
                    false
                } else {
                    prefs.scanMinCharsPerPage = n
                    true
                }
            }
        }

        // ------------------------------------------------------------ WebDAV

        private fun bindSync(prefs: ReadItPrefs) {
            findPreference<EditTextPreference>("webdav_url")?.let {
                it.text = prefs.webDavUrl
                it.setOnPreferenceChangeListener { pref, v ->
                    val normalized = DavUrl.normalizeInput(v as? String ?: "")
                    prefs.webDavUrl = normalized
                    // 回填规范化结果（补全 scheme / 去尾斜杠），否则用户下次打开还看到原样输入。
                    // 返回 false 是刻意的：框架若返回 true 会把「原始值」再写一遍，覆盖掉回填。
                    (pref as? EditTextPreference)?.text = normalized
                    when {
                        normalized.isEmpty() -> Unit
                        DavUrl.isCleartext(normalized) ->
                            toast(getString(R.string.sync_cleartext_warning))
                        else -> Unit
                    }
                    false
                }
            }
            findPreference<EditTextPreference>("webdav_user")?.let {
                it.text = prefs.webDavUser
                it.setOnPreferenceChangeListener { _, v -> prefs.webDavUser = v as? String ?: ""; true }
            }
            findPreference<EditTextPreference>("webdav_password")?.let {
                it.text = prefs.webDavPassword
                it.setOnPreferenceChangeListener { _, v -> prefs.webDavPassword = v as? String ?: ""; true }
            }
            findPreference<EditTextPreference>("webdav_dir")?.let {
                it.text = prefs.webDavDir
                it.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                it.setOnPreferenceChangeListener { _, v ->
                    val s = (v as? String)?.trim().orEmpty()
                    prefs.webDavDir = s.ifBlank { ReadItPrefs.DEFAULT_WEBDAV_DIR }
                    true
                }
            }

            val syncNow = findPreference<Preference>("sync_now")
            syncNow?.setOnPreferenceClickListener {
                startSync(prefs, syncNow)
                true
            }

            findPreference<Preference>("sync_reset")?.setOnPreferenceClickListener {
                SyncStateStore.clear(requireContext())
                toast(getString(R.string.sync_reset_done))
                true
            }
        }

        /** 同步是阻塞 IO，放单线程池跑；结果回主线程弹 Toast + 写 summary */
        private fun startSync(prefs: ReadItPrefs, pref: Preference) {
            val ctx = context ?: return
            if (syncing) {
                toast(getString(R.string.sync_running))
                return
            }
            if (!prefs.webDavConfigured) {
                toast(getString(R.string.sync_not_configured))
                return
            }
            syncing = true
            pref.summary = getString(R.string.sync_running)
            val appCtx = ctx.applicationContext

            io.execute {
                val result: Pair<WebDavSync.SyncReport?, String> = try {
                    val client = WebDavClient(
                        prefs.webDavUrl,
                        prefs.webDavUser,
                        prefs.webDavPassword,
                        prefs.webDavDir
                    )
                    WebDavSync(client).sync(appCtx) to ""
                } catch (e: Exception) {
                    ReadItLog.e("sync failed", e)
                    null to (e.message ?: "")
                }
                main.post {
                    syncing = false
                    val report = result.first
                    pref.summary = if (report != null) {
                        getString(R.string.sync_done, report.summary())
                    } else {
                        getString(R.string.sync_failed, result.second)
                    }
                }
            }
        }

        // ------------------------------------------------------------ 备份与恢复

        private fun bindBackup() {
            findPreference<Preference>("backup_export")?.setOnPreferenceClickListener {
                createConfigJson.launch("readit_config.json")
                true
            }
            findPreference<Preference>("backup_import")?.setOnPreferenceClickListener {
                // 覆盖式恢复，先确认 —— 用户很可能刚在别的设备上调好了排版
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.backup_import_confirm_title)
                    .setMessage(R.string.backup_import_confirm_msg)
                    .setPositiveButton(R.string.pref_backup_import) { _, _ ->
                        pickConfigJson.launch(arrayOf("*/*"))
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
                true
            }
        }

        private fun exportConfig(uri: android.net.Uri) {
            val ctx = context ?: return
            try {
                val json = ConfigBackup.toJson(
                    ConfigBackup.snapshot(ReadItPrefs.get(ctx), BuildConfig.VERSION_NAME)
                )
                ConfigBackup.writeTo(ctx, uri, json)
                toast(getString(R.string.backup_export_ok))
            } catch (e: Exception) {
                ReadItLog.e("config export failed", e)
                toast(getString(R.string.backup_export_failed, e.message ?: ""))
            }
        }

        private fun importConfig(uri: android.net.Uri) {
            val ctx = context ?: return
            try {
                val snapshot = ConfigBackup.parse(ConfigBackup.readFrom(ctx, uri))
                val prefs = ReadItPrefs.get(ctx)
                val keys = ConfigBackup.restore(prefs, snapshot)
                // perfTier / refreshMode 都可能被改；EAL 缓存的检测结果必须重算。
                // 这里已经在主线程（SAF 回调），而 Eal.reload 内部会反射调厂商 EPD SDK。
                Eal.reload(ctx)
                RefreshModeManager.apply(prefs.refreshMode)
                syncControlsFromPrefs(prefs)
                findPreference<Preference>("eal_summary")?.summary = Eal.describe()
                toast(getString(R.string.backup_import_ok, keys))
            } catch (e: IllegalArgumentException) {
                ReadItLog.w("config import rejected: ${e.message}")
                toast(getString(R.string.backup_import_failed, e.message ?: ""))
            } catch (e: Exception) {
                ReadItLog.e("config import failed", e)
                toast(getString(R.string.backup_import_failed, e.message ?: ""))
            }
        }

        /** 导入后把控件显示值对齐到配置（`setValue` 不会触发 OnPreferenceChangeListener） */
        private fun syncControlsFromPrefs(prefs: ReadItPrefs) {
            findPreference<ListPreference>("perf_tier")?.value = prefs.perfTier.key
            findPreference<ListPreference>("refresh_mode")?.value = prefs.refreshMode.key
            findPreference<SwitchPreferenceCompat>("pdf_crop")?.isChecked = prefs.pdfCropEnabled
            findPreference<EditTextPreference>("scan_sample_pages")?.text =
                prefs.scanSamplePages.toString()
            findPreference<EditTextPreference>("scan_min_chars")?.text =
                prefs.scanMinCharsPerPage.toString()
            findPreference<EditTextPreference>("webdav_url")?.text = prefs.webDavUrl
            findPreference<EditTextPreference>("webdav_user")?.text = prefs.webDavUser
            findPreference<EditTextPreference>("webdav_dir")?.text = prefs.webDavDir
            findPreference<Preference>("books_dir")?.summary = booksDirLabel(prefs)
        }

        // ------------------------------------------------------------ 输入

        private fun bindInput() {
            findPreference<Preference>("key_learn")?.setOnPreferenceClickListener {
                KeyLearnDialog.show(requireActivity())
                true
            }

            findPreference<Preference>("key_reset")?.setOnPreferenceClickListener {
                ReadItPrefs.get(requireContext()).clearKeyMaps()
                true
            }

            findPreference<Preference>("device_info")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), MainActivity::class.java))
                true
            }

            findPreference<Preference>("device_report")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), DeviceCollectActivity::class.java))
                true
            }

            findPreference<Preference>("rerun_onboarding")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), OnboardingActivity::class.java))
                true
            }

            findPreference<Preference>("app_version")?.summary =
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

            findPreference<Preference>("eal_summary")?.summary =
                runCatching { Eal.describe() }.getOrDefault("")
        }

        // ------------------------------------------------------------ 关于

        private fun bindAbout() {
            findPreference<Preference>("about")?.setOnPreferenceClickListener {
                showAbout()
                true
            }
        }

        /**
         * 关于对话框：软件名称 / 版本 / 开发者 / 项目主页。
         *
         * -VersionName/Code 取自 [BuildConfig]，不从资源再抄一份 —— 否则每次发版都可能忘了同步文案。
         * - 仓库地址走 `ACTION_VIEW`，但**先 resolveActivity**：墨水屏设备不一定带浏览器，
         *   直接 startActivity 会抛 ActivityNotFoundException 把设置页带到隔壁进程之外。
         */
        private fun showAbout() {
            val repo = getString(R.string.about_repo_url)
            val body = getString(
                R.string.about_body,
                getString(R.string.app_name),
                getString(R.string.app_name_en),
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                getString(R.string.about_developer),
                repo
            )
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.about_title)
                .setMessage(body)
                .setPositiveButton(R.string.about_open_repo) { _, _ -> openRepo(repo) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun openRepo(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                startActivity(intent)
            } else {
                toast(getString(R.string.about_browser_missing))
            }
        }

        private fun toast(msg: String) {
            val ctx = context ?: return
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }

        override fun onDestroy() {
            io.shutdownNow()
            super.onDestroy()
        }
    }

    companion object {
        /** 供按键学习向导复用：写入一条映射 */
        fun persistKey(prefs: ReadItPrefs, keyCode: Int, actionKey: String) {
            prefs.putKeyMap(keyCode, actionKey)
        }

        fun actionLabelOf(key: String?): String = InputMapper.Action.from(key).name
    }
}

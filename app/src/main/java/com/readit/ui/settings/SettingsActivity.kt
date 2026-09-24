package com.readit.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.readit.core.display.RenderMode
import com.readit.core.display.ScreenProfileDetector
import com.readit.core.display.ScreenProfileResult
import com.readit.core.eal.DeviceRepository
import com.readit.core.light.ScreenLight
import com.readit.core.text.Fonts
import com.readit.core.text.UserFonts
import com.readit.core.eal.Eal
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.core.eal.RefreshModeManager
import com.readit.core.input.InputMapper
import com.readit.core.util.ReadItLog
import com.readit.data.BackupNaming
import com.readit.data.backup.ConfigBackup
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.StorageManager
import com.readit.eink.BuildConfig
import com.readit.eink.R
import com.readit.eink.ui.MainActivity
import com.readit.sync.SyncCapability
import com.readit.sync.SyncRunner
import com.readit.sync.SyncScheduler
import com.readit.sync.VersionedBackup
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

        /**
         * API 33+ 的前台服务通知需要 POST_NOTIFICATIONS 运行时授权。
         * 被拒也不影响同步执行（前台服务照跑），只是通知不可见，
         * 所以回调里不做任何提示，避免给用户「必须授权」的压力。
         */
        private val askNotifications =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
            bindDisplay(prefs)
            bindTypography(prefs)
            bindReading(prefs)
            bindLight(prefs)
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

        // ------------------------------------------------------------ 屏幕适配

        /**
         * 显示：渲染模式 + 屏幕诊断信息。
         *
         * 渲染模式小结特意显示成「自动（当前：锐利）」而不是只显示「自动」：
         * AUTO 的解析结果随设备变（疑似墨水屏 -> 锐利），只显示「自动」等于没告诉用户
         * 现在到底在用什么模式，排查「发虚」时会少一条关键线索。
         */
        private fun bindDisplay(prefs: ReadItPrefs) {
            val profile = ScreenProfileDetector.detect(requireContext(), prefs.renderMode)

            val render = findPreference<ListPreference>("render_mode")
            render?.value = prefs.renderMode.key
            render?.summary = renderModeLabel(prefs.renderMode, profile.renderMode)
            render?.setOnPreferenceChangeListener { _, newValue ->
                val mode = RenderMode.from(newValue as? String)
                prefs.renderMode = mode
                val p = ScreenProfileDetector.detect(requireContext(), mode)
                render.summary = renderModeLabel(mode, p.renderMode)
                true
            }

            findPreference<Preference>("screen_info")?.summary = screenInfoLabel(profile)
        }

        private fun renderModeLabel(preference: RenderMode, resolved: RenderMode): String =
            if (preference == RenderMode.AUTO) {
                getString(R.string.render_mode_auto_resolved, resolvedLabel(resolved))
            } else {
                resolvedLabel(resolved)
            }

        private fun resolvedLabel(mode: RenderMode): String = when (mode) {
            RenderMode.SHARP -> getString(R.string.render_mode_sharp)
            RenderMode.SMOOTH -> getString(R.string.render_mode_smooth)
            RenderMode.AUTO -> getString(R.string.render_mode_auto)
        }

        private fun screenInfoLabel(p: ScreenProfileResult): String {
            val m = p.metrics
            val sb = StringBuilder()
            sb.append("${m.panelWidthPx}×${m.panelHeightPx} · ${m.densityDpi}dpi · sw${m.swDp}dp")
            sb.append('\n').append(getString(R.string.screen_info_class, p.sizeClass.key))
            if (p.metrics.panelWidthPx != p.metrics.appWidthPx) {
                sb.append('\n')
                    .append(getString(R.string.screen_info_app_size, m.appWidthPx, m.appHeightPx))
            }
            if (p.densityRatio > 0f && kotlin.math.abs(p.densityRatio - 1f) > 0.05f) {
                // 只做展示：厂商把分档设得与物理 dpi 不同是常态（Civi2 就是 401.7/440），
                // 不代表画面被缩放 —— 别把它写成告警。
                sb.append('\n').append(
                    getString(R.string.screen_info_physical_dpi, "%.0f".format(p.physicalDpi))
                )
            }
            if (p.scaledBySystem) {
                sb.append('\n').append(
                    getString(R.string.screen_info_scaled, "%.2f".format(p.panelScale))
                )
            }
            return sb.toString()
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

        // ------------------------------------------------------------ 屏幕灯

        /**
         * 屏幕灯（开关 + 亮度等级）。
         *
         * 非墨水屏设备**只给亮度**：屏幕的亮灭归系统管，App 里塞一个「关灯」只会让人困惑
         * （而且窗口亮度 0 在普通屏上就是纯黑，看着像死机）。开关项直接 `isVisible = false`。
         */
        private fun bindLight(prefs: ReadItPrefs) {
            val canToggle = ScreenLight.canToggle(requireContext())
            val eink = ScreenLight.isEink(requireContext())

            findPreference<SwitchPreferenceCompat>("light_on")?.let { p ->
                p.isVisible = canToggle
                p.isChecked = prefs.lightOn
                p.setOnPreferenceChangeListener { _, v ->
                    prefs.lightOn = v as? Boolean ?: true
                    ScreenLight.setOn(requireContext(), prefs.lightOn)
                    ScreenLight.applyToWindow(activity?.window, prefs.lightOn, prefs.lightLevel)
                    true
                }
            }

            findPreference<SeekBarPreference>("light_level")?.let { p ->
                // max/min/step 走代码设置：androidx.preference 的 max 是 android:max，
                // 本文件根节点未声明 android 命名空间，写 XML 会 AAPT 报 attribute not found。
                p.max = ScreenLight.LEVEL_MAX
                p.min = ScreenLight.LEVEL_MIN
                p.seekBarIncrement = ScreenLight.LEVEL_STEP
                p.value = prefs.lightLevel
                p.summary = if (eink) {
                    getString(R.string.pref_light_level_summary)
                } else {
                    getString(R.string.light_brightness_only)
                }
                p.setOnPreferenceChangeListener { _, v ->
                    val level = (v as? Int) ?: prefs.lightLevel
                    prefs.lightLevel = level
                    ScreenLight.setLevel(requireContext(), level)
                    ScreenLight.applyToWindow(
                        activity?.window,
                        ScreenLight.isOn(requireContext(), prefs.lightOn),
                        level
                    )
                    true
                }
            }
        }

        // ------------------------------------------------------------ 字体与排版

        private fun bindTypography(prefs: ReadItPrefs) {
            // 字号：10–20sp。max/min/step 走代码设置（SeekBarPreference 的 max 是 android:max，
            // 本文件根节点未声明 android 命名空间，写 XML 会 AAPT 报错——同屏幕灯亮度那处）。
            findPreference<SeekBarPreference>("font_size")?.let { p ->
                p.min = Fonts.MIN_FONT_SP.toInt()
                p.max = Fonts.MAX_FONT_SP.toInt()
                p.seekBarIncrement = 1
                p.value = prefs.fontSizeSp.toInt()
                p.summary = getString(R.string.pref_font_size_summary, p.value)
                p.setOnPreferenceChangeListener { _, v ->
                    prefs.fontSizeSp = (v as? Int)?.toFloat() ?: prefs.fontSizeSp
                    p.summary = getString(R.string.pref_font_size_summary, prefs.fontSizeSp.toInt())
                    true
                }
            }

            val userFonts = UserFonts.scan(requireContext())
            val ids = Fonts.builtinIds() + userFonts.map { it.id }
            val labels = resources.getStringArray(R.array.font_builtin_labels).toMutableList()
            userFonts.forEach { labels.add(getString(R.string.font_user_label, it.displayName())) }

            // 列表项必须动态拼：用户字体随时可能增删，写死在 XML 里会与磁盘不一致
            findPreference<ListPreference>("font_family")?.let { p ->
                p.entries = labels.toTypedArray()
                p.entryValues = ids.toTypedArray()
                p.value = UserFonts.currentId(requireContext())
                p.setOnPreferenceChangeListener { _, v ->
                    prefs.fontFamily = Fonts.sanitize(v as? String, ids)
                    true
                }
            }

            // F27 反色：读写同一份 prefs，与阅读页「排版」面板里的开关是同一个值
            findPreference<SwitchPreferenceCompat>("invert")?.let { p ->
                p.isChecked = prefs.invertEnabled
                p.setOnPreferenceChangeListener { _, v ->
                    prefs.invertEnabled = v as? Boolean ?: false
                    true
                }
            }

            findPreference<Preference>("font_dir")?.let { p ->
                p.summary = getString(R.string.pref_font_dir_summary, UserFonts.dirLabel(requireContext()))
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
                    SyncScheduler.apply(requireContext())
                    false
                }
            }
            findPreference<EditTextPreference>("webdav_user")?.let {
                it.text = prefs.webDavUser
                it.setOnPreferenceChangeListener { _, v ->
                    prefs.webDavUser = v as? String ?: ""
                    SyncScheduler.apply(requireContext())
                    true
                }
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

            // ---------------------------------------------------- 自动同步（§2.3 A1）
            findPreference<SwitchPreferenceCompat>("sync_auto")?.let { p ->
                p.isChecked = prefs.syncAutoEnabled
                p.setOnPreferenceChangeListener { _, v ->
                    val on = v as? Boolean ?: false
                    prefs.syncAutoEnabled = on
                    SyncScheduler.apply(requireContext())
                    if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        askNotifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                    toast(getString(if (on) R.string.sync_auto_on else R.string.sync_auto_off))
                    true
                }
            }

            findPreference<Preference>("sync_strategy")?.let { p ->
                val api = Build.VERSION.SDK_INT
                p.summary = when (SyncCapability.modeOf(api)) {
                    SyncCapability.Mode.MANUAL_ONLY ->
                        getString(R.string.sync_strategy_manual, api)
                    SyncCapability.Mode.BACKGROUND ->
                        getString(R.string.sync_strategy_bg, api, SyncCapability.intervalHours(api))
                    SyncCapability.Mode.FOREGROUND ->
                        getString(R.string.sync_strategy_fg, api, SyncCapability.intervalHours(api))
                }
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
                val result: Pair<SyncRunner.Outcome?, String> = try {
                    val client = WebDavClient(
                        prefs.webDavUrl,
                        prefs.webDavUser,
                        prefs.webDavPassword,
                        prefs.webDavDir
                    )
                    SyncRunner.run(appCtx, client) to ""
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

            // F28：云端版本化备份
            val cloudCreate = findPreference<Preference>("backup_cloud_create")
            cloudCreate?.setOnPreferenceClickListener {
                val prefs = ReadItPrefs.get(requireContext())
                if (!prefs.webDavConfigured) {
                    toast(getString(R.string.backup_cloud_need_config))
                    return@setOnPreferenceClickListener true
                }
                cloudCreate.summary = getString(R.string.backup_cloud_running)
                val appCtx = requireContext().applicationContext
                io.execute {
                    val msg = try {
                        val name = VersionedBackup(webDavClient(prefs), BuildConfig.VERSION_NAME)
                            .backup(appCtx)
                        getString(R.string.backup_cloud_created, BackupNaming.display(name))
                    } catch (e: Exception) {
                        ReadItLog.e("cloud backup failed", e)
                        getString(R.string.backup_cloud_failed, e.message ?: "")
                    }
                    main.post {
                        cloudCreate.summary = getString(R.string.pref_backup_cloud_create_summary)
                        toast(msg)
                    }
                }
                true
            }

            val cloudRestore = findPreference<Preference>("backup_cloud_restore")
            cloudRestore?.setOnPreferenceClickListener {
                val prefs = ReadItPrefs.get(requireContext())
                if (!prefs.webDavConfigured) {
                    toast(getString(R.string.backup_cloud_need_config))
                    return@setOnPreferenceClickListener true
                }
                cloudRestore.summary = getString(R.string.backup_cloud_running)
                io.execute {
                    val entries = try {
                        VersionedBackup(webDavClient(prefs), BuildConfig.VERSION_NAME).list()
                    } catch (e: Exception) {
                        ReadItLog.e("cloud backup list failed", e)
                        main.post {
                            cloudRestore.summary = getString(R.string.pref_backup_cloud_restore_summary)
                            toast(getString(R.string.backup_cloud_failed, e.message ?: ""))
                        }
                        return@execute
                    }
                    main.post {
                        cloudRestore.summary = getString(R.string.pref_backup_cloud_restore_summary)
                        if (entries.isEmpty()) {
                            toast(getString(R.string.backup_cloud_empty))
                        } else {
                            askRestore(prefs, entries)
                        }
                    }
                }
                true
            }
        }

        private fun webDavClient(prefs: ReadItPrefs) = WebDavClient(
            prefs.webDavUrl,
            prefs.webDavUser,
            prefs.webDavPassword,
            prefs.webDavDir
        )

        /** 版本列表 → 二次确认 → 恢复。两个对话框都是必需的：恢复会覆盖本机数据 */
        private fun askRestore(prefs: ReadItPrefs, entries: List<VersionedBackup.Entry>) {
            val labels = entries.map { "${it.display}（${sizeText(it.size)}）" }.toTypedArray()
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.backup_cloud_pick)
                .setItems(labels) { _, which ->
                    val entry = entries[which]
                    AlertDialog.Builder(requireContext())
                        .setTitle(R.string.pref_backup_cloud_restore)
                        .setMessage(R.string.backup_cloud_restore_confirm)
                        .setPositiveButton(R.string.action_confirm) { _, _ -> doRestore(prefs, entry) }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        private fun doRestore(prefs: ReadItPrefs, entry: VersionedBackup.Entry) {
            val appCtx = requireContext().applicationContext
            io.execute {
                val msg = try {
                    val r = VersionedBackup(webDavClient(prefs), BuildConfig.VERSION_NAME).restore(appCtx, entry)
                    main.post {
                        // 与「导入配置」同一条纪律：档位可能变了，EAL 缓存必须重算，
                        // 设置页上依赖 prefs 的控件也要重新同步，否则界面显示的还是旧值。
                        Eal.reload(appCtx)
                        RefreshModeManager.apply(prefs.refreshMode)
                        syncControlsFromPrefs(prefs)
                        findPreference<Preference>("eal_summary")?.summary = Eal.describe()
                    }
                    getString(R.string.backup_cloud_restored, "${entry.display} · ${r.summary()}")
                } catch (e: Exception) {
                    ReadItLog.e("cloud restore failed", e)
                    getString(R.string.backup_cloud_failed, e.message ?: "")
                }
                main.post { toast(msg) }
            }
        }

        private fun sizeText(bytes: Long): String = when {
            bytes < 0 -> "?"
            bytes < 1024 -> "${bytes}B"
            else -> "${bytes / 1024}KB"
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
            findPreference<SwitchPreferenceCompat>("invert")?.isChecked = prefs.invertEnabled
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

package com.readit.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode

/**
 * 统一配置读写：SharedPreferences 名 = readit_prefs（规范 §0）。
 */
class ReadItPrefs(private val sp: SharedPreferences) {

    companion object {
        const val NAME = "readit_prefs"

        private const val K_PERF_TIER = "perf_tier"
        private const val K_REFRESH_MODE = "refresh_mode"
        private const val K_FONT_SIZE_SP = "font_size_sp"
        private const val K_LINE_SPACING = "line_spacing"
        private const val K_MARGIN_DP = "margin_dp"
        private const val K_KEYMAP_PREFIX = "keymap_"
        private const val K_PDF_CROP = "pdf_crop"
        private const val K_SCAN_SAMPLE_PAGES = "scan_sample_pages"
        private const val K_SCAN_MIN_CHARS = "scan_min_chars"
        private const val K_SCAN_MIN_IMAGE_RATIO = "scan_min_image_ratio"
        private const val K_FORCE_IMPORT_PREFIX = "force_import_"
        private const val K_WEBDAV_URL = "webdav_url"
        private const val K_WEBDAV_USER = "webdav_user"
        private const val K_WEBDAV_PASSWORD = "webdav_password"
        private const val K_WEBDAV_DIR = "webdav_dir"
        private const val K_ONBOARDING_DONE = "onboarding_done"
        private const val K_BOOKS_DIR = "books_dir"

        @Volatile
        private var instance: ReadItPrefs? = null

        fun get(context: Context): ReadItPrefs {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val created = ReadItPrefs(
                    context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
                )
                instance = created
                return created
            }
        }

        const val DEFAULT_FONT_SIZE_SP = 14f
        const val DEFAULT_LINE_SPACING = 1.3f
        const val DEFAULT_MARGIN_DP = 12
        const val DEFAULT_WEBDAV_DIR = "/ReadIt"
    }

    var perfTier: PerfTier
        get() = PerfTier.from(sp.getString(K_PERF_TIER, PerfTier.AUTO.key))
        set(v) = sp.edit().putString(K_PERF_TIER, v.key).apply()

    var refreshMode: RefreshMode
        get() = RefreshMode.from(sp.getString(K_REFRESH_MODE, RefreshMode.AUTO.key))
        set(v) = sp.edit().putString(K_REFRESH_MODE, v.key).apply()

    var fontSizeSp: Float
        get() = sp.getFloat(K_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP)
        set(v) = sp.edit().putFloat(K_FONT_SIZE_SP, v).apply()

    var lineSpacing: Float
        get() = sp.getFloat(K_LINE_SPACING, DEFAULT_LINE_SPACING)
        set(v) = sp.edit().putFloat(K_LINE_SPACING, v).apply()

    var marginDp: Int
        get() = sp.getInt(K_MARGIN_DP, DEFAULT_MARGIN_DP)
        set(v) = sp.edit().putInt(K_MARGIN_DP, v).apply()

    /** PDF 裁边（F07）：仅渲染档可用，默认开启 */
    var pdfCropEnabled: Boolean
        get() = sp.getBoolean(K_PDF_CROP, true)
        set(v) = sp.edit().putBoolean(K_PDF_CROP, v).apply()

    // ---------------------------------------------------------------- 扫描版检测（F08）

    /**
     * 采样页数。
     *
     * 注意存储形态：设置页用 EditTextPreference，落盘为 **String**；
     * 早期代码用 putInt。这里两种都认，避免换实现后旧值读不出来。
     */
    var scanSamplePages: Int
        get() = intPref(K_SCAN_SAMPLE_PAGES, 3)
        set(v) = putIntAsString(K_SCAN_SAMPLE_PAGES, v)

    /** 单页文本层字符数阈值，低于此值视为无文本层 */
    var scanMinCharsPerPage: Int
        get() = intPref(K_SCAN_MIN_CHARS, 100)
        set(v) = putIntAsString(K_SCAN_MIN_CHARS, v)

    /** 整页大图的采样页占比阈值 */
    var scanMinImagePageRatio: Float
        get() = sp.getFloat(K_SCAN_MIN_IMAGE_RATIO, 0.5f)
        set(v) = sp.edit().putFloat(K_SCAN_MIN_IMAGE_RATIO, v).apply()

    fun scanThresholds(): com.readit.pdf.ScanThresholds = com.readit.pdf.ScanThresholds(
        samplePages = scanSamplePages,
        minCharsPerPage = scanMinCharsPerPage,
        minImagePageRatio = scanMinImagePageRatio
    ).sanitized()

    private fun intPref(key: String, def: Int): Int {
        val asString = try {
            sp.getString(key, null)
        } catch (e: ClassCastException) {
            null
        }
        asString?.trim()?.toIntOrNull()?.let { return it }
        return try {
            sp.getInt(key, def)
        } catch (e: ClassCastException) {
            def
        }
    }

    private fun putIntAsString(key: String, v: Int) =
        sp.edit().putString(key, v.toString()).apply()

    /** 用户对某本书选择「强制导入」后，后续打开不再拦截 */
    fun markForceImport(bookId: String) =
        sp.edit().putBoolean(K_FORCE_IMPORT_PREFIX + bookId, true).apply()

    fun isForceImport(bookId: String): Boolean =
        sp.getBoolean(K_FORCE_IMPORT_PREFIX + bookId, false)

    // ---------------------------------------------------------------- WebDAV（F13）

    var webDavUrl: String
        get() = sp.getString(K_WEBDAV_URL, "").orEmpty()
        set(v) = sp.edit().putString(K_WEBDAV_URL, v.trim()).apply()

    var webDavUser: String
        get() = sp.getString(K_WEBDAV_USER, "").orEmpty()
        set(v) = sp.edit().putString(K_WEBDAV_USER, v.trim()).apply()

    /**
     * 注意：明文存储。本应用面向侧载的封闭设备，未引入 Keystore 加密；
     * 若后续需要多用户/共享设备，应改为 EncryptedSharedPreferences。
     */
    var webDavPassword: String
        get() = sp.getString(K_WEBDAV_PASSWORD, "").orEmpty()
        set(v) = sp.edit().putString(K_WEBDAV_PASSWORD, v).apply()

    var webDavDir: String
        get() = sp.getString(K_WEBDAV_DIR, DEFAULT_WEBDAV_DIR).orEmpty()
        set(v) = sp.edit().putString(K_WEBDAV_DIR, v.trim()).apply()

    /** 三项必填齐全才允许发起同步 */
    val webDavConfigured: Boolean
        get() = webDavUrl.isNotBlank() && webDavUser.isNotBlank()

    /** 按键学习向导产出的映射：KeyCode -> action */
    fun putKeyMap(keyCode: Int, action: String) =
        sp.edit().putString(K_KEYMAP_PREFIX + keyCode, action).apply()

    fun getKeyMap(keyCode: Int): String? = sp.getString(K_KEYMAP_PREFIX + keyCode, null)

    /** 全部按键映射（keyCode -> action）。供配置备份导出。 */
    fun allKeyMaps(): Map<Int, String> {
        val out = LinkedHashMap<Int, String>()
        for ((k, v) in sp.all) {
            if (!k.startsWith(K_KEYMAP_PREFIX)) continue
            val code = k.removePrefix(K_KEYMAP_PREFIX).toIntOrNull() ?: continue
            (v as? String)?.let { out[code] = it }
        }
        return out
    }

    fun clearKeyMaps() {
        val e = sp.edit()
        sp.all.keys.filter { it.startsWith(K_KEYMAP_PREFIX) }.forEach { e.remove(it) }
        e.apply()
    }

    // ---------------------------------------------------------------- 首次引导（Phase 4）

    /** 首次启动引导是否已完成；跳过也算完成（用户明确表过态） */
    var onboardingDone: Boolean
        get() = sp.getBoolean(K_ONBOARDING_DONE, false)
        set(v) = sp.edit().putBoolean(K_ONBOARDING_DONE, v).apply()

    // ---------------------------------------------------------------- 书籍目录

    /**
     * 书籍保存目录（绝对路径）。**空 = 使用应用内部默认目录**（`filesDir/books`）。
     *
     * 用真实路径而非 SAF 树 URI：阅读器四条读取链（TXT/EPUB/DOCX/PDF）全走
     * `java.io.File` 绝对路径，改造成 `DocumentFile` 代价过大；故采用
     * 「真实路径 + 分级存储权限」（API30+ 需用户授予「所有文件访问」）。
     */
    var booksDir: String
        get() = sp.getString(K_BOOKS_DIR, "").orEmpty()
        set(v) = sp.edit().putString(K_BOOKS_DIR, v.trim()).apply()
}

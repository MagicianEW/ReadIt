package com.readit.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.readit.core.display.RenderMode
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.data.PageTurnMode

/**
 * 统一配置读写：SharedPreferences 名 = readit_prefs（规范 §0）。
 */
class ReadItPrefs(private val sp: SharedPreferences) {

    companion object {
        const val NAME = "readit_prefs"

        private const val K_PERF_TIER = "perf_tier"
        private const val K_REFRESH_MODE = "refresh_mode"
        /** 文字渲染模式。**注意与 [K_REFRESH_MODE]（E-Ink 刷新模式）是两个不同的东西** */
        private const val K_RENDER_MODE = "render_mode"
        private const val K_PAGE_TURN_MODE = "page_turn_mode"
        private const val K_FONT_SIZE_SP = "font_size_sp"
        private const val K_FONT_FAMILY = "font_family"
        private const val K_LINE_SPACING = "line_spacing"
        private const val K_MARGIN_DP = "margin_dp"
        private const val K_KEYMAP_PREFIX = "keymap_"
        private const val K_PDF_CROP = "pdf_crop"
        private const val K_SCAN_SAMPLE_PAGES = "scan_sample_pages"
        private const val K_SCAN_MIN_CHARS = "scan_min_chars"
        private const val K_SCAN_MIN_IMAGE_RATIO = "scan_min_image_ratio"
        private const val K_FORCE_IMPORT_PREFIX = "force_import_"
        private const val K_CHARSET_PREFIX = "charset_"
        private const val K_WEBDAV_URL = "webdav_url"
        private const val K_WEBDAV_USER = "webdav_user"
        private const val K_WEBDAV_PASSWORD = "webdav_password"
        private const val K_WEBDAV_DIR = "webdav_dir"
        private const val K_ONBOARDING_DONE = "onboarding_done"
        private const val K_BOOKS_DIR = "books_dir"
        private const val K_LIGHT_ON = "light_on"
        private const val K_LIGHT_LEVEL = "light_level"
        private const val K_SYNC_AUTO = "sync_auto"
        private const val K_INVERT = "invert"

        /**
         * 「用户显式设置过」的独立标记键（设备属性的三件套：档位 / 刷新 / 渲染）。
         *
         * 为什么不直接用 `sp.contains(值键)` 当判据：配置恢复自身也会写值键，
         * 一旦拿 `contains` 当「显式设置过」，**恢复一次就把守卫永久烧掉**——
         * 此后从任何设备导入档位/刷新/渲染全被拒，正是 DeviceScopedConfig
         * 文档开头要防的「慢机档位盖到快机上」事故，只是推迟到第二次。
         * 值键与「用户设置过」标记必须是两件事，故另立键。
         */
        private const val K_USER_SET_PERF_TIER = "user_set_perf_tier"
        private const val K_USER_SET_REFRESH_MODE = "user_set_refresh_mode"
        private const val K_USER_SET_RENDER_MODE = "user_set_render_mode"

        /** 屏幕灯默认开着；默认亮度 50% */
        const val DEFAULT_LIGHT_ON = true
        const val DEFAULT_LIGHT_LEVEL = 50

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
        const val DEFAULT_FONT_FAMILY = com.readit.core.text.Fonts.ID_DEFAULT
        const val DEFAULT_LINE_SPACING = 1.3f
        const val DEFAULT_MARGIN_DP = 12
        const val DEFAULT_WEBDAV_DIR = "/ReadIt"

        /** 自动同步默认开启；配合「仅非计费网络」约束，默认行为是低打扰的 */
        const val DEFAULT_SYNC_AUTO = true
    }

    var perfTier: PerfTier
        get() = PerfTier.from(sp.getString(K_PERF_TIER, PerfTier.AUTO.key))
        set(v) {
            sp.edit().putString(K_PERF_TIER, v.key).putBoolean(K_USER_SET_PERF_TIER, true).apply()
        }

    /**
     * 用户是否**显式设置过**性能档位。
     *
     * 判据是独立的 [K_USER_SET_PERF_TIER] 标记，不是值键是否存在：
     * 主动选「自动」也算一次显式决策，不该被配置恢复当成「没设过」而顶掉；
     * 而配置恢复自己写入的值**不算**显式决策（走 [setPerfTierFromRestore]）。
     * 供 [com.readit.data.backup.DeviceScopedConfig] 在恢复配置时决定是否保留本机值。
     */
    val hasExplicitPerfTier: Boolean get() = sp.getBoolean(K_USER_SET_PERF_TIER, false)

    /** 配置恢复专用：写值但**不**标记为「用户显式设置」，避免把设备属性守卫一次烧掉 */
    fun setPerfTierFromRestore(v: PerfTier) {
        sp.edit().putString(K_PERF_TIER, v.key).apply()
    }

    var refreshMode: RefreshMode
        get() = RefreshMode.from(sp.getString(K_REFRESH_MODE, RefreshMode.AUTO.key))
        set(v) {
            sp.edit().putString(K_REFRESH_MODE, v.key).putBoolean(K_USER_SET_REFRESH_MODE, true).apply()
        }

    /** 同 [hasExplicitPerfTier]，作用于刷新模式 */
    val hasExplicitRefreshMode: Boolean get() = sp.getBoolean(K_USER_SET_REFRESH_MODE, false)

    /** 配置恢复专用：不标记显式设置（见 [setPerfTierFromRestore]） */
    fun setRefreshModeFromRestore(v: RefreshMode) {
        sp.edit().putString(K_REFRESH_MODE, v.key).apply()
    }

    /**
     * 文字渲染模式（[RenderMode]）：平滑（抗锯齿）/ 锐利（点对点）。
     *
     * 默认 [RenderMode.AUTO] —— 每次取用时按「是否疑似墨水屏」解析（见 [ScreenProfile]），
     * **不落盘具体值**：换设备、换 ROM 后结论可能变，落盘会锁死旧结论。
     */
    var renderMode: RenderMode
        get() = RenderMode.from(sp.getString(K_RENDER_MODE, RenderMode.AUTO.key))
        set(v) {
            sp.edit().putString(K_RENDER_MODE, v.key).putBoolean(K_USER_SET_RENDER_MODE, true).apply()
        }

    /**
     * 同 [hasExplicitPerfTier]，作用于渲染模式。
     *
     * 渲染模式也是**设备属性**（墨水屏该锐利、LCD 该平滑），
     * 所以跨设备恢复配置时同样只在「本机没显式选过」时才接受备份值。
     */
    val hasExplicitRenderMode: Boolean get() = sp.getBoolean(K_USER_SET_RENDER_MODE, false)

    /** 配置恢复专用：不标记显式设置（见 [setPerfTierFromRestore]） */
    fun setRenderModeFromRestore(v: RenderMode) {
        sp.edit().putString(K_RENDER_MODE, v.key).apply()
    }

    /**
     * 翻页方式（[PageTurnMode]）。
     *
     * 默认 [PageTurnMode.TAP]（点按翻页，即 v0.1.7 之前的行为）。
     *
     * 这是**用户偏好**而不是设备属性：它描述的是「你想怎么翻书」，
     * 换设备后依然成立，所以跟字号 / 行距一样正常参与配置备份恢复
     * （不像性能档位 / 渲染模式那样要按设备隔离）。
     *
     * 墨水屏上的强制回落不写回这里 —— 回落发生在
     * [com.readit.data.resolvePageTurnMode]，偏好值本身原样保留。
     */
    var pageTurnMode: PageTurnMode
        get() = PageTurnMode.from(sp.getString(K_PAGE_TURN_MODE, PageTurnMode.TAP.key))
        set(v) = sp.edit().putString(K_PAGE_TURN_MODE, v.key).apply()

    var fontSizeSp: Float
        get() = com.readit.core.text.Fonts.clampFontSizeSp(sp.getFloat(K_FONT_SIZE_SP, DEFAULT_FONT_SIZE_SP))
        set(v) = sp.edit().putFloat(K_FONT_SIZE_SP, com.readit.core.text.Fonts.clampFontSizeSp(v)).apply()

    /**
     * 字体 id（内置 id 或 `user:<文件名>`）。
     *
     * 读取时只做「非空」校正——真正的「该字体还在不在」要等扫完字体目录才能判断，
     * 由 [com.readit.core.text.UserFonts.currentId] 负责，避免每次读 prefs 都去扫盘。
     */
    var fontFamily: String
        get() = sp.getString(K_FONT_FAMILY, DEFAULT_FONT_FAMILY)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_FONT_FAMILY
        set(v) = sp.edit().putString(K_FONT_FAMILY, v).apply()

    /**
     * 行距倍数。读写两侧都 clamp（与 [fontSizeSp] 同一纪律）——
     * 越界值只能来自配置备份 / 跨设备恢复 / 系统 prefs 还原，而 `TxtCanvasView.onDraw`
     * 拿到 `<= 0` 或 `NaN` 的行距会叠字或整页空白。
     */
    var lineSpacing: Float
        get() = com.readit.core.text.Fonts.clampLineSpacing(sp.getFloat(K_LINE_SPACING, DEFAULT_LINE_SPACING))
        set(v) = sp.edit().putFloat(K_LINE_SPACING, com.readit.core.text.Fonts.clampLineSpacing(v)).apply()

    /**
     * 页边距（dp）。同样双向 clamp：`marginDp` 越界会让
     * `TxtCanvasView` 的可用宽度算出非正数，`Paint.breakText` 返回 0 而绘制循环不推进。
     */
    var marginDp: Int
        get() = com.readit.core.text.Fonts.clampMarginDp(sp.getInt(K_MARGIN_DP, DEFAULT_MARGIN_DP))
        set(v) = sp.edit().putInt(K_MARGIN_DP, com.readit.core.text.Fonts.clampMarginDp(v)).apply()

    /**
     * 反色（黑白置换，F27）。默认关。
     *
     * 为什么不做「背景色 / 主题」：墨水屏只有黑白两级，见
     * [com.readit.core.display.Inversion] 里的取舍说明。
     */
    var invertEnabled: Boolean
        get() = sp.getBoolean(K_INVERT, false)
        set(v) = sp.edit().putBoolean(K_INVERT, v).apply()

    /** PDF 裁边（F07）：仅渲染档可用，默认开启 */
    var pdfCropEnabled: Boolean
        get() = sp.getBoolean(K_PDF_CROP, true)
        set(v) = sp.edit().putBoolean(K_PDF_CROP, v).apply()

    // ---------------------------------------------------------------- 屏幕灯

    /** 屏幕灯开关（仅墨水屏有意义；非墨水屏恒按「开着」处理） */
    var lightOn: Boolean
        get() = sp.getBoolean(K_LIGHT_ON, DEFAULT_LIGHT_ON)
        set(v) = sp.edit().putBoolean(K_LIGHT_ON, v).apply()

    /** 亮度等级 0..100 */
    var lightLevel: Int
        get() = sp.getInt(K_LIGHT_LEVEL, DEFAULT_LIGHT_LEVEL)
            .coerceIn(com.readit.core.light.ScreenLight.LEVEL_MIN, com.readit.core.light.ScreenLight.LEVEL_MAX)
        set(v) = sp.edit().putInt(
            K_LIGHT_LEVEL,
            v.coerceIn(com.readit.core.light.ScreenLight.LEVEL_MIN, com.readit.core.light.ScreenLight.LEVEL_MAX)
        ).apply()

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

    // ---------------------------------------------------------------- 编码（F15）

    /**
     * 用户为该书手动选定的编码名；null = 没选过，走自动检测。
     *
     * 按书记忆而不是全局默认值：阅读主体是 GBK 时代的中文 TXT，同一台设备上
     * 不同来源的书编码并不一致，全局默认值反而会制造新的乱码。
     */
    fun charsetFor(bookId: String): String? = sp.getString(K_CHARSET_PREFIX + bookId, null)

    fun setCharsetFor(bookId: String, name: String?) =
        sp.edit().apply { if (name == null) remove(K_CHARSET_PREFIX + bookId) else putString(K_CHARSET_PREFIX + bookId, name) }
            .apply()

    // ---------------------------------------------------------------- 按书记忆的搬家（重命名 / 删除）

    /**
     * 书籍被重命名：把它的按书记忆从旧书名搬到新书名。
     *
     * 必须搬的理由：`bookId` 就是文件名（`ReaderActivity` 里 `bookId = file.name`）。
     * 只改文件不搬记忆，用户重命名一本书之后就会「进度归零 + 编码回落到自动检测」，
     * 而这两件事都不会报错 —— 典型静默数据丢失。
     */
    fun moveBookPrefs(fromId: String, toId: String) {
        if (fromId == toId) return
        val e = sp.edit()
        sp.getString(K_CHARSET_PREFIX + fromId, null)?.let {
            e.remove(K_CHARSET_PREFIX + fromId)
            e.putString(K_CHARSET_PREFIX + toId, it)
        }
        if (sp.getBoolean(K_FORCE_IMPORT_PREFIX + fromId, false)) {
            e.remove(K_FORCE_IMPORT_PREFIX + fromId)
            e.putBoolean(K_FORCE_IMPORT_PREFIX + toId, true)
        }
        e.apply()
    }

    /** 书籍被删除：清掉它的按书记忆，否则同名新书会继承旧书的编码 / 强制导入标记 */
    fun clearBookPrefs(bookId: String) {
        sp.edit()
            .remove(K_CHARSET_PREFIX + bookId)
            .remove(K_FORCE_IMPORT_PREFIX + bookId)
            .apply()
    }

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

    /**
     * 自动同步开关（未闭环项 A1）。默认开启，但要三个条件同时成立才真挂任务：
     * 档位支持（API23+）、开关打开、WebDAV 已配置；且调度时额外限定「仅非计费网络」。
     */
    var syncAutoEnabled: Boolean
        get() = sp.getBoolean(K_SYNC_AUTO, DEFAULT_SYNC_AUTO)
        set(v) = sp.edit().putBoolean(K_SYNC_AUTO, v).apply()

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

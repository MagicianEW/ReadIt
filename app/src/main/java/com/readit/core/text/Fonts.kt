package com.readit.core.text

/**
 * 字体选择的纯逻辑层（**不依赖 Android**，JVM 可直接测）。
 *
 * 三类：
 *  - **系统族名**（default/serif/mono/light）：走 Android 通用族名，TXT 用 `Typeface`、
 *    WebView 写同名 CSS。注意：设备缺对应字体时会**静默回退**（很多墨水屏没有衬线字体）。
 *  - **内置打包字体**（`pkg:xxx`）：随 APK 发布的 OFL 开源字体，见
 *    `tools/gen_packaged_fonts.py`（下载源、字表口径、授权都记在那里）。
 *    TXT 用 `Typeface.createFromAsset`；EPUB 用预置 @font-face（asset 同源，能加载）；
 *    DOCX 由 `ReaderActivity.docxFontFaceSrc()` 把字体拷进转换产物目录，再注入
 *    `@font-face` + `*{font-family:X !important}`（见 `DocxWebView.setFontFamily`）。
 *    注：早期结论「DOCX 的 WebView 禁了 file 访问所以不生效」是**错的** ——
 *    `allowFileAccess=true`，图片走的就是同一套 `file://` 相对路径；真因是当时压根
 *    没给 `@font-face` 声明。已在 KY-01L 真机验收（TEXT / HTML 两条路，2026-09-22）。
 *  - **用户字体**（`user:<文件名>`）：`<书籍目录>/fonts` 下的 .ttf/.otf。
 *    TXT 必然生效；EPUB/DOCX 不保证，UI 上要标注「仅 TXT」（见 `isTxtOnly`）。
 */
object Fonts {

    const val ID_DEFAULT = "default"
    const val ID_SERIF = "serif"
    const val ID_MONO = "mono"
    const val ID_LIGHT = "light"

    /** 内置打包字体（id -> assets 路径 / CSS family 名） */
    const val PKG_SERIF_SC = "pkg:noto-serif-sc"
    const val PKG_WENKAI = "pkg:lxgw-wenkai"

    const val USER_PREFIX = "user:"

    /**
     * 内置字体条目：系统族名 + 打包字体。
     * **顺序 = string-array `font_builtin_labels` 的顺序，两边必须一起改。**
     * [asset] 为 null 时表示走系统族名；非空则是 APK 内 assets 路径。
     */
    data class Builtin(val id: String, val css: String, val asset: String? = null) {
        fun isPackaged(): Boolean = asset != null
    }

    fun builtins(): List<Builtin> = listOf(
        Builtin(ID_DEFAULT, "sans-serif"),
        Builtin(ID_SERIF, "serif"),
        Builtin(ID_MONO, "monospace"),
        Builtin(ID_LIGHT, "sans-serif-light"),
        Builtin(PKG_SERIF_SC, "'ReadIt Serif SC'", "fonts/readit_font_notoserif_sc.otf"),
        Builtin(PKG_WENKAI, "'ReadIt WenKai'", "fonts/readit_font_lxgw_wenkai.ttf")
    )

    fun builtinIds(): List<String> = builtins().map { it.id }

    /** 打包字体的 assets 路径；非打包字体返回 null */
    fun assetPath(id: String?): String? = builtins().firstOrNull { it.id == id }?.asset

    fun isUser(id: String?): Boolean = id != null && id.startsWith(USER_PREFIX)

    /** `user:a.ttf` -> `a.ttf`；非用户字体返回 null（不接受空文件名） */
    fun userFileName(id: String?): String? {
        if (!isUser(id)) return null
        val name = id!!.removePrefix(USER_PREFIX)
        return name.takeIf { it.isNotBlank() && it.contains(".") }
    }

    fun userId(fileName: String): String = USER_PREFIX + fileName

    /** 文件名 -> 显示名（去扩展名），用于 Spinner / ListPreference 条目 */
    fun userDisplayName(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot > 0) fileName.substring(0, dot) else fileName
    }

    /** WebView 用的 CSS font-family 值。用户字体加引号，避免文件名里的空格/连字符破坏 CSS。 */
    fun cssFamily(id: String?): String {
        if (isUser(id)) {
            val name = userFileName(id) ?: return "sans-serif"
            return "'" + name.replace("'", "\\'") + "'"
        }
        return builtins().firstOrNull { it.id == id }?.css ?: "sans-serif"
    }

    /** 是否是内置打包字体（有实体字体文件，不只是系统族名） */
    fun isPackaged(id: String?): Boolean = assetPath(id) != null

    /**
     * 校正字体 id：内置 id 直接放行；用户字体必须仍在已扫描到的列表里（字体文件被删/改名后
     * 要静默回退，否则阅读页拿不到 Typeface，会一直用旧值或空指针）。其余一律回退默认。
     */
    fun sanitize(id: String?, availableUserIds: Collection<String> = emptyList()): String {
        if (id.isNullOrBlank()) return ID_DEFAULT
        if (id in builtinIds()) return id
        if (isUser(id) && id in availableUserIds) return id
        return ID_DEFAULT
    }

    /** 用户字体只对 TXT 保证生效（WebView 侧要靠 @font-face 注入，不兜底） */
    fun isTxtOnly(id: String?): Boolean = isUser(id)

    // ---------------------------------------------------------------- 字号

    const val MIN_FONT_SP = 10f
    const val MAX_FONT_SP = 20f

    fun clampFontSizeSp(value: Float): Float =
        if (value.isNaN()) DEFAULT_SIZE_SP else value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)

    const val DEFAULT_SIZE_SP = 14f
}

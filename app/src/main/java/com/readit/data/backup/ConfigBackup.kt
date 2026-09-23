package com.readit.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.core.text.Fonts
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import java.io.File
import java.io.IOException

/**
 * 配置备份 / 恢复（§8 Phase 4「引导 + 设置 + 备份」）。
 *
 * 设计取舍：
 *  1. **用带类型的 data class 而不是直接 dump SharedPreferences** —— 后者是
 *     `Map<String, Any?>`，JSON 往返会把 Int/Float 都变成 Double，恢复时只能靠猜。
 *     显式 schema 还有一个好处：它本身就是「当前有哪些配置项」的清单。
 *  2. **不导出 WebDAV 密码** —— 备份文件会落在用户的下载目录 / 网盘里，
 *     明文密码跟着跑出去是不能接受的。导入后需要用户重新填一次。
 *     同理不导出 `force_import_*` 这类「已阅」标记（属于阅读状态，不是配置）。
 *  3. **校验 format + version** —— 备份文件来自哪个版本必须能识别，
 *     否则未来字段语义变化时会静默套用错误配置。
 */
object ConfigBackup {

    const val FORMAT = "readit-config"
    const val VERSION = 1

    private val gson = Gson()

    data class Snapshot(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val appVersion: String = "",
        val exportedAt: Long = 0L,
        val perfTier: String = PerfTier.AUTO.key,
        val refreshMode: String = RefreshMode.AUTO.key,
        val fontSizeSp: Float = ReadItPrefs.DEFAULT_FONT_SIZE_SP,
        val fontFamily: String = ReadItPrefs.DEFAULT_FONT_FAMILY,
        val lineSpacing: Float = ReadItPrefs.DEFAULT_LINE_SPACING,
        val marginDp: Int = ReadItPrefs.DEFAULT_MARGIN_DP,
        val invert: Boolean = false,
        val pdfCrop: Boolean = true,
        val scanSamplePages: Int = 3,
        val scanMinCharsPerPage: Int = 100,
        val scanMinImagePageRatio: Float = 0.5f,
        val webDavUrl: String = "",
        val webDavUser: String = "",
        val webDavDir: String = ReadItPrefs.DEFAULT_WEBDAV_DIR,
        /** 书籍保存目录绝对路径；空 = 应用内部默认目录 */
        val booksDir: String = "",
        /** keyCode 的字符串形式 -> Action 名（Gson 对 Map<Int,*> 的键处理不直观，统一用字符串） */
        val keyMaps: Map<String, String> = emptyMap()
    ) {
        /** 一行式摘要，用于回显 */
        fun summary(): String =
            "档位 $perfTier · 刷新 $refreshMode · 字号 ${fontSizeSp}sp · 按键映射 ${keyMaps.size} 条"
    }

    // ------------------------------------------------------------------ 导出

    fun snapshot(prefs: ReadItPrefs, appVersion: String): Snapshot = Snapshot(
        appVersion = appVersion,
        exportedAt = System.currentTimeMillis(),
        perfTier = prefs.perfTier.key,
        refreshMode = prefs.refreshMode.key,
        fontSizeSp = prefs.fontSizeSp,
        fontFamily = prefs.fontFamily,
        lineSpacing = prefs.lineSpacing,
        marginDp = prefs.marginDp,
        invert = prefs.invertEnabled,
        pdfCrop = prefs.pdfCropEnabled,
        scanSamplePages = prefs.scanSamplePages,
        scanMinCharsPerPage = prefs.scanMinCharsPerPage,
        scanMinImagePageRatio = prefs.scanMinImagePageRatio,
        webDavUrl = prefs.webDavUrl,
        webDavUser = prefs.webDavUser,
        webDavDir = prefs.webDavDir,
        booksDir = prefs.booksDir,
        keyMaps = prefs.allKeyMaps().mapKeys { it.key.toString() }
    )

    fun toJson(s: Snapshot): String = gson.toJson(s)

    // ------------------------------------------------------------------ 导入

    /**
     * 解析并校验备份内容。
     *
     * @throws IllegalArgumentException 空内容 / 非法 JSON / 非本应用备份 / 版本过新
     */
    @Throws(IllegalArgumentException::class)
    fun parse(json: String): Snapshot {
        if (json.isBlank()) throw IllegalArgumentException("内容为空")
        val s = try {
            gson.fromJson(json, Snapshot::class.java)
        } catch (e: Exception) {
            null
        } ?: throw IllegalArgumentException("JSON 解析失败")

        if (s.format != FORMAT) throw IllegalArgumentException("不是 ReadIt 配置文件（format=${s.format}）")
        if (s.version > VERSION) {
            throw IllegalArgumentException("备份来自更新的版本（v${s.version} > v$VERSION），请先升级应用")
        }
        return s
    }

    /**
     * 覆盖式恢复。返回恢复的按键映射条数（0 表示备份里没有按键映射）。
     *
     * **不是无差别覆盖**：`perfTier` / `refreshMode` 属「设备属性」，只在目标设备
     * **从未显式设置过**该项时才接受备份值，否则保留本机现值 ——
     * 判据与实测理由见 [DeviceScopedConfig]。`booksDir` 同样有存在性校验。
     * 两条恢复路径（设置页「导入配置」与 F28 云端恢复）都走这个方法，规则天然一致。
     *
     * 注意：调用方应在本方法返回后、**主线程**上调用 `Eal.reload()`
     * —— perfTier 变了，EAL 的缓存结果必须重算。
     */
    fun restore(prefs: ReadItPrefs, s: Snapshot): Int {
        // 设备属性项（档位 / 刷新模式）只在「本机从未显式设置过」时才接受备份里的值。
        // 理由与实测后果见 DeviceScopedConfig 的文档；一句话：档位描述的是硬件，
        // 跨设备照搬会把目标机无声降到更保守的渲染档。
        if (DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_PERF_TIER, prefs.hasExplicitPerfTier)) {
            ReadItLog.i("config restore: 本机已显式设置档位 ${prefs.perfTier.key}，忽略备份里的 ${s.perfTier}")
        } else {
            prefs.perfTier = PerfTier.from(s.perfTier)
        }
        if (DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_REFRESH_MODE, prefs.hasExplicitRefreshMode)) {
            ReadItLog.i("config restore: 本机已显式设置刷新模式 ${prefs.refreshMode.key}，忽略备份里的 ${s.refreshMode}")
        } else {
            prefs.refreshMode = RefreshMode.from(s.refreshMode)
        }

        prefs.fontSizeSp = s.fontSizeSp
        // 字体：备份可能来自另一台设备，用户字体未必在；这里只做「内置/非空」校正，
        // 真正的「文件还在吗」由 UserFonts.currentId() 在使用时兜底（会静默回退默认）。
        prefs.fontFamily = Fonts.sanitize(s.fontFamily)
        prefs.lineSpacing = s.lineSpacing
        prefs.marginDp = s.marginDp
        prefs.invertEnabled = s.invert
        prefs.pdfCropEnabled = s.pdfCrop
        prefs.scanSamplePages = s.scanSamplePages
        prefs.scanMinCharsPerPage = s.scanMinCharsPerPage
        prefs.scanMinImagePageRatio = s.scanMinImagePageRatio
        prefs.webDavUrl = s.webDavUrl
        prefs.webDavUser = s.webDavUser
        prefs.webDavDir = s.webDavDir

        // 书籍目录：只有目标路径「真的存在且是目录」时才恢复。
        // 备份可能来自另一台设备，路径未必存在；把无效路径写进配置会让书架静默变空，
        // 所以宁可保留本机现值。空值代表「应用内部默认目录」，照常写入。
        if (s.booksDir.isBlank()) {
            prefs.booksDir = ""
        } else {
            val dir = File(s.booksDir)
            if (dir.isDirectory) {
                prefs.booksDir = dir.absolutePath
            } else {
                ReadItLog.w("config restore: booksDir 不存在或不是目录，保留本机现值: ${s.booksDir}")
            }
        }

        // 按键映射是「整组替换」而不是合并：备份代表用户当时的完整意图，
        // 合并会让上一台设备的映射残留下来。
        prefs.clearKeyMaps()
        var restored = 0
        for ((k, v) in s.keyMaps) {
            val code = k.toIntOrNull() ?: continue
            prefs.putKeyMap(code, v)
            restored++
        }
        // 两行分开记：第一行是「备份里写的什么」，第二行是「实际落到本机的是什么」。
        // 档位/刷新可能被上面保留本机而没生效，合成一行会看不出来。
        ReadItLog.i("config restored: keys=$restored 备份内容[${s.summary()}]")
        ReadItLog.i("config effective: 档位 ${prefs.perfTier.key} · 刷新 ${prefs.refreshMode.key} · 字号 ${prefs.fontSizeSp}sp")
        return restored
    }

    // ------------------------------------------------------------------ SAF IO

    @Throws(IOException::class)
    fun writeTo(context: Context, uri: Uri, json: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        } ?: throw IOException("无法写入所选位置")
    }

    @Throws(IOException::class)
    fun readFrom(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: throw IOException("无法读取所选文件")
    }
}

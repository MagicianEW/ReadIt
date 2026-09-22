package com.readit.core.text

import android.graphics.Typeface
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.data.storage.StorageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 字体目录扫描 + `Typeface` 解析（Android 层，纯逻辑在 [Fonts]）。
 *
 * 目录优先级：`<书籍目录>/fonts`（USB 传书时顺手就能放字体）→ 不可写则退回 `filesDir/fonts`。
 * 只认 `.ttf` / `.otf`，按文件名排序，保证列表顺序稳定（否则 ListPreference 每次打开都在跳）。
 */
object UserFonts {

    private const val DIR_NAME = "fonts"
    private val EXTENSIONS = listOf("ttf", "otf")

    data class Entry(val id: String, val fileName: String, val file: File) {
        fun displayName(): String = Fonts.userDisplayName(fileName)
    }

    /** 字体目录：优先书籍目录下的 fonts，兜底内部 filesDir/fonts（并确保存在） */
    fun fontsDir(context: android.content.Context): File {
        val candidate = File(StorageManager.booksDir(context), DIR_NAME)
        if (usable(candidate)) return candidate
        val internal = File(context.filesDir, DIR_NAME)
        if (!internal.exists()) internal.mkdirs()
        return internal
    }

    private fun usable(dir: File): Boolean =
        (dir.exists() && dir.isDirectory && dir.canRead()) || dir.mkdirs()

    fun scan(context: android.content.Context): List<Entry> {
        val dir = fontsDir(context)
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            .sortedBy { it.name.lowercase() }
            .map { Entry(Fonts.userId(it.name), it.name, it) }
            .toList()
    }

    // ---------------------------------------------------------------- Typeface

    private val cache = ConcurrentHashMap<String, Typeface?>()

    /** 清缓存：字体文件被替换后（同名不同内容）需要重新解析 */
    fun invalidate() = cache.clear()

    /**
     * 按 id 取 Typeface；内置字体走系统族名，用户字体走 `createFromFile`。
     * 解析失败一律返回 null（调用方保持原字体），**绝不抛异常到阅读页**。
     */
    fun typefaceFor(context: android.content.Context, id: String?): Typeface? {
        val key = id ?: Fonts.ID_DEFAULT
        cache[key]?.let { return it }
        val tf = runCatching { resolve(context, key) }.getOrNull()
        if (tf != null) cache[key] = tf
        else ReadItLog.w("font resolve failed, keep system default: $key")
        return tf
    }

    private fun resolve(context: android.content.Context, id: String): Typeface? {
        // 内置打包字体：直接走 assets（OFL 开源字体，见 tools/gen_packaged_fonts.py）
        Fonts.assetPath(id)?.let { asset ->
            return Typeface.createFromAsset(context.assets, asset)
        }
        return when {
            id == Fonts.ID_DEFAULT -> Typeface.DEFAULT
            id == Fonts.ID_SERIF -> Typeface.SERIF
            id == Fonts.ID_MONO -> Typeface.MONOSPACE
            id == Fonts.ID_LIGHT -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                ?: Typeface.DEFAULT
            Fonts.isUser(id) -> {
                val name = Fonts.userFileName(id) ?: return null
                val file = File(fontsDir(context), name)
                if (!file.isFile || !file.canRead()) return null
                Typeface.createFromFile(file)
            }
            else -> Typeface.DEFAULT
        }
    }

    /** 供设置页 summary 显示的目录路径 */
    fun dirLabel(context: android.content.Context): String = fontsDir(context).absolutePath

    /** 当前生效字体 id（已按现存字体校正过） */
    fun currentId(context: android.content.Context): String {
        val prefs = ReadItPrefs.get(context)
        val available = scan(context).map { it.id }
        return Fonts.sanitize(prefs.fontFamily, available)
    }
}

package com.readit.core.text

import com.readit.core.util.ReadItLog
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

/**
 * 编码检测（规范 §3.2.1，F15）。
 *
 * 基于 juniversalchardet 采样检测。F15 的验收是「失败可手动选择」，所以这里除了
 * 自动检测，还要能告诉调用方「这次结果靠不靠谱」——自动检测失败（null）与
 * **解出来是乱码**（不抛异常，只是每个字节都替换成 U+FFFD）都要能识别，
 * 否则 UI 永远不会给用户选择的机会，MANUAL_CANDIDATES 就成了死代码。
 */
object EncodingDetector {

    private const val SAMPLE_BYTES = 64 * 1024

    /** 乱码判定采样长度：整本几 MB 的书没必要全扫，头部足够代表 */
    private const val CHECK_CHARS = 32 * 1024

    /** 无 BOM UTF-16 探测的采样字节数（偶数对齐） */
    private const val UTF16_SAMPLE = 8 * 1024

    /** 样本里至少出现这么多 NUL 才考虑 UTF-16 */
    private const val MIN_UTF16_ZEROS = 2

    /** 解码可信度阈值：低于此值认为「解出来不像正文」，不是 UTF-16 */
    private const val UTF16_PLAUSIBLE = 0.9f

    /**
     * 替换字符占比超过该阈值即判乱码。
     *
     * 正常中文文本里不会出现 U+FFFD；一旦用错编码解码 GBK，几乎每个汉字都会变成
     * U+FFFD，占比远超 30%。取 2% 既留足余量，又不会因为个别异常字符误报。
     */
    const val GARBLED_RATIO = 0.02f

    /** 手动选择列表（中文 E-Ink 场景常用编码置前） */
    val MANUAL_CANDIDATES = listOf(
        "UTF-8", "GBK", "GB18030", "BIG5", "UTF-16LE", "UTF-16BE", "ISO-8859-1"
    )

    /**
     * @return 检测到的 Charset；无法判定返回 null
     */
    fun detect(file: File): Charset? {
        val name = detectName(file)
        if (name.isNullOrBlank()) return null
        return try {
            Charset.forName(name)
        } catch (e: Exception) {
            ReadItLog.w("unsupported charset: $name")
            null
        }
    }

    fun detectName(file: File): String? {
        if (!file.exists()) return null
        // BOM 优先判定（juniversalchardet 对 UTF-8 with BOM 也能识别，这里提前短路）
        readBom(file)?.let { return it }

        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(minOf(SAMPLE_BYTES.toLong(), file.length()).toInt())
                val read = fis.read(buf)
                if (read <= 0) return null
                val detector = UniversalDetector(null)
                detector.handleData(buf, 0, read)
                detector.dataEnd()
                val cs = detector.detectedCharset
                detector.reset()
                if (cs.isNullOrBlank()) {
                    // juniversalchardet 只覆盖 8-bit 编码，无 BOM 的 UTF-16 一律落到这里。
                    // 兜底探测只在「chardet 也束手无策」时接管，绝不覆盖它的结论。
                    val utf16 = detectUtf16NoBom(file)
                    if (utf16 != null) {
                        ReadItLog.i("encoding detect: ${file.name} -> $utf16 (no-bom utf16 fallback)")
                        utf16
                    } else {
                        ReadItLog.w("encoding detect failed: ${file.name}")
                        null
                    }
                } else {
                    ReadItLog.i("encoding detect: ${file.name} -> $cs")
                    cs
                }
            }
        } catch (e: Exception) {
            ReadItLog.e("encoding detect error: ${file.name}", e)
            null
        }
    }

    /**
     * 无 BOM 的 UTF-16 定向探测。
     *
     * 依据：**GBK / GB18030 / BIG5 / UTF-8 的正文里几乎不可能出现 0x00**
     * （GBK 系字节固定落在 0x40~0xFE，UTF-8 除 NUL 外不含 0），
     * 所以「样本里出现 NUL」本身就是 UTF-16 的强信号。
     *
     * 字节序不靠数 0 的位置猜——纯中文 UTF-16 两种字节序都会产生 0。改为各解一遍，
     * 取「常用汉字 / ASCII / 中文标点」占比高的那个；两者都低就认为不是 UTF-16。
     */
    private fun detectUtf16NoBom(file: File): String? {
        val buf = try {
            FileInputStream(file).use { fis ->
                val want = minOf(UTF16_SAMPLE.toLong(), file.length()).toInt()
                if (want < 4) return null
                val even = want - want % 2
                val b = ByteArray(even)
                if (fis.read(b) != even) return null
                b
            }
        } catch (e: Exception) {
            return null
        }

        var zeros = 0
        for (x in buf) if (x == 0.toByte()) zeros++
        if (zeros < MIN_UTF16_ZEROS) return null

        val le = plausibleRatio(String(buf, Charsets.UTF_16LE))
        val be = plausibleRatio(String(buf, Charsets.UTF_16BE))
        return when {
            le >= UTF16_PLAUSIBLE && le >= be -> "UTF-16LE"
            be >= UTF16_PLAUSIBLE -> "UTF-16BE"
            else -> null
        }
    }

    /** 解码结果里「像是正文」的字符占比：常用汉字 / 扩展汉字 / 中文标点 / ASCII 可见字符 */
    private fun plausibleRatio(s: String): Float {
        if (s.isEmpty()) return 0f
        var good = 0
        for (i in 0 until s.length) {
            val c = s[i].code
            if ((c in 0x4E00..0x9FFF) ||   // CJK 基本区
                (c in 0x3400..0x4DBF) ||   // CJK 扩展 A
                (c in 0x3000..0x303F) ||   // 中文标点
                (c in 0xFF00..0xFFEF) ||   // 全角/半角形式
                (c in 0x20..0x7E) ||       // ASCII 可见
                (c in 0xD800..0xDFFF) ||   // 代理对（扩展区汉字的 UTF-16 表示）
                c == '\n'.code || c == '\r'.code || c == '\t'.code
            ) good++
        }
        return good.toFloat() / s.length
    }

    /**
     * 用指定编码名读整本；失败抛异常，由调用方决定退路。
     *
     * 单独抽出来是因为重 earliest 路径（用户手动选完重读）与首次打开共用同一段IO，
     * 别让调用方各自拼 readText。
     */
    fun readWith(file: File, charsetName: String): String {
        val cs = Charset.forName(charsetName) // 不支持的名字直接抛
        return file.readText(cs)
    }

    /**
     * 替换字符（U+FFFD）占采样区的比例。
     *
     * 解码器遇到非法字节序列时会写 '\uFFFD' 而**不抛异常**——这正是 F15 最容易
     * 静默失败的地方：文件"读成功了"，用户看到的却满屏豆腐块。
     */
    fun suspiciousRatio(text: String): Float {
        if (text.isEmpty()) return 0f
        val end = minOf(CHECK_CHARS, text.length)
        var bad = 0
        for (i in 0 until end) {
            if (text[i] == '\uFFFD') bad++
        }
        return bad.toFloat() / end
    }

    /** 是否为疑似乱码（供 UI 决定要不要弹手动选择） */
    fun looksGarbled(text: String): Boolean = suspiciousRatio(text) > GARBLED_RATIO

    private fun readBom(file: File): String? {
        return try {
            FileInputStream(file).use { fis ->
                val head = ByteArray(3)
                val n = fis.read(head)
                if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
                    "UTF-8"
                } else if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
                    "UTF-16LE"
                } else if (n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
                    "UTF-16BE"
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}

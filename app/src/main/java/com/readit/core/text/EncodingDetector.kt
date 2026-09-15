package com.readit.core.text

import com.readit.core.util.ReadItLog
import org.mozilla.universalchardet.UniversalDetector
import java.io.File
import java.io.FileInputStream
import java.nio.charset.Charset

/**
 * 编码检测（规范 §3.2.1，F15）。
 *
 * 基于 juniversalchardet 采样检测；失败时返回 null，由调用方弹出手动选择列表。
 */
object EncodingDetector {

    private const val SAMPLE_BYTES = 64 * 1024

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
                    ReadItLog.w("encoding detect failed: ${file.name}")
                    null
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

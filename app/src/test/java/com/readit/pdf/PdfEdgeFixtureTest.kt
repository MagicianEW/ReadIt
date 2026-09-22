package com.readit.pdf

import com.readit.pdf.PdfOpenError
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/**
 * §9.3 边界场景：混合版 PDF 与损坏/加密 PDF。
 *
 * 验收口径是「不崩溃 + 给出正确原因」，不是「必须成功打开」：
 * 损坏文件允许失败，但失败必须以 IOException 的形式暴露，且加密要能被单独识别出来
 * （否则用户只会看到「已切换为文本模式」这种错误提示，见 PdfOpenError 的注释）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PdfEdgeFixtureTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_edge_test").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("fixtures/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    // -------------------------------------------------------------- 混合版

    @Test
    fun `mixed pdf is treated as text based because sampling hits text pages`() {
        for (name in listOf("readit_mixed_10p.pdf", "readit_mixed_scanheavy_12p.pdf")) {
            val f = fixture(name)
            val ex = PdfTextExtractor()
            ex.open(f)
            val v = ex.detectScan()
            println("[MIXED] $name -> ${v.describe()}")
            ex.close()
            // 两种混合版都不该被判为扫描版：
            // 只要 3 个采样页里有 1 页带文本层，平均每页字符数就远超 minCharsPerPage=100，
            // 「低文本」这一路不成立。这是采样法在混合版上的固有倾向，不是 bug，
            // 但意味着「扫描页占多数」的书仍会按文本版打开 —— 已知的口径边界，见报告。
            org.junit.Assert.assertFalse("$name 不应判为扫描版", v.scanned)
        }
    }

    // -------------------------------------------------------------- 损坏 / 加密

    @Test
    fun `damaged pdfs fail as IOException and never crash`() {
        val cases = listOf(
            "readit_damaged_truncated.pdf",
            "readit_damaged_badheader.pdf",
            "readit_damaged_zero.pdf",
            "readit_damaged_xref.pdf"
        )
        for (name in cases) {
            val f = fixture(name)
            var outcome: String
            try {
                val ex = PdfTextExtractor()
                ex.open(f)
                val pages = ex.pageCount
                val v = ex.detectScan()
                ex.close()
                outcome = "opened pages=$pages scan=${v.scanned}"
            } catch (e: IOException) {
                outcome = "IOException: ${e.message?.take(70)}"
            } catch (e: Throwable) {
                outcome = "UNEXPECTED ${e.javaClass.simpleName}: ${e.message?.take(70)}"
            }
            println("[DAMAGED] $name -> $outcome")
            org.junit.Assert.assertFalse(
                "$name 抛出了非受检异常（会直接崩进程）",
                outcome.startsWith("UNEXPECTED")
            )
        }
    }

    /**
     * 空用户口令的 AES 加密 PDF **能**被 PdfBox 解开。
     *
     * 这条容易想错：加密 ≠ 打不开。出版方常用「空用户口令 + 权限限制」的加密方式
     * （禁止打印/复制），内容并不需要口令。所以「加密」不能一刀切判为不支持。
     */
    @Test
    fun `aes pdf with empty user password opens fine`() {
        val f = fixture("readit_encrypted_aes.pdf")
        val ex = PdfTextExtractor()
        ex.open(f)
        val pages = ex.pageCount
        val text = if (pages > 0) ex.extractPage(0) else ""
        ex.close()
        println("[ENCRYPTED empty-pw] pages=$pages firstPage=${text.take(40).replace("\n", " ")}")
        org.junit.Assert.assertTrue("空用户口令加密 PDF 应能打开（实测 pages=$pages）", pages > 0)
        org.junit.Assert.assertTrue("打开后应能抽到文本", text.isNotBlank())
    }

    /** 真正设了用户口令的，才是「不支持」，且失败原因必须能被单独识别出来 */
    @Test
    fun `aes pdf with user password fails and is identified as encryption`() {
        val f = fixture("readit_encrypted_userpw.pdf")
        var thrown: Throwable? = null
        try {
            PdfTextExtractor().open(f).close()
        } catch (e: Throwable) {
            thrown = e
        }
        println("[ENCRYPTED user-pw] thrown=${thrown?.javaClass?.simpleName} msg=${thrown?.message?.take(80)}")
        org.junit.Assert.assertNotNull("带用户口令的加密 PDF 必须打开失败", thrown)
        org.junit.Assert.assertTrue(
            "失败原因必须能被识别为「需要密码」，否则用户只会看到「没有可提取的文本层」",
            PdfOpenError.isEncrypted(thrown)
        )
    }
}

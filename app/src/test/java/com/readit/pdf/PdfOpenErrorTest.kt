package com.readit.pdf

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §9.3 边界场景表第 5 行：加密 PDF 应被识别为「不支持」，而不是走通用渲染降级。
 */
class PdfOpenErrorTest {

    @Test
    fun `pdfbox decrypt message is detected`() {
        // 真机 06_pdf_encrypted.pdf 实测文案
        val e = IOException("Cannot decrypt PDF, the password is incorrect")
        assertTrue(PdfOpenError.isEncrypted(e))
    }

    @Test
    fun `pdfium password message is detected through cause chain`() {
        val root = RuntimeException("Password required or incorrect password.")
        val wrapped = IOException("pdfium load failed: Password required or incorrect password.", root)
        assertTrue(PdfOpenError.isEncrypted(wrapped))
    }

    @Test
    fun `encryption wording is detected`() {
        assertTrue(PdfOpenError.isEncrypted(IOException("document is encrypted")))
        assertTrue(PdfOpenError.isEncrypted(IOException("unsupported encryption dictionary")))
    }

    @Test
    fun `unrelated failures are not treated as encrypted`() {
        assertFalse(PdfOpenError.isEncrypted(null))
        assertFalse(PdfOpenError.isEncrypted(IOException("PDF header not found")))
        assertFalse(PdfOpenError.isEncrypted(IOException("pdf open failed: EACCES")))
        assertFalse(PdfOpenError.isEncrypted(RuntimeException("boom")))
    }

    @Test
    fun `cyclic cause chain terminates`() {
        // 真·环形 cause 链 a -> b -> a：靠深度上限收口，不能死循环
        val a = IOException("boom")
        val b = IOException("bang")
        a.initCause(b)
        b.initCause(a)
        assertFalse(PdfOpenError.isEncrypted(a))
    }
}

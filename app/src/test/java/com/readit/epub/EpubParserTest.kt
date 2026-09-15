package com.readit.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P2：EPUB 容器解析回归（F03 / F10）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpubParserTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = File(System.getProperty("java.io.tmpdir"), "readit_epub_test").apply { mkdirs() }
    }

    private fun fixture(name: String): File {
        val f = File(tmp, name)
        javaClass.classLoader.getResourceAsStream("fixtures/$name").use { src ->
            requireNotNull(src) { "fixture missing: $name" }
            f.outputStream().use { dst -> src.copyTo(dst) }
        }
        return f
    }

    @Test
    fun `epub3 parses nav xhtml toc with nesting`() {
        val book = EpubParser.parse(fixture("readit_sample.epub"))

        assertEquals("目录测试书", book.title)
        assertEquals("OEBPS/content.opf", book.opfPath)
        assertEquals(3, book.spine.size)
        assertEquals(EpubParser.TocSource.NAV_XHTML, book.tocSource)
        assertFalse(book.encrypted)

        // 目录展开顺序：第一章 / 第一节 / 第二节 / 第二章 / 第三章
        assertEquals(5, book.toc.size)
        assertEquals("第一章 开篇", book.toc[0].title)
        assertEquals(0, book.toc[0].depth)
        assertEquals("第一节 起因", book.toc[1].title)
        assertEquals(1, book.toc[1].depth)
        assertEquals("第二节 经过", book.toc[2].title)
        assertEquals(1, book.toc[2].depth)
        assertEquals("第二章 发展", book.toc[3].title)
        assertEquals(0, book.toc[3].depth)
        assertEquals("第三章 结局", book.toc[4].title)
        assertEquals(0, book.toc[4].depth)

        // 目录 → spine 下标（带 fragment 的一并归并到 chap01）
        assertEquals(0, book.toc[0].spineIndex)
        assertEquals(0, book.toc[1].spineIndex)
        assertEquals(0, book.toc[2].spineIndex)
        assertEquals(1, book.toc[3].spineIndex)
        assertEquals(2, book.toc[4].spineIndex)

        // href 已解析为 zip 根相对路径并保留 fragment
        assertEquals("OEBPS/chap01.xhtml#s1", book.toc[1].href)
        assertEquals("OEBPS/chap03.xhtml", book.toc[4].href)
        assertEquals("OEBPS/chap01.xhtml", book.spine[0].href)
    }

    @Test
    fun `epub2 falls back to ncx`() {
        val book = EpubParser.parse(fixture("readit_sample_ncx.epub"))

        assertEquals(EpubParser.TocSource.NCX, book.tocSource)
        assertEquals(4, book.toc.size)
        assertEquals("第一章 开篇", book.toc[0].title)
        assertEquals(0, book.toc[0].depth)
        assertEquals("第一节 起因", book.toc[1].title)
        assertEquals(1, book.toc[1].depth)
        assertEquals(0, book.toc[1].spineIndex)
        assertEquals("OEBPS/chap01.xhtml#s1", book.toc[1].href)
    }

    @Test
    fun `missing toc document falls back to spine order`() {
        val book = EpubParser.parse(fixture("readit_broken.epub"))

        assertEquals(EpubParser.TocSource.SPINE, book.tocSource)
        assertEquals(2, book.spine.size)
        assertEquals(2, book.toc.size)
        assertTrue(book.warnings.any { it.contains("no toc document") })
        // 兜底目录必须可跳转
        assertTrue(book.toc.all { it.spineIndex >= 0 })
    }

    @Test
    fun `encryption xml marks book as drm`() {
        val file = File(tmp, "readit_drm.epub")
        ZipOutputStream(FileOutputStream(file)).use { z ->
            fun put(name: String, content: String, stored: Boolean = false) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    val b = content.toByteArray()
                    e.size = b.size.toLong()
                    e.compressedSize = b.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(b) }.value
                }
                z.putNextEntry(e)
                z.write(content.toByteArray())
                z.closeEntry()
            }
            put("mimetype", "application/epub+zip", stored = true)
            put("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>""")
            put("META-INF/encryption.xml", """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData/></encryption>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">x</dc:identifier>
<dc:title>DRM 测试书</dc:title></metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="c1"/></spine></package>""")
        }

        val book = EpubParser.parse(file)
        assertTrue("encryption.xml 应判定为加密", book.encrypted)
        assertEquals("DRM 测试书", book.title)
    }

    @Test
    fun `normalize resolves relative hrefs`() {
        assertEquals("OEBPS/chap01.xhtml", EpubParser.normalize("OEBPS", "chap01.xhtml"))
        assertEquals("OEBPS/chap01.xhtml", EpubParser.normalize("OEBPS", "./chap01.xhtml"))
        // base 语义是「所在目录」
        assertEquals("OEBPS/nav/text/chap01.xhtml", EpubParser.normalize("OEBPS/nav", "text/chap01.xhtml"))
        assertEquals("OEBPS/text/chap01.xhtml", EpubParser.normalize("OEBPS/nav", "../text/chap01.xhtml"))
        assertEquals("OEBPS/chap01.xhtml#s2", EpubParser.normalize("OEBPS", "chap01.xhtml#s2"))
        assertEquals("OEBPS/my file.xhtml", EpubParser.normalize("OEBPS", "my%20file.xhtml"))
        assertEquals("OEBPS/a+b.xhtml", EpubParser.normalize("OEBPS", "a+b.xhtml"))
        assertEquals("chap01.xhtml", EpubParser.normalize("OEBPS", "/chap01.xhtml"))
    }

    @Test
    fun `parentOf handles root level opf`() {
        assertEquals("OEBPS", EpubParser.parentOf("OEBPS/content.opf"))
        assertEquals("", EpubParser.parentOf("content.opf"))
    }
}

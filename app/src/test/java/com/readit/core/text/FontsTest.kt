package com.readit.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FontsTest {

    // ---------------------------------------------------------------- 内置字体

    @Test
    fun builtinOrderIsStableAndDefaultFirst() {
        assertEquals(
            listOf("default", "serif", "mono", "light", "pkg:noto-serif-sc", "pkg:lxgw-wenkai"),
            Fonts.builtinIds()
        )
    }

    // ---------------------------------------------------------------- 内置打包字体

    @Test
    fun packagedFontsHaveAssetAndQuotedCss() {
        assertEquals("fonts/readit_font_notoserif_sc.otf", Fonts.assetPath(Fonts.PKG_SERIF_SC))
        assertEquals("fonts/readit_font_lxgw_wenkai.ttf", Fonts.assetPath(Fonts.PKG_WENKAI))
        // CSS family 名带空格，必须加引号，否则 epub 里会被解析成多个 family
        assertEquals("'ReadIt Serif SC'", Fonts.cssFamily(Fonts.PKG_SERIF_SC))
        assertEquals("'ReadIt WenKai'", Fonts.cssFamily(Fonts.PKG_WENKAI))
    }

    @Test
    fun systemFamiliesAreNotPackaged() {
        assertEquals(null, Fonts.assetPath("serif"))
        assertEquals(null, Fonts.assetPath("default"))
        assertFalse(Fonts.isPackaged("serif"))
        assertTrue(Fonts.isPackaged(Fonts.PKG_WENKAI))
        assertFalse(Fonts.isPackaged(Fonts.userId("a.ttf")))
    }

    @Test
    fun packagedFontsSurviveSanitizeWithoutDiskScan() {
        // 打包字体跟着 APK 走，不需要（也没有）"文件还在吗"的检查
        assertEquals(Fonts.PKG_SERIF_SC, Fonts.sanitize(Fonts.PKG_SERIF_SC, emptyList()))
        assertEquals(Fonts.PKG_WENKAI, Fonts.sanitize(Fonts.PKG_WENKAI, emptyList()))
    }

    @Test
    fun packagedFontsAreNotMarkedTxtOnly() {
        // 「仅 TXT」只针对用户字体：打包字体在 EPUB 里靠预置 @font-face 也能用
        assertFalse(Fonts.isTxtOnly(Fonts.PKG_SERIF_SC))
        assertTrue(Fonts.isTxtOnly(Fonts.userId("a.ttf")))
    }

    @Test
    fun cssFamilyFallsBackToSansForUnknownId() {
        assertEquals("sans-serif", Fonts.cssFamily("default"))
        assertEquals("serif", Fonts.cssFamily("serif"))
        assertEquals("monospace", Fonts.cssFamily("mono"))
        // 未知 id 不能产生非法 CSS，一律回退
        assertEquals("sans-serif", Fonts.cssFamily("nope"))
        assertEquals("sans-serif", Fonts.cssFamily(null))
    }

    // ---------------------------------------------------------------- 用户字体

    @Test
    fun userIdRoundTrip() {
        val id = Fonts.userId("Kai.ttf")
        assertTrue(Fonts.isUser(id))
        assertEquals("Kai.ttf", Fonts.userFileName(id))
    }

    @Test
    fun userFileNameRejectsNonUserAndEmptyName() {
        assertNull(Fonts.userFileName("serif"))
        assertNull(Fonts.userFileName(Fonts.USER_PREFIX))
        assertTrue(Fonts.isUser(Fonts.USER_PREFIX + "a.ttf"))
        assertFalse(Fonts.isUser("serif"))
        assertFalse(Fonts.isUser(null))
    }

    @Test
    fun userDisplayNameStripsExtension() {
        assertEquals("Kai", Fonts.userDisplayName("Kai.ttf"))
        assertEquals("Song", Fonts.userDisplayName("Song.otf"))
        // 无扩展名/点开头：原样返回，不能崩
        assertEquals("NoExt", Fonts.userDisplayName("NoExt"))
        assertEquals(".ttf", Fonts.userDisplayName(".ttf"))
    }

    @Test
    fun cssFamilyQuotesUserFontName() {
        // 文件名里有空格/连字符，不加引号会破坏 CSS
        assertEquals("'My Font.ttf'", Fonts.cssFamily(Fonts.userId("My Font.ttf")))
        assertEquals("'a-b.ttf'", Fonts.cssFamily(Fonts.userId("a-b.ttf")))
    }

    @Test
    fun userFontIsTxtOnly() {
        assertTrue(Fonts.isTxtOnly(Fonts.userId("a.ttf")))
        assertFalse(Fonts.isTxtOnly("serif"))
    }

    // ---------------------------------------------------------------- 校正

    @Test
    fun sanitizeKeepsBuiltinAndKnownUserFont() {
        assertEquals("serif", Fonts.sanitize("serif", emptyList()))
        val id = Fonts.userId("a.ttf")
        assertEquals(id, Fonts.sanitize(id, listOf(id)))
    }

    @Test
    fun sanitizeFallsBackWhenFontFileGone() {
        // 字体文件被删：不能让阅读页拿着一个不存在的 id 去要 Typeface
        assertEquals(Fonts.ID_DEFAULT, Fonts.sanitize(Fonts.userId("gone.ttf"), emptyList()))
        assertEquals(Fonts.ID_DEFAULT, Fonts.sanitize(Fonts.userId("gone.ttf"), listOf(Fonts.userId("other.ttf"))))
    }

    @Test
    fun sanitizeHandlesBlankAndJunk() {
        assertEquals(Fonts.ID_DEFAULT, Fonts.sanitize(null))
        assertEquals(Fonts.ID_DEFAULT, Fonts.sanitize(""))
        assertEquals(Fonts.ID_DEFAULT, Fonts.sanitize("../../etc/passwd"))
    }

    // ---------------------------------------------------------------- 字号

    @Test
    fun fontSizeIsClamped() {
        assertEquals(10f, Fonts.clampFontSizeSp(1f), 0f)
        assertEquals(20f, Fonts.clampFontSizeSp(99f), 0f)
        assertEquals(14f, Fonts.clampFontSizeSp(14f), 0f)
        // NaN 不能污染 prefs（会让 textSize 变成 NaN，画布直接空白）
        assertEquals(Fonts.DEFAULT_SIZE_SP, Fonts.clampFontSizeSp(Float.NaN), 0f)
    }

    @Test
    fun defaultFontSizeIsInsideRange() {
        assertTrue(Fonts.DEFAULT_SIZE_SP >= Fonts.MIN_FONT_SP)
        assertTrue(Fonts.DEFAULT_SIZE_SP <= Fonts.MAX_FONT_SP)
    }
}

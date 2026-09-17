package com.readit.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.data.prefs.ReadItPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 配置备份 / 恢复回归（Phase 4）。
 *
 * 用 Robolectric 拿真实 SharedPreferences；每个用例一份独立的 prefs 文件，
 * 并且**直接构造** [ReadItPrefs]（它是个带缓存的单例，走 get() 会跨用例串味）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ConfigBackupTest {

    private lateinit var ctx: Context
    private var seq = 0

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
    }

    private fun freshPrefs(): ReadItPrefs {
        seq++
        return ReadItPrefs(ctx.getSharedPreferences("cfg_test_$seq", Context.MODE_PRIVATE))
    }

    // ---------------------------------------------------------------- 往返

    @Test
    fun `snapshot round trips through json`() {
        val prefs = freshPrefs()
        prefs.perfTier = PerfTier.STANDARD
        prefs.refreshMode = RefreshMode.REGAL
        prefs.fontSizeSp = 18f
        prefs.lineSpacing = 1.6f
        prefs.marginDp = 20
        prefs.pdfCropEnabled = false
        prefs.scanSamplePages = 5
        prefs.scanMinCharsPerPage = 42
        prefs.scanMinImagePageRatio = 0.7f
        prefs.webDavUrl = "http://192.168.1.10:5005/webdav"
        prefs.webDavUser = "reader"
        prefs.webDavDir = "/Books"
        prefs.putKeyMap(92, "PREV_PAGE")
        prefs.putKeyMap(93, "NEXT_PAGE")

        val restored = ConfigBackup.parse(
            ConfigBackup.toJson(ConfigBackup.snapshot(prefs, "1.0.0-dev"))
        )

        assertEquals(PerfTier.STANDARD, PerfTier.from(restored.perfTier))
        assertEquals(RefreshMode.REGAL, RefreshMode.from(restored.refreshMode))
        assertEquals(18f, restored.fontSizeSp, 0.0001f)
        assertEquals(1.6f, restored.lineSpacing, 0.0001f)
        assertEquals(20, restored.marginDp)
        assertFalse(restored.pdfCrop)
        assertEquals(5, restored.scanSamplePages)
        assertEquals(42, restored.scanMinCharsPerPage)
        assertEquals(0.7f, restored.scanMinImagePageRatio, 0.0001f)
        assertEquals("http://192.168.1.10:5005/webdav", restored.webDavUrl)
        assertEquals("reader", restored.webDavUser)
        assertEquals("/Books", restored.webDavDir)
        assertEquals(mapOf("92" to "PREV_PAGE", "93" to "NEXT_PAGE"), restored.keyMaps)
        assertEquals("1.0.0-dev", restored.appVersion)
    }

    @Test
    fun `integer preferences survive the json round trip as integers`() {
        // 这正是当初不用 Map<String, Any?> 直接 dump 的原因：
        // 那样走一趟 JSON 之后 Int 会变成 Double，恢复时只能靠猜
        val prefs = freshPrefs()
        prefs.scanSamplePages = 7
        prefs.scanMinCharsPerPage = 55
        prefs.fontSizeSp = 18f
        val snapshot = ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(prefs, "v")))

        assertEquals(7, snapshot.scanSamplePages)
        assertEquals(55, snapshot.scanMinCharsPerPage)
        assertEquals(18f, snapshot.fontSizeSp, 0.0001f)
    }

    // ---------------------------------------------------------------- 安全

    @Test
    fun `export never contains the webdav password`() {
        val prefs = freshPrefs()
        prefs.webDavUrl = "https://dav.example.com/dav"
        prefs.webDavUser = "reader"
        prefs.webDavPassword = "sup3r-secret-token"

        val json = ConfigBackup.toJson(ConfigBackup.snapshot(prefs, "v"))

        assertFalse("备份文件不能带密码", json.contains("sup3r-secret-token"))
        assertFalse(json.contains("password", ignoreCase = true))
        assertTrue(json.contains("reader"))
    }

    @Test
    fun `import keeps the existing password untouched`() {
        val source = freshPrefs()
        source.fontSizeSp = 20f
        val target = freshPrefs()
        target.webDavPassword = "keep-me"

        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))

        assertEquals("keep-me", target.webDavPassword)
        assertEquals(20f, target.fontSizeSp, 0.0001f)
    }

    // ---------------------------------------------------------------- 校验

    @Test
    fun `parse rejects blank content`() {
        assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse("   ") }
    }

    @Test
    fun `parse rejects non json content`() {
        assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse("not json at all") }
    }

    @Test
    fun `parse rejects foreign format`() {
        val json = """{"format":"other-app","version":1}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse(json) }
        assertTrue(e.message.orEmpty().contains("不是 ReadIt 配置文件"))
    }

    @Test
    fun `parse rejects newer version`() {
        val json = """{"format":"readit-config","version":${ConfigBackup.VERSION + 1}}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ConfigBackup.parse(json) }
        assertTrue(e.message.orEmpty().contains("升级应用"))
    }

    @Test
    fun `parse accepts same version`() {
        val json = """{"format":"readit-config","version":${ConfigBackup.VERSION}}"""
        assertEquals(ConfigBackup.VERSION, ConfigBackup.parse(json).version)
    }

    // ---------------------------------------------------------------- 恢复语义

    @Test
    fun `restore replaces key maps instead of merging`() {
        val source = freshPrefs()
        source.putKeyMap(66, "NEXT_PAGE")

        val target = freshPrefs()
        target.putKeyMap(111, "BACK")
        target.putKeyMap(66, "MENU")

        val n = ConfigBackup.restore(
            target,
            ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v")))
        )

        assertEquals(1, n)
        assertNull("上一台设备残留的映射必须被清掉", target.getKeyMap(111))
        assertEquals("NEXT_PAGE", target.getKeyMap(66))
    }

    @Test
    fun `restore with empty key maps clears existing ones`() {
        val target = freshPrefs()
        target.putKeyMap(111, "BACK")

        val n = ConfigBackup.restore(target, ConfigBackup.snapshot(freshPrefs(), "v"))

        assertEquals(0, n)
        assertNull(target.getKeyMap(111))
    }

    @Test
    fun `snapshot defaults match a fresh install`() {
        val snapshot = ConfigBackup.snapshot(freshPrefs(), "v")
        assertEquals(PerfTier.AUTO.key, snapshot.perfTier)
        assertEquals(RefreshMode.AUTO.key, snapshot.refreshMode)
        assertEquals(ReadItPrefs.DEFAULT_FONT_SIZE_SP, snapshot.fontSizeSp, 0.0001f)
        assertEquals(ReadItPrefs.DEFAULT_WEBDAV_DIR, snapshot.webDavDir)
        assertTrue(snapshot.keyMaps.isEmpty())
    }

    @Test
    fun `summary mentions the main knobs`() {
        val s = ConfigBackup.snapshot(freshPrefs(), "v")
        assertTrue(s.summary().contains("档位"))
        assertTrue(s.summary().contains("按键映射"))
    }

    // ---------------------------------------------------------------- 书籍目录

    @Test
    fun `books dir round trips through json`() {
        val prefs = freshPrefs()
        prefs.booksDir = "/storage/emulated/0/ReadIt"
        val restored = ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(prefs, "v")))
        assertEquals("/storage/emulated/0/ReadIt", restored.booksDir)
    }

    @Test
    fun `restore of an existing books dir is applied`() {
        val source = freshPrefs()
        source.booksDir = ctx.cacheDir.absolutePath
        val target = freshPrefs()
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals(ctx.cacheDir.absolutePath, target.booksDir)
    }

    @Test
    fun `restore skips a books dir that does not exist`() {
        // 备份可能来自另一台设备，路径未必存在；写进配置会让书架静默变空
        val source = freshPrefs()
        source.booksDir = "/no/such/dir/readit-xyz"
        val target = freshPrefs()
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals("", target.booksDir)
    }
}

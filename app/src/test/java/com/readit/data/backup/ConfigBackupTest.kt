package com.readit.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readit.core.display.RenderMode
import com.readit.core.eal.PerfTier
import com.readit.core.eal.RefreshMode
import com.readit.data.prefs.ReadItPrefs
import java.io.File
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
        // 判据是「存在且非空」，所以测试目录里必须真有书 —— 否则这个用例测不出任何东西
        File(ctx.cacheDir, "book.txt").writeText("x")
        val target = freshPrefs()
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals(ctx.cacheDir.absolutePath, target.booksDir)
    }

    /**
     * 同名**空**目录必须被拒绝。
     *
     * 原来只查 `isDirectory`，于是换机残留 / 外置卡挂载点上恰好存在的空目录会被接受 ——
     * 而 `listBooks` 只列这个目录，用户看到的是**空书架**，书还躺在旧目录里，
     * 且全程没有任何提示。这条用例把该场景钉住。
     */
    @Test
    fun `restore skips an existing but empty books dir`() {
        val empty = File(ctx.cacheDir, "empty_books").apply { mkdirs() }
        assertTrue(empty.listFiles()?.isEmpty() == true)
        val source = freshPrefs()
        source.booksDir = empty.absolutePath
        val target = freshPrefs()
        target.booksDir = "/some/existing/dir"
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals("/some/existing/dir", target.booksDir)
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

    // ------------------------------------------------- 设备属性守卫（回归）

    /**
     * 「恢复配置」**不能**把守卫自己烧掉。
     *
     * 原来的判据是 `sp.contains(值键)`，而 restore 的赋值分支恰好创建这个键 ——
     * 于是「恢复一次」之后 `hasExplicitPerfTier` 永久为 true，第二次起从任何设备导入
     * 档位/刷新/渲染模式全被拒。这正是 DeviceScopedConfig 文档开头描述的
     * 「慢机档位盖到快机」事故，只是被推迟到第二次恢复。
     *
     * `DeviceScopedConfigTest` 只测纯函数 `keepLocal`，测不出这个交互；
     * 这里用真实 SharedPreferences 走两轮 restore 才钉得住。
     */
    @Test
    fun `连续两次 restore 都能接受设备属性`() {
        val slow = freshPrefs()
        slow.perfTier = PerfTier.FALLBACK
        val slowSnapshot = ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(slow, "v")))
        val fast = freshPrefs()
        val fastSnapshot = ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(fast, "v")))

        // 第一轮：目标机没设过 → 接受备份值，但**不得**因此变成「显式设置过」
        ConfigBackup.restore(fast, slowSnapshot)
        assertEquals(PerfTier.FALLBACK, fast.perfTier)
        assertFalse(
            "配置恢复自身不应被记为用户显式设置",
            fast.hasExplicitPerfTier
        )

        // 第二轮：仍应可接受 —— 这是修复前会挂掉的那一步
        ConfigBackup.restore(fast, fastSnapshot)
        assertEquals(PerfTier.AUTO, fast.perfTier)
    }

    @Test
    fun `用户手动设过档位时 restore 保留本机值`() {
        val target = freshPrefs()
        // 用户在设置页主动选过：这是显式决策
        target.perfTier = PerfTier.ENHANCED
        val source = freshPrefs()
        source.perfTier = PerfTier.FALLBACK
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals(PerfTier.ENHANCED, target.perfTier)
    }

    /** 同一条纪律适用于刷新模式与渲染模式（三件套） */
    @Test
    fun `渲染模式同样不被连续 restore 烧掉守卫`() {
        val source = freshPrefs()
        source.renderMode = RenderMode.SHARP
        val target = freshPrefs()
        ConfigBackup.restore(target, ConfigBackup.parse(ConfigBackup.toJson(ConfigBackup.snapshot(source, "v"))))
        assertEquals(RenderMode.SHARP, target.renderMode)
        assertFalse(target.hasExplicitRenderMode)
    }
}

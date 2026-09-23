package com.readit.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3：WebDAV 双向同步的决策矩阵（F13 / R19）。
 *
 * 同步最怕的不是网络失败，而是「判断错了谁更新」导致覆盖别人的修改。
 * 所以这里把 [SyncDecider] 的每条分支都钉死，特别是：
 *  - 没有基线记录时**必须**判冲突，而不是默认下载（那会静默覆盖本地）
 *  - ETag 优先于 Last-Modified，Last-Modified 优先于体积
 *  - 三条链都断时判「变了」，宁可多同步一次也不漏
 */
class SyncDeciderTest {

    private fun record(
        etag: String? = "\"v1\"",
        lastModified: String? = "Mon, 01 Sep 2026 00:00:00 GMT",
        remoteSize: Long = 1000L,
        localSize: Long = 1000L,
        localLastModified: Long = 100L
    ) = SyncRecord(
        href = "/ReadIt/book.epub",
        etag = etag,
        lastModified = lastModified,
        remoteSize = remoteSize,
        localSize = localSize,
        localLastModified = localLastModified
    )

    @Test
    fun `one sided files route to download or upload`() {
        assertEquals(SyncAction.DOWNLOAD_NEW, SyncDecider.remoteOnly().action)
        assertEquals(SyncAction.UPLOAD_NEW, SyncDecider.localOnly().action)
    }

    @Test
    fun `missing baseline is a conflict not a silent download`() {
        val d = SyncDecider.both(
            RemoteMeta("\"x\"", "Mon, 01 Sep 2026 00:00:00 GMT", 1000L),
            LocalMeta(1000L, 100L),
            null
        )
        assertEquals(SyncAction.CONFLICT, d.action)
    }

    @Test
    fun `identical fingerprints are up to date`() {
        val d = SyncDecider.both(
            RemoteMeta("\"v1\"", "Mon, 01 Sep 2026 00:00:00 GMT", 1000L),
            LocalMeta(1000L, 100L),
            record()
        )
        assertEquals(SyncAction.UP_TO_DATE, d.action)
    }

    @Test
    fun `remote changed only leads to download`() {
        val d = SyncDecider.both(
            RemoteMeta("\"v2\"", "Tue, 02 Sep 2026 00:00:00 GMT", 1200L),
            LocalMeta(1000L, 100L),
            record()
        )
        assertEquals(SyncAction.DOWNLOAD_UPDATE, d.action)
    }

    @Test
    fun `local changed only leads to upload`() {
        val d = SyncDecider.both(
            RemoteMeta("\"v1\"", "Mon, 01 Sep 2026 00:00:00 GMT", 1000L),
            LocalMeta(1500L, 200L),
            record()
        )
        assertEquals(SyncAction.UPLOAD_UPDATE, d.action)
    }

    @Test
    fun `both changed is a conflict`() {
        val d = SyncDecider.both(
            RemoteMeta("\"v2\"", "Tue, 02 Sep 2026 00:00:00 GMT", 1200L),
            LocalMeta(1500L, 200L),
            record()
        )
        assertEquals(SyncAction.CONFLICT, d.action)
        assertTrue(d.reason.contains("both"))
    }

    // ------------------------------------------------------------ 兜底链

    @Test
    fun `etag wins over last modified and size`() {
        val rec = record(etag = "\"v1\"", lastModified = "OLD", remoteSize = 1000L)
        // ETag 相同 -> 认为没变，即便 Last-Modified / 体积都不一样
        assertFalse(
            SyncDecider.remoteChanged(
                RemoteMeta("\"v1\"", "NEW", 9999L), rec
            )
        )
        // ETag 不同 -> 认为变了
        assertTrue(
            SyncDecider.remoteChanged(
                RemoteMeta("\"v2\"", "OLD", 1000L), rec
            )
        )
    }

    @Test
    fun `last modified is used when etag missing`() {
        val rec = record(etag = null, lastModified = "Mon, 01 Sep 2026 00:00:00 GMT")
        assertFalse(SyncDecider.remoteChanged(RemoteMeta(null, "Mon, 01 Sep 2026 00:00:00 GMT", 1000L), rec))
        assertTrue(SyncDecider.remoteChanged(RemoteMeta(null, "Wed, 03 Sep 2026 00:00:00 GMT", 1000L), rec))
    }

    @Test
    fun `size is the last resort`() {
        val rec = record(etag = null, lastModified = null, remoteSize = 1000L)
        assertFalse(SyncDecider.remoteChanged(RemoteMeta(null, null, 1000L), rec))
        assertTrue(SyncDecider.remoteChanged(RemoteMeta(null, null, 1001L), rec))
    }

    @Test
    fun `when all three signals are absent assume changed`() {
        val rec = record(etag = null, lastModified = null, remoteSize = -1L)
        assertTrue(SyncDecider.remoteChanged(RemoteMeta(null, null, -1L), rec))
    }

    @Test
    fun `partial etag availability falls through to last modified`() {
        // 本地有 ETag、服务端这次没返回 -> 不能判「没变」，继续比 Last-Modified
        val rec = record(etag = "\"v1\"", lastModified = "A")
        assertFalse(SyncDecider.remoteChanged(RemoteMeta(null, "A", 1000L), rec))
        assertTrue(SyncDecider.remoteChanged(RemoteMeta(null, "B", 1000L), rec))
    }

    // ---------------- ETag 归一化（真机 E2E 才暴露的坑） ----------------

    @Test
    fun `etag quoted by response header equals unquoted one from PROPFIND`() {
        // 还原真机现场：基线来自 PUT/GET 响应头（带引号），
        // 远端来自 PROPFIND <getetag>（wsgidav 不给引号）。
        // 不归一化 -> 判「远端变了」-> 每轮同步重下整库，永远收敛不了。
        val value = "ec716e12ee50809ee8ef92cb5f50c797-1790127075-43598"
        val rec = record(etag = "\"$value\"")
        assertFalse(SyncDecider.remoteChanged(RemoteMeta(value, null, 43598L), rec))
        // 反向也要成立（基线来自 PROPFIND、远端给了带引号的响应头）
        val recUnquoted = record(etag = value)
        assertFalse(SyncDecider.remoteChanged(RemoteMeta("\"$value\"", null, 43598L), recUnquoted))
        // 真换了版本仍必须判「变了」
        assertTrue(SyncDecider.remoteChanged(RemoteMeta("$value-x", null, 43598L), rec))
    }

    @Test
    fun `weak etag prefix is ignored when comparing`() {
        val rec = record(etag = "W/\"v9\"")
        assertFalse(SyncDecider.remoteChanged(RemoteMeta("\"v9\"", null, 1000L), rec))
    }

    @Test
    fun `normalize etag strips wrapper forms and treats blanks as absent`() {
        assertEquals("abc", normalizeEtag("\"abc\""))
        assertEquals("abc", normalizeEtag("abc"))
        assertEquals("abc", normalizeEtag("W/\"abc\""))
        assertEquals("abc", normalizeEtag("  \"abc\"  "))
        assertEquals(null, normalizeEtag(null))
        assertEquals(null, normalizeEtag(""))
        assertEquals(null, normalizeEtag("   "))
        assertEquals(null, normalizeEtag("\"\""))
    }

    @Test
    fun `etag that only differs by quoting is up to date end to end`() {
        // 端到端语义：两侧同名文件、本地未改、远端 etag 只是少了引号 -> UP_TO_DATE
        val rec = record(etag = "\"v1\"", localSize = 1000L, localLastModified = 100L)
        val d = SyncDecider.both(
            RemoteMeta("v1", "Mon, 01 Sep 2026 00:00:00 GMT", 1000L),
            LocalMeta(1000L, 100L),
            rec
        )
        assertEquals(SyncAction.UP_TO_DATE, d.action)
    }
}

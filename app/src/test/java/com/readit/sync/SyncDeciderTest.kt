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
}

package com.readit.data.storage

import com.readit.data.storage.BooksDirPolicy.Probe
import com.readit.data.storage.BooksDirPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 书籍目录校验策略回归。
 *
 * 每个判定分支都要有一例（这类"确认前校验"的 bug 全都是某个分支漏判/误判）。
 */
class BooksDirPolicyTest {

    private fun probe(
        exists: Boolean = true,
        isDir: Boolean = true,
        canRead: Boolean = true,
        canWrite: Boolean = true,
        children: Int = 0,
    ) = Probe(exists, isDir, canRead, canWrite, children)

    @Test
    fun `empty writable dir passes`() {
        assertEquals(Verdict.OK, BooksDirPolicy.evaluate(probe(), isCurrent = false))
    }

    @Test
    fun `missing dir rejected`() {
        assertEquals(
            Verdict.NOT_EXIST,
            BooksDirPolicy.evaluate(probe(exists = false, isDir = false, canRead = false, canWrite = false), false)
        )
    }

    @Test
    fun `file instead of dir rejected`() {
        assertEquals(Verdict.NOT_DIR, BooksDirPolicy.evaluate(probe(isDir = false), false))
    }

    @Test
    fun `read only dir rejected`() {
        assertEquals(Verdict.NOT_WRITABLE, BooksDirPolicy.evaluate(probe(canWrite = false), false))
    }

    @Test
    fun `unreadable dir rejected`() {
        assertEquals(Verdict.NOT_READABLE, BooksDirPolicy.evaluate(probe(canRead = false), false))
    }

    @Test
    fun `non empty dir rejected when not current`() {
        assertEquals(Verdict.NOT_EMPTY, BooksDirPolicy.evaluate(probe(children = 3), isCurrent = false))
    }

    @Test
    fun `non empty dir allowed when it is the current dir`() {
        // 已导入过书的当前目录，重选时不能被"非空"卡死
        assertEquals(Verdict.OK, BooksDirPolicy.evaluate(probe(children = 42), isCurrent = true))
    }

    @Test
    fun `existence takes precedence over other problems`() {
        assertEquals(
            Verdict.NOT_EXIST,
            BooksDirPolicy.evaluate(probe(exists = false, isDir = false, children = 9), false)
        )
    }

    @Test
    fun `not a dir takes precedence over write problem`() {
        assertEquals(Verdict.NOT_DIR, BooksDirPolicy.evaluate(probe(isDir = false, canWrite = false), false))
    }
}

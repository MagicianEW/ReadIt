package com.readit.data.stats

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * 单本书的阅读统计（F26）。
 *
 * `bookId` 是**文件名**（与 [com.readit.data.ProgressStore] 同口径），
 * 所以重命名要搬家、删除要清理 —— 与进度 / 编码记忆是同一套纪律。
 */
data class BookStats(
    val title: String = "",
    val openCount: Int = 0,
    val totalMs: Long = 0L,
    val pageTurns: Int = 0,
    val lastReadAt: Long = 0L,
    /** 最近读到的位置（自绘画布口径的字符偏移），用于「读到哪了」 */
    val charOffset: Int = 0,
    /** 最近一次打开时的总页数 */
    val totalPages: Int = 0
)

/**
 * 统计文件的整体结构。
 *
 * 取舍：**总时长这类全局量单独存，不靠 books 求和**。
 * 书被删掉时 per-book 记录要清（否则同名新书会继承旧书的时长），
 * 但「我总共读了多少小时」不该因为你删了一本书就凭空缩水。
 */
data class ReadingStatsFile(
    val version: Int = ReadingStats.VERSION,
    val books: Map<String, BookStats> = emptyMap(),
    val totalOpenCount: Int = 0,
    val totalMs: Long = 0L,
    val totalPageTurns: Int = 0,
    val firstReadAt: Long = 0L
)

/**
 * 阅读统计的纯函数部分（F26）。
 *
 * 刻意不依赖 Android 类型：合并、裁剪、时长分档都是最容易算错又最难在真机上
 * 观察的地方（少算一次会话、多算一次翻页，界面上完全看不出来），必须能在 JVM 上锁住。
 */
object ReadingStats {

    const val VERSION = 1

    /**
     * 短于这个时长的会话不计入。
     *
     * 打开一本书再立刻返回（误触、看错了书）不应该计一次「打开」，
     * 更不该在累计时长里塞进一堆毫秒级样本把平均值拉花。
     */
    const val MIN_SESSION_MS = 1_000L

    /** 一次阅读会话，由调用方在 onPause 时结算 */
    data class Session(
        val bookId: String,
        val title: String,
        val durationMs: Long,
        val pageTurns: Int,
        val charOffset: Int,
        val totalPages: Int,
        val endedAt: Long
    )

    /** 时长分档，UI 侧再映射到字符串资源（纯函数不碰资源） */
    sealed class Duration {
        data class HoursMinutes(val hours: Int, val minutes: Int) : Duration()
        data class MinutesSeconds(val minutes: Int, val seconds: Int) : Duration()
        data class Seconds(val seconds: Int) : Duration()
    }

    fun duration(ms: Long): Duration {
        val total = (ms.coerceAtLeast(0L)) / 1000
        val h = (total / 3600).toInt()
        val m = ((total % 3600) / 60).toInt()
        val s = (total % 60).toInt()
        return when {
            h > 0 -> Duration.HoursMinutes(h, m)
            m > 0 -> Duration.MinutesSeconds(m, s)
            else -> Duration.Seconds(s)
        }
    }

    /**
     * 把一次会话并进统计。
     *
     * 过短会话直接原样返回（不算打开、不算时长、不算翻页），
     * 这样调用方不需要自己去判断「这次算不算数」。
     */
    fun apply(file: ReadingStatsFile, s: Session): ReadingStatsFile {
        if (s.durationMs < MIN_SESSION_MS) return file
        val prev = file.books[s.bookId]
        val merged = BookStats(
            title = s.title.ifBlank { prev?.title.orEmpty() },
            openCount = (prev?.openCount ?: 0) + 1,
            totalMs = (prev?.totalMs ?: 0L) + s.durationMs,
            pageTurns = (prev?.pageTurns ?: 0) + s.pageTurns.coerceAtLeast(0),
            lastReadAt = s.endedAt,
            charOffset = if (s.charOffset > 0) s.charOffset else (prev?.charOffset ?: 0),
            totalPages = if (s.totalPages > 0) s.totalPages else (prev?.totalPages ?: 0)
        )
        return file.copy(
            books = file.books + (s.bookId to merged),
            totalOpenCount = file.totalOpenCount + 1,
            totalMs = file.totalMs + s.durationMs,
            totalPageTurns = file.totalPageTurns + s.pageTurns.coerceAtLeast(0),
            firstReadAt = if (file.firstReadAt == 0L) s.endedAt else file.firstReadAt
        )
    }

    /** 按累计时长取前 N 本（同长按时长、再按打开次数稳定排序） */
    fun topBooks(file: ReadingStatsFile, limit: Int): List<Pair<String, BookStats>> =
        file.books.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, BookStats>> { it.value.totalMs }
                    .thenByDescending { it.value.openCount }
                    .thenBy { it.key }
            )
            .take(limit.coerceAtLeast(0))
            .map { it.key to it.value }

    /** 书被删除：清掉 per-book 记录（全局累计保留） */
    fun without(file: ReadingStatsFile, bookId: String): ReadingStatsFile =
        if (!file.books.containsKey(bookId)) file else file.copy(books = file.books - bookId)

    /** 书被重命名：per-book 记录搬到新名下，两个名字 sanitize 后撞车时不搬（同 ProgressStore） */
    fun renamed(file: ReadingStatsFile, fromId: String, toId: String): ReadingStatsFile {
        if (fromId == toId) return file
        val src = file.books[fromId] ?: return file
        return file.copy(books = (file.books - fromId) + (toId to src.copy(title = toId)))
    }
}

/**
 * 统计的持久化（F26）。
 *
 * 存 `filesDir/stats/stats.json`；写盘口径与 [com.readit.data.ProgressStore] 一致
 * （临时文件 + rename，失败再退直写），不能因为一次写失败把整份统计清零。
 */
object ReadingStatsStore {

    private const val DIR = "stats"
    private const val FILE_NAME = "stats.json"

    private val gson = Gson()

    @Synchronized
    fun load(context: Context): ReadingStatsFile {
        val f = file(context)
        if (!f.exists()) return ReadingStatsFile()
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), ReadingStatsFile::class.java) ?: ReadingStatsFile()
        } catch (e: Exception) {
            ReadItLog.w("stats load failed: ${e.message}")
            ReadingStatsFile()
        }
    }

    @Synchronized
    fun save(context: Context, file: ReadingStatsFile) {
        val f = file(context)
        try {
            f.parentFile?.mkdirs()
            val json = gson.toJson(file)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                ReadItLog.w("stats rename failed, falling back to direct write")
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ReadItLog.w("stats save failed: ${e.message}")
        }
    }

    /** 结算一次会话 */
    @Synchronized
    fun record(context: Context, session: ReadingStats.Session) {
        val before = load(context)
        val after = ReadingStats.apply(before, session)
        if (after === before) return
        save(context, after)
        ReadItLog.i(
            "stats recorded: book=${session.bookId} ms=${session.durationMs} " +
                "turns=${session.pageTurns} totalMs=${after.totalMs}"
        )
    }

    @Synchronized
    fun delete(context: Context, bookId: String) {
        val before = load(context)
        val after = ReadingStats.without(before, bookId)
        if (after !== before) save(context, after)
    }

    @Synchronized
    fun move(context: Context, fromId: String, toId: String) {
        val before = load(context)
        val after = ReadingStats.renamed(before, fromId, toId)
        if (after !== before) save(context, after)
    }

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 单本统计；不存在返回 null */
    fun book(context: Context, bookId: String): BookStats? = load(context).books[bookId]

    private fun file(context: Context) = File(File(context.filesDir, DIR), FILE_NAME)
}

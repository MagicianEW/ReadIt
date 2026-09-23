package com.readit.data

import android.content.Context
import com.google.gson.Gson
import com.readit.core.util.ReadItLog
import java.io.File

/**
 * 一条书签（F25）。
 *
 * 位置口径与 [ReadingPosition] 完全一致：EPUB 完整渲染记 CFI，PDF 记页码，
 * 其余（自绘画布四条路径）记字符偏移。三种坐标只有一种会真正被填上，
 * 由 `cfi` 是否非空 / `pageIndex >= 0` 来区分。
 *
 * 字段保持 public：Gson 反序列化时**不会**应用 Kotlin 的默认值（它绕过构造器，
 * 缺字段一律留 0/null）。所以「写的时候一定写全所有字段」是这套模型的前提 ——
 * [ReadingPosition] 也是同一取舍。私有字段在这里只会让排查变难，没有收益。
 */
data class Bookmark(
    val charOffset: Int = 0,
    val chapterIndex: Int = 0,
    val cfi: String? = null,
    val pageIndex: Int = -1,
    val label: String = "",
    val createdAt: Long = 0L
) {
    companion object {
        fun fromPosition(pos: ReadingPosition, label: String, now: Long): Bookmark =
            Bookmark(
                charOffset = pos.charOffset,
                chapterIndex = pos.chapterIndex,
                cfi = pos.cfi,
                pageIndex = pos.pageIndex,
                label = label,
                createdAt = now
            )
    }
}

/** 单本书的书签集合（F25）。按书存一个文件，与进度 / 统计同一条纪律。 */
data class BookmarkFile(
    val version: Int = Bookmarks.VERSION,
    val items: List<Bookmark> = emptyList()
)

/**
 * 书签的纯函数部分（F25）。
 *
 * 刻意不依赖 Android 类型：去重、裁剪、排序这三件事在真机上「看起来都对」，
 * 但重复项要读到第二次才会发现、丢最早一条要攒够 200 条才暴露 —— 只能靠单测锁住。
 */
object Bookmarks {

    const val VERSION = 1

    /**
     * 每本书的书签上限。
     *
     * 墨水屏翻页慢，一次通读下来书签数不会多；设这个上限是为了防「按住加书签键
     * 连点几百次」把 JSON 撑大、每次开书都多解析几十 KB。
     */
    const val MAX_PER_BOOK = 200

    /**
     * 书签身份。
     *
     * 同一处位置只能有一条 —— 否则用户在同一页连按两次「加书签」会得到两条
     * 一模一样的记录，列表里看着像重复渲染。
     * 精度优先级：CFI（EPUB，段落级）> 页码（PDF）> 字符偏移（自绘画布）。
     */
    fun key(cfi: String?, pageIndex: Int, charOffset: Int): String {
        val c = cfi?.trim().orEmpty()
        return when {
            c.isNotEmpty() -> "cfi:$c"
            pageIndex >= 0 -> "page:$pageIndex"
            else -> "char:$charOffset"
        }
    }

    fun keyOf(bm: Bookmark): String = key(bm.cfi, bm.pageIndex, bm.charOffset)

    /** 当前 [ReadingPosition] 对应的书签身份，供「这一页有没有书签」判断 */
    fun keyOfPosition(pos: ReadingPosition): String = key(pos.cfi, pos.pageIndex, pos.charOffset)

    /**
     * 阅读序坐标：页 > 字符偏移。
     *
     * CFI 是字符串，没法比大小，退化成 0 —— 结果只是 EPUB 书签不按位置排，
     * 仍然全部可见、可跳转（比猜一个错误的顺序安全）。
     */
    private fun order(bm: Bookmark): Int = if (bm.pageIndex >= 0) bm.pageIndex else bm.charOffset

    /**
     * 加一条书签。同位置已存在则**替换**（刷新 label / 时间），不产生重复项。
     *
     * 超过 [MAX_PER_BOOK] 时丢**最早加入**的一条（不是阅读位置最早的）——
     * 用户连点最容易撑爆上限，丢最早加入的那条正是他想反悔的。
     */
    fun add(file: BookmarkFile, bm: Bookmark): BookmarkFile {
        val k = keyOf(bm)
        val kept = file.items.filterNot { keyOf(it) == k }
        val appended = kept + bm
        val trimmed =
            if (appended.size > MAX_PER_BOOK) appended.subList(appended.size - MAX_PER_BOOK, appended.size).toList()
            else appended
        return file.copy(items = trimmed)
    }

    /** 删一条书签；不存在原样返回（避免无谓写盘） */
    fun remove(file: BookmarkFile, key: String): BookmarkFile =
        if (file.items.none { keyOf(it) == key }) file
        else file.copy(items = file.items.filterNot { keyOf(it) == key })

    fun has(file: BookmarkFile, key: String): Boolean = file.items.any { keyOf(it) == key }

    /**
     * 并集合并（F25 多设备同步）。
     *
     * 书签是**增量收藏**，两台设备各加各的应当都留下，所以不适用「谁新谁赢」的整体覆盖：
     * 整体覆盖必然丢掉另一台设备刚加的那几条。同一个位置两边都有时取 `createdAt` 大的
     * （label 以最新那次为准），合并后超过上限按加入时间丢最老的。
     *
     * 已知取舍：**删除不跨设备传播**。做不到「墓碑」而不引入一套新的状态文件，
     * 而墨水屏上误删一张书签的代价（再找回去）远小于书签凭空消失。删除只在本机生效，
     * 直到别的设备把它推回来。这一条写在 [MergePolicy] 的同步说明里。
     */
    fun merge(a: BookmarkFile, b: BookmarkFile): BookmarkFile {
        val byKey = LinkedHashMap<String, Bookmark>()
        for (bm in a.items + b.items) {
            val k = keyOf(bm)
            val old = byKey[k]
            if (old == null || bm.createdAt >= old.createdAt) byKey[k] = bm
        }
        val merged = byKey.values.sortedWith(compareBy({ order(it) }, { it.createdAt }))
        val trimmed =
            if (merged.size > MAX_PER_BOOK) merged.subList(merged.size - MAX_PER_BOOK, merged.size).toList()
            else merged
        return BookmarkFile(items = trimmed)
    }

    /** 按阅读位置升序；同位置按加入时间（稳定，UI 列表不会跳来跳去） */
    fun sorted(file: BookmarkFile): List<Bookmark> =
        file.items.sortedWith(compareBy({ order(it) }, { it.createdAt }))
}

/**
 * 书签持久化（F25）。存 `filesDir/bookmarks/<safe>.json`。
 *
 * 书名 sanitize 规则与 [ProgressStore] 完全一致（非 `[A-Za-z0-9._-]` 一律换 `_`），
 * 这样「两个中文书名撞到同一条记录」的概率与重命名搬家的行为都与进度对齐。
 */
object BookmarkStore {

    private const val DIR = "bookmarks"
    private val gson = Gson()

    fun load(context: Context, bookId: String): BookmarkFile {
        val f = file(context, bookId)
        if (!f.exists()) return BookmarkFile()
        return try {
            gson.fromJson(f.readText(Charsets.UTF_8), BookmarkFile::class.java) ?: BookmarkFile()
        } catch (e: Exception) {
            ReadItLog.w("bookmarks load failed: ${e.message}")
            BookmarkFile()
        }
    }

    fun save(context: Context, bookId: String, file: BookmarkFile) {
        val f = file(context, bookId)
        try {
            f.parentFile?.mkdirs()
            val json = gson.toJson(file)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(f)) {
                ReadItLog.w("bookmarks rename failed, falling back to direct write")
                f.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            ReadItLog.w("bookmarks save failed: ${e.message}")
        }
    }

    /** 书被删除：书签一并清掉，否则同名新书会继承旧书签 */
    fun delete(context: Context, bookId: String) {
        runCatching { file(context, bookId).delete() }
            .onFailure { ReadItLog.w("bookmarks delete failed: ${it.message}") }
    }

    /**
     * 重命名搬家。与 [ProgressStore.move] 同一套纪律：
     * **确认新记录读得回来才删旧的**；两个书名 sanitize 后撞车时直接放弃。
     */
    fun move(context: Context, fromId: String, toId: String) {
        val src = file(context, fromId)
        if (src.absolutePath == file(context, toId).absolutePath) return
        if (!src.exists()) return

        val cur = load(context, fromId)
        if (cur.items.isEmpty()) return
        save(context, toId, cur)
        if (load(context, toId).items.isEmpty()) {
            ReadItLog.w("bookmarks move unverified, keeping old record: $fromId -> $toId")
            return
        }
        src.delete()
    }

    /** 目录，供同步层枚举本机所有书签文件 */
    fun dir(context: Context): File = File(context.filesDir, DIR)

    /** 某本书对应的文件名（同步层要用它拼远端的 `readit_bookmarks_<name>`） */
    fun fileNameFor(bookId: String): String = "${safe(bookId)}.json"

    private fun file(context: Context, bookId: String): File =
        File(dir(context), fileNameFor(bookId))

    private fun safe(bookId: String): String = bookId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

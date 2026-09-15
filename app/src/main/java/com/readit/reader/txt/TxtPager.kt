package com.readit.reader.txt

/**
 * TXT 懒分页（规范 §3.2.1 / §5.1，F01）。
 *
 * 分页容量由渲染层按屏幕尺寸与字号算出（charsPerLine × linesPerPage），
 * 保证「显示」与「进度/总页数」是同一套口径，不会出现页码与内容对不上。
 *
 * 懒分页：页边界按需计算并缓存，不全量预扫描，1GB 设备也能秒开大文件。
 */
class TxtPager(
    private val text: String,
    charsPerLine: Int,
    linesPerPage: Int
) {

    data class Page(val index: Int, val start: Int, val end: Int, val text: String) {
        val length: Int get() = end - start
    }

    /** 单页字符容量估算 */
    val capacity: Int

    init {
        val c = charsPerLine.coerceAtLeast(MIN_CHARS_PER_LINE)
        val l = linesPerPage.coerceAtLeast(1)
        capacity = (c * l).coerceAtLeast(MIN_CAPACITY)
    }

    /** 已计算的页起始偏移（懒构建） */
    private val starts = ArrayList<Int>().apply { add(0) }

    val textLength: Int get() = text.length

    /**
     * 总页数估算（不触发全量分页，用于进度条刻度）。
     * 段落优先断行会让实际填充率低于容量，故按 80% 折算，避免估算明显偏小。
     */
    fun estimatedPageCount(): Int {
        if (text.isEmpty()) return 1
        val eff = (capacity * 0.8f).toInt().coerceAtLeast(1)
        return ((text.length + eff - 1) / eff).coerceAtLeast(1)
    }

    /**
     * 精确总页数（与 page() 完全同一口径）。
     *
     * 会把页起始索引一次性构建到文末并**缓存**在 starts 里，之后各次调用都是 O(1)。
     * 仅供页码/总页数显示使用。
     *
     * 替代此前渲染层的写法 `while (page(n) != null) n++`：
     * 那种写法每数一页都要为那一页 substring 一份正文，且每次回调都从头重算，
     * 等于「翻一页就把整本书重新分页一遍」，大文件上是实打实的卡顿源。
     */
    fun exactPageCount(): Int {
        if (text.isEmpty()) return 1
        var guard = 0
        while (guard++ < MAX_PAGE_GUARD) {
            val last = starts[starts.size - 1]
            if (last >= text.length) break
            val next = nextBoundary(last)
            if (next <= last || next >= text.length) break
            starts.add(next)
        }
        return starts.size.coerceAtLeast(1)
    }

    /** 取第 index 页（按需计算并缓存边界） */
    fun page(index: Int): Page? {
        if (index < 0) return null
        if (text.isEmpty()) return if (index == 0) Page(0, 0, 0, "") else null
        ensureStarts(index)
        if (index >= starts.size) return null
        val start = starts[index]
        if (start >= text.length && index > 0) return null
        val end = resolveEnd(index)
        return Page(index, start, end, text.substring(start, end))
    }

    /** 确保 starts[index] 已存在 */
    private fun ensureStarts(index: Int) {
        while (starts.size <= index) {
            val last = starts[starts.size - 1]
            if (last >= text.length) return
            val next = nextBoundary(last)
            if (next <= last || next >= text.length) return
            starts.add(next)
        }
    }

    /** 确定第 index 页的结束位置，顺带缓存下一页起点 */
    private fun resolveEnd(index: Int): Int {
        if (index + 1 < starts.size) return starts[index + 1]
        val start = starts[index]
        val next = nextBoundary(start)
        if (next <= start || next >= text.length) return text.length
        starts.add(next)
        return next
    }

    /**
     * 字符偏移 -> 页码（用于进度恢复与目录跳转）。
     *
     * 早期实现在这里抄了个近路：
     * ```
     * for (i in starts.size - 1 downTo 0) { if (o >= starts[i]) return i }
     * ```
     * 它只验证了「偏移 >= 该页页首」，**没有验证「偏移 < 该页页尾」**。
     * 新建的 pager 边界表只有 `[0]`，于是任何非零偏移都会立刻命中 `i = 0` 返回 ——
     * 换句话说**每次冷启动恢复阅读进度都只会得到第 0 页**，而分页、翻页、页码显示
     * 全部正常，日志里也只是一句正常的 `start page=0`，属于查不出来的那种。
     *
     * 正确做法：先在已构建的边界里定位到「页首 <= 偏移」的最大页，再从该页向后
     * 推进（按需构建边界），直到偏移落进某一页的 [start, end)。
     */
    fun pageOfOffset(offset: Int): Int {
        val o = offset.coerceIn(0, text.length)
        if (text.isEmpty()) return 0

        // 1) 在已构建边界中二分出最后一个 starts[i] <= o
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= o) lo = mid else hi = mid - 1
        }

        // 2) 从该页起向后推进，直到偏移落进页内
        var i = lo
        while (true) {
            val p = page(i) ?: return i
            if (o < p.end) return i
            if (p.end >= text.length) return i
            i++
        }
    }

    /** 从 from 开始，计算下一页起点 */
    private fun nextBoundary(from: Int): Int {
        val len = text.length
        if (from >= len) return from
        var end = (from + capacity).coerceAtMost(len)
        if (end >= len) return len

        // 1) 优先在段落边界断开（容量 60% 之后的第一个换行）
        val minBreak = from + (capacity * 0.6).toInt()
        val nl = text.lastIndexOf('\n', end)
        if (nl >= minBreak && nl < end) return nl + 1

        // 2) 退而求其次：断在句读/空格处
        for (i in end downTo minBreak) {
            val c = text[i]
            if (c == '　' || c == ' ' || c == '。' || c == '！' || c == '？' ||
                c == '，' || c == '；' || c == '.' || c == ',' || c == ' '
            ) {
                return i + 1
            }
        }

        // 3) 超长无断点的行：硬切（避免死循环）
        return (from + capacity).coerceAtMost(len)
    }

    companion object {
        private const val MIN_CHARS_PER_LINE = 6
        private const val MIN_CAPACITY = 40

        /** 全量分页的硬上限，防畸形输入（如超大单行）把主线程拖死 */
        private const val MAX_PAGE_GUARD = 1_000_000
    }
}

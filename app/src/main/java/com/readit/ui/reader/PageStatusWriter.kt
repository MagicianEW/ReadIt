package com.readit.ui.reader

import android.content.Context
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import com.readit.eink.R

/**
 * 阅读页底部状态条（页码 + 已读长度条）的写入器。
 *
 * ## 为什么必须 post
 *
 * 画布的「页变了」回调有两个来源，**两个都会让直接 setText 失效**：
 *
 *  1. `onSizeChanged -> rebuild() -> notifyPageChanged()` —— 发生在**布局过程中**。
 *     此时 `setText` 会触发「布局中调用 requestLayout」，Android 把这次改动丢掉，
 *     于是页码始终是空的。
 *  2. `setDocument()` 跑在 **io 线程**（打开 TXT 的解码在后台），它内部的 `rebuild()`
 *     同样会回调 —— 那就是**跨线程改 UI**。
 *
 * 两种情况的症状一模一样：页码不显示、已读长度条不出现。
 *
 * `View.post` 一次解决两者：不论从哪个线程、哪个时刻调用，都排到主线程消息队列，
 * 且在当前这一轮布局结束之后才执行。
 *
 * ## 为什么要单独抽一个类
 *
 * 这条「必须 post」的纪律很容易在后来的一次重构里被「顺手优化」掉
 * （直接 setText 看起来更简单，而且在小屏真机上不一定立刻暴露）。
 * 抽成类之后，行为可以被单测钉住：调用后**立刻**读，文本应当还没变；
 * 主线程 idle 之后才变。
 */
class PageStatusWriter(
    private val context: Context,
    private val pageInfo: TextView,
    private val progress: ProgressBar
) {

    /**
     * 写入页码与已读比例。
     *
     * @param index 当前页（0-based）
     * @param total 总页数；<= 0 表示未知，此时只写页码并隐藏长度条
     */
    fun apply(index: Int, total: Int) {
        pageInfo.post {
            pageInfo.text = context.getString(R.string.page_info, index + 1, total)
            if (total <= 0) {
                progress.visibility = View.GONE
                return@post
            }
            progress.visibility = View.VISIBLE
            progress.progress = ((index + 1) * 100 / total).coerceIn(0, 100)
        }
    }
}

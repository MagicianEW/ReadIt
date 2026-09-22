package com.readit.core.util

import android.os.SystemClock

/**
 * §7 非功能指标的埋点工具。
 *
 * 为什么要有这个东西：需求里十二项非功能指标（启动 <4s、TXT 首屏 <4s、翻页 <500ms、
 * EPUB 首章 <8s、PDF 首屏 <8s …）此前**一条都无法证明**——代码跑得再快，没有埋点
 * 就只能猜。全工程原本只有两处耗时日志（EPUB 渲染、WebDAV 同步），其余全是纸面承诺。
 *
 * 设计上刻意做到最小：
 *  - 只写 logcat，不上报、不落盘、不引入依赖，避免影响体积红线（§3.4）；
 *  - 统一用 `elapsedRealtime()`（含睡眠的单调时钟），不用 `currentTimeMillis()`
 *    （墙钟会被 NTP 校时回拨，差值可能为负）；
 *  - 名字统一以 `metrics: ` 开头，便于 logcat 过滤 `ReadIt: metrics:` 一次性取全。
 */
object Metrics {

    private const val TAG_PREFIX = "metrics: "

    /** Application 起点，用于「启动耗时」类指标 */
    var appStartedMs: Long = 0L
        private set

    fun markAppStart() {
        appStartedMs = SystemClock.elapsedRealtime()
    }

    /** 自 Application 起的毫秒；未完成 markAppStart 调用时返回 -1 */
    fun sinceAppStart(): Long =
        if (appStartedMs == 0L) -1L else SystemClock.elapsedRealtime() - appStartedMs

    fun now(): Long = SystemClock.elapsedRealtime()

    /** 从 [start] 到现在过了多少毫秒 */
    fun since(start: Long): Long = SystemClock.elapsedRealtime() - start

    /** 冷启动到某个界面就绪（§7 启动时间 <4s） */
    fun logBoot(stage: String) {
        ReadItLog.i("${TAG_PREFIX}boot $stage=${sinceAppStart()}ms")
    }

    /** 书籍打开到首帧可见（§7 TXT 首屏 <4s / EPUB 首章 <8s / PDF 首屏 <8s） */
    fun logFirstFrame(file: String, mode: String, startMs: Long) {
        ReadItLog.i("${TAG_PREFIX}firstFrame file=$file mode=$mode ms=${since(startMs)}")
    }

    /** 翻页耗时：用户动作触发到新页面回调（§7 TXT 翻页 <500ms / <300ms） */
    fun logPageTurn(file: String, mode: String, startMs: Long) {
        ReadItLog.i("${TAG_PREFIX}pageTurn file=$file mode=$mode ms=${since(startMs)}")
    }

    /** 目录跳转耗时（§7 / F10 跳转 <500ms） */
    fun logTocJump(file: String, mode: String, startMs: Long) {
        ReadItLog.i("${TAG_PREFIX}tocJump file=$file mode=$mode ms=${since(startMs)}")
    }
}

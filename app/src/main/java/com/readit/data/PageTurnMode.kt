package com.readit.data

/**
 * 翻页方式。
 *
 * 为什么放在 data 层：翻页方式要同时影响「输入判定」（哪些手势算翻页）、
 * 「渲染表现」（要不要动画 / 是不是连续滚动）和「设置项显隐」（墨水屏不提供），
 * 这三处散在 Activity / View / 设置页里。做成一个纯枚举 + 纯函数之后，
 * 这条规则可以在 JVM 上直接测，而它出过的毛病（模式与手势对不上、
 * 墨水屏上跑起动画）恰恰是单测能抓住的类型。
 *
 * ## 为什么墨水屏只保留 [TAP]
 *
 * 墨水屏的物理特性决定了另外三种都不合适：
 *  - 刷新一次就是一次全屏重绘，动画过程会留下一串残影（ghosting），
 *    读起来像「屏幕脏了」；
 *  - 连续滚动在这种屏上会变成一整屏一整屏地跳，比翻页更难受；
 *  - 滑动翻页要求用户做「快速甩动」，而墨水屏的触控延迟本来就大。
 * 所以 [resolvePageTurnMode] 在墨水屏上一律强制回落 [TAP]，
 * 用户的偏好值仍然保留 —— 换到普通屏幕（平板/手机）时自动生效。
 */
enum class PageTurnMode(val key: String) {
    /**
     * 点按翻页（**现有行为**）：点屏幕左三分之一 = 上一页，右三分之一 = 下一页，
     * 中间 = 调出菜单。
     */
    TAP("TAP"),

    /**
     * 滑动翻页：只有左右滑动才翻页，点按不翻页。
     *
     * 与 [TAP] 的区别是**点按不再翻页** —— 拇指习惯在手机上滑动，
     * 点按翻页反而容易误触。
     */
    SWIPE("SWIPE"),

    /**
     * 上下滚动：正文变成连续的一长条，纵向拖动阅读。
     *
     * 只对自绘文本路径（TXT / EPUB 文本降级 / DOCX 文本降级 / PDF 文本降级）成立。
     * 固定版式的 PDF 原版渲染没有「连续」可言，仍按页翻。
     */
    SCROLL("SCROLL"),

    /**
     * 翻页动画：翻页时带横向滑动动画。
     *
     * 与 [TAP] 的翻页判定完全相同，只是多一段过渡动画。
     */
    ANIM("ANIM");

    companion object {
        fun from(raw: String?): PageTurnMode =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: TAP
    }
}

/**
 * 决定实际生效的翻页方式。
 *
 * @param preferred 用户在设置里选的值
 * @param einkLikely 是否疑似墨水屏（见 [com.readit.core.display.ScreenProfileDetector.einkLikely]）
 *
 * 墨水屏一律回落 [PageTurnMode.TAP]：动画在墨水屏上是残影灾难，
 * 滚动模式也不符合墨水屏的翻页习惯。
 * **用户的偏好值本身不被改写**，所以同一台设备换成普通屏幕（拆了保护壳接 HDMI 之类）
 * 或者用户手动关掉「墨水屏判定」后，偏好在普通屏上会自动重新生效。
 */
fun resolvePageTurnMode(preferred: PageTurnMode, einkLikely: Boolean): PageTurnMode =
    if (einkLikely) PageTurnMode.TAP else preferred

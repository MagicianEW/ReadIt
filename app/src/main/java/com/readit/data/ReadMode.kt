package com.readit.data

/**
 * 阅读页当前的渲染模式。
 *
 * 为什么放在 data 层而不是 `ReaderActivity` 内部：
 * 进度写入策略（[ProgressPolicy]）需要按模式判定「位置来源是否已有值」，
 * 把它做成纯枚举 + 纯函数之后，这条策略可以在 JVM 上直接测——
 * 而它出过的事故（异步打开期间把进度写回 0）恰恰是单测能抓住的类型。
 */
enum class ReadMode {
    TXT,
    EPUB_FULL,
    EPUB_TEXT,
    DOCX_HTML,
    DOCX_TEXT,
    PDF_RENDER,
    PDF_TEXT
}

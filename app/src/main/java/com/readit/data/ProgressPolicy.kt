package com.readit.data

/**
 * 「onPause 时该不该写阅读进度、写什么」的判定（F09 回归点）。
 *
 * ## 为什么需要这层判定
 *
 * 打开 PDF / DOCX / EPUB 都要先经过异步解析或跨进程转换（DOCX 走 `:converter` 进程，
 * 大文档可能数秒）。这段时间里 `mode` 还是初始的 [ReadMode.TXT]，而画布里**什么都没有**。
 *
 * 用户在这段时间按返回（大文件上很常见）或系统把应用切到后台时，`onPause`
 * 会按 TXT 分支取 `canvas.currentOffset()`（= 0）当成进度，而
 * [ProgressStore.save] 是**无条件覆盖**——于是用户上次读到第 10 页的真实进度被清零，
 * 下次打开从头开始。属于「静默数据丢失」，日志里只会看到一行正常的 `progress saved`。
 *
 * ## 规则
 *
 * **只有当前模式对应的位置来源确实有值时才写；否则跳过，保留磁盘上已有的进度。**
 */
object ProgressPolicy {

    sealed class Decision {
        /** 文档尚未就绪：跳过写入，磁盘上的旧进度保持不变 */
        object Skip : Decision()

        /** 可以写入 */
        data class Save(val pos: ReadingPosition) : Decision()
    }

    /**
     * 判定输入。字段默认值刻意选成「没有有效位置」，这样漏传字段会退化成 Skip
     * （宁可少写一次进度，也不能写错覆盖）。
     */
    data class Input(
        val mode: ReadMode,
        val now: Long,
        /** 画布是否已装载文档（`TxtCanvasView.hasDocument`） */
        val canvasHasDocument: Boolean = false,
        val canvasOffset: Int = 0,
        val canvasChapterIndex: Int = 0,
        /** EPUB 完整渲染：当前 CFI；首帧 relocated 到达之前为空串 */
        val epubCfi: String? = null,
        val epubSpineIndex: Int = 0,
        /** PDF：当前页与总页数；打不开时 pageCount 为 0 */
        val pdfPage: Int = -1,
        val pdfPageCount: Int = 0,
        /** DOCX HTML 路径：最后一次跳转/点击到的标题序号 */
        val docxHeading: Int = 0
    )

    fun decide(input: Input): Decision = when (input.mode) {

        // CFI 为空说明还停在空白首帧，写回去会把上次的 CFI 覆盖掉
        ReadMode.EPUB_FULL -> {
            val cfi = input.epubCfi.orEmpty()
            if (cfi.isBlank()) {
                Decision.Skip
            } else {
                Decision.Save(
                    ReadingPosition(
                        charOffset = 0,
                        chapterIndex = input.epubSpineIndex,
                        updatedAt = input.now,
                        cfi = cfi
                    )
                )
            }
        }

        // pageCount == 0 说明 PDF 还没打开成功（渲染档可能马上会退到文本档）
        ReadMode.PDF_RENDER -> {
            if (input.pdfPageCount <= 0 || input.pdfPage < 0) {
                Decision.Skip
            } else {
                Decision.Save(
                    ReadingPosition(
                        charOffset = 0,
                        chapterIndex = input.pdfPage,
                        updatedAt = input.now,
                        pageIndex = input.pdfPage
                    )
                )
            }
        }

        // HTML 路径的位置就是标题序号，调用方在切 mode 之前已用存档初始化过，
        // 因此进入该模式即可安全写回（幂等）。
        ReadMode.DOCX_HTML -> Decision.Save(
            ReadingPosition(
                charOffset = 0,
                chapterIndex = input.docxHeading,
                updatedAt = input.now
            )
        )

        // 以下四种都以画布承载正文：画布为空即文档未就绪
        ReadMode.TXT,
        ReadMode.EPUB_TEXT,
        ReadMode.DOCX_TEXT,
        ReadMode.PDF_TEXT -> {
            if (!input.canvasHasDocument) {
                Decision.Skip
            } else {
                Decision.Save(
                    ReadingPosition(
                        charOffset = input.canvasOffset,
                        chapterIndex = input.canvasChapterIndex,
                        updatedAt = input.now
                    )
                )
            }
        }
    }
}

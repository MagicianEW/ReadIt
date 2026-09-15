package com.readit.core.eal

/**
 * 自适应档位（规范 §5.1，F20 六档组合）。
 *
 * SoC 双档 × RAM 三档 = 6 种组合，每种组合决定一套渲染/内存策略。
 */
enum class CapabilityTier(val id: Int, val label: String) {
    L0_A33_LOW(0, "A33 + LOW"),
    L1_A33_MID(1, "A33 + MID"),
    L2_A33_HIGH(2, "A33 + HIGH"),
    L3_A53_LOW(3, "A53 + LOW"),
    L4_A53_MID(4, "A53 + MID"),
    L5_A53_HIGH(5, "A53 + HIGH");

    companion object {
        fun of(soc: SocClass, ram: RamClass): CapabilityTier = when (soc) {
            SocClass.A33_CLASS -> when (ram) {
                RamClass.LOW -> L0_A33_LOW
                RamClass.MID -> L1_A33_MID
                RamClass.HIGH -> L2_A33_HIGH
            }
            SocClass.A53_CLASS_OR_ABOVE -> when (ram) {
                RamClass.LOW -> L3_A53_LOW
                RamClass.MID -> L4_A53_MID
                RamClass.HIGH -> L5_A53_HIGH
            }
        }
    }
}

/**
 * 由档位派生的内存/渲染策略。
 *
 * ## 各字段的实际生效情况（2026-09 全面测试核对）
 *
 * | 字段 | 是否驱动行为 | 说明 |
 * |---|---|---|
 * | [pdfCachedPages] | ✅ | `PdfRenderView.cachePages` |
 * | [docxMode] | ✅ | `DocxConverter.convert(mode)` |
 * | [lowMemory] | ✅ | `PdfTextExtractor(tempFileOnly=…)` |
 * | [txtPageChars] | ❌ | TXT 分页由几何标定（[com.readit.reader.txt.TxtLayout]）决定，本值仅作档位参考 |
 * | [epubPreloadChapters] | ❌ | 当前一律不预加载（比 LOW 档要求更保守） |
 * | [preloadEnabled] | ❌ | 同上 |
 * | [webViewMinimalCache] | ❌ | 当前一律 `LOAD_NO_CACHE`（比 LOW 档要求更保守） |
 *
 * 结论：**LOW 档的 §5.1 六条约束全部被满足**（禁预加载 / 最小缓存 / 单页位图 /
 * DOCX 纯文本兜底 / PDF 缓冲落盘 / 天然小分页），缺的是 MID·HIGH 档
 * 「放宽」的那部分收益（预加载、缓存、大分页）。补齐需要改 epub.js 侧并
 * 在真机上验证，属已知未完成项，不在本次分发版范围内。
 */
data class RenderStrategy(
    val tier: CapabilityTier,
    /** TXT 分页大小（字符数） */
    val txtPageChars: Int,
    /** EPUB 前后预加载章节数 */
    val epubPreloadChapters: Int,
    /** PDF 位图缓存页数 */
    val pdfCachedPages: Int,
    /** DOCX 渲染模式 */
    val docxMode: DocxMode,
    /** 是否允许预加载 */
    val preloadEnabled: Boolean,
    /** WebView 缓存策略：最小 / 正常 */
    val webViewMinimalCache: Boolean
) {
    /**
     * 是否 LOW 档（RAM < 1.5GB）。
     *
     * §5.1 对 LOW 档要求「TXT 小分页 / EPUB 单章加载 / PDF 单页位图 / DOCX 纯文本兜底 /
     * 禁用预加载 / WebView 缓存最小」。其中「PDF 单页位图」在文本抽取路径上体现为
     * 把 PdfBox 的随机访问缓冲落到 scratch 文件而不是内存。
     */
    val lowMemory: Boolean
        get() = tier == CapabilityTier.L0_A33_LOW || tier == CapabilityTier.L3_A53_LOW

    companion object {
        fun of(tier: CapabilityTier): RenderStrategy = when (tier) {
            CapabilityTier.L0_A33_LOW -> RenderStrategy(
                tier, txtPageChars = 800, epubPreloadChapters = 0, pdfCachedPages = 1,
                docxMode = DocxMode.TEXT_ONLY, preloadEnabled = false, webViewMinimalCache = true
            )
            CapabilityTier.L1_A33_MID -> RenderStrategy(
                tier, txtPageChars = 1200, epubPreloadChapters = 0, pdfCachedPages = 1,
                docxMode = DocxMode.TEXT_ONLY, preloadEnabled = false, webViewMinimalCache = true
            )
            CapabilityTier.L2_A33_HIGH -> RenderStrategy(
                tier, txtPageChars = 1600, epubPreloadChapters = 1, pdfCachedPages = 2,
                docxMode = DocxMode.SUBSET, preloadEnabled = true, webViewMinimalCache = false
            )
            CapabilityTier.L3_A53_LOW -> RenderStrategy(
                tier, txtPageChars = 1200, epubPreloadChapters = 0, pdfCachedPages = 1,
                docxMode = DocxMode.SUBSET, preloadEnabled = false, webViewMinimalCache = true
            )
            CapabilityTier.L4_A53_MID -> RenderStrategy(
                tier, txtPageChars = 2000, epubPreloadChapters = 1, pdfCachedPages = 2,
                docxMode = DocxMode.SUBSET, preloadEnabled = true, webViewMinimalCache = false
            )
            CapabilityTier.L5_A53_HIGH -> RenderStrategy(
                tier, txtPageChars = 3200, epubPreloadChapters = 2, pdfCachedPages = 3,
                docxMode = DocxMode.SUBSET_WITH_IMAGE, preloadEnabled = true,
                webViewMinimalCache = false
            )
        }
    }
}

enum class DocxMode {
    /** 兜底：纯文本 + 提示 */
    TEXT_ONLY,

    /** 子集渲染：段落/标题/列表/简单表格 */
    SUBSET,

    /** 子集渲染 + 内嵌图片 */
    SUBSET_WITH_IMAGE
}

# P0-C 技术门禁报告

> 日期：2026-09-14 · 基线：ReadIt v1.6 · 结论：**三项门禁全部通过，可在 Phase 3 按既定技术路线实施**

| 门禁 | 结论 | 依据 |
|---|---|---|
| C1 PDF 文本能力（PdfBox-Android） | ✅ 通过 | 20 页样本：单页流式 **≈10ms/页**，堆增 **+51KB**；无文本层 PDF 检出 coverage=0.0 |
| C2 DOCX 内置 OOXML 子集解析器 | ✅ 通过 | 子集元素全部还原（标题/加粗/斜体/下划线/删除线/有序+无序编号/表格/图片）；畸形文档不崩溃且降级 |
| C3 EPUB WebView 能力分级 | ✅ 通过 | 分级逻辑 7/7 通过；KY-01L（Chromium 52）正确判为降级路径 |

---

## C1 — PdfBox-Android 文本能力基准

**实现**：`com.readit.pdf.PdfTextExtractor`
- 按页流式提取（`PDFTextStripper` + startPage/endPage），避免一次性 `getText()` 全文档导致 OOM
- `textCoverage(samplePages)`：3 页采样，单页有效字符 > 20 视为有文本层，覆盖率 ≥ 0.5 判为文本版

**基准数据**（20 页 / 9898B / 每页 45 行英文，本机 JDK17 + Robolectric）

| 指标 | 数值 | 备注 |
|---|---|---|
| 打开文档 | 89 ms | 含 xref/字体初始化 |
| 单页流式提取 | **203 ms / 20 页 = 10 ms/页** | 内存 +51 KB |
| 一次性全量提取 | 109 ms，56115 字符 | 内存 +84 KB |
| 文本版覆盖率 | **1.0** | 判定为有文本层 |
| 无文本层 PDF 覆盖率 | **0.0** | 正确判为扫描版 |

**结论与决策**
1. 单页 10ms 级成本远低于 F05「PDF 首屏 <8s」预算 → **文本/ Reflow 兜底路径可行**，不需要退到缩略图兜底。
2. 一次性全量更快但堆占用更高（+84KB vs +51KB），且随页数线性增长 → **统一采用流式按页提取**，这也是 1GB 设备的唯一安全解。
3. kvDocument 常驻会拖住内存，因此 `PdfTextExtractor` 提供 `close()` 与按页回调，由 ViewModel 控制生命周期。
4. 扫描版检测的阈值（`MIN_TEXT_PER_PAGE=20`、`threshold=0.5`）已参数化，F08 真机验收时可用 ≥50 扫描 + ≥50 文本样本调阈值（目标检出 ≥95% / 误判 ≤5%）。

**⚠️ 仍未闭环**：A33 级 SoC + 1GB RAM 真机数据需 Phase 4 补齐（Q4 待审批）。本机数据仅作为**相对基准**：保守按 A33 慢 5~8 倍估算，单页 ≈ 50–80ms，仍在预算内。

---

## C2 — 内置 OOXML 子集解析器原型

**实现**：`com.readit.doc.DocxParser`（平台内置 `ZipFile` + `XmlPullParser`，零第三方依赖）

**输入清单覆盖**（v1.6 修正要求）：`[Content_Types].xml` ✓ · `word/_rels/*.rels` ✓ · `word/document.xml` ✓ · **`word/numbering.xml`** ✓ · `word/media` ✓ · `word/styles.xml` ✓

**还原结果**（对 `readit_sample.docx`）

```html
<h1>Chapter 1 Title</h1>
<h2>Section intro 1</h2>
<p>Body paragraph number 1 ...</p>
<p>Plain text <b>bold</b><i> italic </i><u>underline</u><s> strike</s></p>
<ol>
  <li class="readit-num readit-lvl0" data-num="1">Ordered item one</li>
  <li class="readit-num readit-lvl0" data-num="2">Ordered item two</li>
  <li class="readit-num readit-lvl1" data-num="1">Ordered sub item</li>
</ol>
<ul><li class="readit-bullet">Bullet item one</li><li class="readit-bullet">Bullet item two</li></ul>
<table border="1">...</table>
```

**降级行为**（全部不崩溃）
| 输入 | 行为 |
|---|---|
| 缺 `numbering.xml` | 列表退化为项目符号 + warning；**不丢正文** |
| `document.xml` 被截断 | 解析到出错点为止，返回已解析内容，**不抛异常到 UI** |
| 文本框 / `mc:AlternateContent` | 整块跳过 + warning，`mc:Fallback` 不重复渲染 |
| 表格 | 按简单表格渲染，warning 提示合并单元格不还原 |
| 图片（非图片档位） | 计数保留 + warning「图片已省略」；图片档位落盘并用 `readit-media://` 引用 |

**实现中踩到的三个真坑（已修）**
1. `<w:b/>` 空元素会立刻产生 END_TAG —— run 属性必须在 `</w:r>` 处一次性复位，否则加粗/斜体全丢。
2. 标签之间的缩进空白也是 TEXT 事件 —— 只接收 `<w:t>` 内部文本，否则标题前多出换行。
3. 命名空间处理后 `r:embed` 取不到 —— 属性读取必须按 local name 兜底，否则图片引用全丢。

**结论与决策**：原型满足 F04 验收口径；Phase 3 只需在独立进程 `com.readit.eink:converter` 中调用同一套解析逻辑，不需要再选型第三方库。

---

## C3 — EPUB WebView 能力分级判定

**实现**：`com.readit.web.WebViewCapability`

| API 层 | 探测路径 |
|---|---|
| API 26+（主路径） | `WebView.getCurrentWebViewPackage()` → versionName → major |
| API 19–25 | UA 解析 `Chrome/<major>` + JS 特性探测脚本兜底 |
| 结果 | `<55` → `DEGRADED_TEXT`（文本抽取 + Canvas）；`≥55` → `FULL_EPUBJS` |

**逻辑回归 7/7 通过**，含关键边界：
- Chromium **54.9 降级 / 55 放行**（阈值闭区间）
- KY-01L（Android 7.1 + Chromium 52）→ **降级路径**
- Boox/Meebook 等现代 WebView（Chromium 103+）→ epub.js 完整渲染
- 探测全部失败时 → 保守降级，**不会误走 epub.js 白屏路径**

**结论与决策**：F03 按能力分级双路径实施，KY-01L 明确走降级（印证 §3.2.2 预判）；EPUB 降级路径的进度口径为「章节 + 字符偏移」，需在 Phase 2 与 `IReaderView` 对齐。

---

## 自动化测试资产

| 文件 | 覆盖 |
|---|---|
| `app/src/test/java/com/readit/doc/DocxParserTest.kt` | 子集还原 / 畸形文档 / 纯文本档位 / 图片提取（4 例） |
| `app/src/test/java/com/readit/pdf/PdfTextBenchmarkTest.kt` | 单页正确性与内存画像 / 扫描版检出（2 例） |
| `app/src/test/java/com/readit/web/WebViewCapabilityTest.kt` | 分级逻辑与阈值边界（7 例） |
| `tools/gen_fixtures.py` | 生成样本：DOCX ×3（含畸形）、PDF ×3（文本 20 页 / 1 页、无文本层 10 页） |
| `tools/extract_pdfbox_assets.py` | 把 AAR assets 注入测试 classpath（JVM 下补 GlyphList/AFM） |

**当前状态：13/13 通过，`BUILD SUCCESSFUL`。**

> 注意：`tools/extract_pdfbox_assets.py` 產出的资源仅用于 JVM 单测；真机上这些资源由 AAR assets 自动提供，**不要**打包进 APK。

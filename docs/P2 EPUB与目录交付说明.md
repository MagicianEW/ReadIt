# P2 — EPUB 渲染 + 目录 + 降级路径

> 日期：2026-09-14 · 基线 ReadIt v1.6 · 状态：**功能闭环，待真机验证（M2）**

## 1. 交付范围

| 需求 | 实现 | 位置 |
|---|---|---|
| F03 EPUB 渲染（首章 < 8s） | 内置 epub.js v0.3.88 + `EpubWebView` + JsBridge | `assets/readit_epub/`、`com.readit.reader.epub` |
| EPUB 容器解析 | `EpubParser`：container.xml → OPF → nav/NCX → spine 兜底 | `com.readit.epub` |
| 能力分级降级路径 | `EpubTextExtractor` 文本抽取 + `TxtCanvasView` 渲染 | `com.readit.epub` |
| F10 目录侧滑 90vw + 跳转 < 500ms | DrawerLayout + RecyclerView，统一 `TocEntry` 模型 | `ui/reader/ReaderActivity.kt` |
| F09 进度绑定 | EPUB 写 CFI + spine 下标；降级路径写字符偏移 + 章节序号 | `data/ReadingProgress.kt` |
| DRM 识别 | `META-INF/encryption.xml` 存在即拒绝打开并提示 | `EpubParser` |

## 2. 三条渲染路径

```
                     ┌─ 扩展名 .txt ────────────────► TxtCanvasView（P1 既有）
打开文件 ─► 路由判定 ┤
                     └─ 扩展名 .epub ─► EpubParser.parse
                                          │
                              WebViewCapability.probe
                                          │
                        ┌─────────────────┴─────────────────┐
                 Chromium ≥ 55                        Chromium < 55
                        │                                   │
              EPUB_FULL：WebView + epub.js        EPUB_TEXT：EpubTextExtractor
              目录跳转 = spine 下标                 目录跳转 = 字符偏移
              进度 = CFI + spine 下标              进度 = 字符偏移 + 章节序号
```

三条路径共用同一套 `TocEntry`、排版面板、输入映射与进度存储，`ReaderActivity` 内只有一个
`mode` 开关决定「翻页/跳转/刷新」落到哪个视图。

## 3. 关键设计

**单版本锁定**：epub.js 固定 v0.3.88（`dist/epub.min.js`，231KB）打进 `assets/readit_epub/`，
不做运行时下载，避免离线设备打不开书；APK 体积代价约 0.2MB。

**目录来源双保险**：目录不依赖 epub.js 的 `book.loaded.navigation` 异步回调，
而是在 Kotlin 侧用 `EpubParser` 同步解析 EPUB3 `nav` → EPUB2 `NCX` → spine 兜底。
好处是降级路径与完整路径拿到完全相同的目录，且首屏不必等 JS。

**跳转用 spine 下标而非 href**：epub.js `spine.get(target)` 对字符串走 `spineByHref` 查表，
而 href 在解析层经过百分号解码/`./`折叠，与 epub.js 内部的 `encodeURI/decodeURI` 结果可能对不上。
传 `number` 直接按下标取 section，稳定且与 `EpubParser` 的 `spineIndex` 一一对应（`#fragment` 归并到所属章）。

**WebView 按需创建**：`EpubWebView` 不写在布局里，只在路由到 EPUB 且能力分级通过时才 `stage.addView`。
打开 TXT 时进程内不存在 WebView 实例（1GB 设备上 WebView 常驻 30–60MB，不能白付）。
`onDestroy` 里先 `removeView` 再 `destroy()`。

**E-Ink 约束**：`reader.html` 内 `*{transition:none;animation:none}` 全局禁用动效；
白底黑字固定；`flow:"paginated"` + `minSpreadWidth:100000` 强制单栏翻页；
每次 `rendered` 事件由 Kotlin 侧请求整屏刷新。

**安全边界**：WebView 仅加载 `file:///android_asset/readit_epub/reader.html`，
`shouldOverrideUrlLoading` 一律返回 true，禁止跳出阅读页；不发起任何网络请求。

## 4. 回归中发现并修复的缺陷

1. **NCX 嵌套 navPoint 丢父节点 / 顺序倒置**
   原实现用单一 `navDepth` + 单个 `label/src` 变量，内层 `<navLabel>/<content>` 会覆盖外层；
   且在 `END_TAG navPoint` 时发射，导致子节点先于父节点入列。
   改为按 `NcxNode` 建树后按文档序展开（`NcxNode` 栈 + DFS）。
   夹具 `readit_sample_ncx.epub` 中 4 个 navPoint（含 1 个嵌套）从「3 条且错位」修正为「4 条且顺序正确」。

2. **非良构 XHTML 导致抽取整体失败**
   真实 EPUB 常见 `&nbsp;` / `&mdash;` / 未闭合 `<p>`，`XmlPullParser` 直接抛异常。
   增加 try/catch → 正则剥离兜底（先删 script/style，再块级标签转换行，最后解码实体）。

## 5. 测试与验收

| 测试类 | 用例 | 说明 |
|---|---|---|
| `EpubParserTest` | 6 | EPUB3 nav 嵌套目录、EPUB2 NCX 回退、无目录 → spine 兜底、DRM 识别、路径归一化、OPF 目录层级 |
| `EpubTextExtractorTest` | 4 | 章节顺序与偏移单调性、TOC↔spine 偏移对齐、段落换行保留、非良构 XHTML 正则兜底、编码判定 |

生成夹具：`python tools/gen_epub_fixtures.py` → `app/src/test/resources/fixtures/readit_{sample,sample_ncx,broken}.epub`

### 实测数据（本机构建）

| 项 | 目标 | 实测 |
|---|---|---|
| 单元测试总数 | 全绿 | **40/40 通过**（P1 的 21 + P0 的 13 + P2 的 10，另 PdfTextBenchmark 2） |
| P2 新增用例 | — | `EpubParserTest` 6/6、`EpubTextExtractorTest` 4/4 |
| APK 体积 | < 20MB Universal | **17.73MB**（debug，含 epub.js 231KB） |
| assets 打包 | `readit_*` 前缀 | `assets/readit_epub/epub.min.js`、`assets/readit_epub/reader.html` ✅ |
| 首章渲染 < 8s | 待真机 | 未实测（有 `ReadIt` TAG 耗时日志可采） |
| 目录跳转 < 500ms | 待真机 | 未实测 |

## 6. 尚未闭环（Phase 4 真机）

- 首章渲染 < 8s：需真机（兜底 SoC + 1GB）实测，当前只有代码路径上的耗时日志（`epub rendered: ... total=Xms`）
- 目录跳转 < 500ms：同上
- Chromium < 55 的降级路径无法在本地验证（本机 SDK 自带的 WebView 版本远超 55），
  需在 KY-01L 或旧设备上确认 `WebViewCapability.probe` 的 UA 分支
- 600×800 下 epub.js 分页是否出现横向溢出（需截图回归）

## 7. 新增 / 改动文件

```
app/src/main/assets/readit_epub/
├── epub.min.js        epub.js v0.3.88（npm mirror 下载，231KB）
└── reader.html        epub.js 宿主页 + JsBridge 暴露的 window.ReadItEpub

app/src/main/java/com/readit/
├── epub/EpubParser.kt          容器/OPF/nav/NCX 解析
├── epub/EpubTextExtractor.kt   文本抽取降级路径
├── reader/epub/EpubWebView.kt  WebView 渲染器 + JsBridge
└── data/Toc.kt                 统一目录条目模型

改动：
├── ui/reader/ReaderActivity.kt   三路径路由 + 统一目录/输入/进度
├── data/ReadingProgress.kt       新增 cfi 字段
├── res/layout/activity_reader.xml  新增 stage + EpubWebView
└── res/values/strings.xml        EPUB 相关文案

app/src/test/java/com/readit/epub/
├── EpubParserTest.kt          (6)
└── EpubTextExtractorTest.kt   (4)

tools/gen_epub_fixtures.py     EPUB 夹具生成器
scripts/g.cmd                  本机 Gradle 包装（绕开 PowerShell 对原生命令 stderr 的中断）
```

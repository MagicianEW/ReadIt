# P3 交付说明 — DOCX + PDF + WebDAV 同步

> 基线：ReadIt v1.6 可行性复审修正版 · 包名 `com.readit.eink`
> 交付日期：2026-09-14
> 结论：**代码闭环，74/74 单元用例通过，`assembleDebug` 成功**；native / WebView / 网络相关的时限验收项留待真机矩阵（P4）。

---

## 1. 需求映射

| 需求 | 内容 | 落地位置 | 状态 |
|---|---|---|---|
| F04 | DOCX 子集渲染 + 复杂元素降级提示 + 独立进程 | `doc/DocxConvertService.kt`、`doc/DocxConverter.kt`、`doc/DocxHtmlToc.kt`、`reader/docx/DocxWebView.kt` | ✅ 代码闭环 |
| F05 | PDF 页面渲染（PdfiumAndroid 1.9.0） | `pdf/PdfCore.kt`、`reader/pdf/PdfRenderView.kt` | ✅ 代码闭环 |
| F06 | PDF 目录（Pdfium 书签 + PdfBox 兜底） | `pdf/PdfCore.bookmarks()`、`pdf/PdfBookmarks.kt` | ✅ 代码闭环 |
| F07 | PDF 裁边 / 去白边 | `pdf/PdfCropper.kt` | ✅ 9 用例覆盖几何判定 |
| F08 | 扫描版检测（3 页采样 / 阈值可调 / 强制导入 / 0 崩溃） | `pdf/ScanDetection.kt`、`pdf/PdfTextExtractor.detectScan()`、`ReadItPrefs.scanThresholds()` | ✅ 逻辑闭环，检出率待真机语料 |
| F13 | WebDAV 双向同步（ETag 优先，不静默覆盖） | `sync/webdav/WebDavClient.kt`、`sync/WebDavSync.kt`、`sync/SyncDecision.kt`、`sync/SyncStateStore.kt` | ✅ 11 用例覆盖决策矩阵 |

---

## 2. 关键设计决策

### 2.1 DOCX 为什么必须独立进程

DOCX 子集解析是纯 CPU + 堆压力的活：`ZipFile` 解压 XML、大文档中间字符串可达几十 MB。放在主进程会在 1GB 设备上与渲染抢堆；一旦解析进程被低内存杀手干掉，阅读器主进程会一起死。

因此 `DocxConvertService` 声明 `android:process=":converter"`：

```xml
<service android:name="com.readit.doc.DocxConvertService"
         android:exported="false"
         android:process=":converter" />
```

进程间不能共享内存，所以入参走 Intent（源文件路径 / 模式 / cache 根目录），出参走 `ResultReceiver`，**产物本身落盘用文件传递**（HTML 可能超 Binder 的 1MB 事务上限，不能塞进 Bundle）。

### 2.2 DOCX 转换的兜底链

```
DocxConverter.convert()
  ├─ 主路径：DocxConvertService（:converter 进程，30s 超时）
  └─ 兜底：服务不可用/抛异常 → 主进程内联 DocxParser 转换
```

宁可慢一点也不能打不开书。`Converted.viaService` 记录实际走通的路径，便于日志定位。

**Bimodal 渲染路径**：按 `RenderStrategy.docxMode` 分叉——
- `TEXT_ONLY`（A33 低/中档）→ 纯文本 + `TxtCanvasView`，不构造 WebView（1GB 设备上 WebView 实例常占 30–60MB）
- `SUBSET` / `SUBSET_WITH_IMAGE` → HTML + `DocxWebView`

### 2.3 标题锚点与目录（F04 × F10 交叉）

`DocxParser` 产出的 HTML 里没有任何 id，WebView 无法跳转。`DocxHtmlToc` 做三件事：

1. 按文档顺序给 `h1~h6` 注入 `id="readit-h-N"`
2. 抽出 `(title, depth, id)` 三元组，映射为统一的 `TocEntry`（`offset` 复用为锚点序号）
3. 把 `readit-media://word/media/x.png` 改写为裸文件名 `x.png`

第 3 步的原因：`DocxParser` 落盘内嵌图片时只取 basename，而 WebView 用 `loadDataWithBaseURL(产物目录)` 挂载，相对路径即可命中——省掉 `shouldInterceptRequest` 自定义 scheme 拦截，E-Ink 上每次拦截都是额外开销。

### 2.4 为什么把判定逻辑从 Bitmap / PDF 里抽出来

同步与裁边最容易错的不是 IO，而是**判断**。所以两处判定都被抽成无 Android 依赖的纯函数，好在 JVM 上把边界全跑一遍：

- `PdfCropper.decide(w, h, minX, minY, maxX, maxY, ink, sampled, config)` — 裁边几何判定
- `SyncDecider.both(remote, local, record)` — 同步方向判定

### 2.5 扫描版检测的双信号口径（F08）

只靠「文本层字符少」会误判（图片书、纯公式页）；只靠「有整页大图」也会误判（带封面的正常 PDF）。因此要求**两个条件同时成立**：

```
平均每页文本字符数 < minCharsPerPage(默认 100)
且 含「整页大图」的采样页占比 ≥ minImagePageRatio(默认 0.5)
```

「整页大图」的量化口径：`图像像素数 / 页面点面积 ≥ 0.5`。整页扫描图的像素数远大于页面点数，比值通常 ≫ 1；小图标 / 水印则 ≪ 1，天然区分。

采样页索引走**首末页必中**的均匀分布（`it * (total-1) / (n-1)`），保证低配设备不会永远只看第 1 页——封面页往往没有正文信息。

检测**只保证大概率**，不承诺 100%，所以强制给出「继续导入」出口，且用 `prefs.isForceImport(bookId)` 记忆用户选择，不重复打扰。

### 2.6 同步的不静默覆盖（R19）

| 情形 | 动作 | 保护手段 |
|---|---|---|
| 远端独有 | 下载 | — |
| 本地独有 | 上传 | `If-None-Match: *` —— 远端若已被别人建了同名文件返回 412，绝不覆盖 |
| 仅本地变 | 上传 | `If-Match: <record.etag>` —— 远端也变了就 412，放弃本次 |
| 仅远端变 | 下载 | — |
| 两侧都变 / 无基线记录 | **记冲突，本次不碰任何一侧** | — |

「变了没有」的判定链严格按 R19：**ETag → Last-Modified → 体积**。三条链全断时返回「变了」，宁可多同步一次也不漏。

无基线记录（第一次同步遇到同名文件）**必须判冲突**而不是默认下载——默认下载就是静默覆盖本地。

---

## 3. 文件清单

### 新增（主源码）

| 文件 | 职责 |
|---|---|
| `doc/DocxConvertService.kt` | `:converter` 进程内的转换服务 |
| `doc/DocxConverter.kt` | 客户端（服务优先 + 主进程兜底） |
| `doc/DocxHtmlToc.kt` | 标题锚点注入 / 目录抽取 / 媒体路径相对化（纯 JVM） |
| `reader/docx/DocxWebView.kt` | DOCX HTML 宿主（JS 仅用于锚点跳转） |
| `reader/pdf/PdfRenderView.kt` | PDF 位图渲染视图（后台线程渲染 + 裁边 + fit-center 绘制） |
| `pdf/PdfCore.kt` | Pdfium 封装（渲染 + 书签） |
| `pdf/PdfCropper.kt` | 裁边（几何判定已抽为纯函数） |
| `pdf/PdfBookmarks.kt` | PdfBox 书签兜底 |
| `pdf/ScanDetection.kt` | 扫描版阈值与判定结果模型 |
| `sync/SyncDecision.kt` | 同步决策层（纯函数） |
| `sync/SyncStateStore.kt` | 同步基线持久化（原子替换） |
| `sync/WebDavSync.kt` | 双向同步编排 |

### 新增（测试）

`pdf/PdfCropperTest.kt`(9) · `pdf/ScanDetectionTest.kt`(7) · `doc/DocxHtmlTocTest.kt`(7) · `sync/SyncDeciderTest.kt`(11)

### 修改

| 文件 | 变更 |
|---|---|
| `ui/reader/ReaderActivity.kt` | 渲染路径由 3 条扩到 7 条（+DOCX_HTML / DOCX_TEXT / PDF_RENDER / PDF_TEXT）；`showOnly()` 保证只有当前视图可见；异步打开 DOCX/PDF |
| `data/Toc.kt` | `TocEntry` 增 `pageIndex`（PDF 页码口径） |
| `data/ReadingProgress.kt` | `ReadingPosition` 增 `pageIndex` |
| `data/prefs/ReadItPrefs.kt` | 增 `pdfCropEnabled` / 扫描阈值 / 强制导入记忆 / WebDAV 四项配置 |
| `pdf/PdfTextExtractor.kt` | 增 `detectScan()` 与 `sampleIndices()` |
| `sync/webdav/WebDavClient.kt` | 增 `upload()`（PUT + 条件请求）与 `ConflictException` |
| `ui/settings/SettingsActivity.kt` | 新增「阅读与解析」「WebDAV 同步」两组设置 |
| `AndroidManifest.xml` | 声明 `:converter` 进程服务 |
| `res/xml/prefs_readit.xml`、`res/values/strings.xml` | 新增配置项与文案 |

---

## 4. 验收实测

```
> Task :app:testDebugUnitTest
> Task :app:assembleDebug
BUILD SUCCESSFUL in 1m 43s
```

| 用例集 | 数量 | 结果 |
|---|---|---|
| `PdfCropperTest` | 9 | ✅ |
| `ScanDetectionTest` | 7 | ✅ |
| `DocxHtmlTocTest` | 7 | ✅ |
| `SyncDeciderTest` | 11 | ✅ |
| 既有用例（P0–P2） | 40 | ✅ |
| **合计** | **74** | **0 失败 / 0 跳过** |

| APK | 体积 | 红线 |
|---|---|---|
| `app-arm64-v8a-debug.apk` | 18.72 MB | < 20 MB ✅ |
| `app-armeabi-v7a-debug.apk` | 18.56 MB | < 20 MB ✅ |

> ⚠️ **P4 修订（2026-09-14）**：上表数值含**增量打包产生的零字节空洞**，并非真实内容体积，且当时未识别。P4 全量 clean 重建后，同口径真实内容为 arm64 **12.772 MB** / v7a **12.621 MB**。另 §3.4 对分包另有更严的口径——「使每个 APK **稳定低于 16MB**」，18.72 MB 实际未达标。详见 `P4 打磨与设备收集交付说明.md` §4.2。

体积构成（arm64 包，按压缩后体积，合计 18.72 MB）：

| 类别 | 压缩后 | 占比 | 说明 |
|---|---|---|---|
| `classes*.dex` | 7.09 MB | 37.9% | 含 Kotlin stdlib、AndroidX、OkHttp、PdfBox |
| **BouncyCastle** | **3.96 MB** | **21.2%** | **由 `pdfbox-android` 传递引入**（bcprov/bcpkix/bcutil 1.72），PDF 加密与证书支持 |
| native libs | 3.14 MB | 16.8% | `libmodpdfium.so` / `libmodft2.so` / `libc++_shared.so` |
| pdfbox / fontbox 资源 | 1.74 MB | 9.3% | cmap 与 `LiberationSans-Regular.ttf` |
| 其他 | 0.73 MB | 3.9% | — |

**余量已不足 1.3 MB**，P4 若还要加依赖，按性价比排序有三条路：

1. **启用 R8**（release 已配好 `minifyEnabled`）—— 主要能砍 dex 段，对 BouncyCastle 的 `.properties` 资源无效
2. **按需裁剪 fontbox cmap** —— 只留 `UniGB-*` / `Adobe-GB1-*` 等中文必需的 cmap，可省约 1 MB。**注意：CLI 模式下 jbig2 用户回退方案（`tools/extract_pdfbox_assets.py` 已备）就是为这类裁剪预留的**
3. ~~**排除 BouncyCastle** —— 收益最大（3.96 MB），但**不建议**：`PDDocument.load` 处理加密 PDF 时会引用 bc 类，排除后将抛 `NoClassDefFoundError`，直接违反 F08「0 崩溃」。如确需排除，必须先加「加密 PDF 预检 + 友好提示」的替代路径。~~
   **【P4 已修正此结论】** 原判断针对「排除 BC 的**类**」，该风险成立。但收益可**在不触碰类的前提下拿到**：这 3.96 MB 中 3.946 MB 是 PQC（Picnic/SIKE）的 `.properties` **资源**，由 `packaging.resources.excludes` 单独剔除即可，加密 PDF 路径完全不受影响。P4 已按此实施并通过全部既有用例。详见 `P4 打磨与设备收集交付说明.md` §4.2。

---

## 5. 真机待验证项（P4）

P3 的验收里有一半指标**只能在真机上拿**，本轮无法给出：

| 项 | 口径 | 备注 |
|---|---|---|
| DOCX 子集渲染首屏 | 目标 < 5s（含跨进程转换） | A33+1GB 最差档 |
| PDF 翻页响应 | 目标 < 800ms / 页 | Pdfium 渲染 + 裁边（裁边为 O(w×h/step²) 单遍扫描） |
| 扫描版检出率 | ≥ 95% / 误判 ≤ 5% / 0 崩溃 | 需真实扫描语料，当前只有阈值逻辑回归 |
| 裁边视觉效果 | 不切字、不误裁 | 算法含「单边留白 > 35% 则放弃」保护 |
| WebDAV 连通性 | API 19 TLS 1.2 握手 | 已完成 P0-B，本轮未接真实服务器回归 |
| 冲突路径 | 两端同时改同一本书 | 逻辑已覆盖，需真实 Nextcloud/坚果云验证 412 行为 |

**已知服务端差异**：部分 WebDAV 服务端（含部分 Nextcloud 配置）PUT 不返回 ETag。此时 `SyncRecord.etag` 为空，判定退到 Last-Modified；若服务端也不返回 Last-Modified，则退到体积比对——体积相同但内容不同会漏检，属 R19 兜底链的固有局限，已如实记录。

---

## 6. 遗留与风险

1. **PdfiumAndroid 1.9.0 上游停止维护（R22）** —— 版本已锁定。`PdfRenderView.open()` 失败时自动退到 PdfBox 文本档，保证「至少能读」，但该路径丢页码信息。
2. **WebDAV 密码明文存储** —— 面向侧载封闭设备，未引入 Keystore。若后续要支持共享设备，应改 `EncryptedSharedPreferences`（代码里已留注释）。
3. **同步冲突无合并策略** —— 当前只上报冲突、不写入。自动化合并（如两端都改名保留）留待 P4 视需求决定。
4. **`PdfRenderView.onDetachedFromWindow` 即释放 native 句柄** —— 视图被移除后需重新 `open()`。当前 Activity 生命周期下不会误触发，若后续引入 ViewPager 需复核。
5. **`androidx.preference` 的 `EditTextPreference` 落盘为 String** —— `ReadItPrefs` 的整数项读取已兼容 String / Int 两种存储形态，避免换实现后旧值读不出（注意 `SharedPreferences.getString` 遇到 Int 会抛 `ClassCastException`，已捕获）。

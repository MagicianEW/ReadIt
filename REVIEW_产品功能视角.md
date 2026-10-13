# ReadIt / 阅即 — 产品功能视角 Code Review

> 审查对象：`MagicianEW/ReadIt` @ `cf9080b`（v0.1.5，versionCode 5）
> 审查日期：2026-09-30
> 审查基线：README 特性表 + `docs/审核报告_v1.6合规性审核.md`（F01–F21 / Phase 0–4）
> 审查方式：14,348 行主源码 + 48 个测试文件逐模块通读；分 TXT / EPUB-DOCX / PDF / 同步数据 四域并行深查 + 墨水屏适配层与 UI 层自查；**所有阻断级与严重级指控均由审查者逐条回读源码复核**
> 范围外：真机冒烟、构建执行、APK 体积实测

---

## 一、总体结论

**工程纪律很高，产品兑现度中等偏上；但有 4 项必须在上线前修复，其中 2 项是安全阻断、2 项是跨设备数据丢失。**

| 维度 | 结论 |
|---|---|
| 需求交付面 | F01–F21 全部有实现落点，Phase 0–4 路线完整，六条渲染路径骨架正确 |
| 代码质量 | 注释密度与设计意图记录是同类项目里少见的水平；多数"看起来像 bug"的地方其实有注释解释 |
| 功能兑现 | 约 60% 达到承诺；**承诺最硬的几条（书签并集、设备属性不跨设备继承、字号四格式全生效、降级诚实性）实际未兑现或反被实现破坏** |
| 安全 | **2 项阻断**：EPUB WebView 可读全盘外传；DOCX 未转义属性注入 |
| 数据安全 | **2 项严重**：书签并集裁剪会跨设备永久丢书签；PDF 渲染路径每次翻页泄漏 native 句柄 |
| 测试 | 决策层/纯函数测得很好，**落地层（"决策 → 实际写哪个文件、带什么条件头"）几乎零覆盖**，缺陷恰好全在这一层 |
| 上线建议 | 修完第一节 6 项可进内测；不建议以当前状态对外分发 |

---

## 二、产品功能兑现矩阵

图例：✅ 兑现 · ⚠️ 部分/有条件 · ❌ 未兑现 · — 非本次范围

### 阅读（四格式）

| 产品承诺 | 状态 | 依据 |
|---|---|---|
| TXT 编码自动检测 + 乱码手选 + 按书记忆 | ✅ | BOM 短路 + juniversalchardet，`EncodingDetector.kt:63/70-89`；按书键=文件名，书籍目录扁平无碰撞 |
| 章节检测 + 目录 + 跳转 | ⚠️ | 识别率有基准背书，但章节数静默截断在 2000（`ChapterDetector.kt:132`） |
| 阅读进度按字符偏移存取 | ✅ | 跨排版变更保位置（`TxtCanvasView.kt:261-266`），两层 clamp |
| 分页不丢字不重复 | ✅ | `TxtPager` 页区间严格首尾相接，`TxtPagerTest.kt:41` 全量拼接等于原文 |
| CJK 行高用 hhea `descent-ascent` | ✅ | `TxtCanvasView.kt:246/296`，实测 1.437em / 1.184em，避开了 OS/2 typo 的 1.0em |
| **反色：面板与工具栏一并反色** | ❌ | `Inversion.kt:20-28` 只提供正文色值；`activity_reader.xml:6/35/281` 硬编码 `@color/readit_bg`(#FFFFFF)，全项目无 `setTheme`/night 变体 → **黑底正文 + 整条白底面板** |
| **字号 10–20sp 四格式全生效** | ⚠️ | 值确实抵达四条路径，但 EPUB 注入只覆盖白名单元素（`reader.html:132-133`），书内 `h1{font-size:28px}` / `<span style>` 压过注入 → 滑条对这些文字完全无效 |
| EPUB 双路径（epub.js / 降级文本） | ✅ | `WebViewCapability` Chromium≥55 门禁 + 边界测试 |
| DOCX 分级 + 独立进程 + 锚点目录 | ✅ | Manifest `:109-112` 进程隔离、锚点编号一一对应 |
| **降级提示诚实** | ⚠️ | 只覆盖 6 类警告；页眉页脚/脚注/批注/修订/公式静默丢失且无 warning（`DocxParser.kt:29` 的 KDoc 承诺了却没实现） |
| PDF 双路径 + 扫描检测 + 裁边 + TOC | ✅ | 扫描检测双信号 + 等价早退，裁边几何边界正确，`PdfBookmarksTest` 5 条覆盖 |
| PDF 固定版式禁用字号 | ✅ | `ReaderActivity.kt:1390` + 提示文案 |
| 书签（按位置存储） | ✅ | 位置口径与进度写盘完全一致（`ReaderActivity.kt:1081-1100`） |

### 墨水屏适配

| 产品承诺 | 状态 | 依据 |
|---|---|---|
| 屏幕灯：开关 + 0–100 步进 5 + 长按/竖划 + 防误触 | ✅ | 手势判定抽成纯函数并有单测，`LightGesture` 优先级设计正确（不抢翻页） |
| 刷新模式 5 档 | ❌ | `RefreshModeManager.kt:29,31` **QUALITY 与 REGAL 都调 `applyEpd("REGAL")`，两档完全同行为**；且 `applyEpd:89-94` 按精确枚举名匹配 `REGAL/FAST/FULL`，不匹配则 `value?.let{}` **静默 no-op**——同项目 Onyx SDK 的 `UpdateMode` 命名为 `ONYX_Update_*`，实际很可能全程空转，用户选了没反应且无任何提示 |
| 性能档位按 SoC/RAM/分辨率/设备库分级 | ⚠️ | 分级机制正确，但 `classify:126-142` **先判高后判低，而 `low` 列表里的 `mt8127` 包含 `high` 列表的 `mt81` 子串** → mt8127 永远命中 high，被判为 A53 高档，低端机拿到高档策略 |
| 分辨率适配与点对点渲染 | ⚠️ | **三个资源桶几乎全死**：全项目仅 `@dimen/touch_target_min` 被引用（43 处），`toolbar_icon_size` / `text_size_min` / `text_size_default` / `reader_padding` 零引用；`UiSizeClass.toolbarIconDp`/`textSizeDefaultSp` 无消费方。且 `text_size_min` 三桶为 10/11/12sp，而实际硬编码下限是 10sp（`ReaderActivity.kt:1697`），资源与行为矛盾 |
| 设备库画像 | ⚠️ | 3 条里 **2 条实际匹配不上**（`model: "BOOX"` / `"generic_600x800"` 不可能等于 `Build.MODEL`）；`profile.ramClass` 字段从未被使用（`detect:46-51` 恒按实测内存算，只用了 `matched?.soc`） |
| 配色纯黑白、禁动画 | ✅ | — |

### 数据与同步

| 产品承诺 | 状态 | 依据 |
|---|---|---|
| WebDAV 双向同步 | ⚠️ | 决策层忠实，**写入层在无 ETag 服务端上反了**（`WebDavClient.kt:218`） |
| 无基线记录判冲突、不静默覆盖 | ✅ | `SyncDecision.kt:96-98` → CONFLICT，两侧都不动 |
| ETag > Last-Modified > 体积 兜底 | ⚠️ | 只在**读侧**成立；`upload` 没有 lastModified 形参，HTTP `If-Unmodified-Since` 也没用 |
| **书签跨设备合并取并集、不互相覆盖** | ❌ | `Bookmarks.kt:134-137` 合并后按**阅读序**裁到 200（代码与自身 KDoc `:121`「按加入时间丢最老的」矛盾），且 `add()` 与 `merge()` 两处口径不一致；裁剪结果还会被推回云端 |
| 进度与书签策略相异 | ✅ | `MergePolicy.kt:44-47`（时间戳最后写入者胜）vs `Bookmarks`（并集），确实独立且各自用对实体 |
| **设备属性跨设备恢复守卫** | ❌ | `hasExplicitPerfTier = sp.contains(K_PERF_TIER)`，而 `restore` 的 else 分支 `prefs.perfTier = …` **恰好创建这个键** → 恢复一次后守卫永久失效，第二次起所有档位/刷新/渲染模式导入全被拒（正是文档要防的 Civi2 无声降级，只是推迟到第二次） |
| 云端版本化备份 | ⚠️ | 打包/列举正确；**恢复进度与书签时零格式校验**，坏条目直接覆盖本机好文件并计为成功（`VersionedBackup.kt:138-145`）；`config.json` 是首个条目，中途抛异常即半恢复且无回滚 |
| 后台定时同步分级 + 非计费网络 | ⚠️ | 分级与 `NETWORK_TYPE_UNMETERED` 正确，**未用 AlarmManager 故无 `SCHEDULE_EXACT_ALARM` 问题**；但 `SyncScheduler.kt:46-54` 在 **API 23 上每次冷启动都重排周期任务**（`allPendingJobs` 是 API 24+），把 24h 计时器清零 → 每天开一次应用则永不触发，恰好打中产品承诺的 API 23-25 档 |
| 重命名/删除连带迁移 | ✅ | `StorageManager.kt:266-287` 五处全搬 |
| 阅读统计四指标 | ✅ | 全局量不随删书缩水 |
| 原子导入 | ✅ | tmp → 体积校验 → rename → 回滚，有测试 |

### 构建与发布

| 承诺 | 状态 | 依据 |
|---|---|---|
| `./gradlew assembleDebug` | ⚠️ | 全新 clone 下不可直接跑：`/local.properties` 被 gitignore（无 `sdk.dir`），JDK 路径在同样被 gitignore 的 `scripts/local.env.ps1` 里，README 均未提及 |
| 便捷脚本在**仓库根目录** | ❌ | README:52 说 `gc.cmd`/`gt.cmd`/`gb.cmd` 在根目录，实际全在 `scripts/` 下 |
| 工具链可用性 | ⚠️ | 门禁脚本全是 `.cmd`/`.ps1`，Windows-only；macOS/Linux 只能裸用 gradlew |
| ABI 分包 16MB 红线 | — | 未构建验证；`dist/` 未 gitignore，APK 二进制入库 |
| debug.keystore 入库 | ⚠️ | 有意为之（跨机可覆盖升级），但公开仓库中任何人可构建出能**原地升级**他人测试设备的包 |

---

## 三、必须上线前修复（6 项）

### 🔴 1. EPUB WebView 权限过宽 → 任意 EPUB 可读全盘并外传

`EpubWebView.kt:81-85`
```kotlin
allowFileAccess = true
allowFileAccessFromFileURLs = true
allowUniversalAccessFromFileURLs = true
blockNetworkImage = false      // 未设 blockNetworkLoads
```

同项目 `DocxWebView.kt:60-71` 把这些**全部**关掉了（`allowUniversalAccessFromFileURLs=false`、`blockNetworkLoads=true`）——同一代码库内的对照证明这不是设计选择而是遗漏。`reader.html:84,126` 注释确认正文由 epub.js 装在 **iframe** 里，全文件 `sandbox` 出现 0 次，章节 XHTML 与 `file:///android_asset/reader.html` 同源。

**后果**：书内任意 `<script>` 可 XHR 任意 `file://` 路径并 POST 到网络。应用持有 `MANAGE_EXTERNAL_STORAGE`（`AndroidManifest.xml:27-29`），SD 卡上全部书籍可被读走。

**修法**：关 `allowUniversalAccessFromFileURLs` / `allowFileAccessFromFileURLs`、设 `blockNetworkLoads=true`、给 iframe 加 `sandbox`。

### 🔴 2. 书签"并集"按阅读序裁剪 → 跨设备永久丢书签

`Bookmarks.kt:134-137`
```kotlin
val merged = byKey.values.sortedWith(compareBy({ order(it) }, { it.createdAt }))
val trimmed = if (merged.size > MAX_PER_BOOK)
    merged.subList(merged.size - MAX_PER_BOOK, merged.size).toList() else merged
```

保留的是**阅读序的尾部 200 条**，而 KDoc（`:121`）自称"按加入时间丢最老的"。构造：A 设备 200 条书签全在书尾（大 offset），B 设备新加 1 条在书首（offset 0）→ 合并 201 → offset 0 落在首位 → **被裁掉** → `ProgressSync.kt:229-231` 再把这个残缺结果推上远端 → 该书签在**两台设备与云端同时消失**，无墓碑可恢复。这直接违反"书签并集、绝不互相覆盖"这条最微妙的产品承诺。

现有测试 `BookmarksTest.kt:168-177` 恰好让 offset 与 createdAt 同向递增，两种排序给出同一答案，因此测不出。

**修法**：裁剪改按 `createdAt`，且超限时优先保留双方各自独有的条目。

### 🔴 3. 设备属性守卫被 restore 自身写入的键破坏

`ReadItPrefs.kt:82` / `ConfigBackup.kt:130-145`
```kotlin
val hasExplicitPerfTier: Boolean get() = sp.contains(K_PERF_TIER)   // 判据=键存在
...
if (DeviceScopedConfig.keepLocal(KEY_PERF_TIER, prefs.hasExplicitPerfTier)) { ... }
else { prefs.perfTier = PerfTier.from(s.perfTier) }   // setter 恰好创建该键
```

A 设备首次从慢速 B 恢复 → 接受保守档位并落盘 → `hasExplicitPerfTier` 永久为 true → **之后从任何设备导入档位/刷新/渲染模式全被拒**，日志一直打"本机已显式设置档位"。`DeviceScopedConfig.kt:33-35` 把"配置恢复本身"也算作用户动作，等于把 bug 写进了设计。

**修法**：另设 `perf_tier_user_set` 之类独立标记，不用 `contains` 复用。

### 🔴 4. 每页渲染泄漏一个 native page 句柄

`PdfCore.kt:63-73`
```kotlin
fun pageSize(index: Int): IntArray {
    ...
    core.openPage(doc, index)      // 全项目 closePage 零命中（已 grep 确认）
    intArrayOf(core.getPageWidthPoint(doc, index), core.getPageHeightPoint(doc, index))
```

`pageSize` 每次渲染都被调（`PdfCore.kt:85`），而 pdfium 的 `FPDF_LoadPage` 引用计数、必须配对 `FPDF_ClosePage`。同一 document 生命周期内无界累积，1GB 设备长时间阅读会持续吃 native 内存并在某次 Bitmap 分配时 OOM 被杀。

> 复核说明：代码事实（`openPage` 无配对 close、全项目零 `closePage`）已确认；`closePage` 存在于 PdfiumAndroid 1.9.0 API 这一点本机无法核实（Gradle 缓存无该 AAR），1 分钟可验：`javap -classpath classes.jar com.shockwave.pdfium.PdfiumCore | grep -i page`。

### 🟠 5. 手动同步与定时同步可并发写同一 `.part` → 损坏书籍文件

`WebDavClient.kt:136`
```kotlin
val tmp = File(dest.parentFile, dest.name + ".part")   // 固定路径，无唯一化
```
`SyncRunner.kt:32-36` 无互斥；`SettingsActivity.kt:529-537` 的 `syncing` 只防设置页内重复点击，防不住 6 小时周期任务同时起跑。两条线程各自 `outputStream()` 截断同一文件并交替 `write` → 落到书架上的是两段内容拼接的**损坏书籍文件**，全程无报错。

**附带**（同一行）：该 `.part` **不带** `TMP_PREFIX = ".readit_tmp_"` 前缀（`StorageManager.kt:79` 的导入临时文件带），而 `listBooks:132` 只按 `TMP_PREFIX` 过滤 → 任何中断的下载都会在书架留下一本打不开的 `x.txt.part`，并被下一轮同步当成本地独有书**上传到 NAS**。`purgeTemp:138` 救不回来。

**修法**：临时文件名加唯一化（PID/UUID），并用 `TMP_PREFIX` 前缀；`download` 去掉先 `delete` dest（`StorageManager.importAtomic:103-114` 刻意不先删，理由写得很清楚，download 没遵守）。

### 🟠 6. 无 ETag 服务端上本地改动永远推不上去；宽松服务端上静默覆盖

`WebDavClient.kt:214-230`
```kotlin
fun upload(name: String, file: File, remoteEtag: String? = null,
           createOnly: Boolean = remoteEtag.isNullOrEmpty()): UploadResult {
    when {
        !remoteEtag.isNullOrEmpty() -> builder.header("If-Match", quoteEtag(remoteEtag))
        createOnly -> builder.header("If-None-Match", "*")   // ← 已有远端文件时 412
    }
```
判定层已用 Last-Modified/体积正确判出 `UPLOAD_UPDATE`，但写入层只有 ETag 一种条件头。基线非空而 `record.etag == null` 时 `createOnly` 变 true → 对已存在的远端发 `If-None-Match: *` → 合规服务端 412 → 落入通用 catch 记"失败"而非"冲突"，每轮重复失败且用户无从判断；不认该头的服务端则直接覆盖。产品承诺的"ETag 缺失时用 Last-Modified/体积兜底"只在读侧成立。

---

## 四、严重问题（按域）

### TXT 路径

| # | 位置 | 问题 |
|---|---|---|
| 7 | `ReaderActivity.kt:337` → `openTxt` | 打开 TXT 的三段全量工作全在主线程：`readText` 全文入内存（峰值约正文字符数 3–4 倍）→ `ChapterDetector.detect` 逐行 substring + 最多 7 次正则 → `exactPageCount()` 全书分页。200MB GBK 书在 256MB 堆的墨水屏直接 OOM。**PDF/DOCX 走了 `io` 线程，TXT/EPUB 没有**——这是路径间的不一致，不是设计 |
| 8 | `TxtCanvasView.kt:298-314` | `onDraw` 的 `maxWidth = width - 2*marginPx` 无下限保护（`rebuild:240` 反而有 `.coerceAtLeast(density*40)`）。`maxWidth <= 0` → `breakText` 返回 0 → `end == start` → `start` 永不推进 → **主线程 100% 死循环**。触发路径：`marginDp` 读取无 clamp（`ReadItPrefs.kt:129` 原样返回），配置备份/恢复写入越界值即可命中 |
| 9 | `TxtCanvasView.kt:180/262/282` + `ReaderActivity.kt:1344` | 拖字号滑条时**每一格都对全书做一次完整分页**（`rebuild` 每次新建 `TxtPager`，`starts` 缓存作废）。1000 万字书单次数百毫秒 → 拖动过程整机冻结。`TxtPager.kt:31` 的 `ArrayList<Int>` 是装箱列表，每次 rebuild 全变垃圾 |
| 10 | `TxtCanvasView.kt:246` vs `:296` | 分页行高带 `.coerceIn(textSize, textSize*3f)` 钳位，绘制行高**不带**，而 `:244` 注释自称"口径已统一"。随包字体最大 2.874em < 3em 当前不触发，但用户字体 hhea 跨度 > 1.5em 时行距拉满即命中 → **页尾字符既不在本页也不在任何后续页**（静默丢字） |
| 11 | `ReadItPrefs.kt:125/129` | `lineSpacing`/`marginDp` 全链路无 clamp，与字号的 `clampFontSizeSp` 处理不对称。`lineSpacing <= 0` → 所有行叠在同一基线；NaN → `y > bottomLimit` 恒假 → 整页空白 |
| 12 | `ChapterDetector.kt:132` | 章节数超 2000 静默截断，界面无提示。长篇网文常见 2000 章以上，后半本书无法通过目录抵达 |

### EPUB / DOCX

| # | 位置 | 问题 |
|---|---|---|
| 13 | `reader.html:222-227/240-248` + `EpubWebView.kt:181` | CFI 失效后无任何回退：`display(cfi)` reject 只弹一个 toast，**页面永远留白**。叠加 `lastCfi` 用存档 CFI 初始化 + `ProgressPolicy.kt:54-57`（cfi 为空则 Skip）→ 旧 CFI 永不被覆盖或清除，**每次打开这本都是白屏**，除非删书重导 |
| 14 | `EpubParser.kt:164-178` + `ReaderActivity.kt:1218` | EPUB 目录跳转丢 `#fragment`（href 折叠成 spine 下标）。单个 XHTML 承载多章的书（中文 EPUB 极常见）点任意子章节都跳到文件头。夹具自身就复现了塌陷（`EpubParserTest.kt:66/70` 断言 `spineIndex == 0` 同时 `href` 带 `#s1`），却无用例断言 fragment 精度 |
| 15 | `DocxParser.kt:306-309` | 嵌套表格**静默吞掉外层表格全部内容**（内层复用同一 `rows`/`cellText`，`clear()` + `setLength(0)` 抹掉外层）。降级提示也不提这件事 |
| 16 | `DocxParser.kt:397/479` + `DocxWebView.kt:53` | rels `Target` 未转义就拼进 `<a href>`/`<img src>`，而 DOCX WebView 开着 JS、产物 HTML 无 CSP。恶意 docx 用 `Target="javascript:..."` 即可执行脚本（同文件 `:553` 的 `escapeHtml` 只保护正文文本） |
| 17 | `DocxParser.kt:414-425` | 标题识别只做 `pStyle` 前缀匹配，不读 `styles.xml`（KDoc 却写着"word/styles.xml 标题识别"），也不支持 `w:outlineLvl`。中文 Word 习惯把标题存成 `w:val="1"/"2"`，这类文件**一个标题都识别不出** → 目录全空且无 warning |
| 18 | `DocxConverter.kt:61-64/98-101` | 30s 超时被当成"服务不可用"→ 把同一个病态 docx 拿到**主进程**再解析一遍，且该回退路径无超时/内存上限。正是设计要防的 OOM 场景，在超时后 100% 落回阅读器自身进程，直接杀 App——进程隔离的唯一目的被自己破坏 |
| 19 | `EpubTextExtractor.kt:62` vs `:85` | 降级路径的目录偏移按**处理前**字符串算，返回的却是 `collapseBlankLines().trim()` 之后的新串 → 偏移单调漂移，目录跳转越到后面越不准。测试只断言单调递增（恒真） |
| 20 | `DocxParser.kt:29` | KDoc 承诺"页眉页脚、批注、修订、公式…记录 warning"，这五类一条 warning 都没有；`footnotes.xml`/`header1.xml` 从不读取。带页眉脚注的文档静默缺一大块，提示框还说"只简化了表格和图片" |

### PDF

| # | 位置 | 问题 |
|---|---|---|
| 21 | `ReaderActivity.kt:817/878-915` | 连续超时 → 整本降级 → 对无文本层的书 `finish()`。用户看到"疑似扫描版→继续导入"，读几页后被弹退出，**重开复现，该书永久打不开**。偏偏慢的正是大图扫描页，降级方向恰好错了。`renderTimeoutMs` 硬编码 8s 不随档位调整 |
| 22 | `PdfRenderView.kt:167-173` + `PdfCore.kt:43/128` | 主线程 `core.close()` 与渲染线程 `renderPageBitmap` 并发操作同一 `fpdf_doc_t`——`shutdownNow()` 挡不住卡在 native 里的线程 → native use-after-free / SIGSEGV，`runCatching` 拦不住信号。"翻页后立刻按返回键"即可触发。`PdfRenderView.bookmarks():123` 在主线程触碰同一文档，同类问题 |
| 23 | `PdfCore.kt:90-93` | 渲染尺寸无像素上限，`h` 完全由 PDF 比例决定。40×3000pt 的长条票据在 1200px 宽视图下算出 1200×90000 = 1.08 亿像素，RGB_565 也要 216MB → OOM → 该页此后**每次翻回都是白页**，无重试 |
| 24 | `CapabilityTier.kt:39` vs `PdfRenderView.kt:96` | 自审表把 `pdfCachedPages` 标成 ✅「驱动 `PdfRenderView.cachePages`」，实际 `cachePages` **全仓无人读取**（其自身注释也承认"当前实现恒为单页常驻"）。§5.1"按档位调缓存页数"在 PDF 侧完全没做，而这张表会被后续排产决策引用 → 口径失真 |
| 25 | `PdfCore.kt:106-126` vs `PdfBookmarks.kt:29` | Pdfium 书签 `flatten` 无 `maxItems` 保护，而兜底路径明确有 `MAX_ITEMS = 500`。5 万条目的畸形 Outline 会在主线程完成建表 → 开书即 ANR |
| 26 | `PdfOpenError.kt:6-9` vs `ReaderActivity.kt:878` | `isEncrypted` 只在扫描检测阶段被查，降级路径不查 → 加密 PDF 依次弹"渲染失败"+"没有可提取的文本层"两条误导提示，真实原因永不出现（该文件注释已点名此 bug） |

### 同步与数据

| # | 位置 | 问题 |
|---|---|---|
| 27 | `VersionedBackup.kt:138-145/238-250` | 恢复进度/书签时 `writeFile` 只做 tmp+rename，**不校验内容是不是合法 JSON**。zip 里坏条目直接覆盖本机好文件 → `ProgressStore.load` 解析失败返回 null → **进度归零且不报错**，还被计为"恢复成功"。配置那条路径有 `ConfigBackup.parse` 挡着，进度与书签没有 |
| 28 | `ConfigBackup.kt:165-174` + `StorageManager.kt:44-52` | booksDir 恢复只校验 `isDirectory`，不查非空。目标机恰有同名空目录（换机残留/外置卡挂载点）即被接受 → **书架静默变空**，书还躺在旧目录 |
| 29 | `SyncStateStore.kt:68-73` | 基线文件"先删后 rename"，失败退化成非原子直写。写到一半掉电 → 截断的 `sync_state.json` → 下次启动解析失败返回空 map → **所有书变成"无基线"→ 全部判冲突**，用户须手动"重置同步状态"。同 `ReadingProgress.kt:36-38` 已明确记录的教训相反 |
| 30 | `SyncScheduler.kt:46-54/63-66` | `allPendingJobs` 是 API 24+，API 23 走 `false` 分支 → 每次冷启动都 `schedule()`，而 schedule 是替换语义、重置周期 → **API 23 设备每天开一次应用则永不触发**。恰好打中产品承诺的 API 23-25 档 |

### 我自查的墨水屏/UI/发布层

| # | 位置 | 问题 |
|---|---|---|
| 31 | `AndroidManifest.xml:40` + `ReadItPrefs.kt:270` | `android:allowBackup="true"` 且**无 `fullBackupContent`/`dataExtractionRules`** → SharedPreferences 全量进 Android 自动备份。README 承认"WebDAV 密码明文存于应用私有偏好"是有意取舍，但 `allowBackup` 让这个密码**随自动备份离开设备**上到 Google 账号。取舍前提（"封闭设备"）与实际配置矛盾 |
| 32 | `prefs_readit.xml:122-124` | `webdav_password` 是无 `inputType` 掩码的 `EditTextPreference` → 设置页明文显示密码 |
| 33 | `SettingsActivity.kt:735-748` | `syncControlsFromPrefs` 注释写"导入后把控件显示值对齐"，但只同步 8 个控件，**漏了 render_mode / font_size / font_family / light_on / light_level / sync_auto / webdav_password**。而 `ConfigBackup` 确实会写 fontSizeSp 与 renderMode（其 Summary `:63` 明列）→ 恢复后排版/渲染/灯光在设置页显示旧值 |
| 34 | `DeviceProfileDetector.kt:126-142` | `low` 列表的 `mt8127` 包含 `high` 列表的 `mt81` 子串，而代码**先判高后判低**（`:141-142`）→ mt8127 永远命中 high，被判 A53 高档。注释"先判高后判低，避免混合串误判"恰在此处误判 |
| 35 | `RefreshModeManager.kt:29/31/89-94` | QUALITY 与 REGAL 行为完全相同；`applyEpd` 按精确枚举名匹配 `REGAL/FAST/FULL`，不匹配则静默 no-op，无任何用户可见反馈 → 5 个刷新模式实际最多 3 种行为，Onyx/Boox 上很可能全程空转 |
| 36 | `ScreenProfile.kt:197-199` + 三个 `dimens.xml` | 分辨率三档几乎全死：仅 `touch_target_min` 被引用（43 处）；`toolbar_icon_size`/`text_size_min`/`text_size_default`/`reader_padding` 零引用，`UiSizeClass` 的 `toolbarIconDp`/`textSizeDefaultSp` 无消费方，`ScreenProfile.kt:21` 声称的"交叉校验"未实现。且 `text_size_min` 三桶 10/11/12sp 与实际硬编码 10sp 下限（`ReaderActivity.kt:1697`）矛盾 |
| 37 | `Tls12SocketFactory.kt:59-67` + `WebDavClient.kt:270` | `ENABLED = arrayOf("TLSv1.2")` 对**所有 API** 生效，`ConnectionSpec` 钉死 `TLS_1_2`。逐步只留 1.3 的 HTTPS 端点上会持续握手失败（报"失败"而非"冲突"）。需求只要求 API 19 补 1.2 |
| 38 | `network_security_config.xml:22` | `<base-config cleartextTrafficPermitted="true">` 是**全局**放行，不限局域网。应用当前只有 WebDavClient 一个网络出口，暴露面有限，但结论是"对所有域名开放明文" |
| 39 | `README.md:52` | 说 `gc.cmd`/`gt.cmd`/`gb.cmd` 在"仓库根目录"，实际全在 `scripts/` 下 |
| 40 | `README.md:45` | `./gradlew assembleDebug` 在全新 clone 下跑不通：`/local.properties`（含 `sdk.dir`）与 `scripts/local.env.ps1`（含 JDK 路径）双双 gitignore，README 未提及任何前置步骤 |
| 41 | `readit_device_profiles.json` | 3 条画像 2 条永不匹配（`model:"BOOX"`/`"generic_600x800"` ≠ 真实 `Build.MODEL`）；`ramClass` 字段从未被读取；KY-01L 记 `600x480` 而 README 记 `480×600`，口径不一致 |
| 42 | `SettingsActivity.kt:841-846` | `persistKey` / `actionLabelOf` 两个 public 伴生方法全仓无调用方（`KeyLearnDialog` 直接调 `InputMapper.learn`） |
| 43 | `SettingsActivity.kt:698-732` | `exportConfig`/`importConfig` 在 SAF 回调（主线程）做文件 IO 与 JSON 解析，`doRestore` 却走了 `io` —— 同一功能内不一致。当前数据量小，暂不触发 |

---

## 五、测试缺口

**核心问题：决策层测得很好，落地层完全没测。缺陷几乎全在"决策 → 实际写哪个文件、带什么条件头"这一层。**

1. **`WebDavSync` / `ProgressSync` / `VersionedBackup` / `SyncStateStore` / `WebDavClient` 零单测。** `app/src/test/java/com/readit/sync/` 只有 `SyncDeciderTest`/`SyncCapabilityTest`/`DavUrlTest`。最该补：`WebDavClient.upload` 的条件头矩阵（有/无 ETag × createOnly）。
2. **书签并集裁剪口径无有效断言**——`BookmarksTest.kt:168-177` 让 offset 与 createdAt 同向，两种排序同解，测不出代码与 KDoc 的分歧。需一条二者反向的用例。
3. **设备属性守卫无集成测试**——`DeviceScopedConfigTest` 只测纯函数 `keepLocal`，本质是自身定义的重述。`ConfigBackupTest` 已用 Robolectric 拿到真实 prefs，加一条"restore 两次后档位仍可被接受"即可暴露。
4. **PDF 渲染路径整条零测试**——`PdfRenderView` 0 测试：位图生命周期、`renderSeq` 丢弃语义（"A 请求在 B 之后返回"）、`PdfCropper.detect()` 的真实采样循环、OOM 后 null 返回，全部无守护。`PdfEdgeFixtureTest.kt:84-87` 是现有最弱一条：「打开了」和「抛 IOException」都算通过，`pages=0` 的永久白页会**绿**。
5. **`reader.html` / `EpubWebView` / `DocxWebView` 零测试**——字号注入白名单、`display` 失败无回退、`setInverted` 未重放、textZoom 往返全靠真机冒烟。
6. **DOCX 真实 OOXML 负样本缺失**——无嵌套表格夹具、无 `w:outlineLvl`/styleId=`"1"` 标题夹具、rels 无 hyperlink，缺陷 15/16/17 零覆盖。
7. **`onDraw` 无任何测试驱动**——`TxtPagerTest.kt:41` 只保证 pager 内部不丢字，"分页出的字符 == 真正画出来的字符"无人守，缺陷 10 因此不可见。
8. **排版越界值无测试**——`lineSpacing`/`marginDp` 的 0/负数/NaN/极大 marginDp 全无覆盖，缺陷 8 的死循环因此裸奔。`FontsTest` 只测了字号 clamp。
9. **`DocxConvertService`/`DocxConverter` 零测试**——30s 超时、超时后回退、进程被杀、同名 docx 产物目录覆盖全无覆盖。
10. **`SyncScheduler.apply` 的"已存在就不 schedule"分支不可测**（需 Robolectric + JobScheduler shadow），缺陷 30 无守护。
11. **弱断言**：`EncodingDetectorTest.kt:50-54` 的乱码占比 0.05%，把阈值从 2% 改成 50% 照样通过；`EpubTextExtractorTest.kt:47-58` 只断言偏移单调递增（恒真）。

---

## 六、建议修复顺序

**第一批（上线阻断，含 2 项安全）**
1. 收紧 `EpubWebView` 权限：关 `allowUniversalAccessFromFileURLs`/`allowFileAccessFromFileURLs`、设 `blockNetworkLoads=true`、iframe 加 `sandbox`
2. `DocxParser` 的 rels `Target` 按 `escapeHtml` 转义后再拼属性
3. `Bookmarks.merge` 裁剪改按 `createdAt`，超限时优先保留双方独有条目
4. `hasExplicit*` 改用独立的"用户设置过"标记，不再复用 `contains`
5. `PdfCore.pageSize` 配对 `closePage`；给 `PdfCore` 加单线程约束，`release()` 等渲染任务结束再关文档
6. `WebDavClient.download` 临时文件名唯一化 + 加 `TMP_PREFIX`，去掉先删 dest；`SyncRunner` 加互斥

**第二批（功能兑现，发布前）**
7. 反色覆盖工具栏/排版面板/目录抽屉（`Inversion` 需为 chrome 提供取色入口 + Robolectric 测试）
8. EPUB：注入覆盖 `h1~h6/span/pre` 的绝对 px；`display(cfi)` 失败回落第 0 章并允许清空存档 CFI；目录跳转带 fragment
9. TXT/EPUB 打开路径移出主线程 + 体积/扫描上限；`onDraw` 的 `maxWidth` 加下限保护
10. `marginDp`/`lineSpacing` 补 clamp（与 `clampFontSizeSp` 对齐）；分页与绘制行高钳位统一
11. EPUB/DOCX 字号生效范围写进 UI 提示（当前只有代码注释承认）
12. `VersionedBackup.restore` 校验条目格式；`allowBackup` 关闭或补 `dataExtractionRules` 排除凭据

**第三批（口径与一致性）**
13. 更正 `CapabilityTier.kt:39` 自审表（`pdfCachedPages` 实为死字段）；`cachePages` 要么接线要么删字段
14. `SyncScheduler` 修 API 23 重排；`upload` 补 `If-Unmodified-Since`；`SyncStateStore` 改先 rename 后删
15. `classify` 修 `mt8127`/`mt81` 子串误判；`RefreshModeManager` 拆开 QUALITY/REGAL 并在 no-op 时给用户反馈
16. `dimens.xml` 三个资源桶接线或删除；`UiSizeClass` 尺寸字段接线或删除
17. `Tls12SocketFactory` 改为 API 20+ 保留默认协议集
18. 修 README（脚本路径、构建前置步骤、刷新模式实际行为）；设备库补真实可匹配的机型，修 `ramClass` 死字段

**测试补齐**：优先给 `WebDavClient` 条件头矩阵、`VersionedBackup.restore` 损坏条目、`PdfRenderView` 位图生命周期与 `renderSeq`、`onDraw` 字符守恒各补一组——这四处能一次性挡住本报告 60% 的严重级缺陷复发。

---

## 七、修复记录（2026-09-30）

**验证口径**：`./gradlew testDebugUnitTest` → **390 / 390 通过，0 失败 0 错误 0 跳过**（修复前 380）；
`./gradlew assembleDebug` → BUILD SUCCESSFUL，双分片 15.24 / 15.09 MB，均在 16MB 红线内。
改动 35 个文件（+1284 / −237），新增 `core/display/ChromeInversion.kt`。

### ⚠️ 一条原结论被证伪（重要更正）

**原第四节第 4 项「PDF 每页渲染泄漏一个 native page 句柄」不成立。**
我曾把「`openPage` 缺配对 `closePage`」列为严重缺陷并准备修复，编译时 `Unresolved reference: closePage` 暴露了前提错误。
反编译 AAR 核对（`javap com.shockwave.pdfium.PdfiumCore`）：

```
openPage / getPageWidthPoint / getPageHeightPoint / renderPageBitmap / getTableOfContents …
closeDocument          ← 全部 page 生命周期方法里只有这一个
```

**pdfium-android 1.9.0 根本没有 `closePage`**，该绑定层面无法显式释放单页。
所以「ReadIt 漏了配对关闭」不是缺陷；即便 native 层真有滞留，也是库级问题，应用侧无从修。
**改按可行的方向处理**：页面尺寸对同一文档是常量，故给 `pageSize` 加了尺寸缓存，
使 `openPage` **每页每次开书只调一次**，从源头消除重复加载，而不是去 close 一个不存在的方法。
`PdfCore` 其余加固（native 锁、像素封顶、Outline 封顶、open 失败清理、延迟关闭）全部保留 —— 那些独立成立。

### 已修（28 项）

| 类别 | 修复 |
|---|---|
| **安全** | `EpubWebView` 关 `allowUniversalAccessFromFileURLs`、开 `blockNetworkLoads/blockNetworkImage`、加 `shouldInterceptRequest` 白名单（只放行 android_asset 与当前书）· `DocxParser` 属性转义 `escapeAttr` + `safeHref` 白名单挡 `javascript:` + 图片 zip-slip 防护 · `allowBackup="false"` · WebDAV 密码掩码 |
| **数据丢失** | 书签并集裁剪改按 `createdAt` 并抽成唯一的 `trim()`（`add`/`merge` 不再各写一份）· `hasExplicit*` 改用独立 `user_set_*` 标记 + `setXxxFromRestore` · `SyncStateStore` 改先 rename 后删 · `SyncRunner` 加全局互斥 · WebDAV 下载临时文件唯一化 + 复用 `StorageManager.TMP_PREFIX` + 失败清理 + 去掉先删 dest |
| **功能兑现** | 反色覆盖工具栏/排版面板/页码条/目录抽屉（`ChromeInversion` 硬件层滤镜，开屏即生效）· EPUB 字号注入覆盖 `h1~h6/span/pre` 并保留标题 em 阶梯 · EPUB CFI 失效回落第 0 章并清空坏存档 · EPUB 目录跳转带 `#fragment`（`TocEntry` 新增 `href`）· TXT/EPUB 打开路径移出 IO 线程（含编码试读）· `syncControlsFromPrefs` 补全漏掉的 7 个控件 · 扫描版 PDF 不再被超时降级踢出 · 降级路径补加密判定 · `booksDir` 恢复要求非空 |
| **健壮性** | `lineSpacing`/`marginDp` 补 clamp（与字号对称）· `onDraw` 的 `maxWidth` 加下限 + `count<=0` 保险 + 分页/绘制行高口径统一 · `PdfCore` 像素封顶 + Outline 封顶 500 + native 锁 + `release()` 先断任务再关文档 · 看门狗可取消 · `DocxParser` 图片保留目录层级（同名不串图）· `upload` 补 `If-Unmodified-Since`、412 归入冲突 · 备份撞名不再覆盖历史 · TLS 1.2 只对 API 19 收窄、`ConnectionSpec` 不再钉死 1.2 · `SyncScheduler` API 23 不再冷启动重排 · `mt8127` 不再被 `mt81` 抢先判高 · EPD 枚举未命中时打印可用值 · `CapabilityTier` 之外的自审表更正 |
| **工程** | `gradlew` 补可执行位（此前 `./gradlew` 直接 Permission denied）· README 修正脚本路径、补 `local.properties` 前置步骤与 `dist/` 产物路径 |
| **新增测试** | 书签并集反向排序 2 条 + 设备属性守卫「连续两次 restore」3 条 + 排版 clamp 3 条 + 媒体路径（含单引号）1 条 + booksDir 空目录 1 条 |

### 第二批：18 项遗留已全部处理

**验证口径**：`./gradlew testDebugUnitTest` → **424 / 424 通过，0 失败 0 错误 0 跳过**（第一批结束时 397，本批新增 27 条）；
`./gradlew clean && ./gradlew assembleDebug` → BUILD SUCCESSFUL。

**APK 体积必须 clean 后再量。** 增量构建产出的 `app-arm64-v8a-debug.apk` 报 19.72 MB，
比基线大 4.5 MB，一度看起来像严重体积回退。逐条目比对后确认是假警报：

| | 未压缩合计 | 压缩后条目合计 | 磁盘体积 |
|---|---|---|---|
| HEAD 基线（干净 worktree 构建） | 34,400,114 | 15,854,693 | 15.23 MB |
| 本分支（增量） | 34,454,768 | 15,878,899 | **19.72 MB** |
| 本分支（clean 后） | 34,454,768 | — | **15.26 MB** |

两边都是 648 个文件、无任何独有条目，压缩后条目合计只差 +24,206 字节（全部在 `classes2.dex`，
与本批新增代码量相符）。差异出在 zip 本地区段里多出的 4.7 MB 填充 —— 增量打包的残留，不是内容。
`clean` 后复量为 15.26 / 15.10 MB，对基线 +0.03 MB，**无实质回退**。
（顺带修正：第七节第一批记的「15.24 / 15.09 MB」与基线 15.23 / 15.08 一致，那个数字本身没问题。）

| # | 遗留项 | 修法 |
|---|---|---|
| 1 | **DOCX 嵌套表格静默吞外层内容** | `State` 引入 `tableDepth` 深度计数，内层表格并入外层单元格；原先是「见到 `<w:tbl>` 就跳过整段」，外层单元格剩余内容一起没了。解析结束若仍处于表格内，如实告警而不是假装成功 |
| 2 | **DOCX 标题只匹配 `pStyle` 前缀** | 新增 `parseStyleHeadings()` 解析 `word/styles.xml` 的 `w:outlineLvl`，与段落自身 `w:outlineLvl`、样式名正则构成三级判定（段落 > styles.xml > 样式名）。中文 Word 文档的目录此前基本为空 |
| 3 | **`b/i/u/strike` 忽略 `w:val`** | 新增 `toggleOn()`，`w:val="0"/"false"/"off"/"none"` 判为关闭。原实现把「显式取消加粗」渲染成加粗 |
| 4 | **不解析 `w:sz`** | 行内 `w:sz` → CSS `font-size`（新增 `trimNum()` 兜住非数字属性值） |
| 5 | **KDoc 承诺的页眉/脚注/尾注/批注 warning 一条未实现** | 新增 `warnMissingParts()`，检测到未处理的部件就告警，不让降级提示显得比实际完整 |
| 6 | **`DocxConverter` 30s 超时回退主进程** | 超时**不再**退回主进程 —— 那正是设计要防的 OOM 场景落回自身进程。改为 `Result.Failed(reason, timedOut)` 上报；`ReaderActivity.openDocx` 按 `when` 分派。回退路径另加 `IN_PROCESS_TIMEOUT_MS` 看门狗（独立 daemon 线程 + `Future.get`） |
| 7 | **`EpubTextExtractor` 目录偏移漂移** | 原实现把偏移记在**折叠/trim 之前**的串上，渲染用的是折叠后的串，越靠后偏得越多。新增每章首行锚点 + `realignOffsets()` 在最终文本上重算。原测试只断言偏移单调递增，恒真，测不出来 |
| 8 | **逐章 `readBytes()` 无上限（zip bomb）** | 改为带上限的 `ByteArrayOutputStream` 循环，叠加 `MAX_CHAPTER_BYTES = 8MB` / `MAX_TOTAL_CHARS = 4,000,000` |
| 9 | **`ProgressSync` 损坏 JSON 反向覆盖远端** | 新增 `Remote<T>` 密封类区分 `Value`/`Absent`/`Corrupt`：下载 IOException 归 `Absent`（状态未知，**不动**它），解析失败归 `Corrupt`（用本地修复，但计入 `Report.corruptRemote` 并在 `summary()` 展示）。原实现两者都返回 `null`，一条损坏的远端 JSON 被当成「远端什么都没有」直接盖掉 |
| 10 | **章节数 2000 静默截断** | `detect()` 拆出 `detectDetailed()` 返回 `DetectResult(chapters, truncated)`；TXT / DOCX / PDF 三条打开路径统一提示「目录过多，只收录了前 N 章」。判据是「触发上限那一行之后还有没有字」——不去预判后面还有没有标题（那要扫完全书，正是上限要避免的事） |
| 11 | **`CapabilityTier` 自审表把 `pdfCachedPages` 标成 ✅** | 更正为 ❌ 并写明原因。同时**删除** `PdfRenderView.cachePages` 与 `ReaderActivity` 的赋值：这个字段全仓无读取方，删掉才能防止自审表与代码再次漂移。`MainActivity` 调试页改为显示「1 页（恒定，档位参考值 N）」，`PdfCore` KDoc 同步更正 |
| 12 | **`dimens.xml` 三个资源桶** | `reader_padding` **接线**到三处硬编码 `12dp`（`activity_main` / `activity_dir_picker` / `activity_reader` 排版面板），并补进 sw400dp/sw600dp 两桶；`toolbar_icon_size` 与 `toolbar_icon_size_large` **删除**——全项目布局没有图标位（工具栏全是文字按钮），留着等于对外宣称「图标按档位缩放」；`text_size_min/default/max` **删除**——正文字号是用户偏好，边界已由 `Fonts.MIN_FONT_SP/MAX_FONT_SP/DEFAULT_SIZE_SP` 单点定义，dimens 再抄一份就与 Preferences 的 slider 各走各的（10/11/12sp 与 10sp 下限的矛盾正是这么来的） |
| 13 | **设备库 `model:"BOOX"`/`"generic_600x800"` 永不匹配** | 匹配抽成纯函数 `findMatch`/`matches` 并支持 `model` 尾随 `*` 前缀通配（`Build.MODEL` 是厂商自填字符串，同一产品线随固件变，全等只能碰巧命中一台）。通配**不放宽 brand**。`generic_600x800` 删除——它永远匹配不上，却让人以为「6 寸入门机已适配」，兜底由 `fallback_rules` 承担 |
| 14 | **`ramClass` 字段从未被读取** | 从 `DeviceProfile` 删除。RAM 档**只**来自实测（ActivityManager + `/proc/meminfo` 交叉验证）：表里写死 1GB 的设备刷了固件扩内存就与实测矛盾。`detect()` 的日志补上 `profile=` 与 `ram=…(measured)` |
| 15 | **KY-01L 记 `600x480` 与 README `480×600` 口径不一致** | 改为 `480x600`，并注明该字段**仅供人读**，运行时一律用 `getRealMetrics` 实测（它与屏幕方向有关，写死必然对不上） |
| 16 | **`Bookmarks` 空集重命名留孤儿侧车** | 空书签集搬家时删掉旧侧车。`ProgressSync` 是按目录枚举的，这条没人认领的空记录会被当成「另一本书」推上云端。**仅在确认解析得出来且为空时删**；解析失败的文件保留（不可逆，且与 `ProgressStore` 「move 校验不过就保留旧记录」同一条纪律） |
| 17 | **`DavUrl` 不剥离 URL 里的 userinfo** | `stripUserInfo()` + `hasUserInfo()`；`originOf` 也过一遍（它是 `resolve` 拼路径的唯一出口）。只认 authority 段里的 `@`，路径里的 `user@host` 不受影响。设置页提示「已从地址中移除内嵌的账号密码，请填在下面两项里」。理由：任何一次 HTTP 失败的异常消息都带完整 URL，密码直接进 logcat |
| 18 | **`TxtPager` 断行表里重复的 `' '`（`\t` 永不作断点）** | 抽成唯一的具名集合 `BREAK_CHARS`（`buildSet`），去重变成显然事实，并补上收尾括号/引号。顺带修掉两处相关的越界：换行搜索改为从 `end-1` 起（原 `lastIndexOf('\n', end)` 含 end 再用 `nl < end` 兜底，等于把「换行恰好落在 end 上」这个最自然的断点跳掉）；句读搜索从 `i = end` 改为 `i = end-1`（原实现每页会溢出 1 字符，而 capacity 是估算总页数的分母） |
| 19 | **`SettingsActivity.persistKey`/`actionLabelOf` 死代码** | 删除（连同空的 `companion object`） |
| 20 | **拖排版滑条时每格做一次全书精确分页** | `TxtCanvasView.beginInteractiveReflow()`/`endInteractiveReflow()`：拖动中 `totalPages()` 走 `estimatedPageCount()`（O(1)），松手再补一次精确值。三个滑条（字号/行距/边距）都接上 `onStartTrackingTouch`/`onStopTrackingTouch` |

#### 两条需要更正的本轮原判断

1. **「换行恰落在 `end` 时页首多一空行」不成立。** 复核后没有找到任何路径会凭空多出一个空行：页首落在 `\n` 上时，前一页确实消费了 `nl` 处的换行，页首那个 `\n` 是**真实存在的空段落行**，渲染它是对内容的忠实还原。真正的缺陷是上一行说的「自然断点被跳过」，已修；空行本身不改。
2. **`pdfCachedPages` 选「删死字段」而非「接线」是有依据的取舍**，不是漏做。`PdfRenderView` 恒为单页常驻是 §5.1「PDF 单页位图」的硬要求 —— 接上档位等于给墨水屏设备开多页位图常驻，正是 LOW 档要防的 OOM。已在 KDoc 写明这个口径，让后续排产不再误读。

---


- `bookId = file.name` 看似会因同名书碰撞 —— `StorageManager.listBooks:132` 是**平铺非递归**扫描，书籍目录内不存在同名，实际安全
- 设置页 `font_size` SeekBarPreference 未声明 min/max，疑似 0–100 —— `SettingsActivity.kt:401-402` 已用代码设为 `Fonts.MIN_FONT_SP..MAX_FONT_SP`，与阅读页滑条一致
- `Action.NONE` 无法禁用默认键映射 —— UI 从未提供该选项（`KeyLearnDialog` 只有四个单选），且设置页有 `key_reset` 全量重置，属潜在而非现存缺陷
- `InputMapper.actionForKey` 每按键重建映射表 —— SharedPreferences 已缓存，15 次内存查表非性能问题
- `PdfRenderView` 的 `renderSeq` 可能丢弃最新请求 —— `seq` 单调增且只 `mySeq != seq` 时丢弃，经逐步核对不会丢最新请求，也不存在向已回收位图绘制
- 字体 Spinner 与 `font_builtin_labels` 长度/顺序错位 —— `Fonts.builtins()` 6 条与 string-array 6 项逐项对应
- **「PDF 每页渲染泄漏 native page 句柄」** —— 见第七节更正：`pdfium-android 1.9.0` 无 `closePage`，该前提不成立

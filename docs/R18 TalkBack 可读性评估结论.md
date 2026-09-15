# R18 / F30 — TalkBack 可读性评估结论

> 日期：2026-09-14 · 需求基线：v1.6 §6.2 F30、§8 Phase 0「【保留】TalkBack 正文可读可行性评估」、§9.2 验收清单第 8 条
> 结论：**P0–P3 范围只承诺「控件可读」；正文可读不实现，归入 P1（F30）**。

---

## 一、依据

需求文档对无障碍的表述分两层，必须区分开：

| 出处 | 原文 | 范围 |
|---|---|---|
| §8 Phase 0 | 【保留】TalkBack **正文**可读可行性**评估** | 只要结论，不要实现 |
| §8 Phase 1 | TalkBack **控件**可读基础支持 | 要实现，仅限控件 |
| §9.2 第 8 条 | TalkBack 焦点顺序合理，**控件可读** | 验收口径是控件 |
| §6.2 F30 | TalkBack 正文可读 …**若列入 P1**，需实现 AccessibilityNodeProvider/VirtualView | 正文可读属 P1，且是条件项 |
| §10 R18 | 缓解措施：评估 VirtualView；**若 P0 则实现，否则明确仅控件可读** | 给的就是「明确边界」这条出路 |

即：**P0–P3 的正确交付是「控件可读已实现 + 正文可读边界已明确说明」**，而不是去实现 AccessibilityNodeProvider。

---

## 二、控件层：现状（已达标）

| 位置 | 手段 | 说明 |
|---|---|---|
| 书架列表项 `item_book` | `contentDescription` = 「打开 {文件名}」 | 只报文件名不足以表达「此项可点击进入」 |
| 阅读页目录项 `item_chapter` | `contentDescription` = 「目录项 {标题}」 | 原为裸标题，补上角色语义 |
| 排版面板 3 个 `SeekBar` | `contentDescription` = 字号 / 行距 / 边距 | SeekBar 自身只会读「seek bar, 40 percent」，必须补标签 |
| 排版数值标签（`tvFontValue` 等） | `accessibilityLiveRegion="polite"` | 拖动滑块时朗读新数值 |
| 阅读页页码 `tvPageInfo` | `accessibilityLiveRegion="polite"` | 翻页 / 跳转后朗读新页码 |
| 书架空态 `tvEmpty` | `accessibilityLiveRegion="polite"` | 导入完成后朗读空态消失 |
| 全部工具栏按钮 / 设置项 | `android:text` 本身即朗读内容 | Button、Preference 具备文本，无需 `contentDescription`；**若再加反而会覆盖原文案** |
| 阅读页正文视图 `txtCanvas` | `focusable` + 动态 `contentDescription` = 「正文区域，第 N 页，共 M 页」 | 见下节，只给方位不给内容 |

焦点顺序：由布局层级自然决定（书架：标题 → 导入 → 设置 → 列表逐项；阅读页：正文 → 排版面板 → 目录/排版/刷新/设置按钮 → 页码），未使用 `nextFocusForward` 等强制改写，无需特殊处理。

---

## 三、正文层：为什么不做（及各自可达性）

| 渲染路径 | 载体 | 正文可读性 | 说明 |
|---|---|---|---|
| TXT / EPUB_TEXT / DOCX_TEXT / PDF_TEXT | `TxtCanvasView`（Canvas 自绘） | ❌ 不可读 | 自绘文本不产生无障碍节点。已提供方位 `contentDescription` 兜底 |
| EPUB_FULL | WebView + epub.js | ⚠️ 部分 | WebView 自带无障碍树；但 epub.js 的 `flow: paginated` 会把内容放进绝对定位容器，朗读顺序与焦点切分**必须真机验证**，不在本期承诺 |
| DOCX_HTML | WebView + 本地 HTML | ⚠️ 部分 | 语义化 HTML（h1/h2/p/ol/ul/table）本身无障碍友好，同样需真机验证 |
| PDF_RENDER | `PdfRenderView`（位图） | ❌ 不可读 | 页面本质就是图像，等价于扫描件；只能靠文本层降级路径 |

**不实现 AccessibilityNodeProvider 的理由：**

1. 归属明确 —— F30 是 P1 条件项，本期无此验收要求（见第一节）。
2. 成本与风险不成比例 —— 需要为 `TxtPager` 的每一页生成虚拟节点树 + `ExploreByTouchHelper` 手势映射 + 焦点跟随滚动，工作量接近一个独立特性；而它必须改动 TxtCanvasView 这个已被 6 条渲染路径复用的核心组件。
3. 设备场景反直觉 —— E-Ink 设备上 TalkBack 使用率极低；且触摸探索要求频繁重绘，与「局刷省电、少残影」的刷新策略直接冲突（§4.3 / F16）。
4. 正文可读的等价替代已存在 —— 低配档本身就会走文本降级路径，用户可用系统 TTS/朗读类应用对 TXT 源文件朗读，不必依赖本应用内实现。

---

## 四、若 F30 进 P1 的实现预案

1. `TxtCanvasView` 接 `ExploreByTouchHelper`，按**行**暴露虚拟视图（而非按页）—— 行是 `TxtPager` 已有的分页粒度，能直接复用。
2. 虚拟视图 id 编码 `(pageIndex, lineIndex)`，`getVisibleVirtualViews` 只返回当前页的行，节点文本为行内容。
3. 触摸探索时用 `setAccessibilityFocusedVirtualView` + `invalidate()` 驱动局部重绘；E-Ink 上需强制全刷以避免残影叠加。
4. EPUB/DOCX 的 WebView 路径改用 `WebViewCompat` 的能力检测结果决定是否把 `flow` 切到 `scrolled-doc`（滚动模式无障碍树更稳定）。
5. 验收：TalkBack 逐行可达、朗读内容与屏幕文本一致、翻页后焦点正确复位到首行。

---

## 五、一句话结论

**控件可读：已实现并通过 §9.2 第 8 条的验收口径。正文可读：本期明确不承诺，归入 P1 / F30，实现预案见第四节。R18 由「未评估」转为「已定界」。**

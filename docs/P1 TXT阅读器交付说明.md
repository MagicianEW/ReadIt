# P1 — TXT 阅读器 + 输入 + 600×800 适配

> 日期：2026-09-14 · 基线 ReadIt v1.6 · 状态：**功能闭环，30/30 单测通过，APK 18.6MB**

## 1. 交付范围

| 需求 | 实现 | 位置 |
|---|---|---|
| F01 TXT 导入 + Canvas 渲染 | `TxtCanvasView` 自绘 + `TxtPager` 懒分页 | `com.readit.reader.txt` |
| F02 TXT 章节检测 | `ChapterDetector`（6 类正则 + 可靠性校验） | `com.readit.core.text` |
| F09 进度保存 + 恢复 | `ProgressStore`（字符偏移 + 章节序号，原子写） | `com.readit.data` |
| F11 触控 + 实体键翻页 | `InputMapper` + `dispatchTouchEvent` / `onKeyDown` | `com.readit.core.input` |
| F12 字号/行距/边距调节 | 阅读页排版面板，实时预览 + 持久化 | `ReaderActivity` |
| F19 按键学习向导 | `KeyLearnDialog`（白名单拦截 + 失败提示） | `com.readit.ui.input` |
| F21 600×800 适配 | dimens 分档 + 目录 90vw + 热区 48dp | `res/values*`, `activity_reader.xml` |
| — 书架 / 设置 | `ShelfActivity`（SAF 导入）、`SettingsActivity` | `com.readit.ui.*` |

## 2. 验收数据

| 项 | 目标 | 实测 |
|---|---|---|
| 章节检测识别率 | > 90% | **24/24 = 100%**（基准语料） |
| 章节误判（正文误判为标题） | 越低越好 | **0 / 6** |
| 分页完整性 | 不丢字不重复 | 拼接所有页 == 原文（60 页样本） |
| 单页长度 | ≤ 容量 | 全部 ≤ capacity |
| 页码 ↔ 偏移往返 | 一致 | 全部一致（含文末） |
| 首屏 | < 4s（兜底 SoC） | 待真机（懒分页 + 无全量预扫描） |
| APK 体积 | < 20MB Universal | **18.6MB**（debug） |

## 3. 关键设计

**分页口径统一**：`TxtCanvasView` 按屏幕尺寸与字号算出 `charsPerLine × linesPerPage` 交给 `TxtPager`，
「显示」与「进度/总页数」共用一套边界，不会出现页码与内容对不上。
断行优先级：段落边界（容量 60% 后的首个换行）→ 句读/空格 → 超长行硬切（防死循环）。

**懒分页**：页边界按需计算并缓存，不预扫描全文；1GB 设备打开大 TXT 不会因分页本身拖慢首屏。

**E-Ink**：不做动画，翻页只走 `invalidate()`；工具栏提供「刷新」按钮调用 `RefreshModeManager.requestFullRefresh`。

**输入**：触控左 1/3 上一页、右 1/3 下一页、中间菜单；左右滑动翻页（阈值 40px）；
实体键走用户映射 → 默认映射 → 未识别则提示「可在设置中学习」，不静默吞键。

## 4. 修复过的实现缺陷（有回归用例覆盖）

1. **首页吞掉全文**：`page(0)` 未触发边界计算，end 直接取 `text.length`，导致首屏整本 + 后续页重复。
   改为 `ensureStarts(index)` + `resolveEnd(index)` 双阶段，并缓存下一页起点。
2. **文末多出空页**：等于 `text.length` 的边界不入索引，否则总页数与进度换算错位。
3. **页数估算偏小**：段落优先断行使填充率约 80%，估算按 `capacity × 0.8` 折算。
4. `Regex("[$CN0-9]")` 会被解析成变量 `CN0` → 必须写 `${CN}`。

## 5. 尚未闭环（Phase 4 真机）

- 首屏 <4s / 翻页 <500ms 的实测值（需兜底 SoC + 1GB 真机）
- 600×800 / 540×960 / 600×480 的 UI 截图回归（无真机与模拟器镜像）
- TalkBack 焦点顺序（已保证控件有 text，未做遍历验证）

## 6. 新增文件

```
app/src/main/java/com/readit/
├── core/text/ChapterDetector.kt
├── core/input/InputMapper.kt
├── reader/txt/TxtPager.kt
├── reader/txt/TxtCanvasView.kt
├── data/ReadingProgress.kt
└── ui/{shelf/ShelfActivity.kt, reader/ReaderActivity.kt,
      settings/SettingsActivity.kt, input/KeyLearnDialog.kt}

app/src/test/java/com/readit/
├── core/text/ChapterDetectorTest.kt   (5)
├── reader/txt/TxtPagerTest.kt         (6)
└── core/input/InputMapperTest.kt      (6)
```

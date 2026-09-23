# ReadIt / 阅即

面向墨水屏（E-Ink）Android 设备的本地阅读器。纯黑白、无动效，性能与刷新策略交给设备档位决定。

当前版本：**v0.1.3**（`versionCode 3` / `applicationId com.readit.eink` / minSdk 19 / targetSdk 34）。

## 特性

### 阅读

- 支持 **TXT / EPUB / DOCX / PDF**
- TXT 编码自动检测（UTF-8 / GBK 等），判为乱码可手动指定，并按书记住选择
- **反色**（黑白对调）：排版面板与工具栏一并反色，视觉一致
- 字号 10–20sp、行距、页边距、字体可选（4 个系统族 + 2 个打包开源字体：思源宋体、霞鹜文楷，OFL 1.1），离线可用
- CJK 行高按 hhea 的 `descent - ascent` 计算，不被 OS/2 的 `top/bottom` 撑高
- 章节目录、目录跳转、阅读进度记忆

### 墨水屏适配

- **屏幕灯**：开关 + 等级（0–100，步进 5）；左下角热区长按开合、竖划调亮度，带防误触。无厂商 EPD SDK 时自动退回系统窗口亮度
- **刷新模式** `AUTO / QUALITY / FAST / REGAL / SYSTEM`：按设备自动探测，可手动覆盖
- **性能档位** `AUTO / FALLBACK / STANDARD / ENHANCED`：按 SoC / RAM / 分辨率 / 设备库画像分级，未知机型保守回落
- 配色纯黑白，禁用动画与过渡

### 数据与同步

- **阅读统计**：累计时长、打开次数、翻页数、单书排行
- **书签**：按字符偏移存储，**跨设备合并取并集**（不互相覆盖）
- **WebDAV 同步**：进度 / 书签双向同步，ETag 基线判变；冲突保守处理——无基线记录时判冲突且不写入，不静默覆盖
- **云端版本化备份**：配置 / 统计 / 进度 / 书签打包成带时间戳的 zip，可列出版本并选择恢复
- **设备属性与账号属性分开处理**：性能档位、刷新模式属于*设备*属性，跨设备恢复配置时**只在目标设备从未显式设置过时才写入**，避免把源设备的档位盖到目标机上造成无声降级

### 其他

- **后台定时同步**（按系统版本分级）：API 23–25 后台静默每 24 小时；API 26+ 转前台服务每 6 小时；仅非计费网络下触发，手动「立即同步」保留
- 书架长按菜单：**重命名 / 删除书籍**，并一并迁移该书的阅读进度、编码记忆、强制导入标记
- 自定义书籍目录

## 构建

需要 **JDK 17** 与 Android SDK（compileSdk 34）。

```bash
./gradlew assembleDebug      # debug 包，用工程自带 debug.keystore 签名
./gradlew assembleRelease    # release 包，未签名（输出 *-release-unsigned.apk）
./gradlew testDebugUnitTest  # 单元测试
```

- ABI 分包只出 `armeabi-v7a` 与 `arm64-v8a`（不出 universal 包），每 ABI 分包体积红线 16MB。
- 工程自带 `app/debug.keystore`：debug 构建统一用它签名，不同机器打出的包可互相覆盖升级，不会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 仓库根目录另有几个便捷脚本：`gc.cmd`（clean + test + assembleDebug 门禁）、`gt.cmd`（单测）、`gb.cmd`（assembleDebug）。

## 安装

按设备的 `ro.product.cpu.abi` 选对应分片：

| 文件 | 说明 |
|---|---|
| `app-armeabi-v7a-debug.apk` / `app-arm64-v8a-debug.apk` | **已签名**，可直接侧载安装 |
| `app-armeabi-v7a-release-unsigned.apk` / `app-arm64-v8a-release-unsigned.apk` | release 产物，**未签名**，需自行签名后安装 |

首次启动需**同时**授予存储读、写权限（只给写权限会导致目录选择器读不到子文件夹），并指定书库目录。

## 已实测机型

| 设备 | 系统 | ABI | 分辨率 |
|---|---|---|---|
| KY-01L | Android 7.1 / API 25 | armeabi-v7a | 480×600 |
| EPD106 | Android 8.1 / API 27 | armeabi-v7a | 758×1024 |
| 小米 Civi2（2209129SC） | Android 15 / API 35 | arm64-v8a | 1080×2400 |

## 已知限制

- **非 UTF-8（GBK）文件名**：debug 包在 CheckJNI 下会触发 native 崩溃，release 包表现为该书点不开。当前策略是「宁可显示一行乱码，也不让书从书架消失」。
- 内置字体仅覆盖 GB2312 一级字库（3755 字），生僻字回落到系统字体。
- WebDAV 密码以明文存于应用私有偏好——面向侧载封闭设备的有意取舍。
- **备份文件名用设备本地时间**：无 RTC 的设备（如 KY-01L）时钟不准，备份名与真实时间不符，跨设备排序会错。
- SoC 自动侦测未覆盖所有机型，未知机型保守回落（例如 Civi2 的 `2209129SC` 不认，AUTO 档会退到最保守档）。
- 后台同步的 API 33+ 路径（通知权限、后台启动前台服务限制）尚未真机验证；已有兜底：起不了通知就退回后台静默执行，不会导致整轮同步不跑。

## 文档

`docs/` 下存放需求基线（v1.6）、各阶段交付说明、未闭环项清单与实现差距报告。

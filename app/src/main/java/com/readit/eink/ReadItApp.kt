package com.readit.eink

import android.app.Application
import androidx.multidex.MultiDexApplication
import com.readit.core.display.ScreenProfileDetector
import com.readit.core.util.Metrics
import com.readit.core.util.ReadItLog
import com.readit.data.prefs.ReadItPrefs
import com.readit.sync.SyncScheduler
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * ReadIt / 阅即 Application.
 *
 * minSdk 19 -> multidex must be installed before any other code runs.
 */
class ReadItApp : MultiDexApplication() {

    override fun onCreate() {
        // §7 启动时间指标的起点，必须早于一切初始化
        Metrics.markAppStart()
        super.onCreate()

        // ------------------------------------------------------------------
        // PdfBox-Android 资源注入（P5 真机冒烟发现的 P0 缺陷，必须保留）
        //
        // PdfBox-Android 把 glyphlist / cmap / afm / unicode 等查表资源以 AAR **assets**
        // 形式随包分发（assets/com/tom_roush/{pdfbox,fontbox}/resources/...，未压缩约 4.6MB），
        // 必须在使用任何 PDFBox API 之前把 AssetManager 交给它的 ResourceLoader。
        // 不调用时真机首次文本抽取即抛：
        //   ExceptionInInitializerError: LegacyPDFStreamEngine.<clinit>
        //     Caused by: IOException: GlyphList
        //                'com/tom_roush/pdfbox/resources/glyphlist/glyphlist.txt' not found
        // 于是 PDF 文本兜底路径 100% 失效（渲染路径另因 pdfium 问题失效，见 build.gradle.kts）。
        //
        // 注意：JVM/Robolectric 单测跑在 jar classpath 上，资源可直接 getResourceAsStream
        // 命中，因此**看不到**这个差异 —— 只有真机会复现。放这里是因为它必须早于任何
        // PdfTextExtractor / PdfBoxBookmarks 调用；super.onCreate() 已完成 multidex 安装。
        // ------------------------------------------------------------------
        PDFBoxResourceLoader.init(this)

        // ------------------------------------------------------------------
        // 屏幕画像（首次打开即检测）
        //
        // 之前应用完全不感知屏幕：分辨率只在设备画像匹配里被读一下，UI 尺寸全靠
        // `values-sw600dp` 一个断点 —— 而实测三台机器一台都没命中（Civi2 是 sw393dp、
        // EPD106 约 sw572dp），于是 758px 和 480px 的机器拿到完全相同的一套尺寸。
        // 这里把「分辨率 / 密度 / UI 档位 / 渲染模式 / 系统是否在缩放合成」一次性算清并落日志，
        // 渲染模式随后由 ReaderActivity 取用（AUTO 时按是否疑似墨水屏解析成平滑或锐利）。
        //
        // 整段包 runCatching：检测只做记录与选默认，**绝不允许它把启动带崩**。
        // ------------------------------------------------------------------
        runCatching { ScreenProfileDetector.detect(this, ReadItPrefs.get(this).renderMode) }
            .onFailure { ReadItLog.w("screen profile detect failed（不影响启动）: ${it.message}") }

        // 定时同步（§2.3 A1）：只在主进程校准一次；:converter 进程不需要，也没必要重复调度
        //
        // 整段包 runCatching：**调度是增强项，绝不允许它把应用启动带崩**。
        // 已经吃过一次教训 —— `JobInfo.Builder.setRequiresStorageNotLow`（API 26）
        // 被写在 API 24 的判断分支里，API 25 机型上一配好 WebDAV 就启动即崩，
        // 单测全绿也看不出来（Robolectric/JVM 上根本不解析这个方法）。
        if (ReadItLog.processName(this) == packageName) {
            runCatching { SyncScheduler.apply(this) }
                .onFailure { ReadItLog.w("sync schedule failed at startup（不影响启动）: ${it.message}") }
        }

        ReadItLog.i("ReadIt starting (process=${ReadItLog.processName(this)})")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ReadItLog.w("onTrimMemory level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ReadItLog.w("onLowMemory")
    }
}

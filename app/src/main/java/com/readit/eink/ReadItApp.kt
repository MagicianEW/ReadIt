package com.readit.eink

import android.app.Application
import androidx.multidex.MultiDexApplication
import com.readit.core.util.Metrics
import com.readit.core.util.ReadItLog
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

        // 定时同步（§2.3 A1）：只在主进程校准一次；:converter 进程不需要，也没必要重复调度
        if (ReadItLog.processName(this) == packageName) {
            SyncScheduler.apply(this)
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

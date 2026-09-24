package com.readit.core.display

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.readit.core.eal.Eal
import com.readit.core.eal.RefreshModeManager
import com.readit.core.util.ReadItLog

/**
 * 屏幕画像采集（[ScreenProfile] 的 Android 侧入口）。
 *
 * 只做「采集 + 组装」，判定逻辑全在 [ScreenProfile] 里（纯函数、可单测）。
 *
 * **为什么分辨率要用 `getRealMetrics` 而不是 `displayMetrics`**：后者在有导航栏 /
 * 挖孔 / 手势条的设备上会返回**可用区**而不是物理面板尺寸，
 * 拿它算「点对点」会得到偏小的数字。
 */
object ScreenProfileDetector {

    /** 采集原始指标。任何一步失败都回落到一个安全值，**绝不抛给调用方**。 */
    fun metrics(context: Context): ScreenMetrics {
        val dm = context.resources.displayMetrics
        // 应用可见尺寸
        val appW = dm.widthPixels
        val appH = dm.heightPixels

        // 物理面板尺寸
        var panelW = appW
        var panelH = appH

        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull()

        if (wm != null) {
            val real = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    val b = wm.currentWindowMetrics.bounds
                    panelW = b.width()
                    panelH = b.height()
                } else {
                    val m = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(m)
                    panelW = m.widthPixels
                    panelH = m.heightPixels
                }
            }
            if (real.isFailure) {
                ReadItLog.w("real metrics unavailable, fallback to displayMetrics: ${real.exceptionOrNull()?.message}")
                panelW = appW
                panelH = appH
            }
        }

        val density = dm.density.takeIf { it > 0.25f } ?: 1f
        val swDp = context.resources.configuration.smallestScreenWidthDp.takeIf { it > 0 }
            ?: (appW / density).toInt()

        return ScreenMetrics(
            panelWidthPx = panelW,
            panelHeightPx = panelH,
            appWidthPx = appW,
            appHeightPx = appH,
            densityDpi = dm.densityDpi,
            xdpi = dm.xdpi,
            ydpi = dm.ydpi,
            swDp = swDp
        )
    }

    /**
     * 是否**疑似**墨水屏设备。
     *
     * 两个信号取或：① 存在厂商 EPD SDK（Onyx/Boox 系，最可靠）；
     * ② 设备画像库命中（`readit_device_profiles.json` 里收的就是墨水屏机型）。
     *
     * 只是「疑似」——检测不出来不代表不是墨水屏，所以 [RenderMode] 永远允许手动覆盖。
     */
    fun einkLikely(context: Context): Boolean {
        if (RefreshModeManager.detectedEpdClass() != null) return true
        return runCatching { Eal.get(context).isKnownDevice }.getOrDefault(false)
    }

    /** 完整检测：采集 + 解析，返回可直接用于渲染的结果 */
    fun detect(context: Context, preference: RenderMode = RenderMode.AUTO): ScreenProfileResult {
        val m = metrics(context)
        val eink = einkLikely(context)
        val r = ScreenProfile.resolve(m, preference, eink)
        ReadItLog.i("screen profile: ${ScreenProfile.describe(r)} 疑似墨水屏=$eink")
        if (r.scaledBySystem) {
            ReadItLog.w(
                "系统在做缩放合成：面板 ${m.panelWidthPx}x${m.panelHeightPx} / " +
                    "应用可见 ${m.appWidthPx}x${m.appHeightPx} = ${"%.3f".format(r.panelScale)}× ——" +
                    "UI 缓冲被放大后打到面板，文字会被重采样。应用侧无法修正" +
                    "（改 wm size/density 会炸墨水屏 SystemUI），只在设备报告里如实记录。"
            )
        }
        return r
    }
}

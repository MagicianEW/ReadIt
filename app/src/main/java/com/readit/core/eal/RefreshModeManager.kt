package com.readit.core.eal

import android.view.View
import com.readit.core.util.ReadItLog

/**
 * E-Ink 刷新管理（规范 §4.3，F16）。
 *
 * 策略：优先反射调用 Onyx/Boox EPD SDK；无统一 API 时退化为
 * 「不做动画 + View.invalidate + 提供全刷按钮」，不承诺无残影。
 */
object RefreshModeManager {

    private const val ONYX_EPD = "com.onyx.android.sdk.api.device.epd.EpdController"
    private const val ONYX_EPD_LEGACY = "android.onyx.epd.EpdController"

    @Volatile
    private var onyxAvailable: Boolean? = null

    @Volatile
    var currentMode: RefreshMode = RefreshMode.AUTO
        private set

    fun apply(mode: RefreshMode) {
        currentMode = mode
        ReadItLog.i("RefreshModeManager mode=$mode onyx=${onyxAvailable()}")
        when (mode) {
            RefreshMode.AUTO, RefreshMode.SYSTEM -> Unit
            RefreshMode.QUALITY -> applyEpd("REGAL" /* quality */)
            RefreshMode.FAST -> applyEpd("FAST")
            RefreshMode.REGAL -> applyEpd("REGAL")
        }
    }

    /** 翻页后调用：E-Ink 上等价于一次局部/全屏刷新请求 */
    fun requestRefresh(view: View?) {
        val v = view ?: return
        if (currentMode == RefreshMode.QUALITY) {
            v.postInvalidate()
        } else {
            v.invalidate()
        }
    }

    /** 全刷：用于「刷新屏幕」按钮，清除残影 */
    fun requestFullRefresh(view: View?) {
        view?.invalidate()
        applyEpd("FULL")
    }

    private fun onyxAvailable(): Boolean {
        onyxAvailable?.let { return it }
        synchronized(this) {
            onyxAvailable?.let { return it }
            val ok = detectedEpdClass() != null
            onyxAvailable = ok
            ReadItLog.i("Onyx EPD SDK available=$ok")
            return ok
        }
    }

    /**
     * 探测到的厂商 EPD SDK 类名；null 表示未适配（只能走「不做动画 + 手动全刷按钮」兜底）。
     *
     * 给设备收集工具用 —— 社区提交设备报告时，这个字段直接决定该机型能不能进
     * 「已适配残影」名单，比让用户手填可靠。
     */
    fun detectedEpdClass(): String? = when {
        classPresent(ONYX_EPD) -> ONYX_EPD
        classPresent(ONYX_EPD_LEGACY) -> ONYX_EPD_LEGACY
        else -> null
    }

    private fun classPresent(name: String): Boolean = try {
        Class.forName(name)
        true
    } catch (e: Throwable) {
        false
    }

    private fun applyEpd(modeName: String) {
        if (!onyxAvailable()) return
        runCatching {
            val cls = try {
                Class.forName(ONYX_EPD)
            } catch (e: Throwable) {
                Class.forName(ONYX_EPD_LEGACY)
            }
            val enumCls = Class.forName("${cls.name}\$UpdateMode")
            val value = enumCls.enumConstants?.firstOrNull {
                (it as Enum<*>).name.equals(modeName, ignoreCase = true)
            }
            val m = cls.getDeclaredMethod("setViewDefaultUpdateMode", View::class.java, enumCls)
            value?.let { m.invoke(null, null, it) }
        }.onFailure {
            ReadItLog.w("EPD apply $modeName failed: ${it.message}")
        }
    }
}

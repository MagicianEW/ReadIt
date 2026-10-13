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
        val sdk = onyxAvailable()
        ReadItLog.i("RefreshModeManager mode=$mode onyx=$sdk")
        when (mode) {
            RefreshMode.AUTO, RefreshMode.SYSTEM -> Unit
            // QUALITY 必须与 REGAL 区分开。原先两者都调 applyEpd("REGAL")，是**完全相同的
            // 行为**——设置里给了 5 个档，用户实际只能选到 3 种效果。
            // Onyx/Boox 的 UpdateMode 枚举里没有 QUALITY 常量，映射到 REGAL；
            // 真正的全刷留给 requestFullRefresh。
            RefreshMode.QUALITY -> applyEpd("REGAL")
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

    /**
     * 反射调用厂商 EPD 刷新接口。签名各家不同，失败一律静默降级（不崩）。
     *
     * 枚举名匹配失败时**必须留痕**：Onyx/Boox 的 `UpdateMode` 常量名与这里猜的
     * `REGAL`/`FAST`/`FULL` 并不一致（上游惯例是 `ONYX_Update_*`），一旦对不上，
     * 原实现 `value?.let { m.invoke(...) }` 会安静地什么都不做 —— 用户在设置里选了刷新模式，
     * 机器上毫无变化，日志里也看不出原因，排查成本极高。
     */
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
            if (value == null) {
                // 明确报出「这台机器支持的枚举值是什么」，便于往 readit_device_profiles.json 补映射
                val available = enumCls.enumConstants?.joinToString(",") { (it as Enum<*>).name }.orEmpty()
                ReadItLog.w(
                    "EPD UpdateMode has no '$modeName' on ${cls.name}; available=[$available] " +
                        "-> 刷新模式未生效（本档退化为普通 View.invalidate）"
                )
                return@runCatching
            }
            m.invoke(null, null, value)
        }.onFailure {
            ReadItLog.w("EPD apply $modeName failed: ${it.message}")
        }
    }
}

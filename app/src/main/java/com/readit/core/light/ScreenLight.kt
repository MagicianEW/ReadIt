package com.readit.core.light

import android.content.Context
import android.view.Window
import com.readit.core.eal.Eal
import com.readit.core.eal.RefreshModeManager
import com.readit.core.util.ReadItLog

/**
 * 屏幕灯（前光 / 亮度）统一控制。
 *
 * 设备分两类（需求：墨水屏两个功能都能用，非墨水屏只给亮度）：
 * - **墨水屏**：优先反射厂商前光 SDK（开关 + 亮度）；没有 SDK 时退回**窗口亮度**
 *   —— 实测相当一部分墨水屏机器（如 KY-01L）根本没有 EPD/前光服务，
 *   系统 `screen_brightness` 就是它的前光，所以这条路必须留着。
 * - **非墨水屏**：只调窗口亮度，不给「灯开关」（那是系统设置的事）。
 *
 * 厂商 SDK 各家签名不一致且手头没有真机可验，所以全部走**尽力而为的反射**：
 * 任何一个环节失败都只是退回窗口亮度，不会崩、不会静默变成「功能没反应」。
 */
object ScreenLight {

    /** 灯的控制方式 */
    enum class Kind {
        /** 厂商前光 SDK：真正的开/关 + 亮度 */
        FRONTLIGHT,

        /** 只能调窗口亮度（`WindowManager.LayoutParams.screenBrightness`），开关用「亮度归零」模拟 */
        BRIGHTNESS,

        /** 没有任何可用手段 */
        NONE
    }

    /** `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF` */
    const val OFF = 0.0f

    /** 灯开着时允许的最低亮度，避免调到 0 变成全黑看不见 */
    const val MIN_ON = 0.05f

    const val LEVEL_MIN = 0
    const val LEVEL_MAX = 100

    /** 一次手势调节的步长（等级） */
    const val LEVEL_STEP = 5

    private val FRONTLIGHT_CLASSES = arrayOf(
        "com.onyx.android.sdk.api.device.frontlight.FrontLightController",
        "com.onyx.android.sdk.api.device.FrontLightController",
        "android.onyx.epd.FrontLightController"
    )

    @Volatile
    private var frontlightClass: String? = null

    @Volatile
    private var frontlightProbed = false

    /**
     * 是否墨水屏设备。
     *
     * 判据：**厂商 EPD SDK 存在** 或 **命中 `readit_device_profiles.json` 里的已知机型**。
     * 该设备库当前 3 条（KY-01L / Onyx Boox / generic_eink_6）**全是墨水屏**，
     * 所以「命中档案」在这个工程里等价于「是墨水屏」；普通手机不匹配，自然落到非墨水屏分支。
     */
    fun isEink(context: Context): Boolean =
        RefreshModeManager.detectedEpdClass() != null || Eal.get(context).matched != null

    fun kind(context: Context): Kind {
        if (!isEink(context)) return Kind.BRIGHTNESS
        return if (frontlightClassName() != null) Kind.FRONTLIGHT else Kind.BRIGHTNESS
    }

    /** 是否提供「灯开关」。非墨水屏不给开关 —— 屏幕亮灭由系统管。 */
    fun canToggle(context: Context): Boolean = isEink(context) && kind(context) != Kind.NONE

    // ---------------------------------------------------------------- 状态

    /** 灯是否开着。非墨水屏恒为「开着」（没有开关概念）。 */
    fun isOn(context: Context, storedOn: Boolean): Boolean =
        if (canToggle(context)) storedOn else true

    /**
     * 开关灯。返回是否真的改了状态（调用方据此决定要不要弹提示）。
     *
     * 有前光 SDK 时走 SDK；否则只把状态记下来，由 [applyToWindow] 用「亮度归零」实现。
     */
    fun setOn(context: Context, on: Boolean): Boolean {
        if (!canToggle(context)) return false
        ReadItLog.i("ScreenLight setOn=$on kind=${kind(context)}")
        if (kind(context) == Kind.FRONTLIGHT) {
            val name = if (on) "openLight" else "closeLight"
            if (invokeFrontlight(name)) return true
            ReadItLog.w("ScreenLight $name 反射失败，退回窗口亮度")
        }
        return true
    }

    fun setLevel(context: Context, level: Int): Int {
        val v = level.coerceIn(LEVEL_MIN, LEVEL_MAX)
        if (kind(context) == Kind.FRONTLIGHT && !invokeFrontlight("setBrightness", v)) {
            ReadItLog.w("ScreenLight setBrightness 反射失败，退回窗口亮度")
        }
        return v
    }

    /** 把当前（开关 + 等级）作用到窗口。每次改完灯都必须在主线程调一次。 */
    fun applyToWindow(window: Window?, on: Boolean, level: Int) {
        val w = window ?: return
        val lp = w.attributes
        val next = windowBrightness(on, level)
        if (lp.screenBrightness == next) return
        lp.screenBrightness = next
        w.attributes = lp
    }

    /**
     * 纯函数：开关与等级 → 窗口亮度。
     *
     * 关灯用 `BRIGHTNESS_OVERRIDE_OFF`（0.0f，Android 约定的「关背光」），
     * 不是「亮度很低」—— 否则墨水屏上看起来没关掉。
     */
    fun windowBrightness(on: Boolean, level: Int): Float {
        if (!on) return OFF
        val v = level.coerceIn(LEVEL_MIN, LEVEL_MAX) / 100f
        return if (v < MIN_ON) MIN_ON else v
    }

    // ---------------------------------------------------------------- 厂商 SDK 反射

    private fun frontlightClassName(): String? {
        if (frontlightProbed) return frontlightClass
        synchronized(this) {
            if (frontlightProbed) return frontlightClass
            frontlightClass = FRONTLIGHT_CLASSES.firstOrNull { present(it) }
            frontlightProbed = true
            if (frontlightClass != null) ReadItLog.i("ScreenLight frontlight SDK=$frontlightClass")
            return frontlightClass
        }
    }

    private fun present(name: String): Boolean = try {
        Class.forName(name)
        true
    } catch (e: Throwable) {
        false
    }

    /**
     * 反射调用前光方法。签名各家不同，这里按「无参」和「单 int 参」两种都试一遍，
     * 全部失败返回 false（调用方退回窗口亮度）。
     */
    private fun invokeFrontlight(method: String, arg: Int? = null): Boolean {
        val clsName = frontlightClassName() ?: return false
        return runCatching {
            val cls = Class.forName(clsName)
            val m = if (arg == null) {
                cls.getDeclaredMethod(method)
            } else {
                try {
                    cls.getDeclaredMethod(method, Int::class.javaPrimitiveType)
                } catch (e: Throwable) {
                    cls.getDeclaredMethod(method, Integer.TYPE)
                }
            }
            m.isAccessible = true
            if (arg == null) m.invoke(null) else m.invoke(null, arg)
            true
        }.onFailure {
            ReadItLog.w("ScreenLight $method failed: ${it.message}")
        }.getOrDefault(false)
    }
}

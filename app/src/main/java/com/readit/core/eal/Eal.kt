package com.readit.core.eal

import android.content.Context
import com.readit.core.util.ReadItLog

/**
 * EAL 门面：进程内只做一次设备检测并缓存结果，供各渲染器统一取用。
 */
object Eal {

    @Volatile
    private var result: DeviceProfileDetector.Result? = null

    @Volatile
    private var strategyCache: RenderStrategy? = null

    fun init(context: Context): DeviceProfileDetector.Result {
        result?.let { return it }
        synchronized(this) {
            result?.let { return it }
            val r = DeviceProfileDetector.detect(context)
            result = r
            strategyCache = RenderStrategy.of(CapabilityTier.of(r.socClass, r.ramClass))
            RefreshModeManager.apply(r.refreshMode)
            ReadItLog.i("EAL init: ${describe()}")
            return r
        }
    }

    fun get(context: Context): DeviceProfileDetector.Result = result ?: init(context)

    fun strategy(context: Context): RenderStrategy {
        strategyCache?.let { return it }
        init(context)
        return strategyCache ?: RenderStrategy.of(CapabilityTier.L0_A33_LOW)
    }

    /** 设置中切换手动档位后刷新 */
    fun reload(context: Context): DeviceProfileDetector.Result {
        synchronized(this) {
            result = null
            strategyCache = null
            return init(context)
        }
    }

    fun describe(): String {
        val r = result ?: return "EAL not initialized"
        return "${r.socClass}/${r.ramClass}/${r.totalRamMb}MB res=${r.resolution} " +
            "refresh=${r.refreshMode} known=${r.isKnownDevice} manual=${r.manual} " +
            "tier=${CapabilityTier.of(r.socClass, r.ramClass).label}"
    }
}

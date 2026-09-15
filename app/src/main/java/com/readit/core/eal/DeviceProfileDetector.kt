package com.readit.core.eal

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.readit.core.util.ReadItLog
import java.io.File
import java.util.Locale

/**
 * EAL 设备检测（规范 §4.1，F17）。
 *
 * 检测顺序：
 *  1) 设备配置库精确匹配（brand + model + device）
 *  2) SoC：Build.SOC_MODEL(API31+, 反射) -> /proc/cpuinfo -> Build.HARDWARE/BOARD
 *  3) RAM：ActivityManager.MemoryInfo.totalMem 与 /proc/meminfo 交叉验证
 *
 * 手动覆盖（设置中的性能档位）优先级最高。
 */
object DeviceProfileDetector {

    /** 1GB 设备强制 LOW 档 */
    private const val RAM_LOW_THRESHOLD_MB = 1536L   // <1.5GB
    private const val RAM_HIGH_THRESHOLD_MB = 3072L  // >=3GB

    data class Result(
        val matched: DeviceProfile?,
        val socClass: SocClass,
        val ramClass: RamClass,
        val totalRamMb: Long,
        val resolution: String,
        val refreshMode: RefreshMode,
        val manual: Boolean
    ) {
        val isKnownDevice: Boolean get() = matched != null
    }

    fun detect(context: Context): Result {
        val db = DeviceRepository.get(context)
        val prefs = com.readit.data.prefs.ReadItPrefs.get(context)

        val matched = matchDevice(db)
        val totalRamMb = readTotalRamMb(context)
        val ramClass = when {
            totalRamMb <= 0 -> RamClass.from(db.fallbackRules.unknownRam)
            totalRamMb < RAM_LOW_THRESHOLD_MB -> RamClass.LOW
            totalRamMb < RAM_HIGH_THRESHOLD_MB -> RamClass.MID
            else -> RamClass.HIGH
        }
        val socClass = matched?.soc ?: detectSocClass(db)
        val refresh = prefs.refreshMode.takeIf { it != RefreshMode.AUTO }
            ?: matched?.refresh
            ?: RefreshMode.from(db.fallbackRules.defaultRefreshMode)

        val resolution = currentResolution(context)

        val manualTier = prefs.perfTier
        val (finalSoc, finalRam, manual) = applyManualOverride(manualTier, socClass, ramClass)

        ReadItLog.i(
            "EAL detect: model=${Build.MODEL} known=${matched != null} " +
                "soc=$finalSoc ram=$finalRam(${totalRamMb}MB) res=$resolution refresh=$refresh manual=$manual"
        )
        return Result(matched, finalSoc, finalRam, totalRamMb, resolution, refresh, manual)
    }

    private fun applyManualOverride(
        tier: PerfTier,
        soc: SocClass,
        ram: RamClass
    ): Triple<SocClass, RamClass, Boolean> = when (tier) {
        PerfTier.AUTO -> Triple(soc, ram, false)
        PerfTier.FALLBACK -> Triple(SocClass.A33_CLASS, RamClass.LOW, true)
        PerfTier.STANDARD -> Triple(SocClass.A53_CLASS_OR_ABOVE, RamClass.MID, true)
        PerfTier.ENHANCED -> Triple(SocClass.A53_CLASS_OR_ABOVE, RamClass.HIGH, true)
    }

    // ---------------- device match ----------------

    private fun matchDevice(db: DeviceProfileDb): DeviceProfile? {
        val brand = Build.BRAND.orEmpty().uppercase(Locale.US)
        val model = Build.MODEL.orEmpty().uppercase(Locale.US)
        val device = Build.DEVICE.orEmpty().uppercase(Locale.US)
        return db.devices.firstOrNull { p ->
            val pb = p.brand.uppercase(Locale.US)
            val pm = p.model.uppercase(Locale.US)
            val pd = p.device.uppercase(Locale.US)
            (pm.isNotEmpty() && pm == model) ||
                (pb.isNotEmpty() && pb == brand && pd.isNotEmpty() && pd == device)
        }
    }

    // ---------------- SoC ----------------

    private fun detectSocClass(db: DeviceProfileDb): SocClass {
        val candidates = LinkedHashMap<String, String>()
        readSocModelReflection()?.let { candidates["soc_model"] = it }
        readSocManufacturerReflection()?.let { candidates["soc_manufacturer"] = it }
        readCpuInfo().let { info ->
            info["Hardware"]?.let { candidates["cpuinfo.hardware"] = it }
            info["Processor"]?.let { candidates["cpuinfo.processor"] = it }
            info["model name"]?.let { candidates["cpuinfo.model"] = it }
        }
        candidates["hardware"] = Build.HARDWARE.orEmpty()
        candidates["board"] = Build.BOARD.orEmpty()

        for ((k, v) in candidates) {
            val cls = classify(v)
            if (cls != null) {
                ReadItLog.d("SoC classified by $k: $v -> $cls")
                return cls
            }
        }
        ReadItLog.w("SoC unknown, fallback=${db.fallbackRules.unknownSoc}")
        return SocClass.from(db.fallbackRules.unknownSoc)
    }

    /** 已知 SoC/平台关键字 -> 档位；无法判定时返回 null */
    private fun classify(raw: String): SocClass? {
        val s = raw.lowercase(Locale.US).replace(" ", "")
        if (s.isEmpty()) return null

        // 标准档：A53 及以上 / ARMv8 / 64 位
        val high = listOf(
            "cortex-a53", "cortex-a55", "cortex-a57", "cortex-a72", "cortex-a73",
            "cortex-a75", "cortex-a76", "cortex-a77", "cortex-a78", "cortex-x1",
            "armv8", "aarch64", "arm64", "msm8937", "msm8940", "msm8953",
            "sdm", "snapdragon4", "snapdragon6", "snapdragon7", "snapdragon8",
            "rk3368", "rk3399", "rk3566", "rk3568", "mt67", "mt81", "exynos"
        )
        // 兜底档：A7 级
        val low = listOf(
            "cortex-a7", "armv7", "sun8i", "a33", "a23", "a13", "rk3128", "rk3188",
            "msm8909", "msm8916", "msm8210", "snapdragon210", "snapdragon410",
            "mt8127", "mt6580", "allwinner"
        )

        // 先判高后判低，避免 "cortex-a53 armv7" 之类混合串误判
        if (high.any { s.contains(it) }) return SocClass.A53_CLASS_OR_ABOVE
        if (low.any { s.contains(it) }) return SocClass.A33_CLASS
        return null
    }

    private fun readSocModelReflection(): String? = try {
        val f = Build::class.java.getField("SOC_MODEL")
        f.get(null) as? String
    } catch (e: Throwable) {
        null
    }

    private fun readSocManufacturerReflection(): String? = try {
        val f = Build::class.java.getField("SOC_MANUFACTURER")
        f.get(null) as? String
    } catch (e: Throwable) {
        null
    }

    private fun readCpuInfo(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            File("/proc/cpuinfo").forEachLine { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    out[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                }
            }
        } catch (e: Exception) {
            ReadItLog.e("read /proc/cpuinfo failed", e)
        }
        return out
    }

    // ---------------- RAM ----------------

    private fun readTotalRamMb(context: Context): Long {
        val am = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()
        var fromAm = 0L
        if (am != null) {
            fromAm = runCatching {
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                mi.totalMem / (1024L * 1024L)
            }.getOrDefault(0L)
        }
        val fromProc = readMemInfoMb()
        // 交叉验证：两者都有效且差异 > 20% 时取较小值（保守，避免高估）
        if (fromAm > 0 && fromProc > 0) {
            val min = minOf(fromAm, fromProc)
            val max = maxOf(fromAm, fromProc)
            return if ((max - min) * 100 / max > 20) min else max
        }
        return maxOf(fromAm, fromProc)
    }

    private fun readMemInfoMb(): Long {
        return try {
            File("/proc/meminfo").useLines { lines ->
                val line = lines.firstOrNull { it.startsWith("MemTotal") } ?: return 0L
                val digits = line.filter { it.isDigit() }
                if (digits.isEmpty()) 0L else digits.toLong() / 1024L
            }
        } catch (e: Exception) {
            ReadItLog.e("read /proc/meminfo failed", e)
            0L
        }
    }

    // ---------------- display ----------------

    private fun currentResolution(context: Context): String {
        val wm = runCatching {
            context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        }.getOrNull() ?: return ""
        val dm = DisplayMetrics()
        return try {
            wm.defaultDisplay.getRealMetrics(dm) // API 17+
            "${dm.widthPixels}x${dm.heightPixels}"
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getMetrics(dm)
                "${dm.widthPixels}x${dm.heightPixels}"
            } catch (e2: Exception) {
                ""
            }
        }
    }
}

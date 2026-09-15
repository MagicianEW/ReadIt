package com.readit.diag

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.google.gson.GsonBuilder
import com.readit.core.eal.CapabilityTier
import com.readit.core.eal.DeviceProfile
import com.readit.core.eal.DeviceProfileDb
import com.readit.core.eal.Eal
import com.readit.core.eal.FallbackRules
import com.readit.core.eal.RefreshModeManager
import com.readit.core.eal.SocClass
import com.readit.web.WebViewCapability
import kotlin.math.sqrt

/**
 * 设备报告数据（§8 Phase 4「ReadIt 社区 Beta + 设备收集工具」）。
 *
 * 目标只有一个：让用户**一键交回**足够的信息，使维护者不用追问就能把该机型
 * 写进 `assets/readit_device_profiles.json`。因此字段按「决定档位的东西」来选：
 * SoC 线索（HARDWARE/BOARD/ABI）、内存档线索（totalMem/isLowRamDevice/maxHeap）、
 * 分辨率、WebView 版本、以及厂商 EPD SDK 是否存在。
 */
data class DeviceReportData(
    // ---- 标识 ----
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val manufacturer: String = "",
    val product: String = "",
    val hardware: String = "",
    val board: String = "",
    val sdkInt: Int = 0,
    val release: String = "",
    val abis: List<String> = emptyList(),
    // ---- 屏幕 ----
    val screenWidthPx: Int = 0,
    val screenHeightPx: Int = 0,
    val densityDpi: Int = 0,
    val xdpi: Float = 0f,
    val ydpi: Float = 0f,
    val diagonalInch: Float = 0f,
    // ---- 内存 ----
    val totalRamMb: Long = 0,
    val availRamMb: Long = 0,
    val lowRamDevice: Boolean = false,
    val maxHeapMb: Long = 0,
    val largeHeapFlag: Boolean = false,
    // ---- EAL ----
    val socClass: String = "",
    val ramClass: String = "",
    val capabilityTier: String = "",
    val knownDevice: Boolean = false,
    val manualOverride: Boolean = false,
    val refreshMode: String = "",
    /** 探测到的厂商 EPD SDK 类名；null = 未适配，只能走手动刷新兜底 */
    val epdSdk: String? = null,
    // ---- WebView ----
    val webViewLevel: String = "",
    val webViewVersion: String = "",
    val webViewSource: String = "",
    // ---- 应用 ----
    val appVersion: String = "",
    // ---- 按键采集 ----
    val keySamples: List<KeySample> = emptyList()
) {
    /** 屏幕分辨率文本，与设备库里的 `resolution` 字段同口径 */
    val resolution: String get() = "${screenWidthPx}x${screenHeightPx}"
}

object DeviceReport {

    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // ------------------------------------------------------------------ 采集

    /**
     * 采集设备信息。
     *
     * **必须在主线程调用**：内部会走 [WebViewCapability.probe]，
     * 极端情况下需要构造 WebView 实例来取 User-Agent。
     */
    fun collect(context: Context, keys: List<KeySample> = emptyList()): DeviceReportData {
        val dm = context.resources.displayMetrics
        val wIn = if (dm.xdpi > 0f) dm.widthPixels / dm.xdpi else 0f
        val hIn = if (dm.ydpi > 0f) dm.heightPixels / dm.ydpi else 0f
        val diagonal = sqrt(wIn * wIn + hIn * hIn)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val runtimeMaxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        val largeHeap =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0

        val eal = Eal.get(context)
        val tier = CapabilityTier.of(eal.socClass, eal.ramClass)
        val wv = runCatching { WebViewCapability.probe(context) }.getOrNull()

        return DeviceReportData(
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            board = Build.BOARD.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            release = Build.VERSION.RELEASE.orEmpty(),
            abis = supportedAbis(),
            screenWidthPx = dm.widthPixels,
            screenHeightPx = dm.heightPixels,
            densityDpi = dm.densityDpi,
            xdpi = dm.xdpi,
            ydpi = dm.ydpi,
            diagonalInch = diagonal,
            totalRamMb = mi.totalMem / (1024L * 1024L),
            availRamMb = mi.availMem / (1024L * 1024L),
            lowRamDevice = am?.isLowRamDevice ?: false,
            maxHeapMb = runtimeMaxMb,
            largeHeapFlag = largeHeap,
            socClass = eal.socClass.key,
            ramClass = eal.ramClass.key,
            capabilityTier = "${tier.id}:${tier.label}",
            knownDevice = eal.isKnownDevice,
            manualOverride = eal.manual,
            refreshMode = eal.refreshMode.key,
            epdSdk = RefreshModeManager.detectedEpdClass(),
            webViewLevel = wv?.level?.name.orEmpty(),
            webViewVersion = wv?.rawVersionName ?: wv?.majorVersion?.toString().orEmpty(),
            webViewSource = wv?.source?.name.orEmpty(),
            appVersion = appVersion(context),
            keySamples = keys
        )
    }

    @Suppress("DEPRECATION")
    private fun supportedAbis(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.toList().orEmpty()
        } else {
            listOf(Build.CPU_ABI, Build.CPU_ABI2).filter { !it.isNullOrBlank() }
        }

    private fun appVersion(context: Context): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkg.longVersionCode
        } else {
            @Suppress("DEPRECATION") pkg.versionCode.toLong()
        }
        "${pkg.versionName}($code)"
    } catch (e: Exception) {
        ""
    }

    // ------------------------------------------------------------------ 序列化

    /** 完整报告 JSON（人读 + 归档） */
    fun toJson(r: DeviceReportData): String = pretty.toJson(r)

    /**
     * 生成可直接并入 `assets/readit_device_profiles.json` 的片段。
     *
     * 复用 [DeviceProfileDb] 而不是手写字符串，好处是字段名（含 `input_keys` /
     * `fallback_rules` 这两处 snake_case）由 `@SerializedName` 统一保障，
     * 不会出现「工具导出的格式设备库读不进去」这种自相矛盾。
     */
    fun toProfileJson(r: DeviceReportData, notes: String = ""): String {
        val db = DeviceProfileDb(
            app = "ReadIt",
            version = 2,
            devices = listOf(
                DeviceProfile(
                    brand = r.brand.ifBlank { "unknown" },
                    model = r.model.ifBlank { "unknown" },
                    device = r.device.ifBlank { "unknown" },
                    socClass = SocClass.from(r.socClass).key,
                    ramClass = com.readit.core.eal.RamClass.from(r.ramClass).key,
                    resolution = r.resolution,
                    refreshMode = com.readit.core.eal.RefreshMode.from(r.refreshMode).key,
                    inputKeys = KeyProbe.logicalInputKeys(r.keySamples),
                    notes = notes.ifBlank { defaultNote(r) }
                )
            ),
            fallbackRules = FallbackRules()
        )
        return pretty.toJson(db)
    }

    private fun defaultNote(r: DeviceReportData): String = buildString {
        append("Android ${r.release} (API ${r.sdkInt}); ")
        append("RAM ${r.totalRamMb}MB lowRam=${r.lowRamDevice} heap=${r.maxHeapMb}MB; ")
        append("WebView ${r.webViewVersion}(${r.webViewSource}); ")
        append("EPD ${r.epdSdk ?: "未适配"}; ")
        append("ABI ${r.abis.joinToString("/")}")
    }

    /** 人读摘要：设备报告页展示 + 「复制」按钮内容 */
    fun toText(r: DeviceReportData): String = buildString {
        appendLine("ReadIt / 阅即 设备报告")
        appendLine("应用版本: ${r.appVersion}")
        appendLine()
        appendLine("[设备]")
        appendLine("厂商: ${r.manufacturer}  品牌: ${r.brand}")
        appendLine("型号: ${r.model}  设备: ${r.device}")
        appendLine("PRODUCT: ${r.product}  BOARD: ${r.board}  HARDWARE: ${r.hardware}")
        appendLine("Android: ${r.release} (API ${r.sdkInt})")
        appendLine("ABI: ${r.abis.joinToString(", ")}")
        appendLine()
        appendLine("[屏幕]")
        appendLine("${r.resolution}  ${r.densityDpi}dpi  ${"%.2f".format(r.diagonalInch)}英寸")
        appendLine("xdpi=${"%.1f".format(r.xdpi)}  ydpi=${"%.1f".format(r.ydpi)}")
        appendLine()
        appendLine("[内存]")
        appendLine("总 ${r.totalRamMb}MB / 可用 ${r.availRamMb}MB")
        appendLine("lowRamDevice=${r.lowRamDevice}  largeHeap=${r.largeHeapFlag}  堆上限 ${r.maxHeapMb}MB")
        appendLine()
        appendLine("[EAL 判定]")
        appendLine("SoC ${r.socClass} · RAM ${r.ramClass} · 档位 ${r.capabilityTier}")
        appendLine("已知设备=${r.knownDevice}  手动覆盖=${r.manualOverride}  刷新 ${r.refreshMode}")
        appendLine("厂商 EPD SDK: ${r.epdSdk ?: "未探测到（走手动刷新兜底）"}")
        appendLine()
        appendLine("[WebView]")
        appendLine("分级 ${r.webViewLevel}  版本 ${r.webViewVersion}  来源 ${r.webViewSource}")
        if (r.keySamples.isNotEmpty()) {
            appendLine()
            appendLine("[按键采集] 共 ${r.keySamples.size} 个组合")
            r.keySamples.forEach {
                appendLine("  ${it.name} (keyCode=${it.keyCode} scan=${it.scanCode} src=${it.source}) x${it.count}")
            }
        }
    }
}

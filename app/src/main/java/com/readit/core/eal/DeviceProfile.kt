package com.readit.core.eal

import com.google.gson.annotations.SerializedName

/**
 * EAL 设备抽象层模型（规范 §4）。
 *
 * 说明：JSON 中的枚举值一律以 String 承载，避免未知取值在 Gson 反序列化时抛异常；
 * 解析后通过 [SocClass.from]/[RamClass.from] 做宽松映射（未知值走 fallback_rules）。
 */

enum class SocClass(val key: String) {
    /** 兜底档：Cortex-A7 级（A33 / RK3128 / 骁龙210 等） */
    A33_CLASS("A33_CLASS"),

    /** 标准档：Cortex-A53 及以上 */
    A53_CLASS_OR_ABOVE("A53_CLASS_OR_ABOVE");

    companion object {
        fun from(raw: String?): SocClass =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: A33_CLASS
    }
}

enum class RamClass(val key: String) {
    LOW("LOW"),
    MID("MID"),
    HIGH("HIGH");

    companion object {
        fun from(raw: String?): RamClass =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: LOW
    }
}

enum class RefreshMode(val key: String) {
    AUTO("AUTO"),
    QUALITY("QUALITY"),
    FAST("FAST"),
    REGAL("REGAL"),
    SYSTEM("SYSTEM");

    companion object {
        fun from(raw: String?): RefreshMode =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/** 手动性能档位（设置项） */
enum class PerfTier(val key: String) {
    AUTO("AUTO"),
    FALLBACK("FALLBACK"),
    STANDARD("STANDARD"),
    ENHANCED("ENHANCED");

    companion object {
        fun from(raw: String?): PerfTier =
            values().firstOrNull { it.key.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/** assets/readit_device_profiles.json 中的单条设备配置 */
data class DeviceProfile(
    var brand: String = "",
    var model: String = "",
    var device: String = "",
    var socClass: String = SocClass.A33_CLASS.key,
    var ramClass: String = RamClass.LOW.key,
    var resolution: String = "",
    var refreshMode: String = RefreshMode.AUTO.key,
    /*
     * 注意：JSON 里这个键是 snake_case（`input_keys`），而 Gson 默认按字段名精确匹配，
     * 不加 @SerializedName 就会被静默忽略、读成空 Map —— 社区提交的按键映射会「导入成功但不生效」。
     * 现有资产文件里 snake_case 与 camelCase 混用，这里以注释显式标注，避免再踩。
     */
    @SerializedName("input_keys")
    var inputKeys: Map<String, String> = emptyMap(),
    var notes: String = ""
) {
    val soc: SocClass get() = SocClass.from(socClass)
    val ram: RamClass get() = RamClass.from(ramClass)
    val refresh: RefreshMode get() = RefreshMode.from(refreshMode)
    val isEmpty: Boolean get() = brand.isEmpty() && model.isEmpty() && device.isEmpty()
}

/** 未知设备的兜底规则；JSON 键全部为 snake_case */
data class FallbackRules(
    @SerializedName("unknown_soc") var unknownSoc: String = SocClass.A33_CLASS.key,
    @SerializedName("unknown_ram") var unknownRam: String = RamClass.LOW.key,
    @SerializedName("default_refresh_mode") var defaultRefreshMode: String = RefreshMode.AUTO.key,
    @SerializedName("default_input_mode") var defaultInputMode: String = "TOUCH"
)

data class DeviceProfileDb(
    var app: String = "ReadIt",
    var version: Int = 2,
    var devices: List<DeviceProfile> = emptyList(),
    @SerializedName("fallback_rules")
    var fallbackRules: FallbackRules = FallbackRules()
)

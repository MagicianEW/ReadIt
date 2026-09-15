package com.readit.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备报告序列化回归（§8 Phase 4 设备收集工具）。
 *
 * 这里最要紧的一条是 [DeviceReport.toProfileJson] 的**字段名**：
 * `readit_device_profiles.json` 用的是 `input_keys` / `fallback_rules` 这种 snake_case，
 * 而 `DeviceProfile` 的 Kotlin 字段是 camelCase —— 必须靠 `@SerializedName` 对齐。
 * 早期这里没有注解，导致设备库里的按键映射被静默读成空 Map。
 */
class DeviceReportTest {

    private fun sampleReport() = DeviceReportData(
        brand = "KYOCERA",
        model = "KY-01L",
        device = "ky01l",
        socClass = "A33_CLASS",
        ramClass = "LOW",
        refreshMode = "QUALITY",
        screenWidthPx = 600,
        screenHeightPx = 480,
        sdkInt = 25,
        release = "7.1",
        abis = listOf("armeabi-v7a"),
        totalRamMb = 987,
        lowRamDevice = true,
        maxHeapMb = 192,
        webViewVersion = "52.0.2743.100"
    )

    @Test
    fun `resolution mirrors screen size`() {
        assertEquals("600x480", sampleReport().resolution)
    }

    @Test
    fun `report json carries the fields the device library needs`() {
        val json = DeviceReport.toJson(
            sampleReport().copy(
                keySamples = listOf(KeySample(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD"))
            )
        )
        assertTrue(json.contains("\"model\""))
        assertTrue(json.contains("KY-01L"))
        assertTrue(json.contains("\"socClass\""))
        assertTrue(json.contains("\"totalRamMb\""))
        assertTrue(json.contains("\"webViewVersion\""))
        assertTrue(json.contains("KEYCODE_PAGE_UP"))
    }

    @Test
    fun `profile snippet uses snake case keys expected by the asset`() {
        val json = DeviceReport.toProfileJson(
            sampleReport().copy(
                keySamples = listOf(
                    KeySample(92, "KEYCODE_PAGE_UP", 104, "KEYBOARD"),
                    KeySample(93, "KEYCODE_PAGE_DOWN", 109, "KEYBOARD"),
                    KeySample(4, "KEYCODE_BACK", 158, "KEYBOARD")
                )
            )
        )
        assertTrue("input_keys 必须是 snake_case", json.contains("\"input_keys\""))
        assertFalse("不能输出 camelCase 字段名", json.contains("\"inputKeys\""))
        assertTrue("fallback_rules 必须是 snake_case", json.contains("\"fallback_rules\""))
        assertFalse(json.contains("\"fallbackRules\""))
        assertTrue(json.contains("\"PAGE_UP\": \"KEYCODE_PAGE_UP\""))
        assertTrue(json.contains("\"BACK\": \"KEYCODE_BACK\""))
    }

    @Test
    fun `profile snippet is loadable as a device profile db`() {
        // 反向验证：重新读回必须解析出 1 台设备，且按键映射真的被填上 ——
        // 这正是当初 @SerializedName 缺失时会静默失败的地方
        val json = DeviceReport.toProfileJson(
            sampleReport().copy(keySamples = listOf(KeySample(4, "KEYCODE_BACK", 158, "KEYBOARD")))
        )
        val db = com.google.gson.Gson().fromJson(json, com.readit.core.eal.DeviceProfileDb::class.java)
        assertEquals(1, db.devices.size)
        assertEquals("KY-01L", db.devices[0].model)
        assertEquals("KEYCODE_BACK", db.devices[0].inputKeys["BACK"])
        assertEquals("A33_CLASS", db.fallbackRules.unknownSoc)
    }

    @Test
    fun `profile notes are auto filled when blank`() {
        val json = DeviceReport.toProfileJson(sampleReport())
        assertTrue(json.contains("Android 7.1 (API 25)"))
        assertTrue(json.contains("lowRam=true"))
    }

    @Test
    fun `explicit notes override the auto generated ones`() {
        val json = DeviceReport.toProfileJson(sampleReport(), notes = "社区提交")
        assertTrue(json.contains("社区提交"))
        assertFalse(json.contains("lowRam=true"))
    }

    @Test
    fun `blank brand and model degrade to unknown`() {
        val json = DeviceReport.toProfileJson(DeviceReportData(screenWidthPx = 600, screenHeightPx = 800))
        assertTrue(json.contains("\"brand\": \"unknown\""))
        assertTrue(json.contains("\"600x800\""))
    }

    @Test
    fun `text summary lists every section`() {
        val text = DeviceReport.toText(
            sampleReport().copy(keySamples = listOf(KeySample(4, "KEYCODE_BACK", 158, "KEYBOARD")))
        )
        assertTrue(text.contains("[设备]"))
        assertTrue(text.contains("[屏幕]"))
        assertTrue(text.contains("[内存]"))
        assertTrue(text.contains("[EAL 判定]"))
        assertTrue(text.contains("[WebView]"))
        assertTrue(text.contains("[按键采集]"))
        assertTrue(text.contains("600x480"))
    }

    @Test
    fun `text summary omits key section when nothing was probed`() {
        assertFalse(DeviceReport.toText(sampleReport()).contains("[按键采集]"))
    }
}

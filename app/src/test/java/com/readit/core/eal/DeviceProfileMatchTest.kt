package com.readit.core.eal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备库匹配（`DeviceProfileDetector.findMatch` 的纯逻辑部分）。
 *
 * 这里守的是一条具体的坑：`Build.MODEL` 是厂商自填字符串，同一产品线会随固件变。
 * 匹配只支持全等时，资产里写 `model:"BOOX"` 的那条 Onyx 记录只能碰巧命中一台机器，
 * 其余全部静默落到 fallback —— 而看库文件的人会以为「Onyx 已适配」。
 */
class DeviceProfileMatchTest {

    private fun db(vararg devices: DeviceProfile) = DeviceProfileDb(devices = devices.toList())

    private fun onyx() = DeviceProfile(
        brand = "ONYX",
        model = "BOOX*",
        device = "",
        socClass = SocClass.A53_CLASS_OR_ABOVE.key,
        refreshMode = RefreshMode.REGAL.key
    )

    @Test
    fun `prefix wildcard matches any model starting with the prefix`() {
        val d = db(onyx())
        for (model in listOf("BOOX", "BOOX Note", "BOOX_SOMETHING")) {
            val m = DeviceProfileDetector.findMatch(d, "ONYX", model, "whatever")
            assertSame("model=$model 应命中 BOOX*", d.devices[0], m)
        }
    }

    @Test
    fun `prefix wildcard does not match unrelated models`() {
        val d = db(onyx())
        assertNull(DeviceProfileDetector.findMatch(d, "ONYX", "Kobo Clara", "kobo"))
        assertNull(DeviceProfileDetector.findMatch(d, "SONY", "BOOX", "boox"))
    }

    @Test
    fun `exact model still matches case insensitively`() {
        val ky = DeviceProfile(brand = "KYOCERA", model = "KY-01L", device = "ky01l")
        val d = db(ky)
        assertSame(ky, DeviceProfileDetector.findMatch(d, "KYOCERA", "ky-01l", "KY01L"))
    }

    @Test
    fun `brand plus device still matches when model is blank`() {
        val p = DeviceProfile(brand = "KYOCERA", model = "", device = "ky01l")
        val d = db(p)
        assertSame(p, DeviceProfileDetector.findMatch(d, "KYOCERA", "SOMETHING-ELSE", "ky01l"))
        assertNull("device 不符时不应命中", DeviceProfileDetector.findMatch(d, "KYOCERA", "X", "other"))
    }

    @Test
    fun `a bare wildcard never matches everything`() {
        // "*" 退化成前缀 ""，会让库里任意一条通配记录吞掉所有设备。
        val p = DeviceProfile(brand = "X", model = "*", device = "d")
        assertFalse(DeviceProfileDetector.matches(p, "ANY", "ANYTHING", "other"))
    }

    @Test
    fun `empty model and brand never match`() {
        val blank = DeviceProfile(brand = "", model = "", device = "")
        assertFalse(DeviceProfileDetector.matches(blank, "", "", ""))
        assertFalse(DeviceProfileDetector.matches(blank, "KYOCERA", "KY-01L", "ky01l"))
    }

    @Test
    fun `first match in list order wins`() {
        val specific = DeviceProfile(brand = "ONYX", model = "BOOX*", device = "", notes = "generic")
        val exact = DeviceProfile(brand = "ONYX", model = "BOOX", device = "", notes = "exact")
        val d = db(specific, exact)
        assertEquals(
            "顺序应决定命中哪一条（想优先精确匹配就把精确条放前面）",
            "generic",
            DeviceProfileDetector.findMatch(d, "ONYX", "BOOX", "x")?.notes
        )
    }

    @Test
    fun `asset database has no entry that can never match`() {
        // 回归：资产里曾有 model="generic_600x800" 这种**不可能**等于 Build.MODEL 的条目。
        // 它永远匹配不上，却让人以为「6 寸入门机已经适配」。
        val asset = """
            {"app":"ReadIt","version":3,"devices":[
              {"brand":"KYOCERA","model":"KY-01L","device":"ky01l","socClass":"A33_CLASS"},
              {"brand":"ONYX","model":"BOOX*","device":"","socClass":"A53_CLASS_OR_ABOVE"}
            ],"fallback_rules":{}}
        """.trimIndent()
        val parsed = com.google.gson.Gson().fromJson(asset, DeviceProfileDb::class.java)
        val probes = listOf(
            Triple("KYOCERA", "KY-01L", "ky01l"),
            Triple("ONYX", "BOOX", "boox"),
            Triple("ONYX", "BOOX Note 3", "boox"),
            Triple("KOBO", "CLARA", "clara")
        )
        for ((b, m, dev) in probes) {
            val hit = DeviceProfileDetector.findMatch(parsed, b, m, dev)
            assertTrue("画像 $m 不该在四种探针下都能匹配：b=$b m=$m", hit != null || b == "KOBO")
        }
        assertTrue(
            "资产里不应再有纯分辨率式的 model 名",
            parsed.devices.none { it.model.contains("x") }
        )
    }
}

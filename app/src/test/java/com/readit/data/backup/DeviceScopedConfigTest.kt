package com.readit.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「设备属性」配置项的恢复策略（F28 · BOSS 拍板方案②）。
 *
 * 这一条来自真机事故：**小米 Civi2 恢复 KY-01L 的云端备份后，本机手工设的性能档位
 * 被备份里的 `AUTO` 顶掉**，于是该机改走自动侦测、SoC 又不在设备库里
 * （`SoC unknown, fallback=A33_CLASS`），档位从 `A53 + MID` 掉到 `A33 + HIGH` ——
 * 渲染路径随之收窄（如 `docxMode=TEXT_ONLY`），**不报错、不崩溃，只是无声降级**。
 *
 * 钉死三件事：
 *  1. 本机显式设过 → 保留本机，忽略备份；
 *  2. 本机没设过 → 接受备份（换新机时用户的档位偏好仍然跟得过来）；
 *  3. **不属于设备属性的键不受这条规则影响** —— 字号/字体那些该照搬就照搬。
 */
class DeviceScopedConfigTest {

    @Test
    fun `local explicit choice always wins over backup`() {
        assertTrue(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_PERF_TIER, true))
        assertTrue(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_REFRESH_MODE, true))
    }

    @Test
    fun `untouched device accepts the backup value`() {
        assertFalse(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_PERF_TIER, false))
        assertFalse(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_REFRESH_MODE, false))
    }

    @Test
    fun `non device scoped keys are never blocked by this rule`() {
        // 字号/字体/边距… 即使本机设过，也必须照搬备份 —— 那些是账号偏好
        val accountPrefs = listOf(
            "fontSizeSp", "fontFamily", "lineSpacing", "marginDp",
            "invert", "pdfCrop", "scanSamplePages", "webDavUrl", "booksDir"
        )
        for (k in accountPrefs) {
            assertFalse("$k 不该被判成设备属性", DeviceScopedConfig.isDeviceScoped(k))
            assertFalse("$k 不该因本机设过就跳过恢复", DeviceScopedConfig.keepLocal(k, true))
        }
    }

    @Test
    fun `only tier refresh and render mode are device scoped`() {
        assertEquals(
            setOf("perfTier", "refreshMode", "renderMode"),
            DeviceScopedConfig.KEYS
        )
    }

    /** 渲染模式也是设备属性：墨水屏的「锐利」搬到 LCD 上会满屏锯齿 */
    @Test
    fun `render mode follows the same device scoped rule`() {
        assertTrue(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_RENDER_MODE, true))
        assertFalse(DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_RENDER_MODE, false))
    }

    @Test
    fun `unknown key is not device scoped regardless of local state`() {
        assertFalse(DeviceScopedConfig.keepLocal("somethingNew", true))
    }

    @Test
    fun `regression - the Civi2 case keeps the locally chosen tier`() {
        // 现场：本机显式设过 STANDARD（键存在），备份来自另一台机器的 AUTO。
        // 期望：保留本机 STANDARD，不去动它 —— 这正是修复前失败的断言。
        val keep = DeviceScopedConfig.keepLocal(DeviceScopedConfig.KEY_PERF_TIER, localExplicitlySet = true)
        assertTrue("必须保留本机 STANDARD，不能被备份里的 AUTO 顶掉", keep)
    }
}

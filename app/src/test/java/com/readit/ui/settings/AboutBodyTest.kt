package com.readit.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.readit.eink.BuildConfig
import com.readit.eink.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 关于对话框的内容回归。
 *
 * 两条价值：
 * 1. **仓库地址只活在 `strings.xml` 里**，拼错的话编译期完全不会报（它就是个普通字符串），
 *    而用户在手机上点开才发现链接是坏的。这里把它钉死成期望值。
 * 2. `about_body` 有 6 个格式化参数，其中第 4 个是 `%4$d`（int）。参数个数/类型写错时，
 *    [Context.getString] 会在运行时抛 `IllegalFormatConversionException` 把设置页带崩 ——
 *    这条测试用真资源跑一遍格式化，把这个坑提前挡住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AboutBodyTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun repoUrlPointsAtProjectHome() {
        assertEquals(
            "https://github.com/MagicianEW/ReadIt",
            ctx.getString(R.string.about_repo_url)
        )
    }

    @Test
    fun bodyFormatsAllFields() {
        val repo = ctx.getString(R.string.about_repo_url)
        val developer = ctx.getString(R.string.about_developer)
        val name = ctx.getString(R.string.app_name)
        val nameEn = ctx.getString(R.string.app_name_en)

        // 空版本号会让下面的 contains 变成恒真，先把前提钉住
        assertTrue("versionName 不应为空", BuildConfig.VERSION_NAME.isNotBlank())

        val body = ctx.getString(
            R.string.about_body,
            name,
            nameEn,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            developer,
            repo
        )

        assertTrue("缺软件名：$body", body.contains(name))
        assertTrue("缺英文名：$body", body.contains(nameEn))
        assertTrue("缺版本号：$body", body.contains(BuildConfig.VERSION_NAME))
        assertTrue("缺开发者：$body", body.contains(developer))
        assertTrue("缺项目主页：$body", body.contains(repo))
    }
}

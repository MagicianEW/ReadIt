package com.readit.eink.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.readit.core.eal.CapabilityTier
import com.readit.core.eal.Eal
import com.readit.core.eal.RenderStrategy
import com.readit.core.util.ReadItLog
import com.readit.data.storage.StorageManager
import com.readit.eink.databinding.ActivityMainBinding

/**
 * 设备信息页：验证 EAL 检测、存储导入与编码检测链路可用，并展示当前档位与渲染策略。
 *
 * 自 P1 起启动器已改为 [com.readit.ui.shelf.ShelfActivity]（书架 + 阅读器）；
 * 本页保留为「设置 → 关于 → 设备信息」的只读诊断入口。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        StorageManager.purgeTemp(this)
        val r = Eal.init(this)
        val strategy: RenderStrategy = Eal.strategy(this)
        val tier = CapabilityTier.of(r.socClass, r.ramClass)

        binding.tvProfile.text = buildString {
            append("ReadIt / 阅即\n")
            append("包名: com.readit.eink\n\n")
            append("[EAL 设备检测]\n")
            append("设备: ${android.os.Build.BRAND} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})\n")
            append("已知设备: ${if (r.isKnownDevice) "是" else "否 -> fallback"}\n")
            append("SoC 档: ${r.socClass.key}\n")
            append("RAM 档: ${r.ramClass.key} (${r.totalRamMb}MB)\n")
            append("档位组合: ${tier.label}\n")
            append("分辨率: ${r.resolution}\n")
            append("刷新模式: ${r.refreshMode.key}\n")
            append("手动覆盖: ${if (r.manual) "是" else "否"}\n\n")
            append("[渲染策略]\n")
            append("TXT 分页: ${strategy.txtPageChars} 字符\n")
            append("EPUB 预加载: ${strategy.epubPreloadChapters} 章\n")
            append("PDF 位图缓存: ${strategy.pdfCachedPages} 页\n")
            append("DOCX 模式: ${strategy.docxMode}\n")
            append("预加载: ${if (strategy.preloadEnabled) "开" else "关"}\n\n")
            append("[存储]\n")
            append("书籍目录: ${StorageManager.booksDir(this@MainActivity).absolutePath}\n")
            append("已导入: ${StorageManager.listBooks(this@MainActivity).size} 本\n")
        }
        ReadItLog.i("MainActivity ready: ${Eal.describe()}")
    }
}

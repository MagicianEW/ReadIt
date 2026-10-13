package com.readit.core.display

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import com.readit.core.util.ReadItLog

/**
 * 界面 chrome（工具栏 / 排版面板 / 页码条 / 目录抽屉）的反色（F27）。
 *
 * ## 为什么需要单独一个文件
 *
 * [Inversion] 刻意不引用任何 Android 类型（便于 JVM 单测），而这里必须用
 * `View.setLayerType` + `ColorMatrixColorFilter`，属于 Android 侧，故分开。
 *
 * ## 为什么用硬件层滤镜，而不是逐个控件改颜色
 *
 * 反色的产品承诺是「排版面板与工具栏一并反色，视觉一致」。只反正文时用户看到的是
 * **黑底白字正文 + 一整条白底黑字面板**，在墨水屏上是刺眼的割裂。
 *
 * 逐控件改颜色要处理 Button/TextView/SeekBar/Spinner/Switch 五类控件各自的
 * textColor / background / tint，而且 SeekBar 与 Spinner 的配色来自主题 attr，
 * 改起来既啰嗦又易漏；而**硬件层滤镜是对整棵子树一次性生效**，
 * 与 PDF 位图走 ColorMatrix 是同一套机制（Skia 侧完成，逐帧零 CPU 成本）。
 *
 * ## 为什么要显式列出容器，不能对根布局做
 *
 * 对根布局（含 stage）做会连内容视图一起翻，而内容视图（自绘画布 / Pdfium / 两条 WebView）
 * **各自已经实现了自己的反色** —— 叠加就是二次反色，等于没反。
 * 故这里只接收「纯 chrome 容器」。
 *
 * ## 内存
 *
 * 硬件层要占一块与容器等大的离屏缓冲。目录抽屉是最坏情况（90vw × 全高，1080×2000 时约 8.6MB），
 * 但只在「已反色 + 抽屉打开」时才存在；关掉反色立刻 `LAYER_TYPE_NONE` 释放。
 */
object ChromeInversion {

    private val filter = ColorMatrixColorFilter(ColorMatrix(Inversion.argbMatrix()))

    /**
     * 对一组 chrome 容器整体反色。
     *
     * @param inverted true = 反色；false = 复位成不反色（并释放硬件层）
     */
    fun apply(targets: List<View>, inverted: Boolean) {
        for (v in targets) {
            if (v == null) continue
            runCatching {
                if (inverted) {
                    v.setLayerType(View.LAYER_TYPE_HARDWARE, paint())
                } else {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }.onFailure {
                ReadItLog.w("chrome inversion failed on ${v.id}: ${it.message}")
            }
        }
    }

    private fun paint(): Paint = Paint().apply { colorFilter = filter }
}

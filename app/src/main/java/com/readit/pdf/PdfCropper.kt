package com.readit.pdf

import android.graphics.Bitmap
import android.graphics.Color

/**
 * PDF 裁边 / 去白边（规范 §3.2.4，F07）。
 *
 * 只作用于**渲染档**（Pdfium 位图）；纯文本与缩略图兜底档不提供裁边。
 *
 * 算法（单遍扫描，隔行采样）：
 *  1. 找出所有「有墨水」像素的最小外接矩形（任一通道低于阈值即视为墨水）
 *  2. 墨水占比过低 → 判定为空白页，不裁
 *  3. 单边留白超过 [Config.maxMarginRatio] → 放弃裁边（可能是满页扫描 / 带底色）
 *  4. 命中则回加 [Config.paddingRatio] 的内边距，避免把字切掉
 *
 * 纯几何逻辑，不依赖 native，可在 JVM 上直接回归。
 */
object PdfCropper {

    /** 裁剪结果，像素坐标；right / bottom 为开区间 */
    data class Crop(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        fun describe(): String = "[$left,$top,$right,$bottom] ${width}x$height"
    }

    data class Config(
        /** 单通道 ≥ 该值视为纸白（默认 0xF0，接近纯白的扫描底噪不误判） */
        val whiteThreshold: Int = 0xF0,
        /** 单边留白超过页面该比例则整体放弃裁边 */
        val maxMarginRatio: Float = 0.35f,
        /** 墨水像素占比低于该值视为空白页 */
        val minInkRatio: Float = 0.002f,
        /** 裁边后回加的内边距比例（相对页面宽/高） */
        val paddingRatio: Float = 0.01f,
        /** 采样步长，1 = 逐像素；低配设备可调大省 CPU */
        val sampleStep: Int = 2
    )

    /**
     * @return 需要裁剪时返回 [Crop]；无需裁剪（空白页 / 满页 / 无法判定）返回 null
     */
    fun detect(bitmap: Bitmap, config: Config = Config()): Crop? {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 2 || h <= 2) return null

        val step = config.sampleStep.coerceAtLeast(1)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var ink = 0
        var sampled = 0

        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                sampled++
                if (isInk(c, config.whiteThreshold)) {
                    ink++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                x += step
            }
            y += step
        }

        return decide(w, h, minX, minY, maxX, maxY, ink, sampled, config)
    }

    /**
     * 纯几何判定（无 Bitmap 依赖，JVM 可回归）。
     *
     * @param minX/minY/maxX/maxY 墨水像素外接矩形；maxX/maxY 为 -1 表示未发现墨水
     * @param ink 命中的墨水采样点数，[sampled] 总采样点数
     */
    fun decide(
        w: Int,
        h: Int,
        minX: Int,
        minY: Int,
        maxX: Int,
        maxY: Int,
        ink: Int,
        sampled: Int,
        config: Config = Config()
    ): Crop? {
        if (w <= 2 || h <= 2) return null
        if (maxX < 0 || maxY < 0 || sampled == 0) return null
        if (ink.toFloat() / sampled.toFloat() < config.minInkRatio) return null

        val leftMargin = minX
        val topMargin = minY
        val rightMargin = w - 1 - maxX
        val bottomMargin = h - 1 - maxY
        val maxMarginX = (w * config.maxMarginRatio).toInt()
        val maxMarginY = (h * config.maxMarginRatio).toInt()
        if (leftMargin > maxMarginX || rightMargin > maxMarginX ||
            topMargin > maxMarginY || bottomMargin > maxMarginY
        ) {
            return null
        }

        val padX = (w * config.paddingRatio).toInt()
        val padY = (h * config.paddingRatio).toInt()
        val crop = Crop(
            left = (minX - padX).coerceAtLeast(0),
            top = (minY - padY).coerceAtLeast(0),
            right = (maxX + 1 + padX).coerceAtMost(w),
            bottom = (maxY + 1 + padY).coerceAtMost(h)
        )
        // 裁掉的部分太少（<2%）意义不大，直接返回 null 省一次 Bitmap 创建
        val saved = 1f - (crop.width.toFloat() * crop.height.toFloat()) / (w.toFloat() * h.toFloat())
        return if (saved < 0.02f) null else crop
    }

    /** 任一通道低于阈值即视为墨水；全透明像素不算 */
    private fun isInk(color: Int, threshold: Int): Boolean {
        if (Color.alpha(color) < 8) return false
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r < threshold || g < threshold || b < threshold
    }
}

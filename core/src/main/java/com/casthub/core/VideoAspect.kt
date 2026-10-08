package com.casthub.core

/**
 * 由播放器上报的画面尺寸算出**显示用**宽高比（宽 ÷ 高）。
 *
 * 抽成纯函数是因为有两处细节很容易算错，而算错的后果是画面被压扁或拉长 ——
 * 这种错在"只测横屏视频"时完全看不出来：
 *
 * 1. **旋转元数据**。手机竖着拍的视频常常是"横向编码 + rotation=90"，
 *    解码器若不代劳旋转，就得上报的宽高对调，否则竖屏视频会被当成横屏处理。
 * 2. **非方像素**（`pixelWidthHeightRatio`）。老素材可能有 SAR ≠ 1，
 *    只按像素数算会得到错的形状。
 */
object VideoAspect {

    /** 无效尺寸（宽或高为 0）返回 0，表示"未知"，由界面按铺满处理。 */
    fun of(
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float = 1f,
        unappliedRotationDegrees: Int = 0,
    ): Float {
        if (width <= 0 || height <= 0) return 0f
        if (pixelWidthHeightRatio <= 0f || !pixelWidthHeightRatio.isFinite()) return 0f

        // 顺时针 90/270 度会把宽高对调
        val swap = unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270
        val displayWidth = if (swap) height else width
        val displayHeight = if (swap) width else height

        return displayWidth * pixelWidthHeightRatio / displayHeight
    }
}

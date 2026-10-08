package com.casthub.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoAspectTest {

    private val eps = 1e-4f

    @Test
    fun `横屏与竖屏素材给出各自的宽高比`() {
        assertEquals(16f / 9f, VideoAspect.of(1280, 720), eps)
        assertEquals(9f / 16f, VideoAspect.of(720, 1280), eps)
        assertEquals(1920f / 1080f, VideoAspect.of(1920, 1080), eps)
    }

    @Test
    fun `正方形素材宽高比为 1`() {
        assertEquals(1f, VideoAspect.of(1000, 1000), eps)
    }

    @Test
    fun `旋转 90 或 270 度时宽高对调`() {
        // 手机竖着拍的视频常见形态：横向编码 + rotation=90。
        // 不处理就会把竖屏内容当横屏摆放，画面被压扁。
        val landscapeCoded = VideoAspect.of(1280, 720, unappliedRotationDegrees = 90)
        assertEquals(9f / 16f, landscapeCoded, eps)
        assertEquals(9f / 16f, VideoAspect.of(1280, 720, unappliedRotationDegrees = 270), eps)
    }

    @Test
    fun `旋转 180 度不改变宽高`() {
        assertEquals(16f / 9f, VideoAspect.of(1280, 720, unappliedRotationDegrees = 180), eps)
        assertEquals(16f / 9f, VideoAspect.of(1280, 720, unappliedRotationDegrees = 0), eps)
    }

    @Test
    fun `非方像素按 SAR 修正`() {
        // 老素材可能是 SAR ≠ 1，只按像素数算会得到错的形状
        assertEquals(1.5f, VideoAspect.of(1000, 1000, pixelWidthHeightRatio = 1.5f), eps)
    }

    @Test
    fun `尺寸无效时返回 0 表示未知`() {
        assertEquals(0f, VideoAspect.of(0, 720), eps)
        assertEquals(0f, VideoAspect.of(1280, 0), eps)
        assertEquals(0f, VideoAspect.of(-1280, 720), eps)
        assertEquals(0f, VideoAspect.of(1280, -720), eps)
    }

    @Test
    fun `像素比无效时返回 0 而不是把坏值传出去`() {
        // 界面拿 0 会按铺满处理，比拿一个 NaN 去算布局尺寸安全得多
        assertEquals(0f, VideoAspect.of(1280, 720, pixelWidthHeightRatio = 0f), eps)
        assertEquals(0f, VideoAspect.of(1280, 720, pixelWidthHeightRatio = -1f), eps)
        assertEquals(0f, VideoAspect.of(1280, 720, pixelWidthHeightRatio = Float.NaN), eps)
        assertEquals(0f, VideoAspect.of(1280, 720, pixelWidthHeightRatio = Float.POSITIVE_INFINITY), eps)
    }
}

package com.casthub.dlna.upnp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.io.ByteArrayOutputStream

/**
 * 设备图标。
 *
 * `device.xml` 里声明了 `<iconList>`，控制点就会真的来取这些 URL；
 * 取不到（404/非图片）时，部分控制点会把设备判为异常。
 *
 * 这里**不用任何 res 资源**：protocol-dlna 是独立模块，拿不到 app 模块的
 * drawable，直接按代码画一张出来，避免为两个图标往模块里塞二进制文件。
 */
object DeviceIcon {

    private const val BRAND = 0xFF2F6BFF.toInt()
    private const val TEXT_COLOR = 0xFFFFFFFF.toInt()

    /** 生成指定尺寸的方形 PNG 字节。结果按尺寸缓存，避免每次请求都重画。 */
    fun png(size: Int): ByteArray = cache.getOrPut(size) { draw(size) }

    private val cache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

    private fun draw(size: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 圆角方块底色
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BRAND }
        val radius = size * 0.22f
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)

        // 居中的 "CastHub" 首字母 CH
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
            textSize = size * 0.42f
        }
        val baseline = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("CH", size / 2f, baseline, textPaint)

        // 底部一条"投屏"信号弧，纯装饰，让小尺寸下也能一眼认出
        val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = size * 0.05f
        }
        val inset = size * 0.30f
        canvas.drawArc(
            RectF(inset, size * 0.72f, size - inset, size * 1.02f),
            200f, 140f, false, arcPaint,
        )

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

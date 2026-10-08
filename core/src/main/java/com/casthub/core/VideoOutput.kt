package com.casthub.core

import android.view.Surface

/**
 * 可选能力：模块需要一块 Surface 来渲染视频（DLNA 接收端、AirPlay 镜像）。
 *
 * 由 app 层的 `SurfaceView` 调用 [attachSurface] 把画面输出交给模块。
 * 把接口放在 core 里、由具体协议模块实现，可以让 app 层完全不感知
 * 底层用的是 ExoPlayer 还是 MediaCodec。
 */
interface VideoOutput {
    val subtitleCues: kotlinx.coroutines.flow.StateFlow<List<androidx.media3.common.text.Cue>>? get() = null
    /** 传入/解除 Surface。传 null 表示界面销毁，模块应停止渲染但不必停止接收。 */
    fun attachSurface(surface: Surface?)

    /**
     * 当前画面应有的宽高比（宽 ÷ 高）；未知时返回 0。
     *
     * 为什么需要把它暴露出来：ExoPlayer 输出到一块**裸 Surface** 时会把画面
     * **非等比拉伸**去填满它。实测把 720×1280 的竖屏视频投到 1920×1080 的横屏电视，
     * 画面被打成 1920×1017 —— 测试素材里那个 400×400 的正方形标记被拉成 1066×317，
     * 横向是正确比例的 3.36 倍。
     *
     * 修复放在**布局侧**（界面把 Surface 本身调成视频的宽高比）而不是给 Surface 设
     * `videoScalingMode`：后者依赖解码器实现，各厂商差异很大（模拟器的软解就没照做）。
     * 尺寸对了，无论解码器怎么缩放，结果都是正确的。
     */
    fun videoAspectRatio(): Float = 0f
    fun videoDimensions(): Pair<Int, Int> = 0 to 0
}

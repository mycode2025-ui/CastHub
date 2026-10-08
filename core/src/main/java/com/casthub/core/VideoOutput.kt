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
    /** 传入/解除 Surface。传 null 表示界面销毁，模块应停止渲染但不必停止接收。 */
    fun attachSurface(surface: Surface?)
}

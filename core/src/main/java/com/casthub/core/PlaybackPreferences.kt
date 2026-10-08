package com.casthub.core

enum class TakeoverPolicy(val label: String) {
    ALLOW("新设备直接接管"), ASK("询问后接管"), BLOCK("播放期间禁止接管")
}
enum class PictureMode(val label: String) {
    FIT("适应屏幕"), FILL("填满屏幕（裁剪）"), ORIGINAL("原始大小")
}
data class PlaybackRequest(val peerAddress: String, val uri: String)
class PlaybackRejectedException(message: String) : java.io.IOException(message)

/** Budget is per media load; a brief recovery never resets it. */
class RetryBudget {
    var attempts = 0
        private set
    fun reset() { attempts = 0 }
    fun nextDelay(): Long? = if (attempts >= 3) null else (2_000L shl attempts++)
}

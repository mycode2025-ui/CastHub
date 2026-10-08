package com.casthub.airplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.casthub.core.CastLogger
import com.casthub.core.PlaybackState
import com.casthub.core.VideoAspect

/**
 * AirPlay 的播放执行器。
 *
 * 和 DLNA 侧不同：AirPlay **视频投屏**时 iPhone 给的是一个播放地址
 * （HLS m3u8 或 mp4 直链），而不是把音视频流推过来 —— 所以只需要"拿 URL 播"。
 * 屏幕镜像才是推流（H.264 over TCP），那部分没做，mDNS 里也没声明该能力。
 *
 * 三个关键点：
 * 1. **ExoPlayer 只能在主线程访问**（DLNA 侧踩过的坑）。AirPlay 的控制请求同样
 *    跑在 HTTP 工作线程上，所以所有播放器操作都经主线程 Handler 投递；
 *    HTTP 线程要读状态，只能读这里的 `@Volatile` 缓存。
 * 2. **缓存必须自己定时刷新**。iPhone 会频繁轮询 `/playback-info`，
 *    如果 position 一直不变，它会判定播放卡死并断开 —— 而 ExoPlayer 的状态回调
 *    只在"状态跳变"时触发，播放中根本不回调。所以起播后挂一个 500ms 的取样循环。
 * 3. **播完不等于投屏结束**。iPhone 可能接着投下一集，`STATE_ENDED` 只停播放器，
 *    会话要留着（和 DLNA 侧同一个结论）。
 */
internal class AirPlayPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
    private var player: ExoPlayer? = null
    private val support = com.casthub.core.PlaybackSupport(context)
    private var pendingStartFraction = 0.0
    private var pendingResumeMs = 0L
    private val recovery = com.casthub.core.PlaybackRecovery { com.casthub.core.ModuleEnabledStore(context).autoRetry() }
    private var recovering = false

    @Volatile
    private var surface: Surface? = null

    @Volatile
    private var lastDurationMs: Long = 0

    @Volatile
    private var lastPositionMs: Long = 0

    @Volatile
    private var lastPlaying = false

    @Volatile
    private var lastReady = false

    @Volatile
    private var lastEnded = false

    @Volatile
    private var lastFailed = false

    /** 画面应有宽高比（宽 ÷ 高），未知为 0。界面拿它给 Surface 定尺寸。 */
    @Volatile
    private var lastAspectRatio: Float = 0f
    private var dimensions = 0 to 0

    /** 状态变化回调（在主线程触发）。 */
    var onStateChanged: (() -> Unit)? = null

    /**
     * 一条内容**真的播完了**（STATE_ENDED）时触发一次。
     *
     * 与 [onStateChanged] 分开的原因和 DLNA 侧一样：ENDED 被映射为
     * [PlaybackState.PAUSED]（播完 ≠ 投屏结束），上层从状态值分不出
     * "用户暂停"和"放完了"，而顺序/循环播放要在放完这一刻接下一条。
     *
     * 只在**进入** ENDED 时回调一次，避免播放器的重复回调把队列推着往前走。
     */
    var onEnded: (() -> Unit)? = null

    /** 出错回调（在主线程触发）。 */
    var onError: ((String) -> Unit)? = null
    var onCues: ((List<androidx.media3.common.text.Cue>) -> Unit)? = null

    /** 起播后的取样循环，保证 /playback-info 能读到递进的进度。 */
    private var sampling = false
    private val sampler = object : Runnable {
        override fun run() {
            player?.let { sampleSupport(it) }
            cache()
            onStateChanged?.invoke()
            if (sampling) mainHandler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    fun setSurface(s: Surface?) {
        surface = s
        onMain { player?.setVideoSurface(s) }
    }

    /**
     * 带超时的 HTTP 源。普通播放与 HLS 共用 —— 早先 HLS 单独建了一个
     * 没设超时的工厂，走默认的 8 秒读超时，弱网下分片下载会被中途掐断。
     */
    private val httpFactory: DefaultHttpDataSource.Factory by lazy {
        DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
    }

    /** 只在主线程调用。 */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(context, com.casthub.core.SubtitleRenderers(context))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)),
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                        CastLogger.d(TAG, "字幕更新：${cueGroup.cues.size}")
                        onCues?.invoke(cueGroup.cues)
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            recovering = false
                            val p = player
                            val target = if (pendingResumeMs > 0) pendingResumeMs
                                else if (p != null && p.duration > 0) (p.duration * pendingStartFraction).toLong() else 0
                            if (target > 0) {
                                pendingStartFraction = 0.0
                                pendingResumeMs = 0L
                                p?.seekTo(target)
                            }
                        }
                        val wasEnded = lastEnded
                        cache()
                        if (state == Player.STATE_ENDED) {
                            CastLogger.i(TAG, "播放结束（会话保留，等 iPhone 下一步指令）")
                            stopSampling()
                            // 只在进入 ENDED 的这一次通知，避免重复回调把队列推着走
                            if (!wasEnded) {
                                lastEnded = true
                                runCatching { onEnded?.invoke() }
                                    .onFailure { CastLogger.w(TAG, "播完回调失败", it) }
                            }
                        }
                        onStateChanged?.invoke()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        cache()
                        // 播完（ENDED）后采样器已停；此时用户拖回去重看，
                        // iPhone 会发 scrub + rate=1，isPlaying 重新变 true。
                        // 不重启采样的话 /playback-info 的位置会一直冻结在结尾，
                        // iPhone 判定播放卡死并主动断连。
                        if (isPlaying) startSampling()
                        onStateChanged?.invoke()
                    }

                    override fun onPlayerError(error: PlaybackException) { handleFailure(player ?: return, error) }

                    /**
                     * 记下画面宽高比交给界面。裸 Surface 上画面会被非等比拉伸填满
                     * （见 `VideoOutput.videoAspectRatio`），所以必须让界面
                     * 把 Surface 调成视频的形状。
                     */
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        dimensions = videoSize.width to videoSize.height
                        lastAspectRatio = VideoAspect.of(
                            width = videoSize.width,
                            height = videoSize.height,
                            pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                            unappliedRotationDegrees = videoSize.unappliedRotationDegrees,
                        )
                        CastLogger.i(
                            TAG,
                            "画面尺寸 ${videoSize.width}x${videoSize.height}" +
                                "（旋转 ${videoSize.unappliedRotationDegrees}°）→ 宽高比 " +
                                "%.4f".format(java.util.Locale.US, lastAspectRatio),
                        )
                    }
                })
                setVideoSurface(surface)
                playWhenReady = true
            }
        player = p
        p.addAnalyticsListener(support)
        return p
    }

    fun play(url: String, startPositionFraction: Double, title: String? = null, resumePositionMs: Long = 0) {
        onMain {
            support.save(player)
            support.open(url, title, startPositionFraction > 0 || resumePositionMs > 0)
            recovery.reset()
            recovering = false
            pendingStartFraction = startPositionFraction.coerceIn(0.0, 1.0)
            onCues?.invoke(emptyList())
            pendingResumeMs = resumePositionMs.coerceAtLeast(0)
            lastFailed = false
            lastEnded = false
            lastPositionMs = 0
            lastDurationMs = 0
            lastAspectRatio = 0f
            dimensions = 0 to 0
            val p = ensurePlayer()
            support.applyPreferences(p)
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaId(url)
                .apply {
                    title?.let {
                        setMediaMetadata(MediaMetadata.Builder().setTitle(it).build())
                    }
                }
                .build()

            p.setMediaItem(item)
            p.prepare()
            p.play()

            lastEnded = false
            lastReady = false
            startSampling()
            CastLogger.i(TAG, "开始播放：${url.take(140)}")
        }
    }

    fun pause() = onMain { player?.pause(); cache() }
    fun resume() = onMain { player?.play(); cache() }

    fun seek(positionSec: Double) = onMain {
        support.resetWatchdog()
        player?.seekTo((positionSec * 1000).toLong())
        cache()
    }

    fun stop() = onMain {
        support.save(player)
        support.resetWatchdog()
        recovery.reset()
        recovering = false
        onCues?.invoke(emptyList())
        stopSampling()
        player?.stop()
        player?.clearMediaItems()
        lastDurationMs = 0
        lastPositionMs = 0
        lastPlaying = false
        lastReady = false
        lastEnded = false
        lastFailed = false
        // 清掉宽高比，否则下一段内容的画面会先按上一段的形状显示
        lastAspectRatio = 0f
        onStateChanged?.invoke()
    }

    fun release() = onMain {
        support.save(player)
        recovery.cancel()
        stopSampling()
        player?.release()
        player = null
    }

    // ─────────────────── 供 HTTP 线程读取的状态 ───────────────────

    fun durationSec(): Double = lastDurationMs / 1000.0
    fun positionSec(): Double = lastPositionMs / 1000.0
    fun isPlaying(): Boolean = lastPlaying
    fun isReady(): Boolean = lastReady

    /** 映射成 core 的统一状态。播完按 PAUSED 处理，见类注释第 3 点。 */
    fun playbackState(): PlaybackState = when {
        recovering -> PlaybackState.BUFFERING
        lastFailed -> PlaybackState.ERROR
        lastEnded -> PlaybackState.PAUSED
        lastPlaying -> PlaybackState.PLAYING
        lastReady -> PlaybackState.PAUSED
        lastPositionMs > 0 -> PlaybackState.PAUSED
        else -> PlaybackState.BUFFERING
    }

    fun durationMs(): Long = lastDurationMs
    fun positionMs(): Long = lastPositionMs

    /** 画面应有宽高比（宽 ÷ 高）；未知返回 0，由界面按铺满处理。 */
    fun videoAspectRatio(): Float = lastAspectRatio
    fun videoDimensions() = dimensions

    fun tracks() = com.casthub.core.PlaybackTracks.choices(player)
    fun selectTrack(choice: com.casthub.core.MediaTrackChoice) = support.select(player, choice)
    fun refreshSubtitleTiming() = onMain { player?.let { it.seekTo(it.currentPosition) } }

    private fun startSampling() {
        sampling = true
        mainHandler.removeCallbacks(sampler)
        mainHandler.postDelayed(sampler, SAMPLE_INTERVAL_MS)
    }

    private fun stopSampling() {
        sampling = false
        mainHandler.removeCallbacks(sampler)
    }

    /** 只在主线程调用。 */
    private fun cache() {
        val p = player ?: return
        lastDurationMs = p.duration.takeIf { it > 0 } ?: 0
        lastPositionMs = p.currentPosition.coerceAtLeast(0)
        lastPlaying = p.isPlaying
        lastEnded = p.playbackState == Player.STATE_ENDED
        lastReady = p.playbackState == Player.STATE_READY
    }

    /** 是否为 HLS。按 URL 判断，不信发送端给的 Content-Type（DLNA 侧同理）。 */
    private fun isHls(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("/m3u8")
    }


    private fun handleFailure(p: ExoPlayer, error: PlaybackException) {
        CastLogger.e(TAG, "播放失败：${error.message}")
        stopSampling()
        cache()
        val position = p.currentPosition
        if (recovery.schedule(error) {
            val current = player ?: return@schedule
            val shouldPlay = current.playWhenReady
            current.seekTo(position)
            support.resetWatchdog()
            current.prepare()
            current.playWhenReady = shouldPlay
            startSampling()
        }) {
            recovering = true
            lastFailed = false
            onStateChanged?.invoke()
            return
        }
        recovering = false
        lastFailed = true
        onStateChanged?.invoke()
        onError?.invoke(com.casthub.core.PlaybackErrors.message(error))
    }

    private fun sampleSupport(p: ExoPlayer) {
        if (support.sample(p, recovery.isPending)) {
            com.casthub.core.CastLogger.w("PlaybackWatchdog", "检测到持续缓冲或进度冻结，进入有限重试")
            val position = p.currentPosition
            p.stop()
            p.seekTo(position)
            handleFailure(p, PlaybackException("播放长时间没有进展", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        }
    }
    fun canSeekNow() = player?.isCurrentMediaItemSeekable == true
    fun wantsToPlay() = player?.playWhenReady == true
    fun playbackDiagnostics() = support.summary(player, recovery.attempts)
    fun resumeCandidate() = support.resumeCandidate
    fun applyTrackPreferences() { player?.let(support::applyPreferences) }
    fun externalSubtitle(address: String?, mimeType: String) = support.externalSubtitle(player, address, mimeType)
    companion object {
        private const val TAG = "AirPlayPlayer"
        private const val USER_AGENT = "CastHub/1.0 (AirPlay Receiver)"

        /** 状态取样间隔。1 秒够 iPhone 轮询用，500ms 让本机 OSD 手感更跟手。 */
        private const val SAMPLE_INTERVAL_MS = 500L
    }
}

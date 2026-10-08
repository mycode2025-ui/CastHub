package com.casthub.dlna.renderer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.casthub.core.CastLogger
import com.casthub.core.MediaInfo
import com.casthub.core.PlaybackPosition
import com.casthub.core.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DLNA 渲染端的播放控制。
 *
 * ── 两个必须遵守的约束（均由真机测试暴露）─────────────────────────────
 *
 * 1. **ExoPlayer 只能在创建它的线程（主线程）上访问。**
 *    DLNA 的 SOAP 动作在 HTTP 工作线程上执行，若直接调用播放器会抛
 *    `IllegalStateException: Player is accessed on the wrong thread`。
 *    因此本类所有对播放器的操作都统一投递到主线程；
 *    HTTP 线程需要读取的进度/时长则通过缓存字段返回。
 *
 * 2. **播放器要用 Media3 而非系统 MediaPlayer。**
 *    Android 6.0 的系统 MediaPlayer 从 API 26 起才支持 HLS，而实测发送端推的是 m3u8。
 */
@OptIn(UnstableApi::class)
class MediaRendererController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onStateChanged: (PlaybackState) -> Unit,
    private val onPositionChanged: (PlaybackPosition) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
    /**
     * 一条内容**真的播完了**（STATE_ENDED）。
     *
     * 之所以要单独一个回调：ENDED 在 [onStateChanged] 里被映射成
     * [PlaybackState.PAUSED]（"播完 ≠ 投屏结束"，映射成 STOPPED 会让界面退回主页），
     * 于是上层从状态值**分不出**"用户按了暂停"和"这一条放完了"。
     * 而顺序/循环播放恰恰要在"放完"这一刻决定要不要接下一条。
     */
    private val onEnded: () -> Unit = {},
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var player: ExoPlayer? = null
    private var progressJob: Job? = null
    private var currentMedia: MediaInfo? = null
    private var pendingSurface: Surface? = null
    private var pendingSeekMs: Long = 0L

    /** 供 HTTP 线程读取的进度缓存（仅在主线程写入）。 */
    @Volatile
    private var cachedPositionMs: Long = 0L

    @Volatile
    private var cachedDurationMs: Long = -1L

    @Volatile
    var state: PlaybackState = PlaybackState.IDLE
        private set

    val currentUri: String? get() = currentMedia?.uri

    // ─────────────────────── 对外操作（线程安全） ───────────────────────

    /** 由 UI 层在 SurfaceView 就绪时调用。 */
    fun attachSurface(surface: Surface?) {
        onMain("attachSurface") {
            pendingSurface = surface
            player?.setVideoSurface(surface)
        }
    }

    /** 打开媒体。对应 DLNA 的 SetAVTransportURI；实际起播等 Play 指令。 */
    fun open(media: MediaInfo) {
        onMain("open") {
            CastLogger.i(TAG, "打开媒体: ${media.title ?: media.uri} (hls=${media.isHls})")
            currentMedia = media
            cachedDurationMs = media.durationMs
            cachedPositionMs = media.startPositionMs
            pendingSeekMs = media.startPositionMs

            val p = ensurePlayer()
            val item = MediaItem.Builder()
                .setUri(Uri.parse(media.uri))
                .apply { media.effectiveMimeType?.let { setMimeType(it) } }
                .build()

            p.setMediaItem(item)
            p.prepare()
        }
    }

    fun play() {
        onMain("play") {
            val p = player ?: return@onMain
            if (pendingSeekMs > 0) {
                p.seekTo(pendingSeekMs)
                pendingSeekMs = 0
            }
            p.playWhenReady = true
        }
    }

    fun pause() {
        onMain("pause") { player?.playWhenReady = false }
    }

    fun stop() {
        onMain("stop") {
            player?.stop()
            player?.clearMediaItems()
            currentMedia = null
            cachedDurationMs = -1L
            cachedPositionMs = 0L
            updateState(PlaybackState.STOPPED)
        }
    }

    fun seekTo(positionMs: Long) {
        onMain("seek") {
            player?.seekTo(positionMs.coerceAtLeast(0L))
            cachedPositionMs = positionMs.coerceAtLeast(0L)
        }
    }

    fun release() {
        progressJob?.cancel()
        progressJob = null
        onMain("release") {
            runCatching {
                player?.setVideoSurface(null)
                player?.release()
            }.onFailure { CastLogger.w(TAG, "释放播放器失败", it) }
            player = null
            currentMedia = null
            cachedDurationMs = -1L
            cachedPositionMs = 0L
        }
    }

    // ─────────────────────── 供 HTTP 线程读取的状态 ───────────────────────

    /** 当前播放位置（毫秒）。读缓存，避免跨线程访问播放器。 */
    fun positionMs(): Long = cachedPositionMs

    /** 时长（毫秒），未知返回 0。 */
    fun duration(): Long = if (cachedDurationMs > 0) cachedDurationMs else 0L

    fun currentPosition(): PlaybackPosition =
        PlaybackPosition(cachedPositionMs, duration(), state)

    // ─────────────────────── 音量（系统媒体音量） ───────────────────────

    private val audioManager: android.media.AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    /** 返回 0..100。 */
    fun systemVolume(): Int = runCatching {
        val am = audioManager ?: return 50
        val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (max <= 0) 0
        else am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) * 100 / max
    }.getOrDefault(50)

    fun setSystemVolume(percent: Int) {
        runCatching {
            val am = audioManager ?: return@runCatching
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max <= 0) return@runCatching
            am.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                percent.coerceIn(0, 100) * max / 100,
                0,
            )
        }.onFailure { CastLogger.w(TAG, "设置音量失败", it) }
    }

    fun isSystemMuted(): Boolean = runCatching {
        audioManager?.isStreamMute(android.media.AudioManager.STREAM_MUSIC) ?: false
    }.getOrDefault(false)

    fun setSystemMuted(muted: Boolean) {
        runCatching {
            // setStreamMute 自 API 26 起已废弃，改用 adjustStreamVolume 的静音指令；
            // 对 STREAM_MUSIC 而言两者效果一致，但前者在部分固件上会连带改动
            // "音量键是否控制该流"的隐藏标志
            audioManager?.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (muted) {
                    android.media.AudioManager.ADJUST_MUTE
                } else {
                    android.media.AudioManager.ADJUST_UNMUTE
                },
                0,
            )
        }.onFailure { CastLogger.w(TAG, "设置静音失败", it) }
    }

    // ─────────────────────── 内部 ───────────────────────

    /** 统一投递到主线程执行；已在主线程则直接执行。 */
    private fun onMain(tag: String, block: () -> Unit) {
        val runnable = Runnable {
            runCatching { block() }.onFailure { t ->
                CastLogger.e(TAG, "[$tag] 执行失败", t)
                if (tag == "open") {
                    updateState(PlaybackState.ERROR)
                    onError("打开媒体失败: ${t.message}", t)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) runnable.run()
        else mainHandler.post(runnable)
    }

    /** 只在主线程调用。 */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }

        // 直链常带防盗链校验，统一注入请求头
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(currentMedia?.httpHeaders ?: emptyMap())

        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val mapped = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY ->
                        if (p.playWhenReady) PlaybackState.PLAYING else PlaybackState.PAUSED

                    // 播完（ENDED）映射为 PAUSED，**不是 STOPPED**。
                    // 语义区别很关键：ENDED 只是这一条内容放完了，会话仍然存在 ——
                    // 发送端还在，用户随时可能点下一集。
                    // 若映射成 STOPPED，上层会把会话判定为结束并退回待机页，
                    // 用户会以为投屏断了（实测现象）。
                    // 真正的"停止"只会由发送端的 Stop 动作触发，见 stop()。
                    Player.STATE_ENDED -> PlaybackState.PAUSED
                    else -> PlaybackState.IDLE
                }
                syncCacheFromPlayer(p)
                if (mapped != state) updateState(mapped)
                // ENENDED 单独通知：上层要靠它判断"该接下一条了"
                if (playbackState == Player.STATE_ENDED) {
                    runCatching { onEnded() }.onFailure { CastLogger.w(TAG, "播完回调失败", it) }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val mapped = when {
                    isPlaying -> PlaybackState.PLAYING
                    state == PlaybackState.BUFFERING -> PlaybackState.BUFFERING
                    else -> PlaybackState.PAUSED
                }
                if (mapped != state) updateState(mapped)
            }

            override fun onPlayerError(error: PlaybackException) {
                CastLogger.e(TAG, "播放错误: ${error.errorCodeName}", error)
                updateState(PlaybackState.ERROR)
                onError("播放错误: ${error.errorCodeName}", error)
            }
        })

        pendingSurface?.let { p.setVideoSurface(it) }
        player = p
        startProgressLoop()
        return p
    }

    /** 主线程：把播放器真实进度同步到缓存。 */
    private fun syncCacheFromPlayer(p: ExoPlayer) {
        runCatching {
            cachedPositionMs = p.currentPosition
            val d = p.duration
            if (d > 0) cachedDurationMs = d
        }
    }

    private fun updateState(newState: PlaybackState) {
        state = newState
        runCatching { onStateChanged(newState) }
    }

    /**
     * 周期性刷新进度缓存。
     * DLNA 的 GetPositionInfo 与 LeLink 的 /scrub 都依赖它。
     */
    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MS)
                val snapshot = withContext(Dispatchers.Main) {
                    player?.let { syncCacheFromPlayer(it) }
                    PlaybackPosition(cachedPositionMs, duration(), state)
                }
                if (snapshot.state.isActive) {
                    runCatching { onPositionChanged(snapshot) }
                }
            }
        }
    }

    companion object {
        private const val TAG = "DlnaRenderer"
        private const val USER_AGENT = "CastHub/1.0 (Android; DLNA DMR)"
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}

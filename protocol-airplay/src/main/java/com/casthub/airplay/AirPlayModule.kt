package com.casthub.airplay

import android.content.Context
import android.view.Surface
import com.casthub.airplay.mdns.MdnsResponder
import com.casthub.airplay.mdns.NsdRegistrar
import com.casthub.core.Availability
import com.casthub.core.CastEvent
import com.casthub.core.CastLogger
import com.casthub.core.CastSession
import com.casthub.core.LocalPlaybackControl
import com.casthub.core.MediaInfo
import com.casthub.core.ModuleEnabledStore
import com.casthub.core.ModuleState
import com.casthub.core.PlaybackPosition
import com.casthub.core.PlaybackQueue
import com.casthub.core.PlaybackState
import com.casthub.core.ProtocolCapability
import com.casthub.core.ProtocolModule
import com.casthub.core.VideoOutput
import com.casthub.core.VolumeGovernor
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * AirPlay 接收模块（纯 Kotlin 自研，无 native 依赖）。
 *
 * 本模块只做**接收端**，让 iPhone / iPad / Mac 把视频投到本机。
 *
 * ── 为什么不用 UxPlay ──────────────────────────────────────────────
 * 常见的 AirPlay 接收端实现是 UxPlay（C），它依赖 GStreamer、libplist、libsodium、
 * OpenSSL，需要先为各 ABI 交叉编译 —— 本机没有 NDK / cmake，也装不下 GStreamer。
 * 而**视频投屏**（与屏幕镜像不同）的协议面其实很窄：AirPlay 只把**播放地址**
 * 交给接收端，解码交给接收端自己。也就是说只需要 mDNS 发现 + 一个 HTTP 服务 +
 * 已有的 Media3 播放器，全部可以用 Kotlin 写。屏幕镜像才是推流（H.264 over TCP），
 * 那部分没有实现，mDNS 里也**刻意没有声明**该能力。
 *
 * 三层结构，彼此解耦：
 *   发现层 [MdnsResponder] + 控制层 [AirPlayHttpServer] + 播放层 [AirPlayPlayer]
 *
 * ── 刻意不做的：AirPlay 音频（RAOP） ──────────────────────────────
 * 苹果自家「音乐」App 走 `_raop._tcp`，需要 RSA 配对 + AES 解密 + ALAC 解码 +
 * RTP 时钟同步，是另一套协议栈，工程量与视频投屏不在一个量级。
 * 与其声明了却连不上（用户在列表里点设备没反应），不如不声明。
 */
class AirPlayModule(private val appContext: Context) :
    ProtocolModule, VideoOutput, LocalPlaybackControl {

    override val id: String = ID
    override val displayName: String = "AirPlay"

    override val description: String =
        "苹果设备专用。iPhone、iPad、Mac 的视频 App 与 Safari 用这条通道投屏"

    override val capabilities: Set<ProtocolCapability> = setOf(ProtocolCapability.RECEIVER)

    private val moduleScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("airplay-module")
    )
    private val store = ModuleEnabledStore(appContext)

    /**
     * 投屏音量闸门：发送端报到最大时，把音量限制在用户自己设定的范围内。
     * 详见 [VolumeGovernor]。
     */
    private val volumeGovernor = VolumeGovernor(appContext)

    /**
     * 播放队列：与 DLNA 侧同一套逻辑（core 的 [PlaybackQueue]），
     * 播放模式也来自同一个设置，因此用户在设置页选一次，两条通道都生效。
     */
    private val queue = PlaybackQueue()

    private val _state = MutableStateFlow<ModuleState>(ModuleState.Stopped)
    override val state: StateFlow<ModuleState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<CastSession>>(emptyList())
    override val sessions: StateFlow<List<CastSession>> = _sessions.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    @Volatile
    private var deviceName: String = store.deviceName(ID, DEFAULT_DEVICE_NAME)
    override val localDeviceName: String get() = deviceName

    @Volatile
    private var currentIp: String = ""
    override val localAddress: String get() = currentIp

    @Volatile
    private var currentPort: Int = 0
    override val localPort: Int get() = currentPort

    @Volatile
    private var currentSurface: Surface? = null

    private var player: AirPlayPlayer? = null
    private var http: AirPlayHttpServer? = null

    /**
     * 停止对外宣告服务的动作。两条通道（系统 NsdManager / 自实现 mDNS）
     * 只能有一条生效，所以统一存一个"怎么停"，避免状态字段与实际不符。
     */
    private var stopAdvertising: (() -> Unit)? = null
    private var activeSession: CastSession? = null

    /** 纯 Kotlin 实现，无 native 依赖，恒定可用。 */
    override fun checkAvailability(): Availability = Availability.Available

    override fun setLocalDeviceName(name: String) {
        val trimmed = name.trim().take(48)
        if (trimmed.isEmpty() || trimmed == deviceName) return
        deviceName = trimmed
        store.setDeviceName(ID, trimmed)
        // 新名字要等模块重启（重新发 mDNS 记录）后才会被 iPhone 看到，
        // 设置页在改完名后会统一触发重启，这里只负责落盘。
        CastLogger.i(TAG, "设备名变更为：$trimmed（重启模块后生效）")
    }

    override suspend fun start() {
        if (_state.value is ModuleState.Running) {
            CastLogger.d(TAG, "AirPlay 模块已在运行")
            return
        }
        _state.value = ModuleState.Starting

        val ip = Net.localIpv4()
        if (ip == null) {
            fail("未找到局域网 IPv4 地址，请确认已连接 Wi-Fi")
            return
        }

        try {
            val playback = AirPlayPlayer(appContext).apply {
                setSurface(currentSurface)
                onStateChanged = { handlePlayerState() }
                onError = { message -> handleError("AirPlay 播放失败：$message", null) }
                // 播完：按设置页选定的模式接下一条（顺序/循环）。
                // AirPlay 没有 SetNextAVTransportURI 那样的"下一条"机制，
                // 所以完全靠电视端自己记住放过的条目。
                onEnded = { handlePlaybackEnded() }
            }

            // 身份与能力公告取一份（含 deviceid / pk / pi / TXT），
            // HTTP 与 mDNS 两条通道共用，保证对外口径完全一致。
            val announce = AirPlayIdentity.announce(appContext)
            val server = AirPlayHttpServer(AIRPLAY_PORT, announce, deviceName).apply {
                sink = createSink(playback)
            }
            if (!server.start()) {
                playback.release()
                fail("AirPlay 控制服务启动失败（端口 ${server.actualPort} 被占用且无法改用其它端口）")
                return
            }

            // 服务宣告：优先用系统 NsdManager，它不可靠时回退到自实现 mDNS。
            // 两者共用同一份 TXT（announce.txt），对外宣称的能力不会因通道不同而变。
            val txt = announce.txt
            val systemNsd = NsdRegistrar(appContext, deviceName, server.actualPort, txt)
            stopAdvertising = if (systemNsd.start()) {
                { systemNsd.stop() }
            } else {
                val responder =
                    MdnsResponder(appContext, deviceName, announce.deviceId, server.actualPort)
                if (!responder.start()) {
                    server.stop()
                    playback.release()
                    fail("mDNS 服务注册失败，iPhone 将搜不到本设备")
                    return
                }
                { responder.stop() }
            }

            player = playback
            http = server
            currentIp = ip
            currentPort = server.actualPort

            _state.value = ModuleState.Running
            CastLogger.i(
                TAG,
                "AirPlay 已启动：$deviceName @$ip:${server.actualPort}（mDNS 224.0.0.251:5353）",
            )
        } catch (t: Throwable) {
            CastLogger.e(TAG, "AirPlay 启动失败", t)
            safeRelease()
            fail("启动失败：${t.message}", t)
        }
    }

    override suspend fun stop() {
        safeRelease()
        _state.value = ModuleState.Stopped
        CastLogger.i(TAG, "AirPlay 已停止")
    }

    override fun attachSurface(surface: Surface?) {
        currentSurface = surface
        player?.setSurface(surface)
    }

    fun shutdown() {
        moduleScope.cancel()
    }

    // ─────────────────── AirPlay 协议回调 → 播放器 ───────────────────

    private fun createSink(playback: AirPlayPlayer): AirPlaySink = object : AirPlaySink {

        override fun onPlay(url: String, startPositionSec: Double, peerAddress: String) {
            val media = MediaInfo(
                uri = url,
                title = null,
                startPositionMs = (startPositionSec * 1000).toLong().coerceAtLeast(0),
            )
            // iPhone 几乎总是紧接着发 /volume?volume=1.0，
            // 必须先把用户当前的音量锁成上限，否则一投屏就被顶到 100%
            volumeGovernor.snapshotCeiling()
            // 记入队列并设为当前条目，播完后据此接下一条
            queue.mode = store.playbackMode()
            queue.onPlayed(media)
            CastLogger.d(TAG, "AirPlay 播放队列：${queue.summary()}")
            playback.play(url, startPositionSec)

            val session = CastSession(
                id = activeSession?.id ?: UUID.randomUUID().toString(),
                protocolId = ID,
                // AirPlay 不报设备名。显示 IP 是真实信息，编一个 "iPhone" 出来
                // 在 Mac 投屏时就是错的。
                peerName = "",
                peerAddress = peerAddress,
                media = media,
                state = PlaybackState.PLAYING,
            )
            activeSession = session
            publishSessions()
            tryEmit(CastEvent.SessionStarted(session))
            tryEmit(CastEvent.MediaChanged(session.id, ID, media))
        }

        override fun onStop() {
            playback.stop()
            endSession("iPhone 已结束投屏")
        }

        override fun onRate(rate: Double) {
            if (rate > 0) playback.resume() else playback.pause()
        }

        override fun onScrub(positionSec: Double) {
            playback.seek(positionSec)
        }

        override fun onVolume(value: Double) {
            volumeGovernor.apply(value)
        }

        override fun playbackInfo(): PlaybackInfo = PlaybackInfo(
            durationSec = playback.durationSec(),
            positionSec = playback.positionSec(),
            rate = if (playback.isPlaying()) 1 else 0,
            ready = playback.isReady(),
        )
    }

    /**
     * 播放器状态变了就同步到会话。
     *
     * 这里由 [AirPlayPlayer] 的 500ms 取样循环驱动 —— 顺带让界面上的进度条
     * 跟着走（会话流一推，主界面就会重画 OSD）。
     */
    private fun handlePlayerState() {
        val session = activeSession
        if (session == null) {
            if (_sessions.value.isNotEmpty()) _sessions.value = emptyList()
            return
        }
        val newState = player?.playbackState() ?: return
        val updated = session.copy(state = newState)
        activeSession = updated
        publishSessions()
        if (session.state != newState) {
            tryEmit(CastEvent.StateChanged(session.id, ID, newState))
        }
    }

    private fun endSession(reason: String) {
        val session = activeSession ?: return
        activeSession = null
        volumeGovernor.reset()
        // 会话真正结束才清队列；单条播完不清，否则顺序播放刚开始就没历史了
        queue.clear()
        _sessions.value = emptyList()
        tryEmit(CastEvent.SessionEnded(session.id, ID, reason))
        CastLogger.i(TAG, "会话结束：$reason")
    }

    /**
     * 一条内容播完后按模式处理。
     *
     * 只有"单曲循环"会动作（回到开头重播）。单曲模式维持原行为：
     * 停在这一条、会话保留，等 iPhone 的下一步指令 ——
     * 播完不等于投屏结束，iPhone 可能接着投下一集。
     *
     * 刻意不做"自动接下一条"：AirPlay 协议里没有播放列表，
     * 本机无从知道下一集是什么（详见 core 的 [PlaybackQueue] 注释）。
     */
    private fun handlePlaybackEnded() {
        queue.mode = store.playbackMode()
        val again = queue.onEndedHandled() ?: run {
            CastLogger.i(TAG, "AirPlay 播放结束（${queue.summary()}），保持会话")
            return
        }
        val p = player ?: return
        CastLogger.i(TAG, "单曲循环：重播 ${again.media.uri.take(60)}")
        p.seek(0.0)
        p.resume()
    }

    // ─────────────────── 接收端本地控制（电视遥控器） ───────────────────

    override val canSeek: Boolean
        get() = activeSession != null && (player?.durationMs() ?: 0) > 0

    override fun seekBy(deltaMs: Long): Boolean {
        val p = player ?: return false
        val duration = p.durationMs()
        if (activeSession == null || duration <= 0) return false

        val target = (p.positionMs() + deltaMs).coerceIn(0L, duration)
        p.seek(target / 1000.0)
        CastLogger.i(
            TAG,
            "遥控器定位：${p.positionMs() / 1000}s → ${target / 1000}s" +
                "（${if (deltaMs > 0) "快进" else "快退"} ${kotlin.math.abs(deltaMs) / 1000}s）",
        )
        // iPhone 靠轮询 /playback-info 读进度，本地改了它会自动跟上，两端不脱节
        return true
    }

    /**
     * 电视端主动结束投屏。
     *
     * 与 iPhone 发来的 [/stop] 走同一条路径：停播放器 + 清会话。
     * iPhone 侧靠轮询 /playback-info 感知（rate 变 0、播放位置不再前进），
     * 会自行收起投屏控制界面。
     */
    override fun stopCasting(): Boolean {
        if (activeSession == null) return false
        CastLogger.i(TAG, "用户在电视端结束了投屏")
        player?.stop()
        endSession("已在电视端结束投屏")
        return true
    }

    override fun currentProgress(): PlaybackPosition? {
        val session = activeSession ?: return null
        val p = player ?: return null
        return PlaybackPosition(
            positionMs = p.positionMs().coerceAtLeast(0),
            durationMs = p.durationMs(),
            state = session.state,
        )
    }

    // ─────────────────── 内部工具 ───────────────────

    private fun publishSessions() {
        _sessions.value = listOfNotNull(activeSession)
    }

    private fun fail(reason: String, cause: Throwable? = null) {
        CastLogger.e(TAG, reason, cause)
        _state.value = ModuleState.Failed(reason, cause)
        tryEmit(CastEvent.Error(ID, reason, cause))
    }

    private fun handleError(message: String, cause: Throwable?) {
        CastLogger.e(TAG, message, cause)
        tryEmit(CastEvent.Error(ID, message, cause))
    }

    private fun tryEmit(event: CastEvent) {
        runCatching { _events.tryEmit(event) }
    }

    private fun safeRelease() {
        runCatching { stopAdvertising?.invoke() }
            .onFailure { CastLogger.w(TAG, "停止服务宣告失败", it) }
        stopAdvertising = null

        runCatching { http?.stop() }
            .onFailure { CastLogger.w(TAG, "停止 AirPlay 服务失败", it) }
        http = null

        runCatching { player?.release() }
            .onFailure { CastLogger.w(TAG, "释放播放器失败", it) }
        player = null

        activeSession = null
        _sessions.value = emptyList()
        currentIp = ""
        currentPort = 0
    }

    // 设备标识 / 配对公钥 / 配对标识统一由 AirPlayIdentity 提供，
    // 它同时是 mDNS TXT、/info、/server-info 三处公告的唯一真源。

    companion object {
        const val ID = "airplay"
        const val DEFAULT_DEVICE_NAME = "CastHub 投屏接收端"

        /** AirPlay 的惯例端口。被占用时会自动改用随机端口，见 [AirPlayHttpServer]。 */
        const val AIRPLAY_PORT = 7000

        private const val TAG = "AirPlayModule"
    }
}

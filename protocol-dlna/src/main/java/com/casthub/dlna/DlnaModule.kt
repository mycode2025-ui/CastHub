package com.casthub.dlna

import android.content.Context
import android.view.Surface
import com.casthub.core.Availability
import com.casthub.core.CastDevice
import com.casthub.core.CastEvent
import com.casthub.core.CastLogger
import com.casthub.core.CastSession
import com.casthub.core.LocalPlaybackControl
import com.casthub.core.MediaInfo
import com.casthub.core.ModuleEnabledStore
import com.casthub.core.ModuleState
import com.casthub.core.PlaybackPosition
import com.casthub.core.PlaybackState
import com.casthub.core.ProtocolCapability
import com.casthub.core.ProtocolModule
import com.casthub.core.VideoOutput
import com.casthub.core.VolumeGovernor
import com.casthub.dlna.dmp.DmcClient
import com.casthub.dlna.dmr.DmrService
import com.casthub.dlna.renderer.MediaRendererController
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
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * DLNA / UPnP 协议模块。
 *
 * 两种能力，可分别使用、整体也可被用户独立关闭：
 * - **RECEIVER**：本机作为 DMR，接收手机 App 推来的媒体
 *   （实测可收夸克网盘；爱奇艺 / 腾讯视频等同样走此通道）
 * - **SENDER**：本机作为 DMC，发现并控制局域网内的其它渲染设备
 *
 * 模块内部由三层组成，彼此解耦：
 *   发现层 [SsdpServer] + 控制层 [UpnpHttpServer]/[DmcClient] + 播放层 [MediaRendererController]
 */
class DlnaModule(private val appContext: Context) : ProtocolModule, VideoOutput, LocalPlaybackControl {

    override val id: String = ID
    override val displayName: String = "DLNA / UPnP"

    override val description: String =
        "通用投屏标准。夸克网盘、爱奇艺、腾讯视频、B 站等绝大多数手机 App 都用这条通道"
    override val capabilities: Set<ProtocolCapability> =
        setOf(ProtocolCapability.RECEIVER, ProtocolCapability.SENDER)

    private val moduleScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("dlna-module")
    )

    private val store = ModuleEnabledStore(appContext)

    /**
     * 投屏音量闸门：手机 App 起播时普遍发 `SetVolume(100)`，
     * 直接照做会把电视顶到最大声。详见 [VolumeGovernor]。
     */
    private val volumeGovernor = VolumeGovernor(appContext)

    private val _state = MutableStateFlow<ModuleState>(ModuleState.Stopped)
    override val state: StateFlow<ModuleState> = _state.asStateFlow()

    private val _sessions = MutableStateFlow<List<CastSession>>(emptyList())
    override val sessions: StateFlow<List<CastSession>> = _sessions.asStateFlow()
    private val _subtitleCues = MutableStateFlow<List<androidx.media3.common.text.Cue>>(emptyList())
    override val subtitleCues = _subtitleCues.asStateFlow()

    private val _events = MutableSharedFlow<CastEvent>(extraBufferCapacity = 64)
    override val events: SharedFlow<CastEvent> = _events.asSharedFlow()

    @Volatile
    private var deviceName: String = store.deviceName(ID, DEFAULT_DEVICE_NAME)
    override val localDeviceName: String get() = deviceName

    @Volatile
    private var currentIp: String = ""
    override val localAddress: String get() = currentIp

    private var controller: MediaRendererController? = null
    private var dmr: DmrService? = null
    private var dmc: DmcClient? = null

    @Volatile
    private var currentSurface: Surface? = null

    var playbackTransaction: ((com.casthub.core.PlaybackRequest, () -> Unit) -> Unit)? = null

    /**
     * 当前接收端会话。
     *
     * ⚠️ 必须 `@Volatile`：它在**三个线程**上被读写 ——
     * DLNA 的 SOAP 动作跑在 HTTP 工作线程（`DmrService` 回调 `onMediaChanged`），
     * 播放器状态回调与电视端本地控制在主线程（`handlePlaybackState` / `stopCasting`），
     * 进度取样协程又在 `Dispatchers.Default`。没有可见性保证时，
     * 界面上会出现"遥控器按了退出却没反应"这类偶发问题。
     */
    @Volatile
    private var activeSession: CastSession? = null

    /** 纯 JVM/Android 实现，无 native 依赖，恒定可用。 */
    override fun checkAvailability(): Availability = Availability.Available

    override fun setLocalDeviceName(name: String) {
        val trimmed = name.trim().take(48)
        if (trimmed.isEmpty() || trimmed == deviceName) return
        deviceName = trimmed
        store.setDeviceName(ID, trimmed)
        CastLogger.i(TAG, "设备名变更为：$trimmed")
        moduleScope.launch {
            runCatching { dmr?.rename(trimmed, currentPort) }
                .onFailure { CastLogger.w(TAG, "重命名后重新注册失败", it) }
        }
    }

    private var currentPort: Int = 0
    override val localPort: Int get() = currentPort

    override suspend fun start() {
        if (_state.value is ModuleState.Running) {
            CastLogger.d(TAG, "DLNA 模块已在运行")
            return
        }
        _state.value = ModuleState.Starting

        try {
            val ip = com.casthub.core.LanAddress.ipv4(appContext)
                ?: error("未找到局域网 IPv4 地址，请确认已连接 Wi-Fi")

            currentIp = ip
            currentPort = NetworkUtils.findFreePort()

            val renderer = MediaRendererController(
                context = appContext,
                scope = moduleScope,
                onStateChanged = ::handlePlaybackState,
                onPositionChanged = { position ->
                    val session = activeSession ?: return@MediaRendererController
                    tryEmit(CastEvent.PositionChanged(session.id, id, position))
                },
                onError = { message, cause -> handleError(message, cause) },
                onCues = { _subtitleCues.value = it },
                // 播完：由电视端按用户在设置页选的模式决定要不要接下一条。
                // ENEDED 在控制器里被映射成 PAUSED（播完 ≠ 投屏结束），
                // 所以这里用独立回调拿这个信号，避免把"用户暂停"误当成"放完了"。
                onEnded = {
                    runCatching { dmr?.onPlaybackEnded() }
                        .onFailure { CastLogger.w(TAG, "播完处理失败", it) }
                },
            )

            val rendererService = DmrService(
                context = appContext,
                localIp = ip,
                controller = renderer,
                callbacks = object : DmrService.Callbacks {
                    override fun onMediaChanged(media: MediaInfo?) {
                        if (media == null) {
                            activeSession = null
                            volumeGovernor.reset()
                            _sessions.value = emptyList()
                            return
                        }
                        // 起播瞬间锁定音量上限：手机紧接着就会发 SetVolume(100)
                        volumeGovernor.snapshotCeiling()
                        val session = (activeSession ?: createSession())
                            .copy(media = media, state = PlaybackState.PLAYING)
                        activeSession = session
                        publishSessions()
                        tryEmit(CastEvent.MediaChanged(session.id, id, media))
                        tryEmit(CastEvent.SessionStarted(session))
                    }

                    override fun onPlaybackStateChanged(state: PlaybackState) =
                        handlePlaybackState(state)

                    override fun onPeerActivity(peerName: String, peerAddress: String) {
                        if (peerName.isBlank() && peerAddress.isBlank()) return
                        val session = activeSession ?: createSession()
                        activeSession = session.copy(peerName = peerName.ifBlank { session.peerName }, peerAddress = peerAddress.ifBlank { session.peerAddress })
                        publishSessions()
                    }

                    override fun onError(message: String, cause: Throwable?) =
                        handleError(message, cause)
                },
            )

            // 音量控制桥接到系统媒体音量。
            rendererService.playbackTransaction = { request, action -> playbackTransaction?.invoke(request, action) ?: action() }
            // 不直接用 renderer.setSystemVolume：手机端 SetVolume 普遍给 100，
            // 照字面执行会把电视顶到最大声。改走闸门，按投屏开始时的音量做上限。
            rendererService.volumeReader = { renderer.systemVolume() }
            rendererService.volumeSink = { percent -> volumeGovernor.applyPercent(percent) }
            rendererService.muteReader = { renderer.isSystemMuted() }
            // 静音意图要先告诉闸门：否则"手机调大音量 → 解除静音"的修复会
            // 把发送端刚下达的 SetMute(1) 立刻顶掉（见 VolumeGovernor.onSenderMute）
            rendererService.muteSink = { muted ->
                volumeGovernor.onSenderMute(muted)
                renderer.setSystemMuted(muted)
            }

            // 播放模式来自用户在设置页的选择，跨协议共用
            rendererService.modeProvider = { store.playbackMode() }

            controller = renderer
            dmr = rendererService
            renderer.attachSurface(currentSurface)
            rendererService.start(deviceName, currentPort)
            dmc = DmcClient()

            _state.value = ModuleState.Running
            CastLogger.i(TAG, "DLNA 模块已启动：$deviceName @$ip:$currentPort")
        } catch (t: Throwable) {
            CastLogger.e(TAG, "DLNA 模块启动失败", t)
            _state.value = ModuleState.Failed("启动失败：${t.message}", t)
            handleError("DLNA 启动失败：${t.message}", t)
            safeRelease()
        }
    }

    override suspend fun stop() {
        safeRelease()
        _state.value = ModuleState.Stopped
        CastLogger.i(TAG, "DLNA 模块已停止")
    }

    private fun safeRelease() {
        runCatching { dmr?.stop() }
            .onFailure { CastLogger.w(TAG, "停止 DMR 失败", it) }
        dmr = null

        runCatching { controller?.release() }
            .onFailure { CastLogger.w(TAG, "释放播放器失败", it) }
        controller = null

        runCatching { dmc?.clear() }
        dmc = null
        dmcSessions.clear()

        activeSession = null
        _sessions.value = emptyList()
        currentIp = ""
        currentPort = 0
        _subtitleCues.value = emptyList()
    }

    override fun attachSurface(surface: Surface?) {
        currentSurface = surface
        controller?.attachSurface(surface)
    }

    override fun videoAspectRatio(): Float = controller?.videoAspectRatio() ?: 0f

    // ─────────────────────── SENDER (DMC) ───────────────────────

    /**
     * DMC 会话映射：sessionId -> 对端设备 id（UDN）。
     *
     * ⚠️ 不能用 `activeSession` 找：DMC 会话根本不进 `activeSession` —— 那是
     * 接收端会话的字段，混用会让"投屏到别的设备"把界面切进接收端的播放态。
     * ⚠️ 也不能用会话里的 `peerAddress` 找：`DmcClient` 以 UDN 为键，
     * 而 `peerAddress` 存的是展示用的 IP，两者对不上。
     *
     * 当前 app 没有 DMC 界面入口，这条链路暂无真实调用方；
     * 但接口留着就必须是通的，否则将来做「投到其他设备」页面时这里全是暗坑。
     */
    private val dmcSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    override suspend fun discoverRenderers(timeoutMs: Long): List<CastDevice> {
        val client = dmc ?: return emptyList()
        return client.discover(timeoutMs)
    }

    suspend fun rendererAt(location: String): Result<CastDevice> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { (dmc ?: error("DLNA 模块未启动")).rendererAt(location) }
    }

    override suspend fun play(device: CastDevice, media: MediaInfo): Result<CastSession> =
        runCatching {
            val client = dmc ?: error("DLNA 模块未启动")
            client.play(device, media).getOrThrow()
            val session = CastSession(
                id = UUID.randomUUID().toString(),
                protocolId = id,
                peerName = device.name,
                peerAddress = device.address,
                media = media,
                state = PlaybackState.PLAYING,
            )
            dmcSessions[session.id] = device.id
            session
        }

    override suspend fun pause(sessionId: String): Result<Unit> =
        runCatching { dmc?.pause(remoteIdOf(sessionId))?.getOrThrow() ?: error("DLNA 模块未启动") }

    override suspend fun resume(sessionId: String): Result<Unit> =
        runCatching { dmc?.resume(remoteIdOf(sessionId))?.getOrThrow() ?: error("DLNA 模块未启动") }

    override suspend fun stopSession(sessionId: String): Result<Unit> =
        runCatching {
            dmc?.stop(remoteIdOf(sessionId))?.getOrThrow() ?: error("DLNA 模块未启动")
            dmcSessions.remove(sessionId)
        }

    override suspend fun seek(sessionId: String, positionMs: Long): Result<Unit> =
        runCatching { dmc?.seek(remoteIdOf(sessionId), positionMs)?.getOrThrow() ?: error("DLNA 模块未启动") }

    /** 由 DMC 会话 id 取对端设备 id（UDN）。未知会话返回空串，对端客户端会报"设备已离线"。 */
    private fun remoteIdOf(sessionId: String): String = dmcSessions[sessionId].orEmpty()

    // ─────────────────────── 内部工具 ───────────────────────

    private fun createSession(): CastSession = CastSession(
        id = UUID.randomUUID().toString(),
        protocolId = id,
        peerName = "DLNA 发送端",
        peerAddress = "",
    )

    private fun handlePlaybackState(newState: PlaybackState) {
        val session = activeSession ?: return
        if (session.state == newState) return
        val updated = session.copy(state = newState)
        activeSession = updated
        publishSessions()
        tryEmit(CastEvent.StateChanged(updated.id, id, newState))
        // 同步给订阅了 GENA 事件的控制端 —— 不能只依赖对方轮询 GetPositionInfo，
        // 只认事件不轮询的控制端会一直显示旧状态
        runCatching { dmr?.notifyTransportChange() }
        if (newState == PlaybackState.STOPPED) {
            tryEmit(CastEvent.SessionEnded(updated.id, id, "播放结束"))
            activeSession = null
            _sessions.value = emptyList()
            // 会话结束必须连队列一起清掉：否则 `GetMediaInfo` 会继续把上一条的
            // `NextURI` / 当前条目报给控制端（谎报"还排着一条"），
            // 严格实现的控制端按钮状态会跟着出错。
            runCatching { dmr?.clearQueue() }
        }
    }

    private fun publishSessions() {
        _sessions.value = listOfNotNull(activeSession)
    }

    private fun handleError(message: String, cause: Throwable?) {
        CastLogger.e(TAG, message, cause)
        tryEmit(CastEvent.Error(id, message, cause))
    }

    private fun tryEmit(event: CastEvent) {
        runCatching { _events.tryEmit(event) }
    }

    // ───────────────────── 接收端本地控制（电视遥控器） ─────────────────────

    /**
     * 当前内容是否可定位。
     *
     * 取决于播放器是否已解析出总时长：点播流起播后很快就有值；
     * 直播流拿不到时长，此时拖动没有意义 —— UI 会明确提示，
     * 而不是按了没反应让用户以为遥控器坏了。
     */
    override val canSeek: Boolean
        get() = controller?.canSeekNow() == true

    override fun seekBy(deltaMs: Long): Boolean {
        val renderer = controller ?: return false
        val current = runCatching { renderer.currentPosition() }.getOrNull() ?: return false
        if (current.durationMs <= 0) return false

        val target = (current.positionMs + deltaMs).coerceIn(0L, current.durationMs)
        renderer.seekTo(target)
        CastLogger.i(
            TAG,
            "遥控器定位：${current.positionMs / 1000}s → ${target / 1000}s" +
                "（${if (deltaMs > 0) "快进" else "快退"} ${kotlin.math.abs(deltaMs) / 1000}s）",
        )
        return true
    }

    override fun currentProgress(): PlaybackPosition? =
        controller?.let { runCatching { it.currentPosition() }.getOrNull() }

    /**
     * 电视端主动结束投屏。
     *
     * [MediaRendererController.stop] 会把播放器置为 STOPPED，状态回流到
     * [handlePlaybackState] —— 那里负责清会话、发 SessionEnded，并顺带推一次
     * AVTransport 的 LastChange，让手机上的播放状态同步停下来。
     * 所以这里只需要触发停止，不需要重复清状态。
     */
    override fun stopCasting(): Boolean {
        val renderer = controller ?: return false
        if (activeSession == null) return false
        CastLogger.i(TAG, "用户在电视端结束了投屏")
        renderer.stop()
        volumeGovernor.reset()
        return true
    }

    override fun setPlaying(play: Boolean): Boolean {
        val p = controller ?: return false
        if (activeSession == null) return false
        if (play) p.play() else p.pause()
        return true
    }
    override fun togglePause(): Boolean {
        val renderer = controller ?: return false
        if (activeSession == null) return false
        if (renderer.wantsToPlay()) renderer.pause() else renderer.play()
        return true
    }

    override fun retryPlayback(): Boolean {
        if (activeSession?.media == null) return false
        controller?.retry() ?: return false
        return true
    }

    override fun tracks() = controller?.tracks().orEmpty()
    override fun selectTrack(choice: com.casthub.core.MediaTrackChoice) = controller?.selectTrack(choice) ?: false

    override fun queueEntries() = dmr?.queueEntries().orEmpty()
    override fun videoDimensions() = controller?.videoDimensions() ?: (0 to 0)
    override fun queueCurrentId() = dmr?.queueCurrentId()
    override fun queueAdd(media: MediaInfo) = dmr?.queueAdd(media) ?: false
    override fun queuePlay(id: String) = dmr?.queuePlay(id) ?: false
    override fun queueStep(delta: Int) = dmr?.queueStep(delta) ?: false
    override fun queueMove(id: String, delta: Int) = dmr?.queueMove(id, delta) ?: false
    override fun queueRemove(id: String) = dmr?.queueRemove(id) ?: false
    override fun playbackDiagnostics() = controller?.playbackDiagnostics() ?: "播放器未启动"
    override fun resumeCandidate() = controller?.resumeCandidate()
    override fun applyTrackPreferences() { controller?.applyTrackPreferences() }
    override fun externalSubtitle(address: String?, mimeType: String) = controller?.externalSubtitle(address, mimeType) ?: false
    override fun seekAbsolute(positionMs: Long): Boolean {
        if (!canSeek) return false
        controller?.seekTo(positionMs)
        return true
    }
    override fun refreshTransportSettings() { dmr?.notifyTransportChange() }
    override fun refreshSubtitleTiming() { controller?.refreshSubtitleTiming() }

    fun shutdown() {
        moduleScope.cancel()
    }

    companion object {
        const val ID = "dlna"
        const val DEFAULT_DEVICE_NAME = "CastHub 投屏接收端"
        private const val TAG = "DlnaModule"
    }
}

package com.casthub.dlna.dmr

import com.casthub.core.CastLogger
import com.casthub.core.MediaInfo
import com.casthub.core.PlaybackMode
import com.casthub.core.PlaybackPosition
import com.casthub.core.PlaybackQueue
import com.casthub.core.PlaybackState
import com.casthub.dlna.renderer.MediaRendererController
import com.casthub.dlna.upnp.DeviceDescription
import com.casthub.dlna.upnp.EventSubscriptionManager
import com.casthub.dlna.upnp.Soap
import com.casthub.dlna.upnp.SsdpServer
import com.casthub.dlna.upnp.UpnpHttpServer
import com.casthub.dlna.upnp.UpnpTime
import java.util.UUID

/**
 * 把本机对外注册为一个标准 UPnP **MediaRenderer (DMR)**。
 *
 * 组成：
 * - [SsdpServer]  负责被发现（SSDP 通告 + M-SEARCH 响应）
 * - [UpnpHttpServer] 负责描述文档、SOAP 控制、GENA 订阅
 * - [MediaRendererController] 负责真正的播放
 *
 * 三者职责独立，任一层可直接替换（例如把播放器换成系统播放器）而不影响其它层。
 */
class DmrService(
    private val context: android.content.Context,
    private val localIp: String,
    private val controller: MediaRendererController,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onMediaChanged(media: MediaInfo?)
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onPeerActivity(peerName: String)
        fun onError(message: String, cause: Throwable? = null)
    }

    /**
     * 设备 UDN。**必须跨进程、跨重启保持稳定**，否则每次启动在控制点眼里都是
     * 一台新设备，投屏列表里会越积越多同名旧条目（AirPlay 侧的
     * `stableDeviceId` 处理过同样的问题，DLNA 这边漏了）。
     * 首次启动生成一个并持久化，之后一直复用。
     */
    private val udn: String = stableUdn(context)

    private var ssdp: SsdpServer? = null
    private var http: UpnpHttpServer? = null

    @Volatile
    private var deviceName: String = "CastHub Receiver"

    /** 最近一次 SetAVTransportURI 的内容，GetPositionInfo 要原样回给发送端。 */
    @Volatile
    private var currentUri: String = ""

    @Volatile
    private var currentMetaXml: String = ""

    @Volatile
    private var pendingStartPositionMs: Long = 0L

    val isRunning: Boolean get() = http?.isRunning == true || ssdp?.isRunning == true

    val deviceUdn: String get() = udn

    /** GENA 事件订阅：AVTransport / RenderingControl 的 LastChange 由它推送。 */
    private val events = EventSubscriptionManager()

    /**
     * 播放队列：由电视端维护，播完按 [PlaybackMode] 决定下一条。
     *
     * 顺序/循环播放不能指望发送端 —— 实测很多手机 App 只在切集时发一次
     * `SetAVTransportURI`，从不发 `SetNextAVTransportURI`，更不会自己接力。
     * 所以"下一条播什么"必须由接收端自己按播放历史推出来。
     */
    private val queue = PlaybackQueue()

    /** 播放模式由应用层注入（用户在设置页选，跨协议共用一个设置）。 */
    var modeProvider: () -> PlaybackMode = { PlaybackMode.SINGLE }

    /**
     * 一条内容播放完毕（ENDED）时调用。
     *
     * 之前一律映射为 PAUSED（会话保留）—— 那是"播完 ≠ 投屏结束"的对，
     * 因为发送端随时可能点下一集。但用户要求"在电视上选顺序/循环"，
     * 于是这里补上：**只有队列里确实还有下一条时才自动接上**，
     * 没有下一条就维持原行为（停在这一条，会话仍在）。
     *
     * 由 [com.casthub.dlna.DlnaModule] 监听 ENDED 后转发进来。
     *
     * @return 是否已切到下一条
     */
    fun onPlaybackEnded(): Boolean {
        queue.mode = modeProvider()
        val again = queue.onEndedHandled() ?: run {
            CastLogger.i(TAG, "播放结束（${queue.summary()}），保持当前会话，等发送端指令")
            return false
        }
        CastLogger.i(TAG, "单曲循环：重播 ${again.media.title ?: again.media.uri.take(60)}")
        // 回到开头重播。走 seek 而不是重新 open：
        // 直链常带时效 token，重新 open 可能已经过期；而这一条本来就在播。
        controller.seekTo(0)
        controller.play()
        notifyTransportChange()
        return true
    }

    /** 供日志/测试查看队列状态。 */
    fun queueSummary(): String = queue.summary()

    /**
     * 清空播放队列。
     *
     * 会话真正结束时调用（发送端 Stop、或用户在电视端结束投屏）。
     * 不清的话 `GetMediaInfo` 会继续上报已经不存在的 `NextURI`，
     * 而单条播完（ENDED）**不能**清 —— 那时用户还可能点下一集。
     */
    fun clearQueue() {
        queue.clear()
    }

    fun start(friendlyName: String, port: Int) {
        stop()
        deviceName = friendlyName
        try {
            // 订阅成功时用这些快照推一次初始事件（UPnP 要求订阅后立刻给当前状态）
            events.initialPayloadProvider = { serviceId ->
                when (serviceId) {
                    "AVTransport" -> buildTransportEvent()
                    "RenderingControl" -> buildRenderingEvent()
                    "ConnectionManager" -> buildConnectionManagerEvent()
                    else -> null
                }
            }
            events.start()
            val httpServer = UpnpHttpServer(port, events).apply {
                handler = Handler()
            }
            httpServer.start()

            val ssdpServer = SsdpServer(
                context = context,
                localIp = localIp,
                httpPort = port,
                deviceName = friendlyName,
                udn = udn,
            )
            ssdpServer.start()

            http = httpServer
            ssdp = ssdpServer
            CastLogger.i(TAG, "DMR 已启动：$friendlyName @ http://$localIp:$port/device.xml")
        } catch (t: Throwable) {
            CastLogger.e(TAG, "DMR 启动失败", t)
            stop()
            callbacks.onError("DLNA 接收端启动失败：${t.message}", t)
        }
    }

    fun stop() {
        runCatching { ssdp?.stop() }
        runCatching { http?.stop() }
        runCatching { events.stop() }
        ssdp = null
        http = null
        currentUri = ""
        currentMetaXml = ""
        pendingStartPositionMs = 0L
        CastLogger.i(TAG, "DMR 已停止")
    }

    /** 换设备名：重新启动以刷新 device.xml 与 SSDP 通告。 */
    fun rename(newName: String, port: Int) {
        if (!isRunning) return
        start(newName, port)
    }

    // ─────────────────────── UPnP 动作处理 ───────────────────────

    private inner class Handler : UpnpHttpServer.Handler {

        override fun deviceXml(): String = DeviceDescription.deviceXml(deviceName, udn)

        override fun scpd(path: String): String? = when (path) {
            "/avtransport.xml" -> DeviceDescription.AVTRANSPORT_SCPD
            "/renderingcontrol.xml" -> DeviceDescription.RENDERING_CONTROL_SCPD
            "/connectionmanager.xml" -> DeviceDescription.CONNECTION_MANAGER_SCPD
            else -> null
        }

        override fun onControl(
            service: String,
            action: String,
            args: Map<String, String>,
        ): Map<String, String>? = when (service) {
            DeviceDescription.SERVICE_AV_TRANSPORT -> avTransport(action, args)
            DeviceDescription.SERVICE_RENDERING_CONTROL -> renderingControl(action, args)
            DeviceDescription.SERVICE_CONNECTION_MANAGER -> connectionManager(action, args)
            else -> null
        }

        // ── AVTransport ──

        private fun avTransport(action: String, args: Map<String, String>): Map<String, String>? =
            when (action) {
                "SetAVTransportURI" -> {
                    handleSetUri(args)
                    notifyTransportChange()
                    emptyMap()
                }

                "SetNextAVTransportURI" -> {
                    // 此前这里是 emptyMap()：SCPD 声明了这个动作，收到却什么都不做 ——
                    // 属于"声明了做不到"。现在如实记录并回读给 GetMediaInfo，
                    // 让发送端知道本机收到了。
                    //
                    // ⚠️ 但**不要**指望它来续播：实测手机 App 基本不发这个动作，
                    // 而且规范明确允许接收端忽略。"下一条播什么"只有发送端知道
                    // （播放列表在它手里），所以顺序/连播必须在手机端做。
                    val uri = args["NextURI"].orEmpty()
                    val meta = args["NextURIMetaData"].orEmpty()
                    if (uri.isBlank()) {
                        CastLogger.w(TAG, "SetNextAVTransportURI 未带 NextURI，忽略")
                    } else {
                        queue.setNext(uri, meta)
                        CastLogger.i(TAG, "已记录发送端给的下一条：${extractTitle(meta) ?: uri.take(60)}")
                    }
                    notifyTransportChange()
                    emptyMap()
                }

                "Play" -> {
                    CastLogger.i(TAG, "▶ Play")
                    // 若发送端给了起始位置（部分 App 用它实现续播），先跳转再播
                    if (pendingStartPositionMs > 0) {
                        controller.seekTo(pendingStartPositionMs)
                        pendingStartPositionMs = 0
                    }
                    controller.play()
                    callbacks.onPeerActivity("")
                    notifyTransportChange()
                    emptyMap()
                }

                "Pause" -> {
                    CastLogger.i(TAG, "⏸ Pause")
                    controller.pause()
                    notifyTransportChange()
                    emptyMap()
                }

                "Stop" -> {
                    CastLogger.i(TAG, "⏹ Stop")
                    controller.stop()
                    // 会话真正结束，此时才清队列。
                    // 单条播完（ENDED）不能清，否则顺序播放刚开始就没历史了。
                    queue.clear()
                    callbacks.onMediaChanged(null)
                    notifyTransportChange()
                    emptyMap()
                }

                "Seek" -> {
                    val unit = args["Unit"].orEmpty()
                    val target = args["Target"].orEmpty()
                    val positionMs = if (unit.equals("REL_TIME", ignoreCase = true)) {
                        UpnpTime.parse(target)
                    } else {
                        target.toLongOrNull() ?: 0L
                    }
                    CastLogger.i(TAG, "⏩ Seek($unit) -> ${positionMs}ms")
                    controller.seekTo(positionMs)
                    notifyTransportChange()
                    emptyMap()
                }

                "GetTransportInfo" -> mapOf(
                    "CurrentTransportState" to controller.state.toUpnpName(),
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )

                "GetPositionInfo" -> {
                    val position = controller.currentPosition()
                    mapOf(
                        "Track" to "1",
                        "TrackDuration" to UpnpTime.format(position.durationMs),
                        "TrackMetaData" to currentMetaXml,
                        "TrackURI" to currentUri,
                        "RelTime" to UpnpTime.format(position.positionMs),
                        "AbsTime" to UpnpTime.format(position.positionMs),
                        "RelCount" to Int.MAX_VALUE.toString(),
                        "AbsCount" to Int.MAX_VALUE.toString(),
                    )
                }

                "GetMediaInfo" -> mapOf(
                    "NrTracks" to "1",
                    "MediaDuration" to UpnpTime.format(controller.duration()),
                    "CurrentURI" to currentUri,
                    "CurrentURIMetaData" to currentMetaXml,
                    // 如实报告还有没有下一条。谎报会让控制端的按钮状态出错。
                    "NextURI" to queue.nextUri,
                    "NextURIMetaData" to queue.nextMetaXml,
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                )

                "GetDeviceCapabilities" -> mapOf(
                    "PlayMedia" to "NETWORK,NONE",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                )

                "GetTransportSettings" -> mapOf(
                    "PlayMode" to "NORMAL",
                    "RecQualityMode" to "NOT_IMPLEMENTED",
                )

                "Next", "Previous" -> emptyMap()

                else -> {
                    CastLogger.w(TAG, "不支持的 AVTransport 动作：$action")
                    null
                }
            }

        private fun handleSetUri(args: Map<String, String>) {
            val uri = args["CurrentURI"].orEmpty()
            val meta = args["CurrentURIMetaData"].orEmpty()

            currentUri = uri
            currentMetaXml = meta
            pendingStartPositionMs = parseStartPosition(meta)

            val title = extractTitle(meta)
            val protocolInfo = extractProtocolInfo(meta)

            val media = MediaInfo(
                uri = uri,
                title = title,
                declaredMimeType = protocolInfo?.substringAfter("http-get:*:" )?.substringBefore(':'),
            )

            CastLogger.i(TAG, "📺 收到投屏：${title ?: "(无标题)"}")
            CastLogger.i(TAG, "   URL: ${uri.take(160)}")
            if (protocolInfo != null) {
                // 实测发送端可能给出与实际不符的 protocolInfo，这里只做记录
                CastLogger.d(TAG, "   protocolInfo: $protocolInfo (仅参考，实际格式按 URL 后缀判断)")
            }

            // 记入播放队列：电视端自己维护"放过哪些条目"，
            // 播完按用户选的模式决定下一条，不依赖发送端是否支持连播。
            queue.mode = modeProvider()
            val entry = queue.onPlayed(media, meta)
            CastLogger.d(TAG, "   ${queue.summary()}，当前=${entry.media.title ?: "?"}")

            callbacks.onMediaChanged(media)
            callbacks.onPeerActivity(extractSenderName(meta))

            // 直链带时效 token，必须立刻起播
            controller.open(media)
        }

        // ── RenderingControl ──

        private fun renderingControl(
            action: String,
            args: Map<String, String>,
        ): Map<String, String>? = when (action) {
            "GetVolume" -> mapOf("CurrentVolume" to volumeReader().toString())

            "SetVolume" -> {
                val volume = args["DesiredVolume"]?.toIntOrNull() ?: 50
                volumeSink(volume)
                notifyRenderingChange()
                emptyMap()
            }

            "GetMute" -> mapOf("CurrentMute" to if (muteReader()) "1" else "0")

            "SetMute" -> {
                val muted = args["DesiredMute"] == "1" || args["DesiredMute"].equals("true", true)
                muteSink(muted)
                notifyRenderingChange()
                emptyMap()
            }

            "ListPresets" -> mapOf("CurrentPresetNameList" to "FactoryDefaults")

            "SelectPreset" -> emptyMap()

            else -> {
                CastLogger.w(TAG, "不支持的 RenderingControl 动作：$action")
                null
            }
        }

        // ── ConnectionManager ──

        private fun connectionManager(
            action: String,
            // ConnectionManager 的动作都不带入参，签名保持与其它服务处理函数一致
            @Suppress("UNUSED_PARAMETER") args: Map<String, String>,
        ): Map<String, String>? = when (action) {
            "GetProtocolInfo" -> {
                // 这个响应决定了发送端是否愿意把流推给本机，必须完整
                mapOf(
                    "Source" to "",
                    "Sink" to DeviceDescription.SINK_PROTOCOL_INFO,
                )
            }

            "GetCurrentConnectionIDs" -> mapOf("ConnectionIDs" to "0")

            "GetCurrentConnectionInfo" -> mapOf(
                "RcsID" to "0",
                "AVTransportID" to "0",
                "ProtocolInfo" to "",
                "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1",
                "Direction" to "Input",
                "Status" to "OK",
            )

            else -> {
                CastLogger.w(TAG, "不支持的 ConnectionManager 动作：$action")
                null
            }
        }
    }

    // ─────────────────────── GENA 事件推送 ───────────────────────

    /**
     * 推送 AVTransport 的 LastChange。
     *
     * 只推**状态类**变量（传输状态 / 当前 URI / 时长），**不推相对时间位置** ——
     * 位置每秒都在变，推进事件会造成通知风暴；按 DLNA 的分工，
     * 位置本来就该由控制端通过 GetPositionInfo 查询。
     */
    internal fun notifyTransportChange() {
        events.notifyLastChange("AVTransport", buildTransportEvent())
    }

    /** 推送 RenderingControl 的 LastChange（音量 / 静音）。 */
    private fun notifyRenderingChange() {
        events.notifyLastChange("RenderingControl", buildRenderingEvent())
    }

    private fun buildTransportEvent(): String {
        val position = runCatching { controller.currentPosition() }.getOrNull()
        return buildString {
            append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\">")
            append("<InstanceID val=\"0\">")
            append("<TransportState val=\"${position?.state?.toUpnpName() ?: "STOPPED"}\"/>")
            append("<TransportStatus val=\"OK\"/>")
            append("<NumberOfTracks val=\"1\"/>")
            append("<CurrentTrack val=\"1\"/>")
            if (currentUri.isNotEmpty()) {
                val escaped = Soap.escape(currentUri)
                append("<AVTransportURI val=\"$escaped\"/>")
                append("<CurrentTrackURI val=\"$escaped\"/>")
            }
            val durationMs = position?.durationMs ?: 0L
            if (durationMs > 0) {
                val duration = UpnpTime.format(durationMs)
                append("<CurrentTrackDuration val=\"$duration\"/>")
                append("<CurrentMediaDuration val=\"$duration\"/>")
            }
            append("</InstanceID></Event>")
        }
    }

    private fun buildRenderingEvent(): String = buildString {
        append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\">")
        append("<InstanceID val=\"0\">")
        append("<Volume channel=\"Master\" val=\"${volumeReader()}\"/>")
        append("<Mute channel=\"Master\" val=\"${if (muteReader()) 1 else 0}\"/>")
        append("</InstanceID></Event>")
    }

    private fun buildConnectionManagerEvent(): String = buildString {
        append("<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/CM/\">")
        append("<InstanceID val=\"0\">")
        append("<SourceProtocolInfo val=\"\"/>")
        append("<SinkProtocolInfo val=\"${Soap.escape(DeviceDescription.SINK_PROTOCOL_INFO)}\"/>")
        append("<CurrentConnectionIDs val=\"0\"/>")
        append("</InstanceID></Event>")
    }

    // ─────────────────────── 音量钩子（由模块注入） ───────────────────────

    var volumeReader: () -> Int = { 50 }
    var volumeSink: (Int) -> Unit = {}
    var muteReader: () -> Boolean = { false }
    var muteSink: (Boolean) -> Unit = {}

    // ─────────────────────── DIDL-Lite 解析 ───────────────────────

    private fun extractTitle(didl: String): String? {
        if (didl.isBlank()) return null
        return Regex("<dc:title>(.*?)</dc:title>", RegexOption.DOT_MATCHES_ALL)
            .find(didl)?.groupValues?.getOrNull(1)
            ?.let { com.casthub.dlna.upnp.Soap.unescape(it).trim() }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun extractSenderName(didl: String): String {
        if (didl.isBlank()) return ""
        return listOf("dc:channel", "dc:uid", "dc:creator")
            .mapNotNull { tag ->
                Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                    .find(didl)?.groupValues?.getOrNull(1)?.trim()
            }
            .firstOrNull { it.isNotEmpty() }
            ?.let { com.casthub.dlna.upnp.Soap.unescape(it) }
            ?: ""
    }

    private fun extractProtocolInfo(didl: String): String? =
        Regex("protocolInfo=\"([^\"]*)\"").find(didl)?.groupValues?.getOrNull(1)

    /** 部分发送端会在 DIDL 里带 Start-Position（秒）。 */
    private fun parseStartPosition(didl: String): Long {
        val match = Regex("Start-Position[^>]*>\\s*([0-9.]+)", RegexOption.IGNORE_CASE)
            .find(didl) ?: return 0L
        return runCatching { (match.groupValues[1].toDouble() * 1000).toLong() }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "DmrService"
        private const val PREFS_IDENTITY = "casthub_dlna_identity"
        private const val KEY_UDN = "udn"

        private fun stableUdn(context: android.content.Context): String {
            val prefs = context.getSharedPreferences(PREFS_IDENTITY, android.content.Context.MODE_PRIVATE)
            prefs.getString(KEY_UDN, null)?.let { return it }
            val generated = "uuid:${UUID.randomUUID()}"
            prefs.edit().putString(KEY_UDN, generated).apply()
            return generated
        }
    }
}

/** 播放状态到 UPnP TransportState 的映射。 */
private fun PlaybackState.toUpnpName(): String = when (this) {
    PlaybackState.PLAYING -> "PLAYING"
    PlaybackState.PAUSED -> "PAUSED_PLAYBACK"
    PlaybackState.BUFFERING -> "TRANSITIONING"
    PlaybackState.STOPPED, PlaybackState.IDLE -> "STOPPED"
    PlaybackState.ERROR -> "STOPPED"
}

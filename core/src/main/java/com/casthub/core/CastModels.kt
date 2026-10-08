package com.casthub.core

/**
 * 协议能力位。一个协议模块可以只实现其中一部分能力，
 * 例如只做接收端（DMR），不做发送端（DMC）。
 */
enum class ProtocolCapability {
    /** 可作为接收端：接收其它设备推来的媒体（DLNA DMR / AirPlay Server）。 */
    RECEIVER,

    /** 可作为发送端：发现并控制网络中的渲染设备（DLNA DMC）。 */
    SENDER,
}

/** 模块可用性。用于「可独立启用」——不可用时给出明确原因而不是静默失败。 */
sealed class Availability {
    /** 当前设备/环境完全支持该模块。 */
    object Available : Availability()

    /** 缺少 native 库或运行时依赖，并附带接入提示。 */
    data class MissingDependency(val dependency: String, val hint: String) : Availability()

    /** 缺少运行时权限。 */
    data class MissingPermission(val permissions: List<String>) : Availability()

    /** 平台本身不支持（如系统 API 低于要求）。 */
    data class UnsupportedPlatform(val reason: String) : Availability()
}

/** 局域网中的一台可投屏设备。跨协议统一模型。 */
data class CastDevice(
    /** 协议内唯一标识（DLNA 用 UDN，AirPlay 用设备 id）。 */
    val id: String,
    val name: String,
    /** 所属协议模块 id，如 "dlna"、"airplay"。 */
    val protocolId: String,
    /** 设备地址（IP）。 */
    val address: String,
    val port: Int = 0,
    val modelName: String? = null,
    val manufacturer: String? = null,
    /** 是否为本机模块自身对外提供的服务。 */
    val isLocal: Boolean = false,
    val capabilities: Set<ProtocolCapability> = emptySet(),
    /** 协议特有的补充信息，用于诊断展示。 */
    val extra: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean =
        other is CastDevice && other.id == id && other.protocolId == protocolId

    override fun hashCode(): Int = 31 * id.hashCode() + protocolId.hashCode()

    override fun toString(): String = "[$protocolId] $name @$address:$port"
}

/** 播放状态。跨协议统一。 */
enum class PlaybackState {
    IDLE, STOPPED, PLAYING, PAUSED, BUFFERING, ERROR;

    val isActive: Boolean get() = this == PLAYING || this == PAUSED || this == BUFFERING
}

/** 待播放的媒体描述。 */
data class MediaInfo(
    val uri: String,
    val title: String? = null,
    /**
     * MIME 类型。注意：不要盲信发送端给的 protocolInfo，
     * 实测夸克 DLNA 通道会把 m3u8 标成 video/mp4。这里以 URL 后缀推断为准，
     * 显式传入的 mimeType 仅作参考。
     */
    val declaredMimeType: String? = null,
    val durationMs: Long = -1L,
    val startPositionMs: Long = 0L,
    val artworkUri: String? = null,
    /** 拉流时需要附加的请求头（防盗链场景）。 */
    val httpHeaders: Map<String, String> = emptyMap(),
) {
    /** 依据 URL 后缀推断是否为 HLS。 */
    val isHls: Boolean
        get() = uri.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    val isDash: Boolean
        get() = uri.substringBefore('?').endsWith(".mpd", ignoreCase = true)

    val effectiveMimeType: String?
        get() = when {
            isHls -> "application/x-mpegURL"
            isDash -> "application/dash+xml"
            else -> declaredMimeType
        }
}

/** 播放进度。 */
data class PlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
    val state: PlaybackState,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** 一次投屏会话。以「谁把什么投到了这里」为核心。 */
data class CastSession(
    val id: String,
    val protocolId: String,
    /** 发起端名称（如 "Xiaomi 25113PN0EC"）。 */
    val peerName: String,
    val peerAddress: String,
    val media: MediaInfo? = null,
    val state: PlaybackState = PlaybackState.IDLE,
    val startedAt: Long = System.currentTimeMillis(),
) {
    val isReceiverSide: Boolean get() = peerAddress.isNotEmpty()
}

/** 模块运行状态。 */
sealed class ModuleState {
    object Stopped : ModuleState()
    object Starting : ModuleState()
    object Running : ModuleState()
    data class Failed(val reason: String, val cause: Throwable? = null) : ModuleState()
    data class Unavailable(val availability: Availability) : ModuleState()

    val isRunning: Boolean get() = this is Running
}

/** 模块对外广播的事件。UI 与协调器都消费这个流。 */
sealed class CastEvent {
    data class SessionStarted(val session: CastSession) : CastEvent()
    data class SessionEnded(val sessionId: String, val protocolId: String, val reason: String? = null) :
        CastEvent()

    data class MediaChanged(val sessionId: String, val protocolId: String, val media: MediaInfo) :
        CastEvent()

    data class StateChanged(
        val sessionId: String,
        val protocolId: String,
        val state: PlaybackState,
    ) : CastEvent()

    data class PositionChanged(
        val sessionId: String,
        val protocolId: String,
        val position: PlaybackPosition,
    ) : CastEvent()

    data class VolumeChanged(
        val sessionId: String,
        val protocolId: String,
        val volume: Int,
        val muted: Boolean,
    ) : CastEvent()

    data class DeviceFound(val device: CastDevice) : CastEvent()
    data class DeviceLost(val deviceId: String, val protocolId: String) : CastEvent()

    data class Error(
        val protocolId: String,
        val message: String,
        val cause: Throwable? = null,
    ) : CastEvent()
}

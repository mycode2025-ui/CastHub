package com.casthub.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 协议模块统一契约。
 *
 * 设计目标：
 * 1. **解耦** —— app 层只依赖本接口，不依赖任何协议实现细节；
 * 2. **可独立启用** —— 每个模块可由用户单独开关，且启动失败只影响自身；
 * 3. **能力可选** —— 只做接收端的模块无需实现发送端方法，默认返回「不支持」。
 *
 * 实现约定：
 * - [start] / [stop] 必须可重复调用而不报错（幂等）；
 * - 任何异常都必须收敛为 [state] 的 Failed 或 [events] 中的 Error，不得向上抛；
 * - 所有阻塞操作必须可取消（协程）。
 */
interface ProtocolModule {

    /** 模块唯一 id，如 "dlna" / "airplay"。 */
    val id: String

    /** 展示名。 */
    val displayName: String

    /**
     * 面向普通用户的一句话说明：这个接收服务能收到哪些设备的投屏。
     *
     * 由协议模块自己提供，UI 无需硬编码任何协议知识 ——
     * 这样新增协议时，界面上的说明文案也会自动跟着来。
     * 未实现时为空字符串，UI 需容忍空值。
     */
    val description: String get() = ""

    val capabilities: Set<ProtocolCapability>

    /** 模块运行状态。 */
    val state: StateFlow<ModuleState>

    /** 当前活跃会话。 */
    val sessions: StateFlow<List<CastSession>>

    /** 事件流，供 UI 与协调器消费。 */
    val events: SharedFlow<CastEvent>

    /** 本机设备对外展示的名称（发送端在设备列表里看到的名字）。 */
    val localDeviceName: String

    /**
     * 本机在该协议下的服务地址（局域网 IP）。未运行时为空字符串。
     * 由 UI 用于展示，便于用户排查网络问题。
     */
    val localAddress: String get() = ""

    /** 本机在该协议下的监听端口。未运行时为 0。 */
    val localPort: Int get() = 0

    /** 修改本机设备名，实现内部负责持久化与重新广播。 */
    fun setLocalDeviceName(name: String)

    /**
     * 在启动前检查可用性。
     * 例如 AirPlay 模块在 native 库缺失时返回 [Availability.MissingDependency]，
     * 而不是等到 start() 抛异常。模块实现内部持有 Context，故无需入参。
     */
    fun checkAvailability(): Availability

    /** 启动模块：开始监听/广播。幂等。 */
    suspend fun start()

    /** 停止模块：释放 socket、线程、播放器。幂等。 */
    suspend fun stop()

    // ───────────────────── SENDER 能力（可选实现） ─────────────────────

    /** 搜索网络中的可投屏渲染设备（DMC / AirPlay 发送端场景）。 */
    suspend fun discoverRenderers(timeoutMs: Long = 8_000L): List<CastDevice> = emptyList()

    /** 将媒体推送到指定设备。 */
    suspend fun play(device: CastDevice, media: MediaInfo): Result<CastSession> =
        Result.failure(UnsupportedOperationException("$displayName 未实现发送端投屏能力"))

    suspend fun pause(sessionId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$displayName 未实现暂停"))

    suspend fun resume(sessionId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$displayName 未实现继续播放"))

    suspend fun seek(sessionId: String, positionMs: Long): Result<Unit> =
        Result.failure(UnsupportedOperationException("$displayName 未实现进度调整"))

    suspend fun stopSession(sessionId: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("$displayName 未实现停止会话"))
}

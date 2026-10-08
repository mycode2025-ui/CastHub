package com.casthub.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 模块注册表：统一管理所有协议模块的启动、停止与开关。
 *
 * 关键设计：
 * - **故障隔离**：单个模块启动失败不影响其它模块，异常被收敛并记录；
 * - **幂等**：重复 start/stop 安全；
 * - **可独立启用**：开关状态落盘，启动时只拉起被启用的模块。
 */
class ModuleRegistry(
    val modules: List<ProtocolModule>,
    private val store: ModuleEnabledStore,
) {

    private val mutex = Mutex()

    fun byId(id: String): ProtocolModule? = modules.firstOrNull { it.id == id }

    fun isEnabled(module: ProtocolModule): Boolean = store.isEnabled(module.id)

    /** 探测模块可用性，异常一律视为不可用，不向上抛。 */
    fun availabilityOf(module: ProtocolModule): Availability =
        runCatching { module.checkAvailability() }
            .getOrElse { Availability.UnsupportedPlatform(it.message ?: it.javaClass.simpleName) }

    /**
     * 切换模块开关。开启时立即启动，关闭时立即释放资源。
     * 即使启动失败也不抛出 —— 失败信息通过模块 state / 日志呈现。
     *
     * ⚠️ 调用方多在 UI 线程（`lifecycleScope.launch` 默认 `Main.immediate`），
     * 而模块的起停是实打实的阻塞 IO（绑 ServerSocket、取 Wi-Fi 多播锁、
     * `NsdManager.registerService` 最长同步等 4 秒）。所以 [startInternal] /
     * [stopInternal] 内部统一切到 [Dispatchers.IO] —— 否则点一下开关就会卡住
     * 主线程（实测 `Choreographer: Skipped 37 frames`），在电视上表现为遥控器按键被吞。
     */
    suspend fun setEnabled(module: ProtocolModule, enabled: Boolean) {
        store.setEnabled(module.id, enabled)
        mutex.withLock {
            if (enabled) startInternal(module) else stopInternal(module)
        }
    }

    /** 启动所有已启用的模块。 */
    suspend fun startEnabled() {
        mutex.withLock {
            modules.forEach { module ->
                if (store.isEnabled(module.id)) startInternal(module)
                else CastLogger.i(TAG, "${module.displayName} 已被用户禁用，跳过启动")
            }
        }
    }

    /** 停止全部模块。 */
    suspend fun stopAll() {
        mutex.withLock { modules.forEach { stopInternal(it) } }
    }

    /** 重启单个模块（改设备名后需要重新广播时使用）。 */
    suspend fun restart(module: ProtocolModule) {
        mutex.withLock {
            stopInternal(module)
            if (store.isEnabled(module.id)) startInternal(module)
        }
    }

    private suspend fun startInternal(module: ProtocolModule) = withContext(Dispatchers.IO) {
        val availability = availabilityOf(module)
        if (availability !is Availability.Available) {
            CastLogger.w(TAG, "${module.displayName} 不可用：${describe(availability)}")
        }
        try {
            module.start()
        } catch (t: Throwable) {
            // start() 契约要求自行收敛异常，这里再兜一层，保证不影响其它模块
            CastLogger.e(TAG, "${module.displayName} 启动时抛出异常", t)
        }
        Unit
    }

    private suspend fun stopInternal(module: ProtocolModule) = withContext(Dispatchers.IO) {
        try {
            module.stop()
        } catch (t: Throwable) {
            CastLogger.e(TAG, "${module.displayName} 停止时抛出异常", t)
        }
        Unit
    }

    private fun describe(a: Availability): String = when (a) {
        is Availability.Available -> "可用"
        is Availability.MissingDependency -> "缺少依赖 ${a.dependency}"
        is Availability.MissingPermission -> "缺少权限 ${a.permissions.joinToString()}"
        is Availability.UnsupportedPlatform -> "平台不支持：${a.reason}"
    }

    companion object {
        private const val TAG = "ModuleRegistry"
    }
}

/**
 * 会话协调器：把各模块的会话汇总成统一视图，供 UI 呈现。
 *
 * 不做协议互斥强杀 —— 各协议天然可以并行接收（DLNA 与 AirPlay 同时各接一路），
 * 协调器只负责聚合与错误汇总。
 */
class SessionCoordinator(private val scope: CoroutineScope) {

    private val lock = Any()
    private val perModule = LinkedHashMap<String, List<CastSession>>()

    private val _sessions = kotlinx.coroutines.flow.MutableStateFlow<List<CastSession>>(emptyList())
    val sessions: kotlinx.coroutines.flow.StateFlow<List<CastSession>> = _sessions

    private val _latestError = kotlinx.coroutines.flow.MutableStateFlow<CastEvent.Error?>(null)
    val latestError: kotlinx.coroutines.flow.StateFlow<CastEvent.Error?> = _latestError

    /** 绑定模块，持续汇总其会话与错误。 */
    fun bind(module: ProtocolModule) {
        scope.launchSafely("bind:${module.id}") {
            module.sessions.collect { list -> merge(module.id, list) }
        }
        scope.launchSafely("errors:${module.id}") {
            module.events.collect { event ->
                if (event is CastEvent.Error) _latestError.value = event
            }
        }
    }

    private fun merge(moduleId: String, list: List<CastSession>) {
        val merged = synchronized(lock) {
            perModule[moduleId] = list
            perModule.values.flatten().sortedByDescending { it.startedAt }
        }
        _sessions.value = merged
    }

    fun clearError() {
        _latestError.value = null
    }
}

package com.casthub.app

import android.app.Application
import android.content.Intent
import com.casthub.airplay.AirPlayModule
import com.casthub.app.update.UpdateCoordinator
import com.casthub.core.CastLogger
import com.casthub.core.ModuleEnabledStore
import com.casthub.core.ModuleRegistry
import com.casthub.core.ProtocolModule
import com.casthub.core.SessionCoordinator
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用装配点。
 *
 * 这里是唯一知道「有哪些协议模块」的地方 —— 上层 UI 与 core 层都不依赖具体协议实现，
 * 新增协议只需在这里加一行注册。
 */
class CastHubApplication : Application() {

    lateinit var store: ModuleEnabledStore
        private set

    /** 已注册的全部协议模块。 */
    lateinit var modules: List<ProtocolModule>
        private set

    lateinit var registry: ModuleRegistry
        private set

    lateinit var coordinator: SessionCoordinator
        private set

    /**
     * 升级检测入口。
     *
     * 挂在 Application 上而不是各 Activity 里各建一个：节流状态（上次检查时间、
     * 被忽略的版本）必须全应用共享，否则主页与设置页会各查一次、各记一份。
     */
    lateinit var updates: UpdateCoordinator
        private set

    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("app")
    )

    override fun onCreate() {
        super.onCreate()

        store = ModuleEnabledStore(this)
        updates = UpdateCoordinator(this)

        // 注册协议模块。新增协议只需在此追加。
        modules = listOf(
            com.casthub.dlna.DlnaModule(this),
            AirPlayModule(this),
        )

        // 拉齐设备名。各协议模块自己也能存名字，若不统一，手机上会看到本设备
        // 以不同名字出现多次（例如 "CastHub 投屏接收端" 与 "CastHub AirPlay"），
        // 这是让用户困惑的反常识点。这里统一成同一个名字。
        applyDeviceName(savedDeviceName)

        registry = ModuleRegistry(modules, store)
        coordinator = SessionCoordinator(appScope)
        modules.forEach { coordinator.bind(it) }
        observeSessionsForForeground()

        CastLogger.i(TAG, "CastHub 启动，已注册 ${modules.size} 个协议模块")

        // 按用户上次的开关状态恢复各模块。
        //
        // ⚠️ 必须显式指定后台线程：appScope 的调度器是 Dispatchers.Main.immediate，
        // 在主线程 launch 时 immediate 会**同步执行协程体直到第一个挂起点**。
        // 启动时这里做的是绑定 socket、获取多播锁、监听 HTTP 服务，
        // 全是实打实的阻塞 IO —— 写在 Main 上等于把首帧绘制往后推（实测启动 4.6 秒）。
        // 模块内部真正需要主线程的部分（ExoPlayer 只能在主线程访问）会自行切换。
        appScope.launch(Dispatchers.IO) {
            runCatching { registry.startEnabled() }
                .onFailure { CastLogger.e(TAG, "启动已启用模块失败", it) }
        }
    }

    /** 当前设备名。所有协议模块共用这一个名字。 */
    val savedDeviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME

    /**
     * 修改设备名并同步到全部模块。
     *
     * 已在运行的模块需要重启才会以新名字重新广播，该动作由调用方
     * （设置页）通过 [ModuleRegistry.restart] 完成，这里只负责落盘与属性同步。
     */
    fun renameDevice(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed == savedDeviceName) return false
        prefs.edit().putString(KEY_DEVICE_NAME, trimmed).apply()
        applyDeviceName(trimmed)
        return true
    }

    private fun applyDeviceName(name: String) {
        modules.forEach { module ->
            runCatching { module.setLocalDeviceName(name) }
                .onFailure { CastLogger.w(TAG, "${module.displayName} 设置设备名失败", it) }
        }
    }

    /** 本次投屏是否已经把主界面拉到过前台，避免同一个会话反复拉起。 */
    @Volatile
    private var sessionBroughtToFront = false

    /**
     * 投屏到来时把主界面带到前台。
     *
     * 接收端的唯一职责就是播放推过来的内容。用户若把它切到后台（例如按了 Home
     * 去看别的），投屏来了却不切回来，就会出现"只有声音、没有画面"的困惑。
     *
     * 注意：Android 10+ 对后台启动 Activity 有严格限制，这里失败也不影响功能 ——
     * 用户手动切回前台时，会话状态流会立刻把界面切成播放态。
     */
    private fun observeSessionsForForeground() {
        appScope.launch {
            coordinator.sessions.collect { sessions ->
                val active = sessions.any { it.state.isActive }
                if (active && !sessionBroughtToFront) {
                    sessionBroughtToFront = true
                    bringMainToFront()
                } else if (!active) {
                    sessionBroughtToFront = false
                }
            }
        }
    }

    private fun bringMainToFront() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                },
            )
        }.onFailure { CastLogger.w(TAG, "无法将主界面切到前台（系统限制），不影响播放", it) }
    }

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    companion object {
        private const val TAG = "CastHubApp"
        private const val PREFS_NAME = "casthub_settings"
        private const val KEY_DEVICE_NAME = "device_name"

        /** 默认设备名。用户可在设置里改成「客厅的电视」这类好记的名字。 */
        const val DEFAULT_DEVICE_NAME = "CastHub 投屏"
    }
}

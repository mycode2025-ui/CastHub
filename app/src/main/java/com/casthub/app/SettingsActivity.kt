package com.casthub.app

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.casthub.app.update.UpdateFlow
import com.casthub.core.CastLogger
import com.casthub.core.ModuleEnabledStore
import com.casthub.core.ModuleState
import com.casthub.core.PlaybackMode
import com.casthub.core.ProtocolModule
import kotlinx.coroutines.launch

/**
 * 设置页 —— 收纳所有配置与诊断内容。
 *
 * 主页刻意不出现这些：协议开关一旦被误关，用户在手机里就再也搜不到本设备，
 * 而"为什么搜不到"对普通用户无从排查。把开关放在这里，并配一句明确的后果说明。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: CastHubApplication
    private lateinit var llModules: LinearLayout

    private lateinit var tvDeviceName: TextView
    private lateinit var tvIp: TextView
    private lateinit var tvPlaybackMode: TextView
    private lateinit var rowPlaybackMode: View
    private lateinit var btnToggleLog: TextView
    private lateinit var llLog: LinearLayout
    private lateinit var tvLog: TextView

    private lateinit var rowCheckUpdate: View
    private lateinit var btnCheckUpdate: TextView
    private lateinit var updateFlow: UpdateFlow

    private val moduleStates = mutableMapOf<String, ModuleState>()
    private val renderedLog = StringBuilder()
    private var unregisterLog: (() -> Unit)? = null
    private var logExpanded = false

    /** 播放模式偏好（跨协议共用，进程重启后保留）。 */
    private val store by lazy { ModuleEnabledStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        app = application as CastHubApplication

        bindViews()
        setupModuleList()
        setupPlaybackMode()
        setupUpdateCheck()
        setupLog()
        observeModules()

        renderDeviceName()
        renderNetwork()
    }

    override fun onDestroy() {
        unregisterLog?.invoke()
        unregisterLog = null
        super.onDestroy()
    }

    // ─────────────────────── 装配 ───────────────────────

    private fun bindViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvIp = findViewById(R.id.tv_ip)
        tvPlaybackMode = findViewById(R.id.tv_playback_mode)
        rowPlaybackMode = findViewById(R.id.row_playback_mode)
        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        btnToggleLog = findViewById(R.id.btn_toggle_log)
        llLog = findViewById(R.id.ll_log)
        tvLog = findViewById(R.id.tv_log)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_rename).setOnClickListener { showRenameDialog() }
        findViewById<View>(R.id.btn_clear_log).setOnClickListener {
            CastLogger.clear()
            renderedLog.setLength(0)
            renderLog()
        }

        btnToggleLog.setOnClickListener { toggleLog() }

        // ScrollView 在 initScrollView() 里**硬编码 setFocusable(true)**，
        // XML 上写的 android:focusable="false" 会被它覆盖 —— 必须在这里再关一次。
        // 否则遥控器焦点会停在这个没有任何视觉反馈的滚动容器上，
        // 到页面最底部就再也下不去（真机实测：第 3 次按下键起焦点卡死）。
        findViewById<android.widget.ScrollView>(R.id.settings_scroll).isFocusable = false
    }

    private fun setupModuleList() {
        llModules = findViewById(R.id.ll_modules)
        renderModules()
    }

    /**
     * 检查更新。
     *
     * 检查期间把右侧文案换成「检查中…」并暂时不可点：不这么做用户会连点几下，
     * 而每一下都是对 GitHub / Gitee 的一次请求 —— 两站的匿名额度都很紧。
     */
    private fun setupUpdateCheck() {
        rowCheckUpdate = findViewById(R.id.row_check_update)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        updateFlow = UpdateFlow(this)

        val trigger = View.OnClickListener { updateFlow.checkManually(::renderUpdateBusy) }
        rowCheckUpdate.setOnClickListener(trigger)
        btnCheckUpdate.setOnClickListener(trigger)
    }

    private fun renderUpdateBusy(busy: Boolean) {
        btnCheckUpdate.setText(if (busy) R.string.action_checking else R.string.action_check_update)
        // 只关 clickable、不动 focusable：焦点仍停在原处（电视上焦点跳走会很突兀），
        // 但按键不会再生效
        rowCheckUpdate.isClickable = !busy
        btnCheckUpdate.isClickable = !busy
        rowCheckUpdate.alpha = if (busy) BUSY_ALPHA else 1f
    }

    /**
     * 渲染接收服务列表。
     *
     * 每次全量重建（模块只有两个，代价可忽略）：模块的地址/端口会随启停变化，
     * 局部更新的收益不足以抵消"忘记刷新某个字段"的风险。
     */
    private fun renderModules() {
        llModules.removeAllViews()
        buildRows().forEach { row ->
            val view = layoutInflater.inflate(R.layout.item_module, llModules, false)
            ModuleRowView.bind(view, row) { module, enabled -> toggleModule(module, enabled) }
            llModules.addView(view)
        }
    }

    /**
     * 播放模式选择。
     *
     * 只提供"单曲播放 / 单曲循环"两种 —— 这两种接收端能可靠实现。
     * 顺序播放、列表循环**故意不做**：接收端拿不到播放列表
     * （手机投一集只发一个 URL，列表在手机 App 里），做了也不会生效。
     * 连播请在手机投屏端选。
     *
     * 两个模块读同一份偏好，DLNA 与 AirPlay 一起生效。
     * 整行可点：电视遥控器只有一个焦点，让整行承载焦点比只让右侧「切换」承载更好操作。
     */
    private fun setupPlaybackMode() {
        renderPlaybackMode()
        rowPlaybackMode.setOnClickListener { showPlaybackModeDialog() }
        findViewById<View>(R.id.btn_playback_mode).setOnClickListener {
            showPlaybackModeDialog()
        }
    }

    private fun renderPlaybackMode() {
        tvPlaybackMode.setText(playbackModeLabel(store.playbackMode()))
    }

    private fun playbackModeLabel(mode: PlaybackMode): Int = when (mode) {
        PlaybackMode.SINGLE -> R.string.playback_mode_single
        PlaybackMode.REPEAT_ONE -> R.string.playback_mode_repeat_one
    }

    private fun showPlaybackModeDialog() {
        val modes = PlaybackMode.entries
        val labels = modes.map { getString(playbackModeLabel(it)) }.toTypedArray()
        val current = modes.indexOf(store.playbackMode())

        // 播完正在放的内容时立刻切模式会让状态难预测（队列指针不变、模式变了），
        // 所以这里只在"没在投屏"时改，避免用户看着画面卡在半路。
        val casting = app.coordinator.sessions.value.any { it.state.isActive }
        if (casting) {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_playback_section)
                .setMessage(R.string.playback_mode_busy)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_playback_section)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                store.setPlaybackMode(modes[which])
                renderPlaybackMode()
                CastLogger.i(TAG, "播放模式切换为：${labels[which]}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupLog() {
        // 先把历史日志填进来：打开面板时只能看到"从现在起"的日志，
        // 排查问题时最关键的那几条（启动失败、投屏被拒）恰恰都在过去
        renderedLog.setLength(0)
        CastLogger.snapshot().takeLast(MAX_LOG_LINES).forEach {
            renderedLog.append(it.format()).append('\n')
        }
        unregisterLog = CastLogger.addListener { entry ->
            runOnUiThread {
                renderedLog.append(entry.format()).append('\n')
                // 只保留最近若干行，避免长时间运行后字符串无限增长
                val lines = renderedLog.toString().lines()
                if (lines.size > MAX_LOG_LINES) {
                    renderedLog.setLength(0)
                    renderedLog.append(lines.takeLast(MAX_LOG_LINES).joinToString("\n"))
                }
                if (logExpanded) renderLog()
            }
        }
    }

    private fun observeModules() {
        app.modules.forEach { module ->
            lifecycleScope.launch {
                module.state.collect { state ->
                    moduleStates[module.id] = state
                    renderModules()
                    renderNetwork()
                }
            }
        }

        /*
         * 投屏一开始就退回主界面。
         *
         * 否则用户正好停在设置页时会很困惑：画面被设置页挡着，只能听到声音。
         * 投屏到来是最高优先级的交互意图，应当立刻让出屏幕。
         */
        lifecycleScope.launch {
            app.coordinator.sessions.collect { sessions ->
                if (sessions.any { it.state.isActive }) finish()
            }
        }
    }

    // ─────────────────────── 渲染 ───────────────────────

    private fun renderDeviceName() {
        tvDeviceName.text = app.savedDeviceName
    }

    private fun renderNetwork() {
        // 各模块自己的监听端口在模块行内展示（见 ModuleAdapter.endpointText）——
        // 多协议时端口各不相同，这里只报本机 IP。
        tvIp.text = app.modules.firstNotNullOfOrNull { it.localAddress.ifBlank { null } } ?: EMPTY_VALUE
    }

    private fun renderLog() {
        val text = renderedLog.toString().trim()
        tvLog.text = if (text.isEmpty()) getString(R.string.log_empty) else text
    }

    private fun toggleLog() {
        logExpanded = !logExpanded
        llLog.visibility = if (logExpanded) View.VISIBLE else View.GONE
        btnToggleLog.setText(
            if (logExpanded) R.string.settings_log_hide else R.string.settings_log_show,
        )
        if (logExpanded) renderLog()
    }

    private fun buildRows(): List<ModuleRow> = app.modules.map { module ->
        ModuleRow(
            module = module,
            state = moduleStates[module.id] ?: module.state.value,
            availability = app.registry.availabilityOf(module),
            enabled = app.registry.isEnabled(module),
        )
    }

    // ─────────────────────── 交互 ───────────────────────

    private fun toggleModule(module: com.casthub.core.ProtocolModule, enabled: Boolean) {
        lifecycleScope.launch {
            runCatching { app.registry.setEnabled(module, enabled) }
                .onFailure { CastLogger.e(TAG, "${module.displayName} 切换失败", it) }
            renderModules()
        }
    }

    /**
     * 修改设备名。名字对所有协议模块统一生效 ——
     * 若每个协议各存一个名字，用户会在手机投屏列表里看到本设备出现多次、名字还不同。
     *
     * ⚠️ 每种"改不成"的情况都必须说清原因。此前一律静默关闭对话框，
     * 用户（尤其是输入了与原名相同的名字时）会以为已经改好了 ——
     * "什么都没变化"看上去最像成功。
     */
    private fun showRenameDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(app.savedDeviceName)
            setSelection(text.length)
            hint = getString(R.string.rename_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    notifyRenameFailed(getString(R.string.rename_empty))
                    return@setPositiveButton
                }
                if (name == app.savedDeviceName) {
                    notifyRenameFailed(getString(R.string.rename_unchanged))
                    return@setPositiveButton
                }
                // 各协议模块内部会把名字截到 48 字符，若这里不截，
                // 会出现"设置页显示的名字"与"手机里看到的名字"不一致。
                val effective = name.take(MAX_DEVICE_NAME)
                if (!app.renameDevice(effective)) return@setPositiveButton

                renderDeviceName()
                if (effective != name) {
                    notifyRenameFailed(
                        getString(R.string.rename_too_long, MAX_DEVICE_NAME, effective),
                    )
                }
                // 已在运行的模块需要重启才会以新名字重新广播
                lifecycleScope.launch {
                    app.modules.forEach { module ->
                        runCatching { app.registry.restart(module) }
                            .onFailure { CastLogger.w(TAG, "${module.displayName} 重启失败", it) }
                    }
                    renderModules()
                    renderNetwork()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 改名没成功时的明确反馈。用对话框而不是 Toast —— 电视上 Toast 在角落，容易被忽略。 */
    private fun notifyRenameFailed(message: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val TAG = "SettingsActivity"
        private const val MAX_LOG_LINES = 400
        private const val EMPTY_VALUE = "—"

        /** 与各协议模块内部的截断长度保持一致（见 ModuleEnabledStore.setDeviceName）。 */
        private const val MAX_DEVICE_NAME = 48

        /** 检查更新期间整行的透明度，用来表达"暂时不可用"。 */
        private const val BUSY_ALPHA = 0.6f
    }
}

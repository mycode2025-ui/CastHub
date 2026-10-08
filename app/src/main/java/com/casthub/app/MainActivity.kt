package com.casthub.app

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.casthub.app.update.UpdateFlow
import com.casthub.core.Availability
import com.casthub.core.CastLogger
import com.casthub.core.CastSession
import com.casthub.core.LocalPlaybackControl
import com.casthub.core.ProtocolModule
import com.casthub.core.VideoOutput
import kotlinx.coroutines.launch

/**
 * 主界面 —— 待机欢迎屏 + 投屏全屏播放，两种状态由投屏会话自动切换。
 *
 * 界面只通过 core 层抽象与协议模块交互，不感知 DLNA 或 AirPlay 的任何细节。
 *
 * 设计取舍（面向普通用户，而非开发者）：
 * - 主页只回答三个问题：**这是哪台设备**、**现在能不能被搜到**、**怎么投屏**；
 * - 协议开关与运行日志属于配置/诊断内容，收进设置页（见 [SettingsActivity]），
 *   避免用户在主页误关服务后设备搜不到；
 * - 投屏中按返回键唤出投屏信息浮层，而不是退出全屏；
 * - 投屏态下方向键被接管为快进 / 快退 —— 用户手里是遥控器，
 *   想看下一段却要专门去掏手机并不合理。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var app: CastHubApplication

    // 待机屏
    private lateinit var homeRoot: View
    private lateinit var tvDeviceName: TextView
    private lateinit var tvStatusDot: TextView
    private lateinit var tvStatus: TextView
    private lateinit var llSupport: LinearLayout
    private lateinit var tvIp: TextView

    // 视频层
    private lateinit var videoLayer: View
    private lateinit var osdPanel: View
    private lateinit var tvOsdTitle: TextView
    private lateinit var tvOsdSource: TextView
    private lateinit var tvOsdHint: TextView
    private lateinit var pbOsdProgress: ProgressBar
    private lateinit var tvOsdPosition: TextView
    private lateinit var tvOsdDuration: TextView
    private lateinit var surfaceView: SurfaceView

    /** 当前是否处于投屏播放态。 */
    private var isCasting = false

    private val osdHandler = Handler(Looper.getMainLooper())
    private val osdHideRunnable = Runnable { hideOsd() }

    /**
     * 投屏中拦截返回键：唤出投屏信息，连按两次则结束投屏。
     *
     * ⚠️ 只弹信息、不给出口是错的（真机实测就是这么设计的，被用户当场指出）：
     * 发送端不断开时（手机 App 被杀、锁屏，或用户就是想在电视这边停），
     * 用户会被锁在全屏播放页里出不去。所以第二次返回键必须真的结束投屏，
     * 而不是继续弹浮层。
     */
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            // 结束投屏后的宽限期内吞掉返回键。
            //
            // 真机实测：遥控器连按两次结束投屏后，偶尔会**再收到一次**返回键
            // （按键长按的连发 / TV 固件把一次按下报成两次）。此时回调已随
            // "退出投屏态"被关闭，那次返回键就落到默认处理 —— Activity 直接
            // finish，用户被甩到系统桌面。表现为"我只是想停止投屏，应用怎么关了"。
            // 这里用一个短宽限期把紧随其后的返回键吃掉。
            if (android.os.SystemClock.elapsedRealtime() < exitGraceUntilMs) return
            handleExitKey()
        }
    }

    /** 上一次按"退出"键的时刻，用于连按两次判定。 */
    private var lastExitPressMs = 0L

    /** 结束投屏后的返回键宽限期截止时刻（见 [backCallback]）。 */
    private var exitGraceUntilMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        app = application as CastHubApplication

        bindViews()
        setupSurface()
        observeState()
        requestNotificationPermissionIfNeeded()
        onBackPressedDispatcher.addCallback(this, backCallback)

        // 启动前台服务，保证退到后台后仍可被发现
        CastForegroundService.start(this)

        // 冷启动静默检查有没有新版本。
        // 受节流约束（默认 6 小时内不重复查），且只在确实有新版本、当前也没在投屏时才会打扰用户 ——
        // 检查失败一律静默，避免离线设备每次开机都弹一个"检查更新失败"。
        UpdateFlow(this).checkOnLaunch()
    }

    override fun onResume() {
        super.onResume()
        // 设置页可能改过设备名，回到前台时刷新一次
        renderHome()
    }

    override fun onDestroy() {
        osdHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ─────────────────────── 视图装配 ───────────────────────

    private fun bindViews() {
        homeRoot = findViewById(R.id.home_root)
        videoLayer = findViewById(R.id.video_layer)
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvStatusDot = findViewById(R.id.tv_status_dot)
        tvStatus = findViewById(R.id.tv_status)
        llSupport = findViewById(R.id.ll_support)
        tvIp = findViewById(R.id.tv_ip)
        // 版本号放底部弱色小字：报问题时一眼能读到，平时不干扰
        findViewById<TextView>(R.id.tv_version).text =
            getString(R.string.home_version, BuildConfig.VERSION_NAME)

        osdPanel = findViewById(R.id.osd_panel)
        tvOsdTitle = findViewById(R.id.tv_osd_title)
        tvOsdSource = findViewById(R.id.tv_osd_source)
        tvOsdHint = findViewById(R.id.tv_osd_hint)
        pbOsdProgress = findViewById(R.id.pb_osd_progress)
        tvOsdPosition = findViewById(R.id.tv_osd_position)
        tvOsdDuration = findViewById(R.id.tv_osd_duration)
        surfaceView = findViewById(R.id.surface_view)

        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // 触摸设备上点画面唤出投屏信息；电视端用遥控器返回键。
        //
        // ⚠️ 这里必须显式关掉 clickable/focusable：video_layer 铺满全屏且没有任何焦点视觉，
        // 一旦它成为页面里唯一的可交互元素，就会被焦点搜索选中、把遥控器焦点吸走 ——
        // 用户按确定键等于点在一片看不见的全屏区域上，表现就是"设置按钮怎么按都没反应"
        // （真机实测踩到）。
        // 注意 clickable 与 focusable 是**两个**标志：触摸唤出 OSD 只需要 clickable，
        // 见 applyCastingMode 里为什么投屏态也不再打开 focusable。
        videoLayer.setOnClickListener {
            if (isCasting) showOsd()
        }
        videoLayer.isClickable = false
        videoLayer.isFocusable = false

        // ScrollView 在 initScrollView() 里硬编码 setFocusable(true)，
        // 会覆盖 XML 上的 android:focusable="false"，这里再关一次
        findViewById<android.widget.ScrollView>(R.id.home_scroll).isFocusable = false
    }

    private fun setupSurface() {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = attachSurface(h.surface)

            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) =
                attachSurface(h.surface)

            override fun surfaceDestroyed(h: SurfaceHolder) = attachSurface(null)
        })
    }

    /** 把 Surface 交给所有支持视频输出的模块。 */
    private fun attachSurface(surface: Surface?) {
        app.modules.forEach { module ->
            runCatching { (module as? VideoOutput)?.attachSurface(surface) }
                .onFailure { CastLogger.w(TAG, "${module.displayName} 绑定 Surface 失败", it) }
        }
    }

    private fun observeState() {
        app.modules.forEach { module ->
            lifecycleScope.launch {
                module.state.collect { renderHome() }
            }
        }
        lifecycleScope.launch {
            app.coordinator.sessions.collect { renderSessions(it) }
        }
    }

    // ─────────────────────── 待机屏渲染 ───────────────────────

    private fun renderHome() {
        tvDeviceName.text = app.savedDeviceName

        val usable = app.modules.filter { app.registry.availabilityOf(it) is Availability.Available }
        val running = usable.filter { it.state.value.isRunning }
        val down = usable - running.toSet()

        // 一句话说清"现在到底能不能被搜到"，这是用户唯一需要理解的状态。
        //
        // 部分未开启时**点名**是哪一个：只写"部分服务未开启"用户不知道该去开哪一项，
        // 而这个状态卡片是主页上唯一说这件事的地方 ——
        // 不另外加一行提示，是因为电视上这一页已经没有多一行的高度余量
        // （1080p 电视可滚区仅 ~356dp，加了会被裁掉）。
        val statusText: String
        val statusColor: Int
        when {
            running.isEmpty() -> {
                statusText = getString(R.string.home_status_off)
                statusColor = R.color.state_error
            }

            down.isNotEmpty() -> {
                statusText = getString(
                    R.string.home_status_partial,
                    down.joinToString("」「") { it.displayName },
                )
                statusColor = R.color.state_warn
            }

            else -> {
                statusText = getString(R.string.home_status_ready)
                statusColor = R.color.state_ok
            }
        }
        tvStatus.text = statusText
        tvStatusDot.setTextColor(ContextCompat.getColor(this, statusColor))

        renderSupport(usable)

        tvIp.text = app.modules.firstNotNullOfOrNull { it.localAddress.ifBlank { null } } ?: EMPTY_VALUE
    }

    /**
     * 支持的投屏方式。按模块真实状态生成，不写死文案 ——
     * 否则出现"界面写着支持、实际用不了"的信息不一致。
     */
    private fun renderSupport(usable: List<ProtocolModule>) {
        llSupport.removeAllViews()
        if (usable.isEmpty()) {
            llSupport.addView(makeChip(getString(R.string.home_support_none), active = false))
            return
        }
        usable.forEach { module ->
            llSupport.addView(makeChip(module.displayName, active = module.state.value.isRunning))
        }
    }

    private fun makeChip(label: String, active: Boolean): TextView = TextView(this).apply {
        text = label
        // 芯片字号走资源：电视（w600dp）与手机是两套值，才能各自挤进自己的一屏
        // 注意用 COMPLEX_UNIT_PX —— getDimension 返回的是像素，
        // 直接赋给 textSize(sp) 会把像素值当 sp 用，字会大得离谱。
        setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_PX,
            resources.getDimension(R.dimen.hero_chip_text),
        )
        setTextColor(
            ContextCompat.getColor(
                this@MainActivity,
                if (active) R.color.text_primary else R.color.text_secondary,
            ),
        )
        setBackgroundResource(if (active) R.drawable.bg_chip_ok else R.drawable.bg_chip_neutral)
        setPadding(dp(18), dp(9), dp(18), dp(9))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(12) }
    }

    // ─────────────────────── 投屏态渲染 ───────────────────────

    private fun renderSessions(sessions: List<CastSession>) {
        val active = sessions.filter { it.state.isActive }
        val nowCasting = active.isNotEmpty()

        // 只在"进入/退出投屏"这个边界上做切换动作。
        // 会话流每秒都会推送进度，若无条件执行会导致 OSD 反复弹出。
        if (nowCasting != isCasting) {
            isCasting = nowCasting
            applyCastingMode(nowCasting)
            if (nowCasting) showOsd()
        }

        if (nowCasting) {
            val session = active.first()
            val media = session.media
            tvOsdTitle.text = media?.title?.takeIf { it.isNotBlank() }
                ?: media?.uri?.take(80)
                ?: getString(R.string.osd_casting)
            tvOsdSource.text = getString(
                R.string.osd_from,
                session.peerName.ifBlank { session.peerAddress },
            )
            // 会话状态每秒推送一次，顺便让进度条跟着走。
            // 只在浮层可见时刷新，看不见的时候不做无用功。
            if (osdPanel.visibility == View.VISIBLE) updateProgress()
        }
    }

    /** 切换待机屏 / 视频层。只动可见性，不改布局参数。 */
    private fun applyCastingMode(casting: Boolean) {
        homeRoot.visibility = if (casting) View.GONE else View.VISIBLE
        // 触摸设备上点画面唤出投屏信息。
        //
        // ⚠️ 只给 clickable，**绝不给 focusable**（两者不是一回事）。
        // 投屏态下 home_root 已 GONE、页面里没有别的可聚焦元素，若这里把
        // video_layer 设成可聚焦，它就会成为**唯一**的焦点落点：方向键先被焦点
        // 机制用掉、到不了 Activity.onKeyDown，于是遥控器 ←/→ 快进时灵时不灵。
        // 实测对照（同一段视频、同一次投屏）：
        //   · 无视图持有焦点 → 方向键 → 0s 跳到 12s，定位日志 1 条；
        //   · video_layer 持有焦点 → 连按 3 次方向键，定位日志 0 条。
        // 这正是本项目在待机态早已记过的"铺满全屏的可点击元素 = 焦点陷阱"，
        // 只是换到了投屏态、换了表现形式（那次是吞掉"确定"键，这次是吞掉"快进"键）。
        // 点击（触摸）本来就不需要可聚焦，去掉它没有任何功能损失。
        videoLayer.isClickable = casting
        videoLayer.isFocusable = false
        videoLayer.keepScreenOn = casting
        applyImmersive(casting)
        if (casting) {
            backCallback.isEnabled = true
            // 上一次投屏残留的"再按一次"计时不能带进新会话，否则进来第一下返回键就退出了
            lastExitPressMs = 0L
            exitGraceUntilMs = 0L
        } else {
            // 结束投屏后**延迟**关闭回调，给紧随其后的返回键留出宽限期（见 backCallback）。
            // 立即关闭会让那一次按键落到默认处理，把应用一起关掉。
            osdHandler.postDelayed({
                if (!isCasting) backCallback.isEnabled = false
            }, BACK_GRACE_MS)
        }
        if (!casting) hideOsd(immediate = true)
    }

    /**
     * 显示投屏信息浮层。
     *
     * @param message 为 null 时显示默认的遥控器操作提示；
     *                传入文案则作为一次性反馈（如快进结果），停留时间更短。
     */
    private fun showOsd(
        message: String? = null,
        durationMs: Long = if (message != null) OSD_FEEDBACK_MS else OSD_VISIBLE_MS,
    ) {
        osdHandler.removeCallbacks(osdHideRunnable)
        osdPanel.animate().cancel()
        osdPanel.alpha = 1f
        osdPanel.visibility = View.VISIBLE
        tvOsdHint.text = message ?: getString(R.string.osd_hint_keys)
        updateProgress()
        osdHandler.postDelayed(osdHideRunnable, durationMs)
    }

    /**
     * 刷新进度条与两端时间。
     *
     * 拿不到总时长时（直播流、元数据缺失）整块隐藏 ——
     * 显示一个永远不动的空条，比不显示更让人困惑。
     */
    private fun updateProgress() {
        // 只取**真正在播**的那个模块：多个协议模块都实现了本地控制，
        // 按列表顺序取第一个会拿到空闲的 DLNA（时长为 0），
        // 结果投屏中进度条反而不显示。
        val progress = app.modules.firstNotNullOfOrNull { module ->
            (module as? LocalPlaybackControl)?.currentProgress()?.takeIf { it.durationMs > 0L }
        }

        val duration = progress?.durationMs ?: 0L
        if (progress == null || duration <= 0L) {
            pbOsdProgress.visibility = View.GONE
            tvOsdPosition.visibility = View.GONE
            tvOsdDuration.visibility = View.GONE
            return
        }

        val position = progress.positionMs.coerceIn(0L, duration)
        pbOsdProgress.visibility = View.VISIBLE
        tvOsdPosition.visibility = View.VISIBLE
        tvOsdDuration.visibility = View.VISIBLE

        pbOsdProgress.progress = (position * PROGRESS_MAX / duration).toInt()
        tvOsdPosition.text = formatTime(position)
        tvOsdDuration.text = formatTime(duration)
    }

    // ─────────────────── 退出投屏（遥控器的唯一出口） ───────────────────

    /**
     * 投屏态下按「退出」键（返回键 / 遥控器停止键）。
     *
     * 第一次按下只是提示，第二次才真的结束 —— 直接一按就停容易被误触，
     * 而投屏停掉要重新在手机上点一遍。提示语必须写清"再按一次"，
     * 否则用户不知道还有下一层。
     */
    private fun handleExitKey() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExitPressMs < EXIT_CONFIRM_MS) {
            lastExitPressMs = 0L
            stopCasting()
            return
        }
        lastExitPressMs = now
        showOsd(getString(R.string.osd_press_again_to_exit), EXIT_CONFIRM_MS)
    }

    /**
     * 结束投屏并退回待机屏。
     *
     * 只对**真正在播**的那个模块下发停止 —— 多协议并存时按顺序取第一个会打到
     * 空闲模块上，结果按了退出却没反应。
     * 界面不需要在这里手动切：模块清掉会话后会话流会推送空列表，
     * [renderSessions] 自然会退回待机屏。
     */
    private fun stopCasting() {
        // 多协议并存时不能用 canSeek / currentProgress 去挑：DLNA 模块即使空闲
        // 也会返回非空的进度（播放器常驻），挑出来的是它，结果 AirPlay 正在播
        // 却按了退出没反应。两个实现的 stopCasting 都在没有会话时返回 false
        // 且无副作用，所以直接"谁真的停了就算谁"。
        val stopped = app.modules
            .mapNotNull { it as? LocalPlaybackControl }
            .firstOrNull { it.stopCasting() } != null
        if (!stopped) {
            // 没有任何模块能停 —— 明说，别让人对着全屏画面连按
            showOsd(getString(R.string.osd_cannot_exit), EXIT_CONFIRM_MS)
            return
        }
        // 宽限期从这一刻开始计时：遥控器连发的那几下返回键要在这里被吃掉
        exitGraceUntilMs = android.os.SystemClock.elapsedRealtime() + BACK_GRACE_MS
        // 兜底：万一模块没能自己清会话，界面也不能继续卡在播放页
        osdHandler.postDelayed({
            if (isCasting && app.coordinator.sessions.value.none { it.state.isActive }) {
                isCasting = false
                applyCastingMode(false)
            }
        }, FALLBACK_HOME_DELAY_MS)
    }

    // ─────────────────────── 遥控器播放控制 ───────────────────────

    /**
     * 投屏态下接管方向键做快进 / 快退。
     *
     * DLNA 的播放控制权本在发送端，但接收端跑在电视上、用户手里是遥控器 ——
     * 为了快进而专门去掏手机并不合理。本地定位后，发送端的轮询会把新进度读回去，
     * 所以两端不会脱节（实测夸克等每秒轮询一次 `GetPositionInfo`）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isCasting) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                handleExitKey()
                return true
            }
            if (handleSeekKey(keyCode)) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleSeekKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(SEEK_STEP_MS)

        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-SEEK_STEP_MS)

        else -> false
    }

    /** @return 是否已消费本次按键（投屏态下方向键一律不继续往下传） */
    private fun seekBy(deltaMs: Long): Boolean {
        // 同理：快进要作用在正在播的那个模块上，而不是列表里的第一个
        val controls = app.modules.mapNotNull { it as? LocalPlaybackControl }
        val control = controls.firstOrNull { it.canSeek }
            ?: controls.firstOrNull()
            ?: return false

        if (!control.seekBy(deltaMs)) {
            showOsd(getString(R.string.osd_cannot_seek))
            return true
        }

        showOsd(
            getString(
                R.string.osd_seek_feedback,
                getString(if (deltaMs > 0) R.string.osd_seek_forward else R.string.osd_seek_backward),
                kotlin.math.abs(deltaMs) / 1000,
            ),
        )
        // 立刻把进度条推到新位置，而不是等下一次会话状态推送 ——
        // 否则按键之后进度条要过一拍才动，手感很差
        updateProgress()
        return true
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val total = ms / 1000
        val hour = total / 3600
        val minute = (total % 3600) / 60
        val second = total % 60
        return if (hour > 0) {
            "%d:%02d:%02d".format(hour, minute, second)
        } else {
            "%02d:%02d".format(minute, second)
        }
    }

    private fun hideOsd(immediate: Boolean = false) {
        osdHandler.removeCallbacks(osdHideRunnable)
        if (immediate || osdPanel.visibility != View.VISIBLE) {
            osdPanel.animate().cancel()
            osdPanel.visibility = View.GONE
            return
        }
        osdPanel.animate()
            .alpha(0f)
            .setDuration(OSD_FADE_MS)
            .withEndAction { osdPanel.visibility = View.GONE }
            .start()
    }

    /** 沉浸式模式：投屏播放时隐藏状态栏与导航栏。 */
    private fun applyImmersive(on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (on) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (on) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // ─────────────────────── 杂项 ───────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFY)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val RC_NOTIFY = 1001

        /** 投屏信息浮层自动隐藏前的停留时长。 */
        private const val OSD_VISIBLE_MS = 4_500L

        /** 一次性反馈（如快进结果）的停留时长，短一些避免长时间遮挡画面。 */
        private const val OSD_FEEDBACK_MS = 1_400L

        /** 连按两次「退出」键结束投屏的判定窗口：与提示语的停留时长一致。 */
        private const val EXIT_CONFIRM_MS = 3_000L

        /**
         * 结束投屏后的返回键宽限期。
         *
         * 遥控器一次按下可能被固件报成两次（长按连发），结束投屏后那多出来的一下
         * 若落到默认处理，会把整个应用关掉。1 秒足够覆盖连发，又不会让用户觉得"返回键失灵"。
         */
        private const val BACK_GRACE_MS = 1_000L

        /** 停掉投屏后仍未自动退回待机屏时，强制退回的兜底延时。 */
        private const val FALLBACK_HOME_DELAY_MS = 800L

        private const val OSD_FADE_MS = 220L
        private const val EMPTY_VALUE = "—"

        /** 遥控器每次快进 / 快退的步长。 */
        private const val SEEK_STEP_MS = 10_000L

        /** 进度条的满量程。用千分比整数即可，避免浮点误差。 */
        private const val PROGRESS_MAX = 1000
    }
}

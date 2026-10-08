package com.casthub.app

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.casthub.core.Availability
import com.casthub.core.ModuleState
import com.casthub.core.ProtocolModule

/** 模块列表的一行数据。 */
data class ModuleRow(
    val module: ProtocolModule,
    val state: ModuleState,
    val availability: Availability,
    val enabled: Boolean,
)

/**
 * 接收服务列表的一行视图绑定。
 *
 * 面向普通用户：文案全部来自协议模块自己提供的 [ProtocolModule.description] 与
 * [Availability] 的接入提示，这里不硬编码任何协议知识。
 *
 * ── 为什么不用 RecyclerView ────────────────────────────────────────
 * 这里固定就是**两行**（DLNA / AirPlay），且整页在 `ScrollView` 里滚动，
 * 列表自身不需要滚动或复用 —— RecyclerView 在这个场景里一点好处都没有，
 * 却带来一个真实的缺陷：`wrap_content` 高度的 RecyclerView 嵌在
 * `ScrollView` 里时，测量只会按照"一个条目"的高度收敛
 * （实测：两个模块只渲染出一个，`rv_modules` 的 bounds 恰好等于第一行 + 外边距）。
 * 换成一个普通的垂直 `LinearLayout` 后这个类问题根本不存在。
 */
object ModuleRowView {

    fun bind(view: View, row: ModuleRow, onToggle: (ProtocolModule, Boolean) -> Unit) {
        val ctx = view.context
        val usable = row.availability is Availability.Available

        view.findViewById<TextView>(R.id.tv_module_name).text = row.module.displayName

        val style = row.state.describe(ctx)
        view.findViewById<TextView>(R.id.tv_module_state).apply {
            text = style.text
            setTextColor(ContextCompat.getColor(ctx, style.color))
            setBackgroundResource(style.bg)
        }

        // 说明：优先展示协议自述，其次是不可用原因
        view.findViewById<TextView>(R.id.tv_module_detail).text = row.module.description.ifBlank {
            ctx.getString(R.string.state_placeholder_device, row.module.localDeviceName)
        }

        // 该模块自己的监听端点。多协议时端口各不相同，逐行显示才不会误导排查的人。
        view.findViewById<TextView>(R.id.tv_module_endpoint).text = row.endpointText(ctx)

        val problem = row.problemText(ctx)
        view.findViewById<TextView>(R.id.tv_module_hint).apply {
            visibility = if (problem == null) View.GONE else View.VISIBLE
            text = problem
        }

        view.findViewById<SwitchCompat>(R.id.switch_module).apply {
            isChecked = row.enabled
            isEnabled = usable
            alpha = if (usable) 1f else 0.4f
        }

        // 整行承担焦点与点击：电视遥控器操作小开关很别扭
        view.isFocusable = usable
        view.isClickable = usable
        view.alpha = if (usable) 1f else 0.55f
        view.setOnClickListener {
            if (usable) onToggle(row.module, !row.enabled)
        }
    }

    private data class StateStyle(
        val text: String,
        @ColorRes val color: Int,
        @DrawableRes val bg: Int,
    )

    /** 状态标签：文案 + 文字色 + 底色，三者一致，避免"绿底红字"这类错配。 */
    private fun ModuleState.describe(ctx: Context): StateStyle = when (this) {
        is ModuleState.Running -> StateStyle(
            ctx.getString(R.string.state_running), R.color.state_ok, R.drawable.bg_chip_ok,
        )

        is ModuleState.Stopped -> StateStyle(
            ctx.getString(R.string.state_stopped), R.color.text_secondary, R.drawable.bg_chip_neutral,
        )

        is ModuleState.Starting -> StateStyle(
            ctx.getString(R.string.state_starting), R.color.state_warn, R.drawable.bg_chip_warn,
        )

        is ModuleState.Failed -> StateStyle(
            ctx.getString(R.string.state_failed), R.color.state_error, R.drawable.bg_chip_warn,
        )

        is ModuleState.Unavailable -> StateStyle(
            ctx.getString(R.string.state_unavailable), R.color.state_warn, R.drawable.bg_chip_warn,
        )
    }

    /** 需要向用户解释的异常信息；一切正常时返回 null。 */
    private fun ModuleRow.problemText(ctx: Context): String? = when (val a = availability) {
        is Availability.Available -> (state as? ModuleState.Failed)
            ?.let { "${ctx.getString(R.string.state_failed)}：${it.reason}" }

        is Availability.MissingDependency ->
            "${ctx.getString(R.string.state_unavailable)} · 缺少 ${a.dependency}。${a.hint}"

        is Availability.MissingPermission ->
            "${ctx.getString(R.string.state_unavailable)} · 缺少权限：${a.permissions.joinToString()}"

        is Availability.UnsupportedPlatform ->
            "${ctx.getString(R.string.state_unavailable)} · 当前设备不支持：${a.reason}"
    }

    /**
     * 该模块自己的监听端点。
     *
     * 只在**运行中**才显示真实地址与端口 —— 未运行时 `localPort` 为 0、
     * `localAddress` 为空，硬拼出来就是 "监听 :0" 这种假信息。
     */
    private fun ModuleRow.endpointText(ctx: Context): String {
        val address = module.localAddress
        val port = module.localPort
        if (state !is ModuleState.Running || port <= 0 || address.isBlank()) {
            return ctx.getString(R.string.module_endpoint_none)
        }
        return ctx.getString(R.string.module_endpoint, address, port)
    }
}

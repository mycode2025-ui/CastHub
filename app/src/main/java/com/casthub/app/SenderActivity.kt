package com.casthub.app

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.casthub.core.CastDevice
import com.casthub.core.MediaInfo
import com.casthub.dlna.DlnaModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/** DLNA sender: media remains hosted at the entered URL for the remote renderer. */
class SenderActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var mediaUrl: EditText
    private lateinit var deviceUrl: EditText
    private var selected: CastDevice? = null
    private var sessionId: String? = null
    private var busy = false
    private val buttons = mutableListOf<Button>()
    private val module get() = (application as CastHubApplication).registry.byId(DlnaModule.ID) as DlnaModule

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getString("session")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(layout); isFocusable = false })
        layout.addView(TextView(this).apply { text = "投到其他设备"; textSize = 28f })
        layout.addView(TextView(this).apply {
            text = "选择同一局域网的 DLNA 接收设备，输入该设备可以访问的视频地址。"
        })
        status = TextView(this).apply { text = "尚未选择设备"; textSize = 18f }
        layout.addView(status)
        mediaUrl = EditText(this).apply { hint = "视频地址（http / https）"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        deviceUrl = EditText(this).apply { hint = "可选：设备描述地址（device.xml）"; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        layout.addView(mediaUrl)
        layout.addView(deviceUrl)
        button(layout, "搜索设备") { discover() }
        button(layout, "按设备地址连接") {
            val url = deviceUrl.text.toString().trim()
            if (!validUrl(url)) { status.text = "请输入有效的设备描述地址"; return@button }
            execute {
                selected = module.rendererAt(url).getOrThrow()
                status.text = "已选择：${selected!!.name}"
            }
        }
        button(layout, "开始投屏") {
            val device = selected
            val url = mediaUrl.text.toString().trim()
            if (device == null || !validUrl(url)) { status.text = "请选择设备并输入有效的视频地址"; return@button }
            execute {
                val session = withContext(Dispatchers.IO) { module.play(device, MediaInfo(url)).getOrThrow() }
                sessionId = session.id
                status.text = "已投到：${device.name}"
            }
        }
        button(layout, "暂停") { remote { module.pause(it).getOrThrow() } }
        button(layout, "继续") { remote { module.resume(it).getOrThrow() } }
        button(layout, "停止") { remote { module.stopSession(it).getOrThrow(); sessionId = null } }
        button(layout, "返回") { finish() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("session", sessionId)
        super.onSaveInstanceState(outState)
    }

    private fun button(layout: LinearLayout, label: String, action: () -> Unit) {
        val view = Button(this).apply { text = label; setOnClickListener { action() } }
        buttons.add(view)
        layout.addView(view, LinearLayout.LayoutParams(-1, -2))
    }

    private fun validUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    private fun execute(action: suspend () -> Unit) {
        if (busy) return
        if (!module.state.value.isRunning) { status.text = "请先在设置里开启 DLNA 服务"; return }
        busy = true
        buttons.forEach { it.isEnabled = false }
        lifecycleScope.launch {
            try { action() } catch (e: Exception) { status.text = "操作失败：${e.message}" }
            finally { busy = false; buttons.forEach { it.isEnabled = true } }
        }
    }

    private fun discover() = execute {
        status.text = "搜索中…"
        val devices = module.discoverRenderers().filter { it.address != module.localAddress }
        if (devices.isEmpty()) { status.text = "未发现设备，可输入设备描述地址连接"; return@execute }
        AlertDialog.Builder(this).setTitle("选择接收设备")
            .setItems(devices.map { "${it.name}（${it.address}）" }.toTypedArray()) { _, index ->
                selected = devices[index]
                status.text = "已选择：${selected!!.name}"
            }.setNegativeButton("取消", null).show()
    }

    private fun remote(action: suspend (String) -> Unit) {
        val id = sessionId ?: run { status.text = "请先开始投屏"; return }
        execute {
            withContext(Dispatchers.IO) { action(id) }
            status.text = "操作已完成"
        }
    }
}

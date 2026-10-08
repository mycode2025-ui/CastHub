package com.casthub.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.casthub.airplay.AirPlayModule
import kotlinx.coroutines.launch

/** Optional independent GPL receiver. Its code is neither linked nor bundled. */
object ExternalAirPlayBridge {
    const val PACKAGE = "io.github.jqssun.airplay"
    const val DOWNLOAD = "https://f-droid.org/packages/io.github.jqssun.airplay/"

    private var showingReturn = false
    fun status(activity: AppCompatActivity): String = if (activity.getSharedPreferences("airplay_bridge", 0).getBoolean("external_active", false))
        "AirPlay 镜像 / 音频：已切换至独立接收器" else "AirPlay 镜像 / 音频（独立接收器）"
    fun onHostResumed(activity: AppCompatActivity) {
        val prefs = activity.getSharedPreferences("airplay_bridge", 0)
        if (!prefs.getBoolean("restore_pending", false) || showingReturn || activity.isFinishing) return
        showingReturn = true
        AlertDialog.Builder(activity).setTitle("恢复 CastHub AirPlay 视频？")
            .setMessage("上次已切换至独立镜像 / 音频接收器。请先在独立接收器中停止接收或退出，再恢复 CastHub 视频服务，以免重复广播和端口冲突。")
            .setPositiveButton("已关闭，恢复视频") { _, _ -> activity.lifecycleScope.launch {
                val app = activity.application as CastHubApplication
                val module = app.registry.byId(AirPlayModule.ID) ?: return@launch
                runCatching { app.registry.setEnabled(module, true) }.onSuccess {
                    if (module.state.value.isRunning) {
                        prefs.edit().clear().apply()
                        Toast.makeText(activity, "CastHub AirPlay 视频服务已恢复", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(activity, "服务未启动，请确认独立接收器已退出后重试", Toast.LENGTH_LONG).show()
                }.onFailure { Toast.makeText(activity, "恢复失败，请检查接收器是否已退出", Toast.LENGTH_LONG).show() }
            } }
            .setNegativeButton("暂不恢复", null)
            .setOnDismissListener { showingReturn = false }.show()
    }
    fun show(activity: AppCompatActivity) {
        val intent = activity.packageManager.getLaunchIntentForPackage(PACKAGE)
        val message = if (Build.VERSION.SDK_INT < 24) {
            "独立 AirPlay 镜像 / 音频接收器需要 Android 7.0 或更新系统。当前设备可继续使用 CastHub 视频投屏。"
        } else if (intent == null) {
            "镜像和音频由独立的开源 AirPlay Server 提供。请从 F-Droid 安装后，再通过此入口启动。CastHub 内置 AirPlay 仍为视频模式。"
        } else {
            "启动独立 AirPlay Server 接收镜像和音频。为避免重复广播，将关闭 CastHub 的 AirPlay 视频服务；返回 CastHub 后会提示恢复视频服务。DLNA 保持可用。"
        }
        val dialog = AlertDialog.Builder(activity).setTitle("AirPlay 镜像 / 音频（独立接收器）")
            .setMessage(message).setNegativeButton("关闭", null)
        if (Build.VERSION.SDK_INT >= 24) {
            dialog.setNeutralButton("安装页面") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD))) }
                    .onFailure { Toast.makeText(activity, "无法打开安装页面", Toast.LENGTH_LONG).show() }
            }
            if (intent != null) dialog.setPositiveButton("启动接收器") { _, _ ->
                activity.lifecycleScope.launch {
                    val app = activity.application as CastHubApplication
                    val module = app.registry.byId(AirPlayModule.ID) ?: return@launch
                    val wasEnabled = app.registry.isEnabled(module)
                    app.registry.setEnabled(module, false)
                    activity.getSharedPreferences("airplay_bridge", 0).edit().putBoolean("external_active", true).putBoolean("restore_pending", wasEnabled).apply()
                    runCatching { activity.startActivity(intent) }.onFailure {
                        activity.getSharedPreferences("airplay_bridge", 0).edit().clear().apply()
                        app.registry.setEnabled(module, wasEnabled)
                        Toast.makeText(activity, "接收器启动失败：${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }
}

package com.casthub.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.casthub.core.CastLogger
import com.casthub.core.DiagnosticRedactor
import com.casthub.core.LanAddress
import kotlinx.coroutines.launch
import java.io.File

object Diagnostics {
    fun report(context: Context): String {
        val app = context.applicationContext as CastHubApplication
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return DiagnosticRedactor.redact(buildString {
            appendLine("CastHub ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("局域网 IPv4: ${LanAddress.ipv4(context) ?: "未连接 Wi-Fi / 有线网络"}")
            appendLine("通知权限: ${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
            manager.allNetworks.forEach { network ->
                val properties = manager.getLinkProperties(network)
                appendLine("网卡: ${properties?.interfaceName}; 地址: ${properties?.linkAddresses}")
            }
            app.modules.forEach {
                appendLine("${it.displayName}: enabled=${app.registry.isEnabled(it)}, ${it.state.value}; ${it.localAddress}:${it.localPort}")
                it.sessions.value.forEach { session -> appendLine("会话: ${session.state}; ${session.media?.title.orEmpty()}") }
            }
            appendLine("\n最近日志（媒体地址与凭据已隐藏）:")
            CastLogger.snapshot().forEach {
                appendLine(it.format())
                it.throwable?.let { error -> appendLine("${error.javaClass.simpleName}: ${error.message}") }
            }
        })
    }

    fun show(activity: AppCompatActivity) {
        val report = report(activity)
        AlertDialog.Builder(activity).setTitle("网络诊断")
            .setMessage(report.substringBefore("最近日志"))
            .setPositiveButton("导出日志") { _, _ ->
                runCatching {
                    val folder = File(activity.cacheDir, "diagnostics").apply { mkdirs() }
                    val file = File(folder, "casthub-diagnostics.txt").apply { writeText(report) }
                    val uri = FileProvider.getUriForFile(activity, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    activity.startActivity(Intent.createChooser(intent, "导出诊断日志"))
                }.onFailure { Toast.makeText(activity, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
            }
            .setNeutralButton("复制诊断") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CastHub 诊断", report))
                Toast.makeText(activity, "已复制诊断", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("重启接收服务") { _, _ ->
                activity.lifecycleScope.launch {
                    val app = activity.application as CastHubApplication
                    app.modules.forEach { app.registry.restart(it) }
                }
            }.show()
    }
}

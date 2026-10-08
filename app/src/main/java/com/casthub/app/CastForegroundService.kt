package com.casthub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.casthub.core.CastLogger

/**
 * 让接收服务在后台存活的前台服务。
 *
 * 投屏接收必须长期驻留：用户把 App 切到后台后，DLNA/AirPlay 的监听不能被杀，
 * 否则手机端会发现设备"忽隐忽现"。前台服务是 Android 上实现这一点的标准手段。
 */
class CastForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        CastLogger.i(TAG, "接收服务已停止")
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_stat_cast)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // 部分定制系统会拒绝前台服务；此时退化为普通服务，不影响功能
            CastLogger.w(TAG, "启动前台服务失败，退化为普通服务", t)
        }
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "casthub_receiver"
        private const val NOTIFICATION_ID = 0x10A5

        fun start(context: Context) {
            val intent = Intent(context, CastForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { CastLogger.w(TAG, "启动接收服务失败", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CastForegroundService::class.java)) }
        }
    }
}

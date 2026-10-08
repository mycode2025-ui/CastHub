package com.casthub.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SystemPlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.casthub.core.CastLogger
import com.casthub.core.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/** Receiver listener stays foreground; the media session controls the active protocol. */
class CastForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private val app get() = application as CastHubApplication
    private var lastNoticeKey = ""
    private var idleUpdate: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        mediaSession = MediaSession(this, "CastHub").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = setPlaying(true)
                override fun onPause() = setPlaying(false)
                override fun onStop() { CastLogger.d(TAG, "系统媒体会话请求停止"); app.activeControl()?.stopCasting() }
                override fun onSeekTo(pos: Long) { app.activeControl()?.seekAbsolute(pos.coerceAtLeast(0)) }
                override fun onSkipToNext() { app.activeControl()?.queueStep(1) }
                override fun onSkipToPrevious() { app.activeControl()?.queueStep(-1) }
            })
            setSessionActivity(openIntent())
        }
        updateSession()
        scope.launch { app.coordinator.sessions.collect { updateSession() } }
    }
    private fun setPlaying(play: Boolean) {
        val control = app.activeControl() ?: return
        control.setPlaying(play)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> app.activeControl()?.togglePause()
            ACTION_STOP -> { CastLogger.d(TAG, "通知请求停止"); app.activeControl()?.stopCasting() }
            ACTION_NEXT -> app.activeControl()?.queueStep(1)
        }
        updateSession()
        return START_STICKY
    }
    override fun onDestroy() {
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        CastLogger.i(TAG, "接收服务已停止")
        super.onDestroy()
    }
    private fun updateSession() {
        // Takeover briefly clears the old protocol before publishing the new one.
        // Removing its MediaStyle notification in that gap makes SystemUI send
        // a delayed stop to this same token, which could stop the new playback.
        if (app.activeControl() == null && mediaSession.isActive) {
            if (idleUpdate?.isActive != true) idleUpdate = scope.launch {
                delay(750)
                if (app.activeControl() == null) publishSession()
            }
            return
        }
        idleUpdate?.cancel()
        idleUpdate = null
        publishSession()
    }
    private fun publishSession() {
        val current = app.coordinator.sessions.value.firstOrNull { it.media != null && (it.state.isActive || it.state == PlaybackState.ERROR) }
        val control = app.activeControl()
        val progress = control?.currentProgress()
        val state = current?.state
        val title = current?.media?.title?.takeIf { it.isNotBlank() } ?: if (current != null) "正在投屏" else getString(R.string.notification_title)
        mediaSession.isActive = current != null
        mediaSession.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, progress?.durationMs ?: 0).build())
        var actions = SystemPlaybackState.ACTION_PLAY or SystemPlaybackState.ACTION_PAUSE or SystemPlaybackState.ACTION_PLAY_PAUSE or SystemPlaybackState.ACTION_STOP
        if (control?.canSeek == true) actions = actions or SystemPlaybackState.ACTION_SEEK_TO
        val entries = control?.queueEntries().orEmpty()
        val index = entries.indexOfFirst { it.id == control?.queueCurrentId() }
        if (index > 0) actions = actions or SystemPlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (index >= 0 && index < entries.lastIndex) actions = actions or SystemPlaybackState.ACTION_SKIP_TO_NEXT
        val systemState = when (state) {
            PlaybackState.PLAYING -> SystemPlaybackState.STATE_PLAYING
            PlaybackState.PAUSED -> SystemPlaybackState.STATE_PAUSED
            PlaybackState.BUFFERING -> SystemPlaybackState.STATE_BUFFERING
            PlaybackState.ERROR -> SystemPlaybackState.STATE_ERROR
            else -> SystemPlaybackState.STATE_STOPPED
        }
        mediaSession.setPlaybackState(SystemPlaybackState.Builder().setActions(if (current == null) 0 else actions)
            .setState(systemState, progress?.positionMs ?: 0, if (state == PlaybackState.PLAYING) 1f else 0f).build())
        val noticeKey = "$title:$state:${index < entries.lastIndex}:${current?.protocolId}"
        if (lastNoticeKey == noticeKey) return
        lastNoticeKey = noticeKey
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        builder.setContentTitle(title).setContentText(if (current == null) getString(R.string.notification_text) else "${current.protocolId} · ${state?.name}")
            .setSmallIcon(R.drawable.ic_stat_cast).setContentIntent(openIntent()).setOngoing(true).setOnlyAlertOnce(true)
        if (current != null) {
            builder.setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(Notification.Action.Builder(if (state == PlaybackState.PAUSED) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    if (state == PlaybackState.PAUSED) "继续" else "暂停", actionIntent(ACTION_TOGGLE, 1)).build())
            if (index >= 0 && index < entries.lastIndex) builder.addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "下一集", actionIntent(ACTION_NEXT, 2)).build())
            builder.addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "结束投屏", actionIntent(ACTION_STOP, 3)).build())
                .setStyle(Notification.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0))
        } else builder.setCategory(Notification.CATEGORY_SERVICE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, builder.build())
        } catch (t: Throwable) { CastLogger.w(TAG, "启动前台通知失败", t) }
    }
    private fun openIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun actionIntent(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, CastForegroundService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }
    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "casthub_receiver"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "com.casthub.app.PLAYBACK_TOGGLE"
        private const val ACTION_STOP = "com.casthub.app.PLAYBACK_STOP"
        private const val ACTION_NEXT = "com.casthub.app.PLAYBACK_NEXT"
        fun start(context: Context) { runCatching { ContextCompat.startForegroundService(context, Intent(context, CastForegroundService::class.java)) }
            .onFailure { CastLogger.w(TAG, "启动接收服务失败", it) } }
    }
}

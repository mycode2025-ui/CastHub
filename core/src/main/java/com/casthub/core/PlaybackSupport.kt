package com.casthub.core

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** One instance per protocol player; everything is invoked on the player's main thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackSupport(context: Context) : AnalyticsListener {
    private val store = ModuleEnabledStore(context)
    private val history = PlaybackHistory(context)
    private val watchdog = StallDetector()
    private var uri = ""
    private var title: String? = null
    private var savedAt = 0L
    private var bufferingAt = -1L
    private var bufferingTotal = 0L
    private var decoder = "未知"
    private var dropped = 0
    var resumeCandidate: HistoryEntry? = null
        private set
    fun open(newUri: String, newTitle: String?, explicitStart: Boolean) {
        uri = newUri; title = newTitle; savedAt = 0
        bufferingAt = -1; bufferingTotal = 0; dropped = 0
        watchdog.reset()
        resumeCandidate = if (store.historyEnabled() && !explicitStart) history.find(uri)?.takeIf { it.resumable } else null
    }
    fun resetWatchdog() = watchdog.reset()
    fun applyPreferences(player: Player) {
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredAudioLanguage(store.audioLanguage().ifEmpty { null })
            .setPreferredTextLanguage(store.textLanguage().ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !store.subtitlesEnabled())
        player.trackSelectionParameters = builder.build()
    }
    fun select(player: Player?, choice: MediaTrackChoice): Boolean {
        if (!PlaybackTracks.select(player, choice) || player == null) return false
        val language = if (choice.group >= 0) player.currentTracks.groups[choice.group].getTrackFormat(choice.index).language.orEmpty() else ""
        if (choice.type == C.TRACK_TYPE_AUDIO) store.setAudioLanguage(language)
        if (choice.type == C.TRACK_TYPE_TEXT) {
            store.setSubtitlesEnabled(choice.group >= 0)
            if (choice.group >= 0) store.setTextLanguage(language)
        }
        return true
    }
    fun sample(player: ExoPlayer, pendingRetry: Boolean): Boolean {
        val now = SystemClock.elapsedRealtime()
        val buffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
        if (buffering && bufferingAt < 0) bufferingAt = now
        if (!buffering && bufferingAt >= 0) { bufferingTotal += now - bufferingAt; bufferingAt = -1 }
        if (store.historyEnabled() && !player.isCurrentMediaItemLive && player.duration > 0 && now - savedAt >= 5_000) {
            save(player); savedAt = now
        }
        if (!store.stallRecovery() || pendingRetry || player.playerError != null) { watchdog.reset(); return false }
        return watchdog.sample(now, player.currentPosition, buffering, player.playbackState == Player.STATE_READY,
            player.playWhenReady, player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            player.duration, player.isCurrentMediaItemLive)
    }
    fun save(player: ExoPlayer?) {
        if (player != null && uri.isNotEmpty() && store.historyEnabled() && !player.isCurrentMediaItemLive)
            history.save(uri, title, player.currentPosition, player.duration)
    }
    fun summary(player: ExoPlayer?, retries: Int): String {
        if (player == null) return "播放器未启动"
        val f = player.videoFormat
        val audio = player.audioFormat
        val elapsed = bufferingTotal + if (bufferingAt >= 0) SystemClock.elapsedRealtime() - bufferingAt else 0
        return "分辨率：${f?.width ?: 0} × ${f?.height ?: 0}\n视频编码：${f?.sampleMimeType ?: "未知"} ${f?.codecs.orEmpty()}\n音频编码：${audio?.sampleMimeType ?: "未知"}\n解码器：$decoder\n可用缓冲：${player.totalBufferedDuration / 1000.0} 秒\n累计缓冲等待：${elapsed / 1000.0} 秒\n丢帧：$dropped\n应用重试：$retries / 3\n直播：${player.isCurrentMediaItemLive}"
    }
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = decoderName }
    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { dropped += droppedFrames }

    /** Changing subtitles preserves the existing media source options and pause intent. */
    fun externalSubtitle(player: ExoPlayer?, address: String?, mimeType: String): Boolean {
        player ?: return false
        val old = player.currentMediaItem ?: return false
        if (address != null) {
            val parsed = Uri.parse(address)
            if (parsed.scheme !in listOf("https", "http", "content")) return false
            if (mimeType !in listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT)) return false
        }
        val config = address?.let { listOf(MediaItem.SubtitleConfiguration.Builder(Uri.parse(it))
            .setMimeType(mimeType).setLabel("外部字幕").setId("casthub-external")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()) } ?: emptyList()
        val position = player.currentPosition
        val play = player.playWhenReady
        watchdog.reset()
        player.setMediaItem(old.buildUpon().setSubtitleConfigurations(config).build(), position)
        if (address != null) { store.setSubtitlesEnabled(true); player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
        player.prepare(); player.playWhenReady = play
        return true
    }
}

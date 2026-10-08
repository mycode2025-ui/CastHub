package com.casthub.core

import android.content.Context

/**
 * 模块开关与个性化配置的持久化。
 * 让「模块可独立启用」这一诉求在进程重启后依然生效。
 */
class ModuleEnabledStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(moduleId: String, default: Boolean = true): Boolean =
        prefs.getBoolean(keyEnabled(moduleId), default)

    fun setEnabled(moduleId: String, enabled: Boolean) {
        prefs.edit().putBoolean(keyEnabled(moduleId), enabled).apply()
    }

    fun deviceName(moduleId: String, default: String): String =
        prefs.getString(keyName(moduleId), null)?.takeIf { it.isNotBlank() } ?: default

    fun setDeviceName(moduleId: String, name: String) {
        val safe = name.trim().take(48)
        if (safe.isEmpty()) return
        prefs.edit().putString(keyName(moduleId), safe).apply()
    }

    /**
     * 播放模式（单曲/顺序/单曲循环/列表循环）。
     *
     * 存的是枚举名而不是下标：下标会随枚举顺序变化而失效，
     * 用户装过旧版再升级时含义会串。
     */
    fun playbackMode(): PlaybackMode =
        PlaybackMode.fromName(prefs.getString(KEY_PLAYBACK_MODE, null))

    fun setPlaybackMode(mode: PlaybackMode) {
        prefs.edit().putString(KEY_PLAYBACK_MODE, mode.name).apply()
    }

    private fun keyEnabled(moduleId: String) = "module_enabled_$moduleId"
    fun autoRetry(): Boolean = prefs.getBoolean("auto_retry", true)
    fun setAutoRetry(value: Boolean) { prefs.edit().putBoolean("auto_retry", value).apply() }
    fun takeoverPolicy(): TakeoverPolicy = TakeoverPolicy.entries.firstOrNull { it.name == prefs.getString("takeover_policy", null) } ?: TakeoverPolicy.ALLOW
    fun setTakeoverPolicy(value: TakeoverPolicy) { prefs.edit().putString("takeover_policy", value.name).apply() }
    fun pictureMode(): PictureMode = PictureMode.entries.firstOrNull { it.name == prefs.getString("picture_mode", null) } ?: PictureMode.FIT
    fun setPictureMode(value: PictureMode) { prefs.edit().putString("picture_mode", value.name).apply() }
    fun subtitleSize(): Int = prefs.getInt("subtitle_size", 100).coerceIn(50, 200)
    fun setSubtitleSize(value: Int) { prefs.edit().putInt("subtitle_size", value.coerceIn(50, 200)).apply() }
    fun subtitleBottom(): Int = prefs.getInt("subtitle_bottom", 8).coerceIn(0, 80)
    fun setSubtitleBottom(value: Int) { prefs.edit().putInt("subtitle_bottom", value.coerceIn(0, 80)).apply() }
    fun subtitleOffsetMs(): Long = prefs.getLong("subtitle_offset_ms", 0).coerceIn(-10_000, 10_000)
    fun setSubtitleOffsetMs(value: Long) { prefs.edit().putLong("subtitle_offset_ms", value.coerceIn(-10_000, 10_000)).apply() }
    private fun keyName(moduleId: String) = "module_device_name_$moduleId"
    fun stallRecovery() = prefs.getBoolean("stall_recovery", true)
    fun setStallRecovery(value: Boolean) { prefs.edit().putBoolean("stall_recovery", value).apply() }
    fun historyEnabled() = prefs.getBoolean("history_enabled", true)
    fun setHistoryEnabled(value: Boolean) { prefs.edit().putBoolean("history_enabled", value).apply() }
    fun audioLanguage() = prefs.getString("audio_language", "").orEmpty()
    fun setAudioLanguage(value: String) { prefs.edit().putString("audio_language", value).apply() }
    fun textLanguage() = prefs.getString("text_language", "").orEmpty()
    fun setTextLanguage(value: String) { prefs.edit().putString("text_language", value).apply() }
    fun subtitlesEnabled() = prefs.getBoolean("subtitles_enabled", true)
    fun setSubtitlesEnabled(value: Boolean) { prefs.edit().putBoolean("subtitles_enabled", value).apply() }

    companion object {
        private const val PREFS_NAME = "casthub_modules"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
    }
}

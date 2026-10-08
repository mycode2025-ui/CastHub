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
    private fun keyName(moduleId: String) = "module_device_name_$moduleId"

    companion object {
        private const val PREFS_NAME = "casthub_modules"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
    }
}

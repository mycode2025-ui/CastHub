package com.casthub.core.update

import android.content.Context

/**
 * 升级检测的本地状态。
 *
 * 为什么需要节流：GitHub 未登录接口是「每出口 IP 每小时 60 次」，而"每次冷启动都查一遍"
 * 意味着用户多开关几次应用就可能把额度耗光（实测该额度在共享出口 IP 上长期为 0，
 * 未鉴权的检查会直接 403）。所以自动检查有最小间隔，只有手动检查才强制联网。
 */
class UpdatePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun lastCheckMs(): Long = prefs.getLong(KEY_LAST_CHECK, 0L)

    fun markChecked(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply()
    }

    /** 用户选择"忽略此版本"的 tag。自动检查不再对它提示，手动检查仍会如实显示。 */
    fun ignoredTag(): String? = prefs.getString(KEY_IGNORED_TAG, null)?.takeIf { it.isNotBlank() }

    fun setIgnoredTag(tag: String?) {
        val edit = prefs.edit()
        if (tag.isNullOrBlank()) edit.remove(KEY_IGNORED_TAG) else edit.putString(KEY_IGNORED_TAG, tag)
        edit.apply()
    }

    fun shouldAutoCheck(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - lastCheckMs() >= AUTO_CHECK_INTERVAL_MS

    companion object {
        private const val PREFS_NAME = "casthub_update"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val KEY_IGNORED_TAG = "ignored_tag"

        /**
         * 自动检查的最小间隔。
         * 太短会吃掉 GitHub 的匿名额度、也浪费电；太长则新版本提示不及时。
         * 6 小时是"一天最多查 4 次"的量级。
         */
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

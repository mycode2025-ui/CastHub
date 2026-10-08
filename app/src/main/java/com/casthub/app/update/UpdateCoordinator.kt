package com.casthub.app.update

import android.content.Context
import com.casthub.app.BuildConfig
import com.casthub.core.CastLogger
import com.casthub.core.update.UpdateCheckResult
import com.casthub.core.update.UpdateChecker
import com.casthub.core.update.UpdatePrefs
import com.casthub.core.update.UpdateSource

/**
 * 升级检测的应用层入口：把"仓库坐标 + 本机版本 + 首选源"与检测器接起来，
 * 并管理节流与忽略状态。
 *
 * 仓库坐标来自 `BuildConfig`（由 `app/build.gradle.kts` 注入），
 * 因此换仓库不需要改这里。
 */
class UpdateCoordinator(context: Context) {

    private val prefs = UpdatePrefs(context.applicationContext)

    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** 首选源：版本信息以此源优先，安装包也从这里先下。构建期可配。 */
    private val preferredSource = BuildConfig.UPDATE_PREFERRED_SOURCE
        .let { UpdateSource.entries.firstOrNull { e -> e.name == it } }
        ?: UpdateSource.GITEE

    /** 首选源的仓库主页，用于打不开浏览器 / 没有安装包时的兜底。 */
    val repoUrl: String get() = "${preferredSource.baseUrl}/${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}"

    private fun newChecker() = UpdateChecker(
        owner = BuildConfig.UPDATE_REPO_OWNER,
        repo = BuildConfig.UPDATE_REPO_NAME,
        currentVersionName = BuildConfig.VERSION_NAME,
        preferredSource = preferredSource,
    )

    /** 设置页手动触发的检查：不受节流限制，结论一律反馈给用户。 */
    suspend fun check(): UpdateCheckResult {
        CastLogger.i(
            TAG,
            "手动检查更新：${BuildConfig.UPDATE_REPO_OWNER}/${BuildConfig.UPDATE_REPO_NAME}，" +
                "当前 v${BuildConfig.VERSION_NAME}，首选源 ${preferredSource.label}",
        )
        val result = newChecker().check()
        prefs.markChecked()
        return result
    }

    /**
     * 冷启动时的静默检查。
     *
     * @return 需要提示用户的更新；下列情况一律返回 null（不打扰）：
     *         未到最小间隔、无更新、检查失败、该版本已被用户忽略。
     */
    suspend fun autoCheck(): UpdateCheckResult.UpdateAvailable? {
        if (!prefs.shouldAutoCheck()) {
            CastLogger.i(
                TAG,
                "距上次检查不足 ${UpdatePrefs.AUTO_CHECK_INTERVAL_MS / 3_600_000} 小时，跳过自动检查",
            )
            return null
        }
        val result = newChecker().check()
        prefs.markChecked()

        val available = result as? UpdateCheckResult.UpdateAvailable
        if (available == null) {
            CastLogger.i(TAG, "自动检查无需提示：${result::class.simpleName}")
            return null
        }
        if (available.candidate.release.tag == prefs.ignoredTag()) {
            CastLogger.i(TAG, "自动检查：${available.candidate.release.tag} 已被用户忽略")
            return null
        }
        return available
    }

    /** 用户选择"忽略此版本"：只影响自动提示，手动检查仍会如实显示。 */
    fun ignore(tag: String) {
        prefs.setIgnoredTag(tag)
        CastLogger.i(TAG, "已忽略版本 $tag，之后不再自动提示")
    }

    private companion object {
        const val TAG = "UpdateCoordinator"
    }
}

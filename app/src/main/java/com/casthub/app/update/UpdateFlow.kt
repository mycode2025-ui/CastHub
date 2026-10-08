package com.casthub.app.update

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.casthub.app.CastHubApplication
import com.casthub.app.R
import com.casthub.core.CastLogger
import com.casthub.core.update.ReleaseNotes
import com.casthub.core.update.UpdateCandidate
import com.casthub.core.update.UpdateCheckResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 升级检测的交互流程：检查 → 结果对话框 → 下载 → 校验签名 → 交给系统安装器。
 *
 * 抽成一个类是因为主页（冷启动静默检查）与设置页（手动检查）要走的是同一条流程，
 * 各自实现一遍必然会出现"其中一处忘了校验签名"这种漏洞。
 *
 * 本类只做交互编排；网络与校验在 [UpdateCoordinator] / [ApkDownloader] 里。
 */
class UpdateFlow(private val activity: AppCompatActivity) {

    private val app: CastHubApplication
        get() = activity.application as CastHubApplication

    private val downloader = ApkDownloader(activity)

    /** 同一次会话里不允许并发检查（用户连点、启动检查与手动检查撞车）。 */
    private var checking = false
    private var downloadJob: Job? = null

    // ─────────────────────── 检查 ───────────────────────

    /**
     * 手动检查（设置页）。
     *
     * @param onBusy 用于让入口显示"检查中…"；结束时会以 false 回调一次
     */
    fun checkManually(onBusy: (Boolean) -> Unit = {}) {
        if (checking) return
        checking = true
        onBusy(true)
        activity.lifecycleScope.launch {
            val result = try {
                app.updates.check()
            } catch (t: Throwable) {
                // 检查本身不该把界面搞崩；异常也要变成一句能读的结论
                CastLogger.w(TAG, "检查更新异常", t)
                UpdateCheckResult.Failed(t.message ?: "检查失败：${t.javaClass.simpleName}")
            }
            checking = false
            onBusy(false)
            if (!alive()) return@launch
            showResult(result)
        }
    }

    /**
     * 冷启动的静默检查：只在确实有新版本时才出现。
     *
     * 投屏中不弹 —— 用户正看着内容，一个盖在画面上的对话框只会让人以为出了问题。
     */
    fun checkOnLaunch() {
        if (checking) return
        if (app.coordinator.sessions.value.any { it.state.isActive }) {
            CastLogger.i(TAG, "正在投屏，跳过启动时的更新检查")
            return
        }
        checking = true
        activity.lifecycleScope.launch {
            val available = runCatching { app.updates.autoCheck() }
                .onFailure { CastLogger.w(TAG, "自动检查更新异常", it) }
                .getOrNull()
            checking = false
            if (available == null || !alive()) return@launch
            if (app.coordinator.sessions.value.any { it.state.isActive }) return@launch
            showUpdateDialog(available)
        }
    }

    // ─────────────────────── 结果 ───────────────────────

    private fun showResult(result: UpdateCheckResult) {
        when (result) {
            is UpdateCheckResult.UpdateAvailable -> showUpdateDialog(result)

            is UpdateCheckResult.UpToDate -> simpleDialog(R.string.update_up_to_date_title)
                .setMessage(
                    buildString {
                        append(
                            activity.getString(
                                R.string.update_up_to_date,
                                result.currentVersionName,
                            ),
                        )
                        appendWarnings(result.warnings)
                    },
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()

            is UpdateCheckResult.Failed -> simpleDialog(R.string.update_failed_title)
                .setMessage(result.message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showUpdateDialog(result: UpdateCheckResult.UpdateAvailable) {
        val candidate = result.candidate
        val release = candidate.release

        val message = buildString {
            append(
                activity.getString(
                    R.string.update_from,
                    result.currentVersionName,
                    release.tag,
                ),
            )
            append('\n')
            append(activity.getString(R.string.update_source, release.source.label))
            candidate.apkSource
                ?.takeIf { it != release.source }
                ?.let {
                    append('\n')
                    append(activity.getString(R.string.update_apk_from, it.label))
                }
            if (candidate.apkSizeBytes > 0) {
                append('\n')
                append(activity.getString(R.string.update_apk_size, formatSize(candidate.apkSizeBytes)))
            }
            if (candidate.apkUrl == null) {
                append("\n\n")
                append(activity.getString(R.string.update_no_apk))
            }

            // ⚠️ 告警必须紧跟头部信息。实测把追加在说明末尾是错的：
            // 说明一长，告警就被顶到对话框可视区之外，"结论可能不完整"这件事
            // 等于没告诉用户 —— 而它恰恰比更新说明更重要。
            appendWarnings(result.warnings)

            if (release.notes.isNotBlank()) {
                // 正文是网络来的任意 Markdown，原样显示会出现 ## / ** / 表格线
                val notes = ReleaseNotes.toPlainText(release.notes, MAX_NOTES_LINES)
                append("\n\n")
                append(activity.getString(R.string.update_release_notes))
                append('\n')
                append(notes.text)
                // 截断标记由清理器给出，不能在这里用行数反推（详见 ReleaseNotes.Plain 的说明）
                if (notes.truncated) {
                    append('\n')
                    append(activity.getString(R.string.update_notes_truncated))
                }
            }
        }

        simpleDialog(R.string.update_available_title, release.tag)
            .setMessage(message)
            .setPositiveButton(R.string.action_upgrade_now) { _, _ -> startUpgrade(candidate) }
            .setNeutralButton(R.string.action_ignore_version) { _, _ ->
                app.updates.ignore(release.tag)
            }
            .setNegativeButton(R.string.action_later, null)
            .show()
    }

    /** 追加数据源告警。有源没取到时结论可能不完整，必须让用户知道。 */
    private fun StringBuilder.appendWarnings(warnings: List<String>) {
        if (warnings.isEmpty()) return
        append("\n\n")
        append("⚠️ ")
        append(activity.getString(R.string.update_warnings_title))
        append("：")
        append(warnings.joinToString("；"))
    }

    // ─────────────────────── 升级 ───────────────────────

    private fun startUpgrade(candidate: UpdateCandidate) {
        val apkUrl = candidate.apkUrl
        if (apkUrl == null) {
            // 两个源都没挂安装包：退化成打开发布页，别让"立即升级"变成一个死按钮
            openPage(candidate.release.pageUrl)
            return
        }
        // 先要授权再下载：没授权就下载等于白耗流量
        if (!downloader.canInstallPackages()) {
            askInstallPermission()
            return
        }
        download(candidate, apkUrl)
    }

    private fun askInstallPermission() {
        simpleDialog(R.string.update_need_permission_title)
            .setMessage(R.string.update_need_permission_message)
            .setPositiveButton(R.string.action_go_settings) { _, _ ->
                runCatching { activity.startActivity(downloader.unknownAppSourcesSettingsIntent()) }
                    .onFailure { CastLogger.w(TAG, "无法打开「安装未知应用」设置页", it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun download(candidate: UpdateCandidate, apkUrl: String) {
        val holder = buildProgressHolder()
        holder.message.text = activity.getString(
            R.string.update_downloading,
            formatSize(0),
            formatSize(candidate.apkSizeBytes),
        )
        val dialog = simpleDialog(R.string.update_downloading_title)
            .setView(holder.root)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, null)
            .show()

        downloadJob?.cancel()
        downloadJob = activity.lifecycleScope.launch {
            var cancelledByUser = false
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                cancelledByUser = true
                downloader.cancelActive()
                dialog.dismiss()
            }

            val fileName = "CastHub-${candidate.release.version.core}.apk"
            val apk = try {
                downloader.download(apkUrl, fileName) { done, total ->
                    // 回调在 IO 线程，切回主线程再动界面
                    activity.runOnUiThread {
                        val known = if (total > 0) total else candidate.apkSizeBytes
                        if (known > 0) {
                            holder.bar.progress = ((done * PROGRESS_MAX) / known).toInt()
                                .coerceIn(0, PROGRESS_MAX)
                        }
                        holder.message.text = if (known > 0) {
                            activity.getString(
                                R.string.update_downloading,
                                formatSize(done),
                                formatSize(known),
                            )
                        } else {
                            activity.getString(R.string.update_downloading_unknown, formatSize(done))
                        }
                    }
                }
            } catch (t: Throwable) {
                if (dialog.isShowing) dialog.dismiss()
                if (cancelledByUser) {
                    CastLogger.i(TAG, "用户取消了下载")
                    return@launch
                }
                CastLogger.w(TAG, "下载安装包失败", t)
                if (alive()) {
                    simpleDialog(R.string.update_download_failed_title)
                        .setMessage(
                            buildString {
                                append(t.message ?: t.javaClass.simpleName)
                                append("\n\n")
                                append(activity.getString(R.string.update_download_failed_hint))
                            },
                        )
                        .setPositiveButton(R.string.action_open_release_page) { _, _ ->
                            openPage(candidate.release.pageUrl)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                return@launch
            }

            if (dialog.isShowing) dialog.dismiss()
            if (!alive()) return@launch

            // ⚠️ 真正的安全关口：签名不一致绝不安装。
            // 走到这里说明包已经从网络落地，只有"与本机同一签名"才能证明
            // 它就是本应用的官方构建；否则装上去等于把应用替换掉。
            when (val check = downloader.checkSignature(apk)) {
                is ApkDownloader.SignatureCheck.Ok -> confirmInstall(apk)

                is ApkDownloader.SignatureCheck.Mismatch -> {
                    CastLogger.e(
                        TAG,
                        "安装包签名校验未通过：本机 ${check.installed} / 安装包 ${check.downloaded}",
                    )
                    apk.delete()
                    simpleDialog(R.string.update_verify_failed_title)
                        .setMessage(
                            activity.getString(
                                R.string.update_verify_mismatch,
                                check.installed ?: "—",
                                check.downloaded ?: "—",
                            ),
                        )
                        .setPositiveButton(R.string.action_open_release_page) { _, _ ->
                            openPage(candidate.release.pageUrl)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }

                is ApkDownloader.SignatureCheck.Unreadable -> {
                    CastLogger.e(TAG, "安装包签名无法读取")
                    apk.delete()
                    simpleDialog(R.string.update_verify_failed_title)
                        .setMessage(R.string.update_verify_unreadable)
                        .setPositiveButton(R.string.action_open_release_page) { _, _ ->
                            openPage(candidate.release.pageUrl)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
        }
    }

    private fun confirmInstall(apk: File) {
        simpleDialog(R.string.update_ready_title)
            .setMessage(R.string.update_ready_message)
            .setPositiveButton(R.string.action_install) { _, _ -> launchInstaller(apk) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchInstaller(apk: File) {
        try {
            activity.startActivity(downloader.installIntent(apk))
        } catch (t: Throwable) {
            CastLogger.w(TAG, "无法调起系统安装器", t)
            simpleDialog(R.string.update_download_failed_title)
                .setMessage(R.string.update_no_installer)
                .setPositiveButton(R.string.action_open_release_page) { _, _ ->
                    openPage(repoFallbackUrl())
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun openPage(url: String) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            CastLogger.w(TAG, "没有可打开链接的应用：$url", e)
            simpleDialog(R.string.update_failed_title)
                .setMessage(activity.getString(R.string.update_no_browser, url))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun repoFallbackUrl(): String = app.updates.repoUrl

    // ─────────────────────── 小工具 ───────────────────────

    private class ProgressHolder(
        val root: LinearLayout,
        val message: TextView,
        val bar: ProgressBar,
    )

    private fun buildProgressHolder(): ProgressHolder {
        val text = TextView(activity).apply {
            setPadding(dp(4), dp(8), dp(4), dp(10))
            textSize = 16f
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = PROGRESS_MAX
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(
                text,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(bar)
        }
        return ProgressHolder(root, text, bar)
    }

    private fun simpleDialog(titleRes: Int) = AlertDialog.Builder(activity).setTitle(titleRes)

    private fun simpleDialog(titleRes: Int, formatArg: String) =
        AlertDialog.Builder(activity).setTitle(activity.getString(titleRes, formatArg))

    private fun alive(): Boolean = !activity.isFinishing && !activity.isDestroyed

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        const val TAG = "UpdateFlow"
        const val PROGRESS_MAX = 1000

        /**
         * 更新说明最多展示这么多行。
         *
         * 按**行**而不是字符数限制：对话框的观感取决于占了几行，
         * 而 Release 正文里的 Markdown 标记会把字符数撑得很虚。
         * 8 行是实测值 —— 12 行时对话框几乎占满手机整屏，电视上更难看。
         */
        const val MAX_NOTES_LINES = 8
    }
}

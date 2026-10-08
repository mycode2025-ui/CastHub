package com.casthub.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.casthub.core.CastLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 升级包的下载、校验与安装。
 *
 * 这是整个应用里唯一"下载并执行外部代码"的通道，因此按不可信输入对待：
 *   1. 只接受 https，且域名必须在 GitHub / Gitee 及其对象存储的范围内 ——
 *      即便 Release 信息被篡改指向别处，也走不出这一步；
 *   2. 下载完成后**必须**校验签名与本机一致才交给安装器 ——
 *      否则装上一个同包名不同签名的包，等于把应用交出去；
 *   3. 安装前确认已获得"安装未知应用"授权，未授权时引导去系统设置，而不是静默失败。
 */
class ApkDownloader(context: Context) {

    private val appContext = context.applicationContext

    /** 签名校验结果。 */
    sealed class SignatureCheck {
        /** 与本机签名一致，可以安装。 */
        object Ok : SignatureCheck()

        /** 签名不一致 —— 绝不安装。 */
        data class Mismatch(val installed: String?, val downloaded: String?) : SignatureCheck()

        /** 读不出签名（文件损坏、非 APK、系统接口返回空）。 */
        object Unreadable : SignatureCheck()
    }

    /**
     * 正在进行的连接。
     *
     * 存在的理由：`input.read()` 在协程里是**阻塞调用、不响应取消**，
     * 光 cancel 协程要等到下一次读返回才会停（可能等满整个读超时）。
     * 想真的中断下载只能断开连接 —— 断开会让 read 立刻抛 IOException。
     */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 中断正在进行的下载（用户点了取消）。 */
    fun cancelActive() {
        activeConnection?.disconnect()
        activeConnection = null
    }

    /**
     * 下载 APK 到应用缓存目录。
     *
     * @param onProgress 在 IO 线程回调；界面侧需自行切主线程
     * @return 下载完成的文件
     */
    suspend fun download(
        url: String,
        targetName: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        requireTrustedUrl(url)

        val dir = File(appContext.cacheDir, DIR).apply { mkdirs() }
        val target = File(dir, targetName)
        // 先写 .part 再改名：进程被杀/断网时不会留下一个"看着完整"的包被后续误用
        val temp = File(dir, "$targetName.part")
        temp.delete()
        target.delete()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            activeConnection = conn
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")

            val total = conn.contentLength.toLong()
            var written = 0L
            conn.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                    output.flush()
                }
            }
            // 服务端给了长度就必须对得上：截断的 APK 会"解析失败"，
            // 报出来是"安装包损坏"，用户根本无从判断
            if (total > 0 && written != total) {
                throw IOException("下载不完整：$written / $total 字节")
            }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            if (activeConnection === conn) activeConnection = null
            conn?.disconnect()
        }

        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        CastLogger.i(TAG, "安装包已下载：${target.name}（${target.length() / 1024} KB）")
        target
    }

    /** 校验下载到的 APK 与本机是否同一签名。 */
    fun checkSignature(apk: File): SignatureCheck {
        val pm = appContext.packageManager
        val archive = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, signFlags()) }
            .getOrNull()
            ?: return SignatureCheck.Unreadable
        val installed = runCatching { pm.getPackageInfo(appContext.packageName, signFlags()) }
            .getOrNull()
            ?: return SignatureCheck.Unreadable

        val fromArchive = signerDigests(archive)
        val fromInstalled = signerDigests(installed)
        if (fromArchive.isEmpty() || fromInstalled.isEmpty()) return SignatureCheck.Unreadable

        return if (fromArchive == fromInstalled) {
            SignatureCheck.Ok
        } else {
            SignatureCheck.Mismatch(
                installed = fromInstalled.first(),
                downloaded = fromArchive.first(),
            )
        }
    }

    /**
     * 查询包信息时用哪个标志。
     *
     * API 28 起 `GET_SIGNATURES` 已废弃且可能不填 `signatures`，
     * 因此新系统上改用 `GET_SIGNING_CERTIFICATES` 走 `signingInfo`。
     */
    private fun signFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

    /**
     * 取签名者证书摘要（已排序，便于直接比较集合）。
     *
     * 用 `apkContentsSigners` 而不是 `signingCertificateHistory`：
     * 后者包含密钥轮换前的旧证书，两个包即便同源也可能因为历史不同而比不相等。
     */
    private fun signerDigests(info: PackageInfo): List<String> {        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            signingInfo.apkContentsSigners.toList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList().orEmpty()
        }
        if (certificates.isEmpty()) return emptyList()
        val digest = MessageDigest.getInstance("SHA-256")
        return certificates
            .map { certificate ->
                digest.reset()
                digest.digest(certificate.toByteArray())
                    .joinToString(":") { "%02X".format(it) }
            }
            .sorted()
    }

    /** Android 8.0 起安装 APK 需要先由用户授予「安装未知应用」。 */
    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun unknownAppSourcesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** 交给系统安装器。用 FileProvider 而不是 `file://` —— Android 7.0 起后者会抛 FileUriExposedException。 */
    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** 清掉缓存里的历史安装包。 */
    fun clearDownloaded() {
        val dir = File(appContext.cacheDir, DIR)
        val removed = dir.listFiles()?.count { it.delete() } ?: 0
        if (removed > 0) CastLogger.i(TAG, "清理了 $removed 个历史安装包")
    }

    /**
     * 只允许从受信域名下载。
     *
     * 这是纵深防御的一层（真正的关口是签名校验）：Release 信息来自网络，
     * 万一被指向第三方主机，这里直接拒绝，而不是乖乖把 APK 拉下来。
     * GitHub 的 Release 资产会 302 到 objects.githubusercontent.com，所以后缀也要放行。
     */
    private fun requireTrustedUrl(url: String) {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") throw IOException("只允许 https 下载（收到 ${scheme ?: "无协议"}）")
        val host = uri.host?.lowercase().orEmpty()
        val trusted = TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
        if (!trusted) throw IOException("下载地址不在受信域名内：$host")
    }

    private companion object {
        const val TAG = "ApkDownloader"
        const val DIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val USER_AGENT = "CastHub-Android-Updater"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024

        val TRUSTED_HOSTS = listOf(
            "github.com",
            "githubusercontent.com",
            "gitee.com",
            "gitee.io",
        )
    }
}

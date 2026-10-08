package com.casthub.core.update

import com.casthub.core.CastLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** 取文本的抽象。抽成接口是为了让"选哪个源、如何降级"这类编排逻辑可以脱离网络单测。 */
fun interface TextFetcher {
    /** @throws IOException 网络失败；非 2xx 抛 [HttpFailure] */
    fun get(url: String, headers: Map<String, String>): String
}

/** HTTP 非 2xx。 */
class HttpFailure(val status: Int, val body: String) : IOException("HTTP $status")

/** 基于 [HttpURLConnection] 的实现：不引 OkHttp，升级检测不值得为它加一个依赖。 */
class UrlConnectionFetcher(
    private val connectTimeoutMs: Int = 6_000,
    private val readTimeoutMs: Int = 8_000,
    private val userAgent: String = USER_AGENT,
) : TextFetcher {

    override fun get(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            useCaches = false
            // 有些网络环境会因为没有 UA 直接拒绝，统一补一个
            setRequestProperty("User-Agent", userAgent)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw HttpFailure(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}

/** 单个数据源的取用结果。[error] 非空表示这一源不可用（不影响另一源出结论）。 */
internal data class SourceOutcome(
    val source: UpdateSource,
    val releases: List<ReleaseInfo> = emptyList(),
    val error: String? = null,
)

/**
 * 检查是否有新版本。
 *
 * 数据源同时查 GitHub 与 Gitee 并**并行**发出：国内直连 GitHub 经常超时甚至被限流，
 * 串行等待会让"检查更新"卡上一二十秒；并行时最坏只等于较慢的那一个。
 * 结论取两站里版本更高的一条 —— 两站的 Release 发布节奏并不一致，
 * 只看其中一个会漏判。
 */
class UpdateChecker(
    private val owner: String,
    private val repo: String,
    private val currentVersionName: String,
    /**
     * 首选数据源：版本信息以此源优先，下载地址也排在降级顺序的第一位。
     *
     * 默认 Gitee 不是随手定的 —— GitHub 未鉴权接口是「每出口 IP 每小时 60 次」，
     * 国内用户的实际经验往往是"GitHub 取不到、Gitee 好好的"；
     * 而一旦要下载几 MB 的安装包，出口带宽更是决定性的。
     * 两个源**都会查**，[preferredSource] 只决定谁优先，不决定谁被跳过。
     */
    private val preferredSource: UpdateSource = UpdateSource.GITEE,
    private val fetcher: TextFetcher = UrlConnectionFetcher(),
) {

    suspend fun check(): UpdateCheckResult {
        val current = Version.parseOrNull(currentVersionName)
            ?: return UpdateCheckResult.Failed("本机版本号无法解析：$currentVersionName")

        val outcomes = coroutineScope {
            val gitee = async(Dispatchers.IO) { fetchSource(UpdateSource.GITEE) }
            val github = async(Dispatchers.IO) { fetchSource(UpdateSource.GITHUB) }
            listOf(gitee.await(), github.await())
        }

        outcomes.forEach { outcome ->
            outcome.error?.let {
                CastLogger.w(TAG, "${outcome.source.label} 取版本失败：$it")
            } ?: CastLogger.i(
                TAG,
                "${outcome.source.label} 取到 ${outcome.releases.size} 条 Release",
            )
        }

        val result = decide(outcomes, current, currentVersionName, preferredSource)
        CastLogger.i(
            TAG,
            "检查更新结论：${result::class.simpleName}（首选源 ${preferredSource.label}）",
        )
        return result
    }

    private suspend fun fetchSource(source: UpdateSource): SourceOutcome = try {
        val body = fetcher.get(ApiUrls.releases(source, owner, repo), headers(source))
        SourceOutcome(source, ReleaseParser.parse(source, owner, repo, body))
    } catch (cancelled: CancellationException) {
        // 协程取消不是"取失败"，必须原样抛出，否则会把取消吞掉、破坏结构化并发
        throw cancelled
    } catch (t: Throwable) {
        SourceOutcome(source, error = describe(source, t))
    }

    private fun headers(source: UpdateSource): Map<String, String> = when (source) {
        // GitHub 强制要求 User-Agent，缺失会直接 403；Accept 用官方推荐的媒体类型
        UpdateSource.GITHUB -> mapOf("Accept" to "application/vnd.github+json")

        UpdateSource.GITEE -> mapOf("Accept" to "application/json")
    }

    /** 把异常翻译成能直接展示给用户的中文说明。 */
    private fun describe(source: UpdateSource, t: Throwable): String = when (t) {
        is HttpFailure -> when (t.status) {
            403 -> if (source == UpdateSource.GITHUB) {
                // 实测：共享出口 IP 上未鉴权的 GitHub 接口很容易整点被用光，
                // 这正是必须把 Gitee 也作为数据源的原因
                "访问受限（GitHub 未登录接口每小时仅 60 次），稍后再试"
            } else {
                "访问受限（403），稍后再试"
            }

            404 -> "仓库不存在或未公开"
            in 500..599 -> "服务端故障（${t.status}）"
            else -> "HTTP ${t.status}"
        }

        is SocketTimeoutException -> "连接超时"
        is UnknownHostException -> "域名解析失败，请检查网络"
        is ReleaseParseException -> t.message ?: "返回内容无法解析"
        is IOException -> "网络错误：${t.message ?: t.javaClass.simpleName}"
        else -> t.message ?: t.javaClass.simpleName
    }

    private companion object {
        const val TAG = "UpdateChecker"
    }
}

/**
 * 汇总各源结论，选出升级候选。
 *
 * 做成独立的纯函数：这段判断（哪个源可用、版本谁高、APK 从哪取、下载该试哪个地址）
 * 是升级功能里最容易出错的部分，必须能脱离网络直接单测。
 */
internal fun decide(
    outcomes: List<SourceOutcome>,
    current: Version,
    currentVersionName: String,
    preferredSource: UpdateSource = UpdateSource.GITEE,
): UpdateCheckResult {
    val usable = outcomes.filter { it.error == null }
    val warnings = outcomes.filter { it.error != null }
        .map { "${it.source.label}：${it.error}" }

    if (usable.isEmpty()) {
        return UpdateCheckResult.Failed(
            outcomes.joinToString("；") { "${it.source.label}：${it.error}" },
        )
    }

    val all = usable.flatMap { it.releases }
    if (all.isEmpty()) {
        // 两个源都取到了，但都还没发布过版本 —— 这确实等于"没有可升级的版本"
        return UpdateCheckResult.UpToDate(currentVersionName, latestTag = null, warnings = warnings)
    }

    // 同版本时优先带 APK 的那条：能直接下载比停在网页上好；
    // 版本与是否带包都相同时才按 priority 决出主源与备源。
    // 这里**只比到"版本 > 是否有包"这两级**，同级的取舍交给下面的排序 ——
    // 把优先级也塞进比较器会让规则散在两处，读的人无法确定到底谁说了算。
    val best = all.maxWithOrNull(
        compareBy<ReleaseInfo> { it.version }
            .thenBy { if (it.apk != null) 1 else 0 },
    ) ?: return UpdateCheckResult.UpToDate(currentVersionName, latestTag = null, warnings = warnings)

    if (best.version <= current) {
        return UpdateCheckResult.UpToDate(currentVersionName, latestTag = best.tag, warnings = warnings)
    }

    // 升级目标版本下所有可用的取包渠道，按 priority 排序：
    // 第一个是主选、其余是"主选下不动时换过去"的降级项。
    // 允许取自另一个源：两站的 Release 常不同步，
    // "Gitee 已发新版本但没挂包、GitHub 挂了包"就是这种情况。
    val channels = all
        .filter { it.version == best.version }
        .distinctBy { it.source }
        .sortedWith(
            compareByDescending<ReleaseInfo> { if (it.apk != null) 1 else 0 }
                .thenByDescending { it.source.priority },
        )

    // 版本信息优先取首选源的那条；首选源没有这个版本时才落到实际选中的那条。
    val primary = channels.firstOrNull { it.source == preferredSource } ?: best
    val primaryApk = channels.firstOrNull { it.apk != null }

    return UpdateCheckResult.UpdateAvailable(
        candidate = UpdateCandidate(
            release = primary,
            apkUrl = primaryApk?.apk?.url,
            apkSource = primaryApk?.source,
            apkSizeBytes = primaryApk?.apk?.sizeBytes ?: 0L,
            apkFallbacks = channels.filter { it.apk != null && it !== primaryApk }
                .map { ApkLocation(it.source, it.apk!!.url, it.apk.sizeBytes) },
            preferredSource = preferredSource,
        ),
        currentVersionName = currentVersionName,
        warnings = warnings,
    )
}

/**
 * 下载地址的降级顺序。
 *
 * 只用来生成"下载失败后该换哪个地址重试"的提示，**不做自动重试**：
 * 一个几 MB 的包下到一半失败再自动重来一遍，在电视上的观感像是卡死了。
 * 让用户看着提示自己决定，比脚本自作主张更容易理解。
 */
internal fun apkTryOrder(candidate: UpdateCandidate): List<ApkLocation> {
    val primary = candidate.apkUrl?.let {
        ApkLocation(candidate.apkSource ?: candidate.preferredSource, it, candidate.apkSizeBytes)
    }
    return listOfNotNull(primary) + candidate.apkFallbacks
}

/** 两站接口地址。 */
internal object ApiUrls {

    /**
     * 取 20 条而不是 1 条：上面要过滤掉 draft / prerelease，也可能存在解析不了 tag 的条目，
     * 只看第一条会把这些情况误当成"没有新版本"。两站的列表都按发布时间倒序。
     */
    private const val PER_PAGE = 20

    fun releases(source: UpdateSource, owner: String, repo: String): String = when (source) {
        UpdateSource.GITHUB ->
            "https://api.github.com/repos/$owner/$repo/releases?per_page=$PER_PAGE"

        UpdateSource.GITEE ->
            "https://gitee.com/api/v5/repos/$owner/$repo/releases?per_page=$PER_PAGE"
    }
}

/** 所有请求共用的 UA：GitHub 缺它会 403，Gitee 也用它区分调用方。 */
private const val USER_AGENT = "CastHub-Android-UpdateCheck"

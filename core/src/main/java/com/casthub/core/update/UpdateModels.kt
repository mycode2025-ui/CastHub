package com.casthub.core.update

/**
 * 升级包来源。两站同为一个项目提供服务，界面上要让用户知道版本信息是从哪取到的。
 *
 * [priority] 越大越优先，是**唯一**的优先级定义处：升级检测用它决定首选源，
 * 下载降级顺序也由它排序。写成一个数而不是在几处各写一遍 when ——
 * 「Gitee 优先」这种策略一旦散在多处，改起来必然漏掉其中一处。
 */
enum class UpdateSource(val label: String, val priority: Int, val baseUrl: String) {
    GITEE("Gitee", 1, "https://gitee.com"),
    GITHUB("GitHub", 0, "https://github.com"),
}

/** Release 里附带的文件，目前只关心 APK。 */
data class ReleaseAsset(
    val name: String,
    val url: String,
    /** Gitee 的资产接口不返回大小，那里恒为 0 —— 不要用它做"是否下载完"的判断。 */
    val sizeBytes: Long,
)

/** 一个可下载的安装包地址及其出处，用于降级顺序。 */
data class ApkLocation(
    val source: UpdateSource,
    val url: String,
    val sizeBytes: Long,
)

/**
 * 某一个仓库上的一条 Release。
 *
 * 刻意保留 [source]：两个源都可能报出新版本，最终选用哪一条、APK 又取自哪里，
 * 需要在日志与界面里说清楚，否则"为什么显示的版本和 GitHub 上的不一样"没法排查。
 */
data class ReleaseInfo(
    val source: UpdateSource,
    val tag: String,
    val version: Version,
    val title: String,
    val notes: String,
    /** 该 Release 的网页地址，取不到直链时引导用户去这里。 */
    val pageUrl: String,
    val apk: ReleaseAsset?,
)

/**
 * 选定的升级候选。
 *
 * 版本信息与 APK 直链可能来自**不同的源**：两站的 Release 常常不同步，
 * 若坚持"版本取自哪个源、APK 就必须来自哪个源"，就会出现
 * "Gitee 已经发了新版本但没挂 APK，于是明明 GitHub 上有包却不给下载"。
 *
 * [apkFallbacks] 是**同版本、另一站**上的安装包地址。存在的理由很实际：
 * Gitee 的附件下载在国内偶尔会抽风，而版本信息恰恰是 Gitee 给的，
 * 此时若只认这一个地址，用户就卡在"有新版本但下不动"。
 * 它只用于**失败后提示换源重试**，不自动重试（见 `apkTryOrder`）。
 */
data class UpdateCandidate(
    val release: ReleaseInfo,
    val apkUrl: String?,
    val apkSource: UpdateSource?,
    val apkSizeBytes: Long,
    val apkFallbacks: List<ApkLocation> = emptyList(),
    /** 本次检查的首选源，用于界面在"两站版本不同"时说明为什么选了这个。 */
    val preferredSource: UpdateSource = UpdateSource.GITEE,
) {
    /** 除主选的包外，还有没有别的站挂着同一个版本的包。 */
    val hasFallbackApk: Boolean get() = apkFallbacks.isNotEmpty()
}

/**
 * 一次检查的结果。
 *
 * [warnings] 存在的理由：只要有一个源取失败，结论就可能是**漏判**的
 * （比如 Gitee 说已是最新、而 GitHub 上其实有更新的版本，只是没取到）。
 * 这种情况必须让用户看到，否则一句"已是最新"就是错的。
 */
sealed class UpdateCheckResult {

    abstract val warnings: List<String>

    /** 有更新。 */
    data class UpdateAvailable(
        val candidate: UpdateCandidate,
        val currentVersionName: String,
        override val warnings: List<String> = emptyList(),
    ) : UpdateCheckResult()

    /** 已是最新（或仓库尚未发布过任何版本）。 */
    data class UpToDate(
        val currentVersionName: String,
        val latestTag: String?,
        override val warnings: List<String> = emptyList(),
    ) : UpdateCheckResult()

    /** 两个源都没取到，无法判断。[message] 已是可以直接展示给用户的中文说明。 */
    data class Failed(val message: String) : UpdateCheckResult() {
        override val warnings: List<String> get() = emptyList()
    }
}

package com.casthub.core.update

/**
 * 升级包的下载域名白名单。
 *
 * 这是升级流程的第一道防线（真正的关口是签名校验）：Release 信息来自网络，
 * 万一被篡改成指向第三方主机，这里直接拒绝，而不是乖乖把包拉下来。
 *
 * 抽成不依赖 Android 的纯函数，是为了能对它写单测 —— 一个"看起来对但少判了一种
 * 情况"的白名单等于没有白名单，而这种写法缺陷光靠读代码很难发现。
 */
object TrustedDownloadHosts {

    /**
     * 允许的域名后缀。
     *
     * `github.com` 的 Release 资产会 302 到 `objects.githubusercontent.com`，
     * 所以对象存储域名也要放行（放行的是"下载会经过的合法跳转目标"，
     * 而不是"任意第三方"）。
     */
    private val ALLOWED_SUFFIXES = listOf(
        "github.com",
        "githubusercontent.com",
        "gitee.com",
        "gitee.io",
    )

    /**
     * @param scheme URL 协议，大小写不敏感
     * @param host   域名，大小写不敏感；带不带前导点都会按"完整标签"匹配
     * @return 是否允许从此地址下载
     */
    fun isTrusted(scheme: String?, host: String?): Boolean {
        // 明文 HTTP 上的安装包可被中间人任意替换，签名校验虽然能兜住，
        // 但没必要让流量先被改一遍
        if (!scheme.equals("https", ignoreCase = true)) return false

        val normalized = host?.trim()?.lowercase()?.removeSuffix(".") ?: return false
        if (normalized.isEmpty()) return false

        // 必须按**域名标签**匹配，不能用 contains：
        // `github.com.evil.tld` 与 `evilgithub.com` 都含有 "github.com"，
        // 但它们不是 GitHub 的域名。
        return ALLOWED_SUFFIXES.any { suffix ->
            normalized == suffix || normalized.endsWith(".$suffix")
        }
    }
}

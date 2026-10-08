package com.casthub.core.update

import org.json.JSONArray
import org.json.JSONObject

/** Release 接口返回的内容无法理解（不是数组、也不是带 message 的错误对象）。 */
class ReleaseParseException(message: String) : Exception(message)

/**
 * 把两站 Releases 接口的 JSON 解析成统一模型。
 *
 * 两站字段**高度相似但并不一致**，差异全部在这里吃掉，上层只面对 [ReleaseInfo]：
 *   · GitHub 有 `draft`，Gitee 没有 —— 缺省按 false；
 *   · Gitee 的 `assets[].size` 不存在 —— 记 0 并明确标注它不可信；
 *   · GitHub 给 `html_url`，Gitee 不给 —— 用 owner/repo/tag 自行拼出 Release 页地址；
 *   · 两站的错误响应都是 `{"message": "..."}`，状态码却可能是 200，必须显式识别，
 *     否则会当成"零个 Release"，表现为静默的"已是最新版本"。
 */
object ReleaseParser {

    fun parse(source: UpdateSource, owner: String, repo: String, body: String): List<ReleaseInfo> {
        val text = body.trim()
        if (text.isEmpty()) throw ReleaseParseException("服务端返回空内容")

        if (!text.startsWith("[")) {
            // 错误对象：把服务端的原话带出去，比"解析失败"有用得多
            val message = runCatching {
                JSONObject(text).optString("message")
            }.getOrNull()
            throw ReleaseParseException(
                message?.takeIf { it.isNotBlank() }?.let { "服务端返回：$it" } ?: "返回内容不是 Release 列表",
            )
        }

        val array = try {
            JSONArray(text)
        } catch (t: Throwable) {
            throw ReleaseParseException("Release 列表格式错误：${t.message}")
        }

        val result = ArrayList<ReleaseInfo>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            parseOne(source, owner, repo, obj)?.let(result::add)
        }
        return result
    }

    private fun parseOne(
        source: UpdateSource,
        owner: String,
        repo: String,
        obj: JSONObject,
    ): ReleaseInfo? {
        // 草稿与预发布都不能作为升级目标：前者对用户不可见，
        // 后者是测试版本，让所有人升级上去不合适
        if (obj.optBoolean("draft", false)) return null
        if (obj.optBoolean("prerelease", false)) return null

        val tag = obj.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        // tag 解析不出来（例如 nightly-20260101）就跳过这一条：
        // 拿它做版本比较会得出莫名其妙的结论
        val version = Version.parseOrNull(tag) ?: return null

        val assets = obj.optJSONArray("assets")
        val apk = pickApk(assets)

        val pageUrl = obj.optString("html_url").takeIf { it.startsWith("http") }
            ?: defaultPageUrl(source, owner, repo, tag)

        return ReleaseInfo(
            source = source,
            tag = tag,
            version = version,
            title = obj.optString("name").trim().ifEmpty { tag },
            notes = obj.optString("body").trim(),
            pageUrl = pageUrl,
            apk = apk,
        )
    }

    /**
     * 从资产列表里挑 APK。
     *
     * 有多个 `.apk` 时不取第一个：若有人同时传了正式包和 debug 包，
     * 取到 debug 包会让升级后签名不符、装不上。同名冲突时宁可挑"名字里没有 debug"的那个。
     */
    private fun pickApk(assets: JSONArray?): ReleaseAsset? {
        if (assets == null || assets.length() == 0) return null
        val candidates = ArrayList<ReleaseAsset>(assets.length())
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name").trim()
            val url = a.optString("browser_download_url").trim()
            if (name.isEmpty() || !url.startsWith("http")) continue
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            candidates.add(ReleaseAsset(name, url, a.optLong("size", 0L)))
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { !it.name.contains("debug", ignoreCase = true) }
            ?: candidates.first()
    }

    private fun defaultPageUrl(
        source: UpdateSource,
        owner: String,
        repo: String,
        tag: String,
    ): String = when (source) {
        UpdateSource.GITHUB -> "https://github.com/$owner/$repo/releases/tag/$tag"
        UpdateSource.GITEE -> "https://gitee.com/$owner/$repo/releases/tag/$tag"
    }
}

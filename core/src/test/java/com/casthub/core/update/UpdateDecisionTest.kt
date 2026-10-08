package com.casthub.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDecisionTest {

    private val current = Version.parseOrNull("1.4.0")!!

    private fun rel(source: UpdateSource, tag: String, apk: String? = null) = ReleaseInfo(
        source = source,
        tag = tag,
        version = Version.parseOrNull(tag)!!,
        title = tag,
        notes = "",
        pageUrl = "https://example.com/$tag",
        apk = apk?.let { ReleaseAsset("CastHub-$tag.apk", it, 1024L) },
    )

    /**
     * 测试内的便捷入口。刻意不叫 `decide` —— 与待测的顶层函数同名会遮蔽它，
     * 后面的调用到底进了哪一个是靠重载决议猜的，读起来不可靠。
     */
    private fun decideWith(vararg outcomes: SourceOutcome) = decide(
        outcomes = outcomes.toList(),
        current = current,
        currentVersionName = "1.4.0",
    )

    /** 显式指定首选源的入口。 */
    private fun decidePreferring(source: UpdateSource, vararg outcomes: SourceOutcome) = decide(
        outcomes = outcomes.toList(),
        current = current,
        currentVersionName = "1.4.0",
        preferredSource = source,
    )

    @Test
    fun `两站都有新版本时取版本更高的那个`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.4.1"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.5.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.5.0", result.candidate.release.tag)
        assertEquals(UpdateSource.GITHUB, result.candidate.release.source)
    }

    @Test
    fun `Gitee 更靠前时选 Gitee`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.6.0"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.5.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals(UpdateSource.GITEE, result.candidate.release.source)
    }

    @Test
    fun `同一个源里取最高版本而不是第一条`() {
        // 两站列表都按发布时间倒序，热修复版本可能排在后面
        val result = decideWith(
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(
                    rel(UpdateSource.GITHUB, "v1.5.0"),
                    rel(UpdateSource.GITHUB, "v1.6.0"),
                    rel(UpdateSource.GITHUB, "v1.4.9"),
                ),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.6.0", result.candidate.release.tag)
    }

    @Test
    fun `远端与本地同版本时判为已是最新`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.4.0"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.4.0"))),
        )
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("v1.4.0", (result as UpdateCheckResult.UpToDate).latestTag)
    }

    @Test
    fun `本地比远端新时（开发版）不提示升级`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.3.9"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.3.8"))),
        )
        assertTrue(result is UpdateCheckResult.UpToDate)
    }

    @Test
    fun `一个源失败时仍要出结论 但必须带上告警`() {
        // 否则一句"已是最新"可能是错的：另一个源上也许有更新的版本没取到
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.4.0"))),
            SourceOutcome(UpdateSource.GITHUB, error = "连接超时"),
        )
        assertTrue(result is UpdateCheckResult.UpToDate)
        val warnings = result.warnings
        assertEquals(1, warnings.size)
        assertTrue(warnings.first().contains("GitHub"))
        assertTrue(warnings.first().contains("连接超时"))
    }

    @Test
    fun `一个源失败时另一源发现的更新照常提示`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(UpdateSource.GITHUB, error = "访问受限（403）"),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.5.0", result.candidate.release.tag)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `两个源都失败时报错 且两边的失败原因都要带出来`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, error = "连接超时"),
            SourceOutcome(UpdateSource.GITHUB, error = "域名解析失败，请检查网络"),
        )
        result as UpdateCheckResult.Failed
        assertTrue(result.message.contains("Gitee"))
        assertTrue(result.message.contains("连接超时"))
        assertTrue(result.message.contains("GitHub"))
        assertTrue(result.message.contains("域名解析失败"))
    }

    @Test
    fun `两站都取到但都还没发布过版本时算已是最新`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, emptyList()),
            SourceOutcome(UpdateSource.GITHUB, emptyList()),
        )
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertNull((result as UpdateCheckResult.UpToDate).latestTag)
    }

    @Test
    fun `最优版本没挂 APK 时可以从另一个源取同版本的包`() {
        // 两站 Release 常不同步：Gitee 发了版本但没挂包、GitHub 挂了包。
        // 若坚持版本与包必须同源，就会出现"明明有包却只能跳网页"。
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.5.0", result.candidate.release.tag)
        assertEquals("https://github.com/a/pkg.apk", result.candidate.apkUrl)
        assertEquals(UpdateSource.GITHUB, result.candidate.apkSource)
    }

    @Test
    fun `同源有包时不动用另一源的包`() {
        val result = decideWith(
            SourceOutcome(
                UpdateSource.GITEE,
                listOf(rel(UpdateSource.GITEE, "v1.5.0", apk = "https://gitee.com/a/pkg.apk")),
            ),
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("https://gitee.com/a/pkg.apk", result.candidate.apkUrl)
    }

    @Test
    fun `两站都没挂 APK 时 apkUrl 为 null —— 由界面引导去 Release 页`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.5.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertNull(result.candidate.apkUrl)
        assertNull(result.candidate.apkSource)
    }

    @Test
    fun `不带 v 前缀的 tag 一样能比较`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "1.4.1"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("1.4.1", result.candidate.release.tag)
    }

    // ───────────────── Gitee 优先 ─────────────────

    @Test
    fun `两站同版本时以 Gitee 为准`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.5.0"))),
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals(UpdateSource.GITEE, result.candidate.release.source)
    }

    @Test
    fun `两站同版本都挂了包时优先下 Gitee 的包`() {
        val result = decideWith(
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
            ),
            SourceOutcome(
                UpdateSource.GITEE,
                listOf(rel(UpdateSource.GITEE, "v1.5.0", apk = "https://gitee.com/a/pkg.apk")),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("https://gitee.com/a/pkg.apk", result.candidate.apkUrl)
        assertEquals(UpdateSource.GITEE, result.candidate.apkSource)
        assertEquals(
            "https://github.com/a/pkg.apk",
            result.candidate.apkFallbacks.single().url,
        )
    }

    @Test
    fun `Gitee 版本更高时用 Gitee`() {
        // 首选源只在**同版本**时起决定作用，不能因为"优先 Gitee"就无视 GitHub 上更高的版本
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.6.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("v1.6.0", result.candidate.release.tag)
        assertEquals(UpdateSource.GITHUB, result.candidate.release.source)
    }

    @Test
    fun `候选里带着首选源 供界面解释两站版本不一致的原因`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITHUB, listOf(rel(UpdateSource.GITHUB, "v1.5.0"))),
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.6.0"))),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals(UpdateSource.GITEE, result.candidate.preferredSource)
    }

    @Test
    fun `首选 Gitee 但只有 GitHub 挂了包时 仍用 GitHub 的包并把 Gitee 排前面等待降级`() {
        // 反过来的情形（Gitee 挂了包、版本信息来自 GitHub）才是降级的主要场景：
        // Gitee 附件下载抽风时要知道 GitHub 上也有同一个包
        val result = decidePreferring(
            UpdateSource.GITHUB,
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertEquals("https://github.com/a/pkg.apk", result.candidate.apkUrl)
        assertEquals(UpdateSource.GITHUB, result.candidate.apkSource)
    }

    @Test
    fun `只有一个源挂了包时不产生多余的降级项`() {
        val result = decideWith(
            SourceOutcome(UpdateSource.GITEE, listOf(rel(UpdateSource.GITEE, "v1.5.0"))),
            SourceOutcome(
                UpdateSource.GITHUB,
                listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
            ),
        )
        result as UpdateCheckResult.UpdateAvailable
        assertTrue(result.candidate.apkFallbacks.isEmpty())
        assertTrue(!result.candidate.hasFallbackApk)
    }

    @Test
    fun `两站都挂了包时降级顺序按优先级排`() {
        val candidate = (
            decideWith(
                SourceOutcome(
                    UpdateSource.GITEE,
                    listOf(rel(UpdateSource.GITEE, "v1.5.0", apk = "https://gitee.com/a/pkg.apk")),
                ),
                SourceOutcome(
                    UpdateSource.GITHUB,
                    listOf(rel(UpdateSource.GITHUB, "v1.5.0", apk = "https://github.com/a/pkg.apk")),
                ),
            ) as UpdateCheckResult.UpdateAvailable
            ).candidate

        val order = apkTryOrder(candidate)
        assertEquals(UpdateSource.GITEE, order[0].source)
        assertEquals(UpdateSource.GITHUB, order[1].source)
        assertEquals(2, order.size)
    }

    @Test
    fun `源优先级只有一处定义 —— Gitee 必须高于 GitHub`() {
        // 这条断言看着像废话，但它是"Gitee 优先"这个策略的**唯一**守门人：
        // 一旦有人把枚举里的 priority 调反，检查与下载两端会同时悄悄失效
        assertTrue(UpdateSource.GITEE.priority > UpdateSource.GITHUB.priority)
    }
}

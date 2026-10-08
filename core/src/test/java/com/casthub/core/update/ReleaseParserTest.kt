package com.casthub.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReleaseParserTest {

    private val owner = "mycode2025-ui"
    private val repo = "CastHub"

    private fun parseGithub(body: String) =
        ReleaseParser.parse(UpdateSource.GITHUB, owner, repo, body)

    private fun parseGitee(body: String) =
        ReleaseParser.parse(UpdateSource.GITEE, owner, repo, body)

    // ───────────────── GitHub ─────────────────

    @Test
    fun `解析 GitHub Release 并取出 APK 资产`() {
        val json = """
            [
              {
                "html_url": "https://github.com/mycode2025-ui/CastHub/releases/tag/v1.4.0",
                "tag_name": "v1.4.0",
                "name": "CastHub 1.4.0",
                "draft": false,
                "prerelease": false,
                "body": "新增升级检测",
                "assets": [
                  {
                    "name": "CastHub-1.4.0.apk",
                    "browser_download_url": "https://github.com/mycode2025-ui/CastHub/releases/download/v1.4.0/CastHub-1.4.0.apk",
                    "size": 12345678
                  }
                ]
              }
            ]
        """.trimIndent()

        val list = parseGithub(json)
        assertEquals(1, list.size)
        val r = list.first()
        assertEquals("v1.4.0", r.tag)
        assertEquals(listOf(1, 4, 0), r.version.numbers)
        assertEquals("CastHub 1.4.0", r.title)
        assertEquals("新增升级检测", r.notes)
        assertEquals("https://github.com/mycode2025-ui/CastHub/releases/tag/v1.4.0", r.pageUrl)
        assertNotNull(r.apk)
        assertEquals(12_345_678L, r.apk!!.sizeBytes)
    }

    @Test
    fun `草稿与预发布不参与升级`() {
        val json = """
            [
              {"tag_name": "v9.0.0", "draft": true,  "prerelease": false, "assets": []},
              {"tag_name": "v8.0.0", "draft": false, "prerelease": true,  "assets": []},
              {"tag_name": "v1.4.0", "draft": false, "prerelease": false, "assets": []}
            ]
        """.trimIndent()
        val list = parseGithub(json)
        assertEquals(1, list.size)
        assertEquals("v1.4.0", list.first().tag)
    }

    @Test
    fun `tag 解析不出来的条目被整条跳过`() {
        val json = """
            [
              {"tag_name": "nightly-20260101", "assets": []},
              {"tag_name": "", "assets": []},
              {"tag_name": "v1.4.0", "assets": []}
            ]
        """.trimIndent()
        val list = parseGithub(json)
        assertEquals(1, list.size)
        assertEquals("v1.4.0", list.first().tag)
    }

    // ───────────────── Gitee ─────────────────

    @Test
    fun `解析 Gitee Release —— 没有 size 也没有 html_url 也要能拼出页面地址`() {
        val json = """
            [
              {
                "id": 1,
                "tag_name": "v1.5.0",
                "target_commitish": "main",
                "prerelease": false,
                "name": "CastHub 1.5.0",
                "body": "说明",
                "created_at": "2026-10-08T10:00:00+08:00",
                "assets": [
                  {
                    "name": "CastHub-1.5.0.apk",
                    "browser_download_url": "https://gitee.com/mycode2025-ui/CastHub/releases/download/v1.5.0/CastHub-1.5.0.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val list = parseGitee(json)
        assertEquals(1, list.size)
        val r = list.first()
        assertEquals(UpdateSource.GITEE, r.source)
        assertEquals("v1.5.0", r.tag)
        assertEquals(listOf(1, 5, 0), r.version.numbers)
        // Gitee 不返回大小，记 0；界面不能拿它判断"是否下载完"
        assertEquals(0L, r.apk!!.sizeBytes)
        assertEquals(
            "https://gitee.com/mycode2025-ui/CastHub/releases/tag/v1.5.0",
            r.pageUrl,
        )
    }

    @Test
    fun `Gitee 缺少 prerelease 字段时按正式版处理`() {
        val json = """[{"tag_name": "v1.4.0", "assets": []}]"""
        assertEquals(1, parseGitee(json).size)
    }

    // ───────────────── 资产挑选 ─────────────────

    @Test
    fun `同时存在正式包与 debug 包时挑正式包`() {
        // 挑错会让升级后签名不符、装不上，所以这条规则必须钉住
        val json = """
            [
              {
                "tag_name": "v1.4.0",
                "assets": [
                  {"name": "CastHub-1.4.0-debug.apk", "browser_download_url": "https://gitee.com/a/debug.apk"},
                  {"name": "CastHub-1.4.0.apk",       "browser_download_url": "https://gitee.com/a/release.apk"}
                ]
              }
            ]
        """.trimIndent()
        assertEquals("https://gitee.com/a/release.apk", parseGitee(json).first().apk!!.url)
    }

    @Test
    fun `非 apk 资产与非法地址被忽略`() {
        val json = """
            [
              {
                "tag_name": "v1.4.0",
                "assets": [
                  {"name": "source.zip", "browser_download_url": "https://gitee.com/a/src.zip"},
                  {"name": "cast.apk",   "browser_download_url": "not-a-url"},
                  {"name": "README.md",  "browser_download_url": "https://gitee.com/a/readme"}
                ]
              }
            ]
        """.trimIndent()
        assertNull(parseGitee(json).first().apk)
    }

    // ───────────────── 错误响应 ─────────────────

    @Test
    fun `服务端的错误对象要显式抛出而不是当成零个 Release`() {
        // Gitee 对不存在的仓库返回 200 + {"message":"404 Not Found"}。
        // 若把它当成空列表，用户看到的就是静默的"已是最新版本"—— 比报错更糟。
        try {
            parseGitee("""{"message": "404 Not Found"}""")
            fail("应当抛出 ReleaseParseException")
        } catch (e: ReleaseParseException) {
            assertTrue(e.message!!.contains("404 Not Found"))
        }
    }

    @Test
    fun `空数组是合法的 —— 表示仓库还没发布过版本`() {
        assertEquals(0, parseGitee("[]").size)
        assertEquals(0, parseGithub("[]").size)
    }

    @Test
    fun `空响应体抛异常而不是静默通过`() {
        try {
            parseGithub("")
            fail("应当抛出 ReleaseParseException")
        } catch (e: ReleaseParseException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }
}

package com.casthub.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedDownloadHostsTest {

    private fun trusted(scheme: String?, host: String?) =
        TrustedDownloadHosts.isTrusted(scheme, host)

    @Test
    fun `放行两站的正式域名与其子域`() {
        assertTrue(trusted("https", "github.com"))
        assertTrue(trusted("https", "gitee.com"))
        assertTrue(trusted("https", "objects.githubusercontent.com"))
        assertTrue(trusted("https", "raw.githubusercontent.com"))
        assertTrue(trusted("https", "cdn.gitee.com"))
    }

    @Test
    fun `大小写与前导点不影响判断`() {
        assertTrue(trusted("HTTPS", "GitHub.COM"))
        assertTrue(trusted("https", "  GITHUB.COM  "))
        assertTrue(trusted("https", "github.com."))
    }

    @Test
    fun `明文 http 一律拒绝`() {
        assertFalse(trusted("http", "github.com"))
        assertFalse(trusted("ftp", "github.com"))
        assertFalse(trusted(null, "github.com"))
    }

    @Test
    fun `长得像的域名不能被放行`() {
        // 这一组是白名单最容易写错的地方：用 contains 判断的话前两条会被放过
        assertFalse(trusted("https", "github.com.evil.tld"))
        assertFalse(trusted("https", "evilgithub.com"))
        assertFalse(trusted("https", "notgithub.com"))
        assertFalse(trusted("https", "github.com.cn"))
        assertFalse(trusted("https", "githubusercontent.com.evil.tld"))
        assertFalse(trusted("https", "fake-gitee.com"))
        // 全角句点不是域名分隔符，不能靠它伪装
        assertFalse(trusted("https", "github.com。evil.tld"))
    }

    @Test
    fun `空域名或空值拒绝`() {
        assertFalse(trusted("https", null))
        assertFalse(trusted("https", ""))
        assertFalse(trusted("https", "   "))
        assertFalse(trusted(null, null))
    }

    @Test
    fun `裸后缀本身算合法域名`() {
        // endsWith(".github.com") 无法匹配 "github.com" 本身，必须单独判断，
        // 否则主站会被误拒
        assertTrue(trusted("https", "github.com"))
        assertTrue(trusted("https", "gitee.io"))
    }
}

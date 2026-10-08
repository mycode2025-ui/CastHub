package com.casthub.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    private fun v(raw: String): Version =
        Version.parseOrNull(raw) ?: error("应能解析：$raw")

    @Test
    fun `带不带 v 前缀都解析成同一个版本`() {
        assertEquals(v("v1.4.0"), v("1.4.0"))
        assertEquals(v("V1.4.0"), v("1.4.0"))
        assertEquals("1.4.0", v("v1.4.0").core)
    }

    @Test
    fun `数字段按数值比较而不是字符串`() {
        // 字符串比较会认为 "1.9.0" > "1.10.0"，这是升级检测里最经典的错
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("1.4.10") > v("1.4.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
    }

    @Test
    fun `段数不同的版本按缺省补零比较`() {
        assertEquals(0, v("1.4").compareTo(v("1.4.0")))
        assertEquals(0, v("1").compareTo(v("1.0.0.0")))
        assertTrue(v("1.4.1") > v("1.4"))
    }

    @Test
    fun `预发布版本比同号正式版旧`() {
        assertTrue(v("1.4.0-rc1") < v("1.4.0"))
        assertTrue(v("1.4.0-beta") < v("1.4.0"))
        assertTrue(v("1.4.0") > v("1.4.0-rc9"))
    }

    @Test
    fun `预发布标记内部按 semver 规则比较`() {
        // 只有**纯数字**标识符按数值比，且必须用 '.' 分隔才是独立标识符：
        assertTrue(v("1.4.0-rc.10") > v("1.4.0-rc.2"))
        // 含字母的标识符整体按 ASCII 字典序 —— 于是 "rc10" < "rc2"。
        // 这看着反直觉，但 semver 规范就是如此，老老实实照做，
        // 免得与 npm / Gradle 等工具的判断结果不一致。
        assertTrue(v("1.4.0-rc10") < v("1.4.0-rc2"))
        // 纯数字标识符优先级低于含字母的标识符
        assertTrue(v("1.4.0-1") < v("1.4.0-alpha"))
        // 段数少的更旧
        assertTrue(v("1.4.0-rc") < v("1.4.0-rc.1"))
    }

    @Test
    fun `加号后的构建元数据不参与比较`() {
        assertEquals(0, v("1.4.0+build5").compareTo(v("1.4.0")))
        assertEquals(0, v("1.4.0-rc1+exp.sha.5114f85").compareTo(v("1.4.0-rc1")))
    }

    @Test
    fun `解析不了的 tag 返回 null 而不是猜出一个版本号`() {
        assertNull(Version.parseOrNull(null))
        assertNull(Version.parseOrNull(""))
        assertNull(Version.parseOrNull("   "))
        // 非版本 tag：绝不能从中抠出数字当版本，否则升级提示会指向莫名其妙的东西
        assertNull(Version.parseOrNull("nightly-20260101"))
        assertNull(Version.parseOrNull("release-notes"))
        assertNull(Version.parseOrNull("v"))
        assertNull(Version.parseOrNull("1.4.0.5.6.7.8.9.10")) // 段数过多
        assertNull(Version.parseOrNull("999999999999")) // 溢出 int
        assertNull(Version.parseOrNull("1.x.0")) // 段里不是数字
    }

    @Test
    fun `首尾空白被忽略`() {
        assertEquals(v("1.4.0"), v("  v1.4.0  "))
    }

    @Test
    fun `toString 还原出可读版本号`() {
        assertEquals("1.4.0", v("1.4.0").toString())
        assertEquals("1.4.0-rc.1", v("1.4.0-rc.1").toString())
    }
}

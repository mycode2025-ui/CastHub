package com.casthub.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    private fun plain(raw: String, maxLines: Int = 12): String =
        ReleaseNotes.toPlainText(raw, maxLines).text

    @Test
    fun `标题标记被去掉 文字保留`() {
        assertEquals("新增功能\n\n细节", plain("# 新增功能\n\n### 细节"))
    }

    @Test
    fun `粗体斜体与行内代码标记被去掉`() {
        assertEquals(
            "重要：请先看 README，再决定 要不要 升级",
            plain("**重要**：请先看 `README`，再决定 *要不要* 升级"),
        )
    }

    @Test
    fun `表格分隔行整行丢弃 数据行用间隔点连接`() {
        val out = plain(
            """
            | 问题 | 现象 |
            |---|---|
            | 闪退 | 打开就崩 |
            """.trimIndent(),
        )
        assertFalse("表格分隔行不该出现", out.contains("---"))
        assertTrue(out.contains("问题 · 现象"))
        assertTrue(out.contains("闪退 · 打开就崩"))
    }

    @Test
    fun `无序列表转成间隔点 有序列表保留序号`() {
        val lines = plain("- 第一条\n* 第二条\n1. 第三步\n2) 第四步").lines()
        assertEquals("· 第一条", lines[0])
        assertEquals("· 第二条", lines[1])
        assertEquals("1. 第三步", lines[2])
        assertEquals("2. 第四步", lines[3])
    }

    @Test
    fun `链接只保留文字 丢掉落地址`() {
        // 电视上点不了链接，留一串 URL 只会把对话框的行撑爆
        assertEquals("见 发布说明", plain("见 [发布说明](https://github.com/a/b/releases/tag/v1)"))
    }

    @Test
    fun `图片只保留替代文字`() {
        assertEquals("截图", plain("![截图](https://x/y.png)"))
    }

    @Test
    fun `引用标记被去掉`() {
        assertEquals("提示内容", plain("> 提示内容"))
    }

    @Test
    fun `连续空行只保留一个 首尾空行被去掉`() {
        assertEquals("第一段\n\n第二段", plain("\n\n第一段\n\n\n\n第二段\n\n\n"))
    }

    @Test
    fun `超出最大行数时截断并标记`() {
        val result = ReleaseNotes.toPlainText((1..30).joinToString("\n") { "第 $it 行" }, maxLines = 5)
        assertTrue(result.truncated)
        assertEquals(5, result.text.lines().size)
        assertEquals("第 1 行", result.text.lines().first())
        assertEquals("第 5 行", result.text.lines().last())
    }

    @Test
    fun `没有超出时不标记截断`() {
        val result = ReleaseNotes.toPlainText("一行\n\n两行", maxLines = 5)
        assertFalse(result.truncated)
        assertEquals("一行\n\n两行", result.text)
    }

    @Test
    fun `尾部空行被算进截断判断 —— 否则会漏报`() {
        // 正文刚好比上限多一个「空行 + 一节标题」时，裁剪后行数会少于上限，
        // 若靠比对行数判断就会漏报，表现成说明被静默切在标题后面
        val raw = "一\n\n二\n\n三\n\n主要修复：\n\n· 细节一\n· 细节二"
        val result = ReleaseNotes.toPlainText(raw, maxLines = 8)
        assertTrue("「主要修复：」之后还有内容，必须标记为截断", result.truncated)
        assertFalse("末尾不该是空行", result.text.endsWith("\n"))
        assertTrue(result.text.lines().last().isNotBlank())
    }

    @Test
    fun `超长正文不会拖垮清理过程`() {
        val result = ReleaseNotes.toPlainText(
            (1..5000).joinToString("\n") { "行 $it" },
            maxLines = 3,
        )
        assertEquals(3, result.text.lines().size)
        assertTrue(result.truncated)
    }

    @Test
    fun `空白正文返回空串且不算截断`() {
        assertEquals("", plain(""))
        assertEquals("", plain("\n\n  \n\t\n"))
        assertFalse(ReleaseNotes.toPlainText("\n\n  \n").truncated)
    }

    @Test
    fun `纯文本原样保留`() {
        val raw = "修复了投屏偶尔搜不到设备的问题。\n需要重启应用生效。"
        assertEquals(raw, plain(raw))
    }
}

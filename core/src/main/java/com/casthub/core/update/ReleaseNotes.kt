package com.casthub.core.update

/**
 * 把 Release 正文里的 Markdown 清成纯文本。
 *
 * 正文是**网络来的任意文本**，不能指望发布者写得干净（我们自己的 Release 说明
 * 就是带标题、粗体、表格的完整 changelog）。原样塞进对话框会看到
 * `## 新增`、`**粗体**`、`| 问题 | 现象 |` 这种痕迹 —— 看起来像界面坏了。
 *
 * 这里只做"去标记"，不做真正的渲染：对话框是纯文本控件，
 * 而且电视上离得远，把表格重排成花哨版式反而更难读。
 */
object ReleaseNotes {

    /** 表格分隔行：`|---|---|`、`|:--|--:|` 之类，没有任何信息量。 */
    private val TABLE_SEPARATOR = Regex("""^\|?[\s:|-]+\|?$""")

    /** 标题标记 `#`~`######`。 */
    private val HEADING = Regex("""^#{1,6}\s*""")

    /** 列表标记 `- ` / `* ` / `+ `。 */
    private val LIST_MARKER = Regex("""^[-*+]\s+""")

    /** 有序列表 `1. `，把序号保留下来即可。 */
    private val ORDERED_MARKER = Regex("""^(\d+)[.)]\s+""")

    private val IMAGE = Regex("""!\[([^\]]*)]\([^)]*\)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")

    /** 连续空白（半角空格与制表符）。 */
    private val SPACES = Regex("""[ \t]{2,}""")

    /**
     * 清理并可能截断后的正文。
     *
     * [truncated] 必须由这里给出，不能交给调用方去比对行数 ——
     * 尾部空行会被去掉，于是"截断后恰好剩 N 行"和"本来就只有 N 行"看起来一样，
     * 调用方据此判断会漏报。漏报的后果是说明被静默切在半句上，
     * 用户以为界面坏了（实测踩到：说明停在「主要修复：」后面什么都没有）。
     */
    data class Plain(val text: String, val truncated: Boolean)

    /**
     * @param maxLines 最多保留多少行（含用于分段的空行）。
     */
    fun toPlainText(raw: String, maxLines: Int = 12): Plain {
        val cleaned = ArrayList<String>()
        var sawContent = false
        var blankPending = false

        for (rawLine in raw.lineSequence()) {
            // 单条 Release 正文可能有几百行，先按行数兜一层，避免为超长文本白做清理
            if (cleaned.size > MAX_SCAN_LINES) break

            val line = cleanLine(rawLine)
            if (line.isEmpty()) {
                // 只在已有内容之后允许出现空行（用于分段），且不连续堆叠
                if (sawContent) blankPending = true
                continue
            }
            if (blankPending && sawContent) {
                cleaned.add("")
                blankPending = false
            }
            cleaned.add(line)
            sawContent = true
        }

        val truncated = cleaned.size > maxLines
        val limited = if (truncated) cleaned.subList(0, maxLines) else cleaned
        // 截断后再去掉尾部空行，否则最后一个可见行可能是个空白
        return Plain(limited.joinToString("\n").trimEnd(), truncated)
    }

    /** 清理前先按行数兜底的上限。 */
    private const val MAX_SCAN_LINES = 500

    private fun cleanLine(rawLine: String): String {
        var s = rawLine.trim()
        if (s.isEmpty()) return ""

        // 表格分隔行先判掉，否则会被下面的竖线处理变成一串 "·"
        if (TABLE_SEPARATOR.matches(s)) return ""

        s = s.replace(HEADING, "")
        s = s.replace(ORDERED_MARKER, "$1. ")
        s = s.replace(LIST_MARKER, "· ")
        while (s.startsWith(">")) s = s.removePrefix(">").trim()

        s = s.replace(IMAGE, "$1")
        // 链接只留文字：电视上点不了，留一串 URL 只会把行撑爆
        s = s.replace(LINK, "$1")

        // 行内代码、粗体、斜体标记
        s = s.replace("`", "")
        s = s.replace("**", "")
        s = s.replace("__", "")
        s = s.replace("*", "")

        // 表格数据行的竖线改成间隔点，至少能看清是两个字段
        if (s.contains('|')) {
            s = s.split('|').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
        }

        return SPACES.replace(s, " ").trim()
    }
}

package com.casthub.core.update

/**
 * 版本号 —— 数字段 + 可选预发布标记。
 *
 * 只做"判断远端是不是比本机新"这一件事需要的那部分 semver，不引第三方库：
 *   · 支持带不带 `v` 前缀的 tag（`v1.4.0` / `1.4.0`），两站的 tag 习惯不一致；
 *   · 数字段按**数值**比较，因此 `1.10.0 > 1.9.0`（字符串比较会判反）；
 *   · 缺省段补 0，因此 `1.4` 与 `1.4.0` 相等；
 *   · 有预发布标记的更旧：`1.4.0-rc1 < 1.4.0`；
 *   · `+` 之后是构建元数据，semver 规定不参与比较，直接丢弃。
 */
data class Version(
    val numbers: List<Int>,
    val preRelease: List<String> = emptyList(),
) : Comparable<Version> {

    /** 纯数字部分，例如 `1.4.0`；用于界面展示。 */
    val core: String get() = numbers.joinToString(".")

    override fun compareTo(other: Version): Int {
        val len = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until len) {
            val a = numbers.getOrElse(i) { 0 }
            val b = other.numbers.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        // 数字段相同，看预发布标记
        if (preRelease.isEmpty() && other.preRelease.isEmpty()) return 0
        if (preRelease.isEmpty()) return 1 // 正式版 > 预发布版
        if (other.preRelease.isEmpty()) return -1

        val preLen = maxOf(preRelease.size, other.preRelease.size)
        for (i in 0 until preLen) {
            val a = preRelease.getOrNull(i) ?: return -1 // 段数少的更旧
            val b = other.preRelease.getOrNull(i) ?: return 1
            val na = a.toIntOrNull()
            val nb = b.toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // 纯数字标识符优先级低于字母（semver 规则）
                nb != null -> 1
                else -> a.compareTo(b)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    override fun toString(): String =
        if (preRelease.isEmpty()) core else "$core-${preRelease.joinToString(".")}"

    companion object {
        /**
         * `[vV]?<数字段>[-<预发布>][+<构建元数据>]`
         *
         * 用 `matchEntire` 而不是 `find`：宁可判为"解析不了"（从而整条 Release 被跳过），
         * 也不要把 `nightly-20260101` 这类非版本 tag 抠出一段数字当版本 ——
         * 那会让升级提示指向一个完全没有意义的版本号。
         */
        private val PATTERN = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:-([^+]*))?(?:\+(.*))?$""")

        /** 段数上限：正常版本不会超过这个数，超过基本是垃圾数据。 */
        private const val MAX_SEGMENTS = 8

        fun parseOrNull(raw: String?): Version? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val match = PATTERN.matchEntire(text) ?: return null

            val segments = match.groupValues[1].split('.')
            if (segments.size > MAX_SEGMENTS) return null
            val numbers = ArrayList<Int>(segments.size)
            for (segment in segments) {
                // 解析不出整数（含溢出）就整条判废：丢段会让版本号整体错位
                numbers.add(segment.toIntOrNull() ?: return null)
            }
            if (numbers.isEmpty()) return null

            val pre = match.groupValues[2]
                .split('.', '-')
                .filter { it.isNotEmpty() }
            return Version(numbers, pre)
        }
    }
}

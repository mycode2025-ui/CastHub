package com.casthub.dlna.upnp

/**
 * UPnP 的时长/时间点格式转换。
 *
 * UPnP AVTransport 规范使用 `HH:MM:SS` 表示时长与播放位置，
 * 而 Seek 的 Target 允许带小数秒（如 `00:01:23.500`）。
 * 把这组转换独立出来，既避免 DMR/DMC 两侧重复实现，也便于单元测试。
 */
object UpnpTime {

    /**
     * 把 `HH:MM:SS(.ms)` / `MM:SS` / 纯秒数 解析为毫秒。
     * 任何非法输入都返回 0，不抛异常（发送端格式五花八门）。
     */
    fun parse(value: String): Long {
        val text = value.trim()
        if (text.isEmpty()) return 0L
        val parts = text.split(':')
        return runCatching {
            when (parts.size) {
                3 -> parts[0].toLong() * 3_600_000L +
                    parts[1].toLong() * 60_000L +
                    secondsToMillis(parts[2])
                2 -> parts[0].toLong() * 60_000L + secondsToMillis(parts[1])
                else -> secondsToMillis(parts[0])
            }
        }.getOrDefault(0L)
    }

    /** 毫秒转 `HH:MM:SS`。未知时长（<=0）返回 `00:00:00`。 */
    fun format(ms: Long): String {
        if (ms <= 0L) return "00:00:00"
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    private fun secondsToMillis(seconds: String): Long =
        (seconds.toDouble() * 1000.0).toLong()
}

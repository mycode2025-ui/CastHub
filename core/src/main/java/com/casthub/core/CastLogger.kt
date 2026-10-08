package com.casthub.core

/**
 * 统一日志。所有协议模块通过它输出，便于在 UI 上直接看到运行轨迹，
 * 也避免各模块各自打 Logcat 导致排查困难。
 *
 * 内部维护一个定长环形缓冲，避免长时间运行内存增长。
 */
object CastLogger {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    data class Entry(
        val timestampMs: Long,
        val level: Level,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    ) {
        fun format(): String =
            "${timeFormat(timestampMs)} ${level.name.first()} $tag: $message"
    }

    /**
     * `SimpleDateFormat` 不是线程安全的，所以按线程各持一份。
     *
     * 不复用的话每写一条日志都要新建一个 `SimpleDateFormat`（内部要构建
     * `GregorianCalendar` 与规则表，开销不小）。设置页打开日志面板时，
     * 每一条日志都要走一次 `format()`，而 SSDP 通告、GENA NOTIFY 都是高频输出。
     */
    private val formatter: ThreadLocal<java.text.SimpleDateFormat> =
        ThreadLocal.withInitial {
            java.text.SimpleDateFormat(TIME_PATTERN, java.util.Locale.US)
        }

    private const val TIME_PATTERN = "HH:mm:ss.SSS"

    /**
     * `ThreadLocal.get()` 在 Kotlin 眼里是平台类型（可能为 null），直接链式调用会被告警。
     * 这里显式兜一次底：真的取不到就现建一个并存回去。
     */
    private fun timeFormat(timestampMs: Long): String {
        val format = formatter.get()
            ?: java.text.SimpleDateFormat(TIME_PATTERN, java.util.Locale.US).also {
                formatter.set(it)
            }
        return format.format(java.util.Date(timestampMs))
    }

    private const val MAX_ENTRIES = 500

    private val lock = Any()
    private val buffer = ArrayDeque<Entry>(MAX_ENTRIES + 1)
    private val listeners = mutableListOf<(Entry) -> Unit>()

    @Volatile
    var minLevel: Level = Level.DEBUG

    /** 读取当前全部日志（快照）。 */
    fun snapshot(): List<Entry> = synchronized(lock) { buffer.toList() }

    fun clear() = synchronized(lock) { buffer.clear() }

    /** 注册监听（UI 用）。返回注销句柄。 */
    fun addListener(listener: (Entry) -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(listener) }
        return { synchronized(lock) { listeners.remove(listener) } }
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val entry = Entry(System.currentTimeMillis(), level, tag, message, throwable)
        val targets: List<(Entry) -> Unit>
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
            targets = listeners.toList()
        }
        // 同时输出到 Logcat，方便 adb 排查
        when (level) {
            Level.DEBUG -> android.util.Log.d(tag, message)
            Level.INFO -> android.util.Log.i(tag, message)
            Level.WARN -> android.util.Log.w(tag, message, throwable)
            Level.ERROR -> android.util.Log.e(tag, message, throwable)
        }
        targets.forEach { runCatching { it(entry) } }
    }
}

package com.casthub.core

/**
 * 投屏播放模式。
 *
 * ── 为什么只有两种，不做"顺序/列表循环" ──────────────────────
 * 最初的版本做了四种（单曲/顺序/单曲循环/列表循环），真机验证后砍掉了后两种。
 * 原因是**接收端拿不到播放列表**：
 *
 *  - 手机投一集，只发一个 URL（`SetAVTransportURI`）。
 *    电视端只知道"现在在播什么"，**不知道接下来该播什么** ——
 *    那个列表在手机 App 里，协议没有把它传过来。
 *  - `SetNextAVTransportURI` 虽然能传"下一条"，但规范允许接收端忽略，
 *    而实测手机 App 基本不发。
 *  - 于是"顺序播放"永远是空的：投第 1 集时队列里只有它自己，
 *    没有下一条可接（真机日志实测：`队列 1/1，播放结束，无下一条`）。
 *
 * 真正的"顺序/连播"必须在**发送端**（手机 App）做 —— 那里才有列表。
 * 接收端能可靠提供的只有下面两种，它们不依赖任何外部信息。
 *
 * 顺带一条原则：**做不到的模式不要摆给用户**。摆一个不会生效的选项，
 * 比没有这个选项更糟 —— 用户会当成 bug。
 */
enum class PlaybackMode(val label: String) {

    /** 单曲：播完就停在这一条（会话保留，等发送端的下一步指令）。 */
    SINGLE("单曲播放"),

    /** 单曲循环：这一条反复播，直到用户结束投屏。 */
    REPEAT_ONE("单曲循环");

    companion object {
        fun fromName(name: String?): PlaybackMode =
            entries.firstOrNull { it.name == name } ?: SINGLE
    }
}

/**
 * 播放队列。
 *
 * 只服务于一种场景：**记住当前正在播的那一条**，好让"单曲循环"在播完后能重播它。
 *
 * 曾经它还想承担"顺序播放"，为此保留了全部历史 —— 但那样做有两个害处：
 *  1. 同一 URL 投两次会被当成重复项（手机上"重连一次"很常见），
 *     结果循环时反复播同一条；
 *  2. 它给人"我们有播放列表"的错觉，实际上列表根本不存在。
 *
 * 所以现在只留当前条目，历史不留。职责越窄越不容易错。
 */
class PlaybackQueue {

    /** 队列里的一条记录：媒体 + 播放时附带的元数据（标题等）。 */
    data class Entry(val media: MediaInfo, val metaXml: String = "")

    private var currentEntry: Entry? = null

    /**
     * 当前播放模式。写入前会由调用方从设置里读一次（`modeProvider` / `store.playbackMode()`），
     * 所以这里只做存储，不触发任何行为。
     */
    var mode: PlaybackMode = PlaybackMode.SINGLE

    val isEmpty: Boolean get() = currentEntry == null

    /** 当前条目；没有任何播放时返回 null。 */
    val current: Entry? get() = currentEntry

    /**
     * 记录一条新播的内容（每次 `SetAVTransportURI` / `/play` 都调用）。
     *
     * 不做去重：同一 URL 连投两次是**两次独立的播放**，
     * 按 URL 去重会把第二次误判成重复，导致循环时播的是上一条。
     */
    fun onPlayed(media: MediaInfo, metaXml: String = ""): Entry {
        val entry = Entry(media, metaXml)
        currentEntry = entry
        return entry
    }

    /**
     * 只入队不切过去（对应 `SetNextAVTransportURI`）。
     *
     * 保留这个入口是因为**协议层要如实响应**：SCPD 声明了这个动作，
     * 收到却静默丢弃属于"声明了做不到"。这里如实记录并回读给 `GetMediaInfo`，
     * 让发送端知道本机确实收到了 —— 哪怕本机不用它来续播。
     */
    var nextUri: String = ""
        private set

    var nextMetaXml: String = ""
        private set

    fun setNext(uri: String, metaXml: String) {
        nextUri = uri
        nextMetaXml = metaXml
    }

    /**
     * 播完之后该做什么。
     *
     * @return 该重播的条目；`null` 表示播完即止（保持会话，等发送端指令）。
     */
    fun onEndedHandled(): Entry? = when (mode) {
        PlaybackMode.SINGLE -> null
        PlaybackMode.REPEAT_ONE -> currentEntry
    }

    /** 清空。会话真正结束时调用（Stop / 电视端主动退出）。 */
    fun clear() {
        currentEntry = null
        nextUri = ""
        nextMetaXml = ""
    }

    /** 供日志与设置页显示的摘要。 */
    fun summary(): String = when {
        currentEntry == null -> "队列为空"
        mode == PlaybackMode.REPEAT_ONE -> "单曲循环：${currentEntry!!.media.title ?: "当前内容"}"
        else -> "单曲：${currentEntry!!.media.title ?: "当前内容"}"
    }
}

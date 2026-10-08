package com.casthub.core

/**
 * 接收端本地播放控制。
 *
 * DLNA / AirPlay 的协议分工是「控制权在发送端」，接收端照做即可。
 * 但接收端跑在电视上，用户手里拿的是遥控器 —— 想在电视上直接快进是很自然的诉求，
 * 不应该强迫他每次都去掏手机。
 *
 * 本地调整进度**不会**造成两端状态脱节：实测发送端（夸克等）会周期性轮询
 * `GetPositionInfo`，接收端返回的进度一变，手机上的进度条就会自动跟上。
 * 这也是为什么这里可以直接改播放器位置，而不需要额外的事件推送。
 *
 * 只由**接收端**模块实现，纯发送端模块不需要。
 */
interface LocalPlaybackControl {

    fun togglePause(): Boolean = false
    fun setPlaying(play: Boolean): Boolean = false
    fun retryPlayback(): Boolean = false
    fun tracks(): List<MediaTrackChoice> = emptyList()
    fun selectTrack(choice: MediaTrackChoice): Boolean = false
    fun queueEntries(): List<PlaybackQueue.Entry> = emptyList()
    fun queueCurrentId(): String? = null
    fun queueAdd(media: MediaInfo): Boolean = false
    fun queuePlay(id: String): Boolean = false
    fun queueRemove(id: String): Boolean = false
    fun queueMove(id: String, delta: Int): Boolean = false
    fun queueStep(delta: Int): Boolean = false
    fun refreshSubtitleTiming() {}
    fun refreshTransportSettings() {}
    fun playbackDiagnostics(): String = "播放器未启动"
    fun resumeCandidate(): HistoryEntry? = null
    fun seekAbsolute(positionMs: Long): Boolean = false
    fun applyTrackPreferences() {}
    fun externalSubtitle(address: String?, mimeType: String): Boolean = false

    /**
     * 以当前位置为基准快进 / 快退。
     *
     * @param deltaMs 正数快进，负数快退。实现内部负责把结果夹在 [0, duration] 内。
     * @return 是否真的执行了定位。未在播放、位置未知、内容不支持拖动时返回 false。
     */
    fun seekBy(deltaMs: Long): Boolean

    /**
     * 当前内容是否支持定位。
     *
     * 直播流等场景拿不到总时长，此时拖动没有意义。
     * UI 据此给出「当前内容不支持拖动」的提示，而不是按了没反应让用户以为坏了。
     */
    val canSeek: Boolean

    /** 当前播放进度；不可用时返回 null。 */
    fun currentProgress(): PlaybackPosition?

    /**
     * 接收端主动结束这次投屏。
     *
     * ── 为什么必须有它 ──────────────────────────────────────────
     * 协议分工上控制权在发送端，接收端照做即可。但**只按协议分工做**会造出
     * 一个把人锁死的界面：投屏中按返回键只弹信息浮层、没有任何出口，
     * 而发送端迟迟不断开时（手机 App 被杀、锁屏、用户就是想在电视这边停），
     * 用户除了拔电源没有别的办法。电视产品里"退出不了"是最严重的一类缺陷。
     *
     * ── 实现要同时做到两件事 ────────────────────────────────────
     * 1. 停掉本地播放、清空会话（界面退回待机屏）；
     * 2. 让发送端也知道停了（DLNA 推 LastChange，AirPlay 停播放器），
     *    否则手机上的进度条还在走，用户以为还在投。
     *
     * @return 是否真的结束了投屏。当前没有会话时返回 false。
     */
    fun stopCasting(): Boolean
}

data class MediaTrackChoice(val label: String, val type: Int, val group: Int, val index: Int)

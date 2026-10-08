package com.casthub.core

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * 投屏音量闸门。
 *
 * ── 为什么要它 ──────────────────────────────────────────────
 * 发送端（手机 App / iPhone）一开始播放，几乎总是把音量报到最大：
 * DLNA 是 `SetVolume(100)`，AirPlay 是 `/volume?volume=1.0`。
 * 接收端若照字面执行，用户刚点投屏，电视音量就被顶到 100% ——
 * 这是把**发送端的音量刻度**当成了**接收端的音量刻度**。
 * 电视音量是用户拿遥控器一格一格调出来的，被一条网络指令顶满，
 * 是明显的反常识行为。
 *
 * ── 做法 ────────────────────────────────────────────────────
 * 投屏开始时把**当时电视的系统音量**记为上限，之后发送端给的音量
 * 只作为 0..1 的比例，映射到 [0, 上限]。于是：
 * - 用户电视音量 40% → iPhone 音量拉满也只有 40%，不会被吓到；
 * - iPhone 上调音量仍然有效（按比例变小或恢复）；
 * - 投屏结束后音量不会比投屏前更大。
 *
 * 静音时给一个偏低的兜底上限：投屏后完全没声音同样是困惑，
 * 但也不会高过一半。
 */
class VolumeGovernor(context: Context) {

    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 投屏期间允许的最大音量（绝对值，0..max）。负数表示还没快照过。 */
    @Volatile
    private var ceiling: Int = -1

    /**
     * 上一次**由本闸门设置**的音量。负数表示还没设置过。
     *
     * 它的用途是区分"音量的变化是谁造成的"：下一次收到发送端指令时，
     * 若系统音量还等于这个值，说明两次指令之间没人动过音量；
     * 若不等，说明用户用遥控器（或别的途径）手动调过 —— 无论调高还是调低，
     * 都以用户调出的值为新的上限。只靠 `current > ceiling` 不等式只能抓到
     * "调高"，"调低"后发送端重发满音量（换集/重播很常见）会把音量顶回旧上限。
     */
    @Volatile
    private var lastSetByUs: Int = -1

    /**
     * 在投屏开始时调用：把当前系统音量锁定为本次投屏的上限。
     *
     * 必须在**起播瞬间**调用，不能等收到音量指令再取 —— 那时可能已经被顶上去了。
     */
    fun snapshotCeiling() {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val current = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        ceiling = if (current > 0) current else (max / 2).coerceAtLeast(1)
        CastLogger.i(TAG, "本次投屏音量上限锁定为 $ceiling/$max（投屏前系统音量 $current）")
    }

    /** 投屏结束：清掉上限，下次投屏重新以当时的音量为准。 */
    fun reset() {
        ceiling = -1
        lastSetByUs = -1
    }

    /** 按 0..1 的比例设置音量（AirPlay `/volume?volume=`）。 */
    fun apply(fraction: Double) {
        val f = fraction.coerceIn(0.0, 1.0)
        applyMapped(f, "发送端 ${(f * 100).roundToInt()}%")
    }

    /** 按 0..100 的百分比设置音量（DLNA `SetVolume`）。 */
    fun applyPercent(percent: Int) {
        val p = percent.coerceIn(0, 100)
        applyMapped(p / 100.0, "发送端 $p%")
    }

    private fun applyMapped(fraction: Double, source: String) {
        val a = audio ?: return
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val limit = currentCeiling(a, max)
        val target = (fraction * limit).roundToInt().coerceIn(0, max)
        CastLogger.i(TAG, "$source → 系统音量 $target/$max（上限 $limit）")
        set(a, max, target)
    }

    /**
     * 取当前上限，顺带处理两种情况：
     * - 还没快照过（发送端先发音量再发播放地址）：现取一次；
     * - 两次指令之间用户手动调过音量（当前值 ≠ 上次我们设的值）：
     *   以用户调出的值为新上限 —— 否则用户自己调小之后，
     *   发送端重发一次满音量指令就把他顶回旧上限。
     *
     * ⚠️ **音量 0 不能作为新上限**。
     * 一旦上限被跟到 0，之后无论手机把音量条拉到哪里，
     * 算出来的目标都是 `比例 × 0 = 0` —— 电视会一直静默，而手机上的音量条明明在动，
     * 用户只会认为"接收端坏了"。0 多数来自静音/异常状态，不代表用户想要的响度，
     * 因此这里保留原上限，等用户真的把音量调起来再跟随。
     */
    private fun currentCeiling(a: AudioManager, max: Int): Int {
        val current = a.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (ceiling < 0) {
            ceiling = if (current > 0) current else (max / 2).coerceAtLeast(1)
            CastLogger.i(TAG, "未预先快照，现取上限 $ceiling/$max")
        } else if (current <= 0) {
            CastLogger.i(TAG, "系统音量为 $current，不作为新上限（保留 $ceiling）")
        } else if (lastSetByUs >= 0 && current != lastSetByUs && current != ceiling) {
            CastLogger.i(
                TAG,
                "用户已手动调整音量到 $current（上次设 $lastSetByUs），上限跟随（原 $ceiling）",
            )
            ceiling = current
        }
        return ceiling
    }

    /**
     * 发送端主动下发的静音指令（DLNA `SetMute`）。
     *
     * 只用于记录意图与日志。**不再用来阻止解除静音** ——
     * 实测（小米电视 Android 6.0）发送端在用户按音量放大键时会先发
     * `SetMute(1)` 再发 `SetVolume(更大值)`，若此时保护"刚收到的静音"，
     * 音量指令就不会解除静音，用户看到的正是"手机上音量在放大、电视是哑的"。
     *
     * 静音的正确表达方式就是 `SetMute(1)` 或 `SetVolume(0)`；而**音量大于 0 的
     * 指令本身就是"要出声"的明确意图**，理应压过此前的静音状态。
     */
    fun onSenderMute(muted: Boolean) {
        CastLogger.i(TAG, if (muted) "发送端要求静音" else "发送端取消静音")
    }

    /**
     * 写入系统音量。
     *
     * 目标大于 0 时**主动解除流静音**。这不是锦上添花，而是必需：
     * Android 的静音是独立于音量值的标志位，`setStreamVolume` 不会清它。
     * 发送端在用户按音量放大键时往往先发 `SetMute(1)` 再发 `SetVolume(更大值)`，
     * 若只写音量值不解除静音，结果就是"手机音量条在放大、电视始终是哑的" ——
     * 音量数值变了但听不到任何声音。
     */
    private fun set(a: AudioManager, max: Int, value: Int) {
        val clamped = value.coerceIn(0, max)
        if (clamped > 0 && isMuted(a)) {
            runCatching {
                // 用 adjustStreamVolume(ADJUST_UNMUTE) 而不是 setStreamMute：
                // 后者自 API 26 起已废弃，且它会改动隐藏的"静音标志位"，
                // 与系统音量键的行为不完全一致
                a.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
            }.onSuccess { CastLogger.i(TAG, "已解除系统静音（音量指令要求出声）") }
                .onFailure { CastLogger.w(TAG, "解除静音失败：${it.message}") }
        }
        runCatching {
            a.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        }.onSuccess {
            // 只有真的设下去才记录，否则下次会把"设置失败造成的差异"误判成用户手动调整
            lastSetByUs = clamped
        }.onFailure { CastLogger.w(TAG, "设置系统音量失败：${it.message}") }
    }

    private fun isMuted(a: AudioManager): Boolean =
        runCatching { a.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)

    private companion object {
        const val TAG = "VolumeGovernor"
    }
}

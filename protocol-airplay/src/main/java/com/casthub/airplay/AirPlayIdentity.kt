package com.casthub.airplay

import android.content.Context
import com.casthub.core.CastLogger
import java.math.BigInteger
import java.security.MessageDigest

/**
 * AirPlay 身份与能力公告的**唯一真源**。
 *
 * ── 为什么要有这个类 ────────────────────────────────────────────────
 * 同一台设备要在三处对外描述自己：mDNS 的 TXT、`GET /info`、`GET /server-info`。
 * 之前这三处各写各的，结果出现自相矛盾（TXT 说 srcvers=220.68，/info 却写
 * sourceVersion=130.14），而客户端会挑任意一处来判定设备是否可用 —— 自相矛盾的
 * 公告等于把自己排除掉。所以现在一律从这里取，物理上不可能再不一致。
 *
 * ── 字段取值依据（2026-10 真机排查）────────────────────────────────
 * 同网段有两台能被 iPhone 隔空播放面板列出的真实接收端：
 *   · 客厅的小米电视（乐播 SDK）
 *   · 本机 192.168.10.205 上的 basicgo 投屏（同样是乐播 SDK）
 * 两台逐字段一致，构成该网络下**唯一被证实可用**的公告形态。此前 CastHub 与之
 * 有四处差异（features 只有 32 位、缺 pk/pi、model 用了 CastHub），面板因此不列出。
 * 本类对齐的就是这台 basicgo（就在同一台电视上，网络条件完全相同）：
 *
 *   deviceid  = <本机独有、稳定的 MAC 形式标识>   （不用真实 MAC 的原因见 [deviceId]）
 *   features  = 0x5A7FFFF7,0x1E      64 位能力位域（低 32 位 , 高 32 位）
 *   srcvers   = 220.68               协议源版本；130.14 是 iOS 5 时代的号，会被判为过旧
 *   model     = AppleTV2,1           两者相同；用通用名会让部分客户端走"未知设备"分支
 *   flags     = 0x4
 *   vv        = 2
 *   rhd       = 5.6.0.0             服务端版本串，只为 TXT 字段集对齐（见 [RHD]）
 *   pk / pi   = 配对公钥与配对标识（见下方说明）
 *
 * ── 关于 pk / pi（务必读）────────────────────────────────────────
 * 这两项在真机上存在，且**两台不同产品的取值完全相同** —— 说明它们是乐播 SDK 的
 * 固定常量，而非按设备生成的密钥。CastHub **没有实现 AirPlay 2 配对**
 * （`/pair-setup`、`/pair-verify` 一律 404），所以这里发布的是**属于本机自己的
 * 一份身份标识**，而不是照抄别家产品的常量：
 *   · `pk` 由本机标识推导，并**校验是合法的 Ed25519 压缩公钥**（RFC 8032 解码条件），
 *     避免某些客户端在解析阶段直接判为非法设备；
 *   · `pi` 是稳定的 UUID v4。
 * 它表达的是"本机是这样一台设备"，不代表我们能完成配对握手。视频投屏走的是
 * AirPlay 1 的 `/play` + Content-Location 明文流程，不需要配对；但音频/镜像类
 * 会话需要配对，本机不支持 —— 请在设置页向用户如实说明这一边界。
 */
internal object AirPlayIdentity {

    /** 能力位域，文本形态（mDNS TXT 用）。 */
    const val FEATURES_TEXT = "0x5A7FFFF7,0x1E"

    /** 能力位域低 32 位（plist 里 features 是整数，取低 32 位与 TXT 首段一致）。 */
    const val FEATURES_INT: Long = 0x5A7FFFF7L

    /** 协议源版本。 */
    const val SRC_VERSION = "220.68"

    /** 型号标识。 */
    const val MODEL = "AppleTV2,1"

    /** 状态位。 */
    const val FLAGS_TEXT = "0x4"
    const val FLAGS_INT: Long = 0x4

    /** 协议版本。 */
    const val PROTOVERS = "1.0"

    /**
     * AirTunes HTTP 服务版本，仅出现在 mDNS TXT 里。
     *
     * 两台真机参照都带这个字段（basicgo `5.6.0.0`、小米电视 `5.5.20`），我们此前没有，
     * 是 TXT 里唯一还缺的字段。它不承载能力语义（描述的是服务端版本），**纯粹用于字段对齐**：
     * TXT 字段集与同网内可用设备完全一致，避免客户端因为"少字段"走进兼容性分支。
     * 注意：这里**不代表**我们实现了乐播/AirTunes 的那套服务，只是个版本串。
     */
    const val RHD = "5.6.0.0"

    private const val TAG = "AirPlayIdentity"
    private const val PREFS = "casthub_airplay_identity"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PK = "pk"
    private const val KEY_PI = "pi"

    /** 兜底公钥：Ed25519 单位元的合法编码（y=1, sign=0）。仅在推导失败时使用。 */
    private const val FALLBACK_PK =
        "0100000000000000000000000000000000000000000000000000000000000000"

    /**
     * 设备标识（MAC 形式，持久化）。
     *
     * ── 为什么不用真实 wlan0 MAC（看似更"正统"，实则有害）─────────────────
     * deviceid 的语义确实是设备 MAC，两台真机参照也都用各自的真实 MAC。但**本机不行**：
     * 同一台电视上还装着 basicgo 投屏（`net.basicgo.tvcast`），它已经用这台电视的真实
     * MAC `B4:FB:E3:2B:A4:C0` 作为自己的 deviceid。若我们也用真实 MAC：
     *   · 两个 AirPlay 接收端对外自称同一个 deviceid；
     *   · iOS 的设备表按 deviceid 归并，会出现互相顶掉 / 名称错乱；
     *   · 用户同时开着 basicgo 时表现不可预测。
     * 所以这里生成一个**本机独有、稳定**的 MAC 形式标识（本地管理地址位 02），
     * 格式完全合法，客户端不会因此区别对待。
     */
    fun deviceId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let { if (it.isNotBlank()) return it }
        val value = randomMac()
        prefs.edit().putString(KEY_DEVICE_ID, value).apply()
        CastLogger.i(TAG, "AirPlay deviceid = $value")
        return value
    }

    /** 配对公钥（64 位十六进制）。 */
    fun publicKey(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_PK, null)?.let { if (it.length == 64) return it }
        val value = deriveValidEd25519PublicKey(deviceId(context))
        prefs.edit().putString(KEY_PK, value).apply()
        return value
    }

    /** 配对标识（UUID）。 */
    fun pairingId(context: Context): String {
        val prefs = prefs(context)
        prefs.getString(KEY_PI, null)?.let { if (it.length == 36) return it }
        val value = deriveUuid(deviceId(context))
        prefs.edit().putString(KEY_PI, value).apply()
        return value
    }

    /** mDNS TXT 记录。字段名与取值同两份真机参照。 */
    fun txt(context: Context): Map<String, String> = announce(context).txt

    /**
     * 一次性取齐三处公告所需的一切。
     *
     * 为什么要"一次性"：deviceid / pk / pi 都是懒生成并落盘的，如果 mDNS 与 HTTP
     * 各自调用一次、恰好撞上首次生成，就可能出现一边用旧值一边用新值的情况。
     * 模块启动时取一份、贯穿本次运行，最稳妥。
     */
    fun announce(context: Context): Announcement =
        Announcement(deviceId(context), publicKey(context), pairingId(context))

    class Announcement(
        val deviceId: String,
        val publicKeyHex: String,
        val pairingId: String,
    ) {
        /** pk 在 plist 里是二进制（`<data>`），不是十六进制文本。 */
        val publicKeyBytes: ByteArray = ByteArray(32) { i ->
            publicKeyHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

        /** 两个 mDNS 通道（系统 NsdManager / 自实现应答器）共用这一份。 */
        val txt: Map<String, String> = mapOf(
            "deviceid" to deviceId,
            "features" to FEATURES_TEXT,
            "srcvers" to SRC_VERSION,
            "flags" to FLAGS_TEXT,
            "vv" to "2",
            "model" to MODEL,
            "pw" to "false",
            "rhd" to RHD,
            "pk" to publicKeyHex,
            "pi" to pairingId,
        )
    }

    // ───────────────────────── 推导 ─────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomMac(): String = buildString {
        append("02")
        repeat(5) {
            append(":")
            append("%02X".format((0..255).random()))
        }
    }

    private fun sha256(vararg chunks: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        chunks.forEach { md.update(it) }
        return md.digest()
    }

    /**
     * 推导一个**合法的** Ed25519 压缩公钥。
     *
     * 直接拿 32 字节随机数当公钥是不行的：Ed25519 的压缩公钥只有约一半的比特串是
     * 曲线上的合法点，非法值会被解析方直接判为坏设备。这里逐个候选做合法性校验
     * （RFC 8032 的解码条件），平均两次即可命中。
     */
    private fun deriveValidEd25519PublicKey(seed: String): String {
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        for (i in 0 until 512) {
            val candidate = sha256(seedBytes, byteArrayOf((i and 0xFF).toByte()))
            if (isValidEd25519Point(candidate)) {
                return candidate.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            }
        }
        CastLogger.w(TAG, "Ed25519 公钥推导失败，使用兜底值")
        return FALLBACK_PK
    }

    private fun deriveUuid(seed: String): String {
        val h = sha256(seed.toByteArray(Charsets.UTF_8), "pi".toByteArray(Charsets.UTF_8))
        h[6] = (((h[6].toInt() and 0x0F) or 0x40)).toByte() // version 4
        h[8] = (((h[8].toInt() and 0x3F) or 0x80)).toByte() // RFC 4122 variant
        fun hex(from: Int, to: Int) =
            (from until to).joinToString("") { "%02x".format(h[it].toInt() and 0xFF) }
        return "${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}"
    }

    // ───────────────── Ed25519 压缩公钥合法性（RFC 8032 §5.1.3）─────────────────

    private val ED_P: BigInteger = BigInteger.valueOf(2).pow(255) - BigInteger.valueOf(19)

    /** d = -121665 / 121666 mod p */
    private val ED_D: BigInteger =
        BigInteger.valueOf(-121665).mod(ED_P)
            .multiply(BigInteger.valueOf(121666).modInverse(ED_P))
            .mod(ED_P)

    /** sqrt(-1) mod p，用于 vx² = -u 时修正 x。 */
    private val ED_SQRT_M1: BigInteger =
        BigInteger.valueOf(2).modPow(ED_P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), ED_P)

    /**
     * 判断 32 字节是否为合法的 Ed25519 压缩公钥。
     *
     * 步骤：取 y（清掉最高位的符号位，小端）、校验 y < p，解
     * x² = (y² - 1) / (d·y² + 1)，若右侧不是二次剩余则非法；
     * 并对 x = 0 且符号位为 1 的退化编码判非法。
     */
    internal fun isValidEd25519Point(encoded: ByteArray): Boolean {
        if (encoded.size != 32) return false
        val sign = (encoded[31].toInt() ushr 7) and 1
        val yBytes = encoded.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte()
        val y = littleEndianToBigInteger(yBytes)
        if (y >= ED_P) return false

        val y2 = y.multiply(y).mod(ED_P)
        val u = y2.subtract(BigInteger.ONE).mod(ED_P)
        val v = y2.multiply(ED_D).add(BigInteger.ONE).mod(ED_P)
        if (v.signum() == 0) return false

        val v3 = v.modPow(BigInteger.valueOf(3), ED_P)
        val v7 = v.modPow(BigInteger.valueOf(7), ED_P)
        val pow = u.multiply(v7).mod(ED_P)
            .modPow(ED_P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), ED_P)
        var x = u.multiply(v3).mod(ED_P).multiply(pow).mod(ED_P)

        val vx2 = v.multiply(x).multiply(x).mod(ED_P)
        val negU = ED_P.subtract(u).mod(ED_P)
        when (vx2) {
            u -> Unit
            negU -> x = x.multiply(ED_SQRT_M1).mod(ED_P)
            else -> return false
        }
        // 退化编码：x = 0 时符号位必须为 0，否则不是规范编码
        if (x.signum() == 0 && sign == 1) return false
        return true
    }

    /** 小端字节串 → 非负 BigInteger。 */
    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val be = ByteArray(bytes.size + 1) // 前置 0，保证被当作非负数
        for (i in bytes.indices) be[be.size - 1 - i] = bytes[i]
        return BigInteger(be)
    }
}

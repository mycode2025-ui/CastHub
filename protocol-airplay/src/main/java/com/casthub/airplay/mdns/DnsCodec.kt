package com.casthub.airplay.mdns

import com.casthub.core.CastLogger
import java.io.ByteArrayOutputStream

/**
 * mDNS / DNS-SD 报文编解码（只实现 AirPlay 发现所需的部分）。
 *
 * 为什么要自己写：AirPlay 的发现层是 **mDNS（Bonjour）**，和 DLNA 的 SSDP
 * 完全是两套东西 —— SSDP 走 239.255.255.250:1900 且用 HTTP 风格的文本报文，
 * mDNS 走 224.0.0.251:5353 且用**二进制 DNS 报文**。所以不能复用 upnp/ 里的 SSDP。
 *
 * 范围：解析查询的 Question 段 + 构造应答的 PTR/SRV/TXT/A 记录。
 * 不支持压缩指针的**写入**（允许，只是报文稍大），但支持**读取**（iOS 会发）。
 */
internal object DnsCodec {

    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33

    /** mDNS 使用 IN 类，最高位置 1 表示「cache flush」（应答中应带上）。 */
    const val CLASS_IN = 1
    const val CLASS_IN_FLUSH = 0x8001

    /**
     * 写入一个域名（点分标签）。结尾补 0 表示根。
     * 不做压缩 —— mDNS 允许，代价只是报文多几十字节。
     */
    fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    /**
     * 从 [offset] 读一个域名，返回 (域名, **读完这个名字之后的**位置)。
     * 支持压缩指针（`0xC0` 开头的高两位为 11）—— 发送端会用它复用报文里出现过的名字。
     *
     * ⚠️ 返回的结束位置必须指向"这个名字在报文里占用的最后一个字节之后"：
     * 走压缩指针时是**指针本身**之后（被指向的名字在别处，不计入这里的长度）；
     * 未压缩时是终止的 `0` 之后。
     *
     * 曾写成「第一次进循环就 `end = pos`」，结果 `_airplay._tcp.local` 这种多标签名字
     * 只算到第一个标签之后（`_airplay` 之后）。调用方 `parseQuestions` 拿这个位置去读
     * QTYPE/QCLASS，于是读到名字中间；QDCOUNT ≥ 2 时第二个 Question 更是直接读到
     * 越界（实测抛 `StringIndexOutOfBoundsException`）。
     * 单条查询看不出问题（名字本身是对的），所以这个 bug 能一直藏着。
     */
    fun readName(buf: ByteArray, offset: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var pos = offset
        // -1 表示"还没确定名字结束的位置"，只有遇到终止 0 或压缩指针才确定
        var end = -1
        var jumps = 0

        while (true) {
            if (jumps++ > MAX_NAME_JUMPS) break // 防止恶意报文的指针环
            if (pos >= buf.size) break
            val len = buf[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    if (end < 0) end = pos
                    break
                }
                // 压缩指针：低 14 位是相对报文起始的偏移
                (len and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= buf.size) break
                    if (end < 0) end = pos + 2
                    pos = ((len and 0x3F) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                }
                // 0x40 / 0x80 是已废弃的标签类型，视为畸形报文
                (len and 0xC0) != 0 -> break
                else -> {
                    if (pos + 1 + len > buf.size) break
                    labels.add(String(buf, pos + 1, len, Charsets.UTF_8))
                    pos += 1 + len
                }
            }
        }
        if (end < 0) end = pos
        return labels.joinToString(".") to end
    }

    /**
     * TXT 记录：若干条 length-prefixed `key=value`。
     *
     * ⚠️ 单条上限 255 字节（DNS 字符串的长度字段只有 1 字节）。
     * 早先的写法是 `write(bytes.size.coerceAtMost(255))` 之后仍写 255 字节 ——
     * 长度字段与实际内容不符，报文直接损坏；若截断点落在多字节 UTF-8 字符中间，
     * 还会产生非法序列。这里改为**整条跳过并告警**：
     * 按 RFC 6763，`key=value` 不允许跨字符串拆开，截断后的值会被对端当成有效值用，
     * 比"这个字段不存在"更危险（例如一个被截短的 `pk`）。
     */
    fun writeTxt(out: ByteArrayOutputStream, entries: List<String>) {
        val body = ByteArrayOutputStream()
        for (entry in entries) {
            val bytes = entry.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_TXT_ENTRY_BYTES) {
                CastLogger.w(
                    TAG,
                    "TXT 条目超过 ${MAX_TXT_ENTRY_BYTES} 字节，已丢弃：${entry.take(24)}…",
                )
                continue
            }
            body.write(bytes.size)
            body.write(bytes)
        }
        if (body.size() == 0) {
            out.write(0) // 空 TXT 也要占一个字节，否则部分解析器报错
        } else {
            out.write(body.toByteArray())
        }
    }

    // ───────────────────────── 记录构造 ─────────────────────────

    fun srvRdata(priority: Int, weight: Int, port: Int, target: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(priority shr 8); out.write(priority and 0xFF)
        out.write(weight shr 8); out.write(weight and 0xFF)
        out.write(port shr 8); out.write(port and 0xFF)
        writeName(out, target)
        return out.toByteArray()
    }

    fun ptrRdata(target: String): ByteArray {
        val out = ByteArrayOutputStream()
        writeName(out, target)
        return out.toByteArray()
    }

    fun txtRdata(entries: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        writeTxt(out, entries)
        return out.toByteArray()
    }

    /**
     * 写一条资源记录。
     *
     * @param flush 是否置 cache-flush 位。mDNS 规定**唯一记录**（SRV/TXT/A 这类
     *   一台设备只有一条的）在应答里应置该位，让其它缓存中的旧记录立刻失效。
     */
    fun writeRecord(
        out: ByteArrayOutputStream,
        name: String,
        type: Int,
        ttl: Int,
        rdata: ByteArray,
        flush: Boolean,
    ) {
        writeName(out, name)
        out.write(type shr 8); out.write(type and 0xFF)
        val cls = if (flush) CLASS_IN_FLUSH else CLASS_IN
        out.write(cls shr 8); out.write(cls and 0xFF)
        out.write(ttl shr 24); out.write(ttl shr 16); out.write(ttl shr 8); out.write(ttl)
        out.write(rdata.size shr 8); out.write(rdata.size and 0xFF)
        out.write(rdata)
    }

    // ───────────────────────── 报文组装 ─────────────────────────

    /**
     * 组装一个 mDNS **应答**报文。
     *
     * @param answers 应答段记录
     * @param additional 附加段记录（SRV/TXT/A 放这里，客户端就不必再发一轮查询）
     */
    fun buildResponse(
        answers: List<Record>,
        additional: List<Record> = emptyList(),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header：ID=0、flags=0x8400（QR=1 应答 + AA=1 权威）、各计数
        out.write(0); out.write(0)
        out.write(0x84); out.write(0x00)
        out.write(0); out.write(0) // QDCOUNT
        out.write(answers.size shr 8); out.write(answers.size and 0xFF)
        out.write(0); out.write(0) // NSCOUNT
        out.write(additional.size shr 8); out.write(additional.size and 0xFF)

        answers.forEach { writeRecord(out, it.name, it.type, it.ttl, it.rdata, it.flush) }
        additional.forEach { writeRecord(out, it.name, it.type, it.ttl, it.rdata, it.flush) }
        return out.toByteArray()
    }

    /** 解析查询报文，返回所有 (QNAME, QTYPE)。解析失败返回空列表。 */
    fun parseQuestions(packet: ByteArray): List<Question> {
        if (packet.size < 12) return emptyList()
        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        // QDCOUNT 来自网络、最大可声明 65535：按它预分配会白吃几百 KB，先夹一下
        val result = ArrayList<Question>(qdCount.coerceAtMost(16))
        var pos = 12
        repeat(qdCount) {
            if (pos >= packet.size) return@repeat
            val (name, end) = readName(packet, pos)
            pos = end
            if (pos + 4 > packet.size) return@repeat
            val qtype = ((packet[pos].toInt() and 0xFF) shl 8) or (packet[pos + 1].toInt() and 0xFF)
            pos += 4 // QTYPE + QCLASS
            result.add(Question(name, qtype))
        }
        return result
    }

    class Record(
        val name: String,
        val type: Int,
        val rdata: ByteArray,
        val ttl: Int = 120,
        val flush: Boolean = true,
    )

    class Question(val name: String, val type: Int)

    private const val TAG = "DnsCodec"

    /** DNS 字符串（TXT 条目）的长度字段只有 1 字节。 */
    private const val MAX_TXT_ENTRY_BYTES = 255

    /** 压缩指针的最大跳转次数，用于截断恶意构造的指针环。 */
    private const val MAX_NAME_JUMPS = 16
}

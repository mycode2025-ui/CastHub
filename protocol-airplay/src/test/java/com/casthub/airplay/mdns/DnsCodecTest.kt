package com.casthub.airplay.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * mDNS / DNS-SD 报文的编解码测试。
 *
 * 这一层是纯字节操作、又直接决定 iPhone 能不能发现本机，出错的代价很高
 * （字段对不上时 iOS 既不采纳也不报错，只表现为"设备列表里没有它"），
 * 所以固定住几条不变量。
 */
class DnsCodecTest {

    @Test
    fun `TXT 记录按长度前缀切分且可还原`() {
        val entries = listOf("deviceid=02:AA:BB:CC:DD:EE", "features=0x5A7FFFF7,0x1E", "vv=2")
        val rdata = DnsCodec.txtRdata(entries)
        assertEquals(entries, decodeTxt(rdata))
    }

    @Test
    fun `超过 255 字节的 TXT 条目被整条丢弃而不是截断`() {
        // DNS 字符串的长度字段只有 1 字节。旧实现写「255 再跟 255 字节」，
        // 长度与实际内容不符 -> 报文损坏；若按字节截断还会切出非法 UTF-8。
        val normal = "deviceid=02:AA:BB:CC:DD:EE"
        val oversized = "pk=" + "a".repeat(300)

        val rdata = DnsCodec.txtRdata(listOf(oversized, normal))
        val decoded = decodeTxt(rdata)

        assertEquals(listOf(normal), decoded)
        assertFalse("超长条目不应以任何形式出现", decoded.any { it.length > 255 })
        // 报文本身必须仍然合法：每个字符串的长度字段都要等于其后实际字节数
        assertEquals(rdata.size, decoded.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 })
    }

    @Test
    fun `空 TXT 也要占一个字节`() {
        // 部分解析器遇到零长度的 TXT rdata 会直接报错
        assertEquals(1, DnsCodec.txtRdata(emptyList()).size)
    }

    @Test
    fun `域名编解码可往返`() {
        val out = ByteArrayOutputStream()
        DnsCodec.writeName(out, "_airplay._tcp.local")
        val bytes = out.toByteArray()
        val (name, end) = DnsCodec.readName(bytes, 0)
        assertEquals("_airplay._tcp.local", name)
        assertEquals(bytes.size, end)
    }

    @Test
    fun `能解析查询报文的 Question 段并读回压缩指针`() {
        // 构造一个带压缩指针的查询：第二个 QNAME 指回偏移 12 处的第一个名字。
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0)              // ID
        out.write(0x00); out.write(0x00)        // flags
        out.write(0); out.write(2)              // QDCOUNT = 2
        out.write(0); out.write(0)              // ANCOUNT
        out.write(0); out.write(0)              // NSCOUNT
        out.write(0); out.write(0)              // ARCOUNT

        val first = ByteArrayOutputStream()
        DnsCodec.writeName(first, "_airplay._tcp.local")
        val firstBytes = first.toByteArray()
        out.write(firstBytes)
        out.write(0); out.write(DnsCodec.TYPE_PTR); out.write(0); out.write(1)   // QTYPE/QCLASS

        out.write(0xC0); out.write(12)          // 指向偏移 12 的第一个名字
        out.write(0); out.write(DnsCodec.TYPE_TXT); out.write(0); out.write(1)

        val questions = DnsCodec.parseQuestions(out.toByteArray())

        assertEquals(2, questions.size)
        assertEquals("_airplay._tcp.local", questions[0].name)
        assertEquals(DnsCodec.TYPE_PTR, questions[0].type)
        assertEquals("_airplay._tcp.local", questions[1].name)
        assertEquals(DnsCodec.TYPE_TXT, questions[1].type)
    }

    @Test
    fun `解析畸形报文返回空列表而不是抛异常`() {
        assertTrue(DnsCodec.parseQuestions(ByteArray(0)).isEmpty())
        assertTrue(DnsCodec.parseQuestions(ByteArray(5)).isEmpty())
    }

    @Test
    fun `应答报文头部计数与实际记录数一致`() {
        val bytes = DnsCodec.buildResponse(
            answers = listOf(
                DnsCodec.Record("_airplay._tcp.local", DnsCodec.TYPE_PTR, DnsCodec.ptrRdata("x")),
            ),
            additional = listOf(
                DnsCodec.Record("x", DnsCodec.TYPE_SRV, DnsCodec.srvRdata(0, 0, 7000, "h.local")),
                DnsCodec.Record("h.local", DnsCodec.TYPE_A, byteArrayOf(10, 0, 0, 1)),
            ),
        )
        // QR=1 + AA=1 -> 0x8400
        assertEquals(0x84, bytes[2].toInt() and 0xFF)
        assertEquals(0x00, bytes[3].toInt() and 0xFF)
        assertEquals(1, ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)) // ANCOUNT
        assertEquals(2, ((bytes[10].toInt() and 0xFF) shl 8) or (bytes[11].toInt() and 0xFF)) // ARCOUNT
    }

    /** 把 TXT 的 rdata 还原成字符串列表（长度前缀分割）。 */
    private fun decodeTxt(rdata: ByteArray): List<String> {
        val result = mutableListOf<String>()
        var pos = 0
        while (pos < rdata.size) {
            val len = rdata[pos].toInt() and 0xFF
            if (len == 0) break
            result.add(String(rdata, pos + 1, len, Charsets.UTF_8))
            pos += 1 + len
        }
        return result
    }
}

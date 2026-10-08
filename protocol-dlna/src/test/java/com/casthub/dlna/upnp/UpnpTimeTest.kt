package com.casthub.dlna.upnp

import org.junit.Assert.assertEquals
import org.junit.Test

/** 时间格式转换测试（DLNA 发送端给出的时间格式并不统一）。 */
class UpnpTimeTest {

    @Test
    fun `解析标准 HH_MM_SS`() {
        assertEquals(0L, UpnpTime.parse("00:00:00"))
        assertEquals(83_000L, UpnpTime.parse("00:01:23"))
        assertEquals(3_723_000L, UpnpTime.parse("01:02:03"))
    }

    @Test
    fun `解析带毫秒的秒字段`() {
        assertEquals(1_500L, UpnpTime.parse("00:00:01.500"))
        assertEquals(90_250L, UpnpTime.parse("00:01:30.250"))
    }

    @Test
    fun `解析 MM_SS 与纯秒数`() {
        assertEquals(90_000L, UpnpTime.parse("01:30"))
        assertEquals(45_000L, UpnpTime.parse("45"))
        assertEquals(2_500L, UpnpTime.parse("2.5"))
    }

    @Test
    fun `非法输入返回零而不抛异常`() {
        assertEquals(0L, UpnpTime.parse(""))
        assertEquals(0L, UpnpTime.parse("   "))
        assertEquals(0L, UpnpTime.parse("abc"))
        assertEquals(0L, UpnpTime.parse("::"))
    }

    @Test
    fun `格式化输出对齐 UPnP 规范`() {
        assertEquals("00:00:00", UpnpTime.format(0))
        assertEquals("00:00:00", UpnpTime.format(-1))
        assertEquals("00:01:23", UpnpTime.format(83_000))
        assertEquals("01:02:03", UpnpTime.format(3_723_000))
        // 毫秒向下取整到秒
        assertEquals("00:00:01", UpnpTime.format(1_999))
    }

    @Test
    fun `解析与格式化可往返`() {
        val samples = listOf(0L, 1_000L, 83_000L, 3_723_000L, 7_200_000L)
        samples.forEach { ms ->
            assertEquals(ms, UpnpTime.parse(UpnpTime.format(ms)))
        }
    }
}

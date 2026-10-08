package com.casthub.dlna.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SOAP 报文编解码的单元测试（纯 JVM，无需设备）。 */
class SoapTest {

    @Test
    fun `从 SOAPACTION 头解析动作名`() {
        val header = "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        assertEquals("SetAVTransportURI", Soap.parseActionName(header))
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", Soap.parseServiceType(header))
    }

    @Test
    fun `非法 SOAPACTION 返回 null 而不是抛异常`() {
        assertNull(Soap.parseActionName(null))
        assertNull(Soap.parseActionName("garbage"))
        assertNull(Soap.parseServiceType(""))
    }

    @Test
    fun `服务类型 URN 解析出服务名而不是版本号`() {
        // "urn:schemas-upnp-org:service:AVTransport:1" 按 ':' 切分后
        // 下标 3 才是服务名，下标 4 是版本号。曾把日志打成 "SOAP 1:Play"。
        assertEquals(
            "AVTransport",
            Soap.serviceNameOf("urn:schemas-upnp-org:service:AVTransport:1"),
        )
        assertEquals(
            "RenderingControl",
            Soap.serviceNameOf("urn:schemas-upnp-org:service:RenderingControl:1"),
        )
        assertEquals(
            "ConnectionManager",
            Soap.serviceNameOf("urn:schemas-upnp-org:service:ConnectionManager:1"),
        )
        assertNull(Soap.serviceNameOf(null))
        assertNull(Soap.serviceNameOf("garbage"))
    }

    @Test
    fun `解析请求入参`() {
        val body = """
            <?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>http://10.0.0.1/video.m3u8?token=abc</CurrentURI>
                  <CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;dc:title&gt;测试&lt;/dc:title&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val args = Soap.parseArguments(body)

        assertEquals("0", args["InstanceID"])
        assertEquals("http://10.0.0.1/video.m3u8?token=abc", args["CurrentURI"])
        // 元数据是转义后的 XML，解析后应还原
        assertTrue(args["CurrentURIMetaData"]!!.contains("<DIDL-Lite>"))
        assertTrue(args["CurrentURIMetaData"]!!.contains("测试"))
    }

    @Test
    fun `构造响应时对文本做 XML 转义`() {
        val xml = Soap.response(
            "urn:schemas-upnp-org:service:AVTransport:1",
            "GetPositionInfo",
            mapOf("TrackURI" to "http://a/b?x=1&y=2", "RelTime" to "00:01:00"),
        )
        assertTrue(xml.contains("GetPositionInfoResponse"))
        // & 必须被转义，否则 XML 非法
        assertTrue(xml.contains("x=1&amp;y=2"))
        assertTrue(xml.contains("<RelTime>00:01:00</RelTime>"))
    }

    @Test
    fun `转义与反转义可往返`() {
        val raw = """<a href="x">A & B</a>"""
        assertEquals(raw, Soap.unescape(Soap.escape(raw)))
    }

    @Test
    fun `Fault 报文包含错误码与描述`() {
        val fault = Soap.fault(401, "Invalid Action")
        assertTrue(fault.contains("<errorCode>401</errorCode>"))
        assertTrue(fault.contains("Invalid Action"))
        assertTrue(fault.contains("UPnPError"))
    }
}

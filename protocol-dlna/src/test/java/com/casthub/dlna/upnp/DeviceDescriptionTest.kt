package com.casthub.dlna.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设备描述与 SCPD 的单元测试：确保关键字段不缺失（缺失会导致发送端搜不到设备）。 */
class DeviceDescriptionTest {

    @Test
    fun `device_xml_含全部必要字段`() {
        val xml = DeviceDescription.deviceXml("测试设备", "uuid:test-udn")

        assertTrue("deviceType 必须是 MediaRenderer:1", xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue("必须包含 UDN", xml.contains("<UDN>uuid:test-udn</UDN>"))
        assertTrue("必须包含 friendlyName", xml.contains("<friendlyName>测试设备</friendlyName>"))
        assertTrue("必须声明 AVTransport", xml.contains("service:AVTransport:1"))
        assertTrue("必须声明 RenderingControl", xml.contains("service:RenderingControl:1"))
        assertTrue("必须声明 ConnectionManager", xml.contains("service:ConnectionManager:1"))
        assertTrue("必须给出控制地址", xml.contains("<controlURL>/control/AVTransport</controlURL>"))
    }

    @Test
    fun `device_xml_对设备名做转义`() {
        val xml = DeviceDescription.deviceXml("A & B <电视>", "uuid:x")
        assertTrue(xml.contains("A &amp; B &lt;电视&gt;"))
        assertFalse("未转义的裸 & 会让 XML 非法", xml.contains("A & B"))
    }

    @Test
    fun `Sink_能力列表覆盖关键格式`() {
        val sink = DeviceDescription.SINK_PROTOCOL_INFO
        // 这几项缺失会导致发送端拒绝推流或设备不出现在列表里
        assertTrue("需支持 mp4", sink.contains("video/mp4"))
        assertTrue("需支持 HLS", sink.contains("mpegurl"))
        assertTrue("需支持 mkv", sink.contains("x-matroska"))
        assertTrue("需有通配兜底", sink.contains("video/*"))
    }

    @Test
    fun `SCPD_包含核心动作`() {
        assertTrue(DeviceDescription.AVTRANSPORT_SCPD.contains("<name>SetAVTransportURI</name>"))
        assertTrue(DeviceDescription.AVTRANSPORT_SCPD.contains("<name>Play</name>"))
        assertTrue(DeviceDescription.AVTRANSPORT_SCPD.contains("<name>GetPositionInfo</name>"))
        assertTrue(DeviceDescription.RENDERING_CONTROL_SCPD.contains("<name>SetVolume</name>"))
        assertTrue(DeviceDescription.CONNECTION_MANAGER_SCPD.contains("<name>GetProtocolInfo</name>"))
    }

    @Test
    fun `SCPD_为合法_XML_结构`() {
        // 粗略校验：每个 <scpd> 开闭标签配平
        listOf(
            DeviceDescription.AVTRANSPORT_SCPD,
            DeviceDescription.RENDERING_CONTROL_SCPD,
            DeviceDescription.CONNECTION_MANAGER_SCPD,
        ).forEach { scpd ->
            assertTrue(scpd.trimStart().startsWith("<?xml"))
            assertEquals(
                "actionList 开闭标签数量应一致",
                Regex("<actionList>").findAll(scpd).count(),
                Regex("</actionList>").findAll(scpd).count(),
            )
            assertEquals(
                "serviceStateTable 开闭标签数量应一致",
                Regex("<serviceStateTable>").findAll(scpd).count(),
                Regex("</serviceStateTable>").findAll(scpd).count(),
            )
        }
    }
}

package com.casthub.dlna.dmp

import com.casthub.core.CastDevice
import com.casthub.core.CastLogger
import com.casthub.core.MediaInfo
import com.casthub.core.ProtocolCapability
import com.casthub.dlna.upnp.Soap
import com.casthub.dlna.upnp.UpnpTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * DLNA 控制点（DMC）：发现局域网内的 MediaRenderer 并驱动其播放。
 *
 * 只涉及控制面：拿到对端的设备描述文档，解析出 AVTransport 的控制地址，
 * 然后发 SOAP 请求。媒体流由对端自己去拉，不经过本机。
 */
class DmcClient {

    /** 已知的对端渲染设备：deviceId -> 控制入口 */
    private val endpoints = ConcurrentHashMap<String, RendererEndpoint>()

    private data class RendererEndpoint(
        val deviceId: String,
        val name: String,
        val address: String,
        val controlUrl: String,
    ) {
        fun toCastDevice(): CastDevice = CastDevice(
            id = deviceId,
            name = name,
            protocolId = "dlna",
            address = address,
            port = runCatching { URL(controlUrl).port }.getOrDefault(80),
            isLocal = false,
            capabilities = setOf(ProtocolCapability.RECEIVER),
            extra = mapOf("controlURL" to controlUrl),
        )
    }

    /**
     * 搜索渲染设备。
     *
     * 流程：组播发 M-SEARCH → 收集 200 OK → 逐个拉 device.xml → 解析 AVTransport 控制地址。
     */
    suspend fun discover(timeoutMs: Long): List<CastDevice> = withContext(Dispatchers.IO) {
        val locations = mutableSetOf<String>()

        // 1) 发 M-SEARCH 并收集响应
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                socket.broadcast = true
                val search = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
                    append("\r\n")
                }.toByteArray(Charsets.UTF_8)

                repeat(2) {
                    socket.send(
                        DatagramPacket(
                            search, search.size,
                            InetAddress.getByName("239.255.255.250"), 1900,
                        )
                    )
                }

                val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 15_000L)
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (!text.startsWith("HTTP/1.1 200", ignoreCase = true)) continue
                        text.lineSequence()
                            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?.let { locations.add(it) }
                    } catch (_: java.net.SocketTimeoutException) {
                        // 继续等待剩余时间
                    }
                }
            }
        }.onFailure { CastLogger.w(TAG, "M-SEARCH 失败", it) }

        // 2) 拉取设备描述并结合已知缓存
        locations.forEach { location ->
            runCatching { loadEndpoint(location) }
                .onFailure { CastLogger.d(TAG, "解析设备失败 $location: ${it.message}") }
        }

        delay(100)
        endpoints.values.map { it.toCastDevice() }
    }

    /** 已知设备（不重新搜索）。 */
    fun knownDevices(): List<CastDevice> = endpoints.values.map { it.toCastDevice() }

    /** 投放媒体：SetAVTransportURI 后自动 Play。 */
    fun play(device: CastDevice, media: MediaInfo): Result<Unit> = runCatching {
        val endpoint = endpoints[device.id] ?: error("设备已离线，请重新搜索")
        invoke(endpoint, "AVTransport", "SetAVTransportURI") {
            mapOf(
                "InstanceID" to "0",
                "CurrentURI" to media.uri,
                "CurrentURIMetaData" to buildDidlLite(media),
            )
        }
        invoke(endpoint, "AVTransport", "Play") {
            mapOf("InstanceID" to "0", "Speed" to "1")
        }
    }

    fun pause(deviceId: String): Result<Unit> = runCatching {
        val endpoint = endpoints[deviceId] ?: error("设备已离线")
        invoke(endpoint, "AVTransport", "Pause") { mapOf("InstanceID" to "0") }
    }

    fun resume(deviceId: String): Result<Unit> = runCatching {
        val endpoint = endpoints[deviceId] ?: error("设备已离线")
        invoke(endpoint, "AVTransport", "Play") {
            mapOf("InstanceID" to "0", "Speed" to "1")
        }
    }

    fun stop(deviceId: String): Result<Unit> = runCatching {
        val endpoint = endpoints[deviceId] ?: error("设备已离线")
        invoke(endpoint, "AVTransport", "Stop") { mapOf("InstanceID" to "0") }
    }

    fun seek(deviceId: String, positionMs: Long): Result<Unit> = runCatching {
        val endpoint = endpoints[deviceId] ?: error("设备已离线")
        invoke(endpoint, "AVTransport", "Seek") {
            mapOf(
                "InstanceID" to "0",
                "Unit" to "REL_TIME",
                "Target" to UpnpTime.format(positionMs),
            )
        }
    }

    fun clear() = endpoints.clear()

    // ─────────────────────── 内部 ───────────────────────

    private fun loadEndpoint(location: String): RendererEndpoint? {
        val xml = httpGet(location) ?: return null

        val udn = Regex("<UDN>(.*?)</UDN>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()
            ?: return null

        val friendlyName = Regex("<friendlyName>(.*?)</friendlyName>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        // 在 serviceList 中定位 AVTransport 的 controlURL
        val controlUrl = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport", ignoreCase = true) }
            ?.let { block ->
                Regex("<controlURL>(.*?)</controlURL>", RegexOption.DOT_MATCHES_ALL)
                    .find(block)?.groupValues?.getOrNull(1)?.trim()
            }
            ?: return null

        val base = URL(location)
        val absoluteControl = if (controlUrl.startsWith("http", ignoreCase = true)) {
            controlUrl
        } else {
            val normalized = if (controlUrl.startsWith("/")) controlUrl else "/$controlUrl"
            "${base.protocol}://${base.host}:${base.port}$normalized"
        }

        val endpoint = RendererEndpoint(
            deviceId = udn,
            name = friendlyName.ifBlank { base.host },
            address = base.host,
            controlUrl = absoluteControl,
        )
        endpoints[udn] = endpoint
        CastLogger.i(TAG, "发现渲染设备：${endpoint.name} @${endpoint.address}")
        return endpoint
    }

    private fun invoke(
        endpoint: RendererEndpoint,
        service: String,
        action: String,
        inputs: () -> Map<String, String>,
    ) {
        val serviceType = "urn:schemas-upnp-org:service:$service:1"
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n")
            append("<s:Body>\n")
            append("<u:$action xmlns:u=\"$serviceType\">\n")
            inputs().forEach { (k, v) -> append("<$k>${Soap.escape(v)}</$k>\n") }
            append("</u:$action>\n")
            append("</s:Body>\n")
            append("</s:Envelope>")
        }

        val connection = URL(endpoint.controlUrl).openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        connection.setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val errorText = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            val detail = "HTTP $code ${errorText?.take(200).orEmpty()}".trim()
            CastLogger.w(TAG, "$action 失败：$detail")
            // 必须抛：调用方（play 等）依赖 runCatching 把失败传回 UI，
            // 只打日志会让"投屏失败"被报成成功，SetURI 失败后还会继续发 Play
            throw java.io.IOException("对端拒绝 $action：$detail")
        }
        connection.disconnect()
    }

    private fun httpGet(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 4_000
        connection.readTimeout = 6_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.bufferedReader().use(BufferedReader::readText)
    }.getOrNull()

    private fun buildDidlLite(media: MediaInfo): String {
        val title = Soap.escape(media.title ?: "CastHub Media")
        val mime = media.effectiveMimeType ?: "video/mp4"
        val uri = Soap.escape(media.uri)
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$title</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\">$uri</res>" +
            "</item></DIDL-Lite>"
    }

    companion object {
        private const val TAG = "DmcClient"
        private const val USER_AGENT = "CastHub/1.0 UPnP/1.0"
    }
}

package com.casthub.dlna.upnp

import com.casthub.core.CastLogger
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * UPnP 的 HTTP 服务端：对外提供描述文档、接收 SOAP 控制、处理 GENA 事件订阅。
 *
 * 为什么手写而不是引入 Web 框架：整个协议面只有 4 个端点，
 * 且对 keep-alive、扩展响应头（SID/TIMEOUT/EXT）有特殊要求，
 * 手写反而更短更可控，也让模块不引入额外依赖。
 *
 * 实测：真实的 DLNA 发送端（夸克网盘）会在同一条连接上连续发多个请求
 * （SetAVTransportURI → Play → 周期性 GetPositionInfo），因此必须支持 keep-alive。
 */
class UpnpHttpServer internal constructor(
    private val port: Int,
    private val events: EventSubscriptionManager,
) {

    /** 由 DMR 实现的业务回调。 */
    interface Handler {
        fun deviceXml(): String
        fun scpd(path: String): String?

        /**
         * 处理 SOAP 动作。
         * @return 出参映射；返回 null 表示动作不支持，将回 SOAP Fault。
         */
        fun onControl(service: String, action: String, args: Map<String, String>): Map<String, String>?
    }

    @Volatile
    var handler: Handler? = null

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /**
     * 已接受、还在处理中的连接。
     * [stop] 时必须把它们一起关掉，否则阻塞在 `readLine` 上的 keep-alive
     * 连接要等 120 秒读超时才会自己退出 —— 服务早就重启了，端口被旧连接拖着。
     */
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var running = false

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        serverSocket = server
        running = true
        acceptThread = Thread({ acceptLoop(server) }, "upnp-http").apply {
            isDaemon = true
            start()
        }
        CastLogger.i(TAG, "UPnP HTTP 服务已监听 :$port")
    }

    fun stop() {
        running = false
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        CastLogger.i(TAG, "UPnP HTTP 服务已停止")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            try {
                val client = server.accept()
                Thread({ serve(client) }, "upnp-http-conn").apply {
                    isDaemon = true
                    start()
                }
            } catch (t: Throwable) {
                if (running) CastLogger.d(TAG, "accept 异常（已忽略）: ${t.message}")
            }
        }
    }

    private fun serve(client: Socket) {
        clients.add(client)
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream()

            while (running) {
                val requestLine = readLine(input) ?: break
                if (requestLine.isBlank()) continue

                val segments = requestLine.split(' ')
                val method = segments.getOrElse(0) { "" }.uppercase()
                val path = segments.getOrElse(1) { "/" }.substringBefore('?')

                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        headers[line.substring(0, colon).trim().lowercase()] =
                            line.substring(colon + 1).trim()
                    }
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    // Content-Length 来自网络、完全不可信：直接按它分配数组，
                    // 同网段任意主机一句 `Content-Length: 2000000000` 就能把 App 打 OOM。
                    // 本服务的合法请求体（SOAP 动作）只有几百字节，给足余量后一律拒绝。
                    if (contentLength > MAX_BODY_BYTES) {
                        CastLogger.w(TAG, "拒绝超大请求体：Content-Length=$contentLength")
                        writeText(output, 413, "Payload Too Large", "text/plain", "payload too large")
                        break
                    }
                    readBody(input, contentLength)
                } else {
                    ""
                }

                val keepAlive = dispatch(method, path, headers, body, output)
                if (!keepAlive) break
            }
        } catch (_: java.net.SocketTimeoutException) {
            // 连接空闲超时，正常关闭
        } catch (t: Throwable) {
            if (running) CastLogger.d(TAG, "连接处理异常（已忽略）: ${t.message}")
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    /** @return 是否保持连接 */
    private fun dispatch(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: String,
        output: OutputStream,
    ): Boolean {
        val h = handler
        if (h == null) {
            writeText(output, 503, "Service Unavailable", "text/plain", "handler not ready")
            return false
        }

        when {
            method == "GET" && path == "/device.xml" -> {
                CastLogger.d(TAG, "GET /device.xml")
                writeText(output, 200, "OK", XML_CONTENT_TYPE, h.deviceXml())
                return true
            }

            // device.xml 里声明了 iconList，控制点会真的回来取；取不到会被判为异常设备
            method == "GET" && path.startsWith("/icon-") -> {
                val size = path.removePrefix("/icon-").removeSuffix(".png").toIntOrNull()
                if (size == null) {
                    writeText(output, 404, "Not Found", "text/plain", "not found")
                    return true
                }
                writeBinary(output, 200, "OK", "image/png", DeviceIcon.png(size))
                return true
            }

            method == "GET" -> {
                val scpd = h.scpd(path)
                if (scpd != null) {
                    writeText(output, 200, "OK", XML_CONTENT_TYPE, scpd)
                } else {
                    writeText(output, 404, "Not Found", "text/plain", "not found")
                }
                return true
            }

            method == "POST" && path.startsWith("/control/") -> {
                val soapAction = headers["soapaction"]
                val action = Soap.parseActionName(soapAction)
                val serviceType = Soap.parseServiceType(soapAction)
                val serviceName = Soap.serviceNameOf(serviceType)

                if (action == null || serviceType == null) {
                    CastLogger.w(TAG, "无法解析 SOAPACTION: $soapAction")
                    writeText(output, 500, "Internal Server Error", XML_CONTENT_TYPE,
                        Soap.fault(401, "Invalid Action"))
                    return true
                }

                val args = Soap.parseArguments(body)
                CastLogger.i(TAG, "SOAP ${serviceName ?: "?"}:$action")
                if (action == "SetAVTransportURI") {
                    CastLogger.i(TAG, "  当前 URI = ${args["CurrentURI"]?.take(120)}")
                }

                val outputs = h.onControl(serviceType, action, args)
                if (outputs == null) {
                    writeText(output, 500, "Internal Server Error", XML_CONTENT_TYPE,
                        Soap.fault(401, "Invalid Action"))
                } else {
                    writeText(
                        output, 200, "OK", XML_CONTENT_TYPE,
                        Soap.response(serviceType, action, outputs),
                        extraHeaders = mapOf("EXT" to ""),
                    )
                }
                return true
            }

            method == "SUBSCRIBE" -> {
                // GENA 事件订阅。服务名取自 eventSubURL 末段（/event/AVTransport）。
                val serviceId = path.substringAfterLast('/')
                when (val outcome = events.subscribe(
                    serviceId = serviceId,
                    callbackHeader = headers["callback"],
                    nt = headers["nt"],
                    sid = headers["sid"],
                    timeoutHeader = headers["timeout"],
                )) {
                    is EventSubscriptionManager.SubscribeOutcome.Ok -> {
                        CastLogger.d(TAG, "SUBSCRIBE /$serviceId -> ${outcome.sid}（${outcome.timeout}s）")
                        writeText(
                            output, 200, "OK", "text/plain", "",
                            extraHeaders = mapOf(
                                "SID" to outcome.sid,
                                "TIMEOUT" to "Second-${outcome.timeout}",
                            ),
                        )
                    }

                    // 参数不合法：控制端自己构造错了，回 400
                    EventSubscriptionManager.SubscribeOutcome.BadRequest -> {
                        CastLogger.w(TAG, "SUBSCRIBE 参数不合法：$path")
                        writeText(output, 400, "Bad Request", "text/plain", "")
                    }

                    // SID 不存在（多为订阅已过期）：回 412，提示控制端重新订阅
                    EventSubscriptionManager.SubscribeOutcome.UnknownSid -> {
                        CastLogger.d(TAG, "SUBSCRIBE 的 SID 不存在：$path")
                        writeText(output, 412, "Precondition Failed", "text/plain", "")
                    }
                }
                return true
            }

            method == "UNSUBSCRIBE" -> {
                val removed = events.unsubscribe(headers["sid"])
                writeText(
                    output,
                    if (removed) 200 else 412,
                    if (removed) "OK" else "Precondition Failed",
                    "text/plain",
                    "",
                )
                return true
            }

            method == "NOTIFY" -> {
                writeText(output, 200, "OK", "text/plain", "")
                return true
            }

            else -> {
                CastLogger.d(TAG, "未处理请求 $method $path")
                writeText(output, 404, "Not Found", "text/plain", "not found")
                return true
            }
        }
    }

    // ─────────────────────── HTTP 原语 ───────────────────────

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(b.toChar())
            if (buffer.length > MAX_LINE) return buffer.toString()
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n <= 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun writeText(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: keep-alive\r\n")
            append("Server: Android/UPnP/1.0 CastHub/1.0\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        }
    }

    private fun writeBinary(
        output: OutputStream,
        code: Int,
        reason: String,
        contentType: String,
        payload: ByteArray,
    ) {
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${payload.size}\r\n")
            append("Connection: keep-alive\r\n")
            append("Server: Android/UPnP/1.0 CastHub/1.0\r\n")
            append("\r\n")
        }
        runCatching {
            output.write(header.toByteArray(Charsets.US_ASCII))
            output.write(payload)
            output.flush()
        }
    }

    companion object {
        private const val TAG = "UpnpHttp"
        private const val XML_CONTENT_TYPE = "text/xml; charset=\"utf-8\""
        private const val SOCKET_TIMEOUT_MS = 120_000
        private const val MAX_LINE = 16 * 1024

        /** 请求体上限。SOAP 动作（含转义后的 DIDL-Lite）实测不超过 8KB，256KB 已极宽松。 */
        private const val MAX_BODY_BYTES = 256 * 1024
    }
}

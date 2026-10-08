package com.casthub.airplay

import com.casthub.airplay.plist.Plist
import com.casthub.core.CastLogger
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

/** 播放状态快照，用于 /playback-info。 */
internal data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    /** 0=暂停 1=播放 */
    val rate: Int,
    val ready: Boolean,
)

/**
 * AirPlay 模块与外界的交互面。
 * 由 [AirPlayModule] 实现，HTTP 层只负责协议编解码，不碰播放器。
 */
internal interface AirPlaySink {
    /**
     * @param peerAddress 发起端的 IP。AirPlay 不报设备名，
     *   界面上显示 IP 比显示一个编造出来的 "iPhone" 更可信。
     */
    fun onPlay(url: String, startPositionSec: Double, peerAddress: String)
    fun onStop()
    fun onRate(rate: Double)
    fun onScrub(positionSec: Double)
    fun onVolume(value: Double)
    fun playbackInfo(): PlaybackInfo
}

/**
 * AirPlay 控制服务（TCP 7000）。
 *
 * iPhone 投屏的实际流程：
 *   1. GET  /server-info      —— 拿设备能力（决定要不要列出这台设备）
 *   2. POST /reverse          —— 建立反向连接，接收端用它推送播放事件（101 切换协议）
 *   3. POST /play             —— 请求体里带着播放地址（Content-Location）
 *   4. GET  /playback-info    —— 轮询播放进度
 *   5. POST /scrub、/rate     —— 拖动 / 暂停继续
 *   6. POST /stop             —— 结束
 *
 * 刻意没有做：`/pair-setup`、`/pair-verify` 等 AirPlay 2 配对流程（一律回 404）。
 * 本模块实现的是 AirPlay 1 的**视频**投屏：客户端把媒体地址交给接收端，
 * 接收端自己去拉流播放 —— 这条路不需要配对。
 * 需要配对的会话（音频流、屏幕镜像）本机不支持，且这一点在公告里也没有声明支持。
 *
 * 公告口径（能力位域、版本号、型号）统一由 [AirPlayIdentity] 提供，与 mDNS TXT 同源，
 * 不会再出现"TXT 说 A、/info 说 B"的自相矛盾。
 *
 * ⚠️ 已知的**过度声明**：`features` 采用与真机参照一致的完整掩码，
 * 其中包含镜像（bit7）、音频（bit9）等本机并未实现的能力。这是为了让设备先能被
 * iOS 列出而做的取舍（此前用窄掩码时面板完全不列出本机），
 * 详见 [AirPlayIdentity] 类注释；后续应逐步收窄到真实能力。
 */
internal class AirPlayHttpServer(
    /** 首选端口（AirPlay 惯例 7000）。被占用时会自动改用一个随机空闲端口。 */
    private var port: Int,
    /** 对外公告的身份（deviceid / pk / pi），与 mDNS TXT 取自同一份。 */
    private val announce: AirPlayIdentity.Announcement,
    private val deviceName: String,
) {

    var sink: AirPlaySink? = null

    @Volatile
    private var running = false
    private var server: ServerSocket? = null

    /**
     * 已接受、还在处理中的连接。
     * [stop] 必须主动关闭：`/reverse` 升级成的长连接会阻塞在 read 上
     * （soTimeout 特意设成 0），不主动关就一直挂到对端自己断开。
     */
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    /** 实际监听的端口。启动成功后才有效；mDNS 的 SRV 记录要填这个值。 */
    val actualPort: Int get() = port

    fun start(): Boolean = try {
        server = bind()
        running = true
        thread(name = "airplay-http", isDaemon = true) { acceptLoop(server!!) }
        CastLogger.i(TAG, "AirPlay 控制服务已监听 :$port")
        true
    } catch (t: Throwable) {
        CastLogger.e(TAG, "AirPlay 控制服务启动失败：${t.message}")
        false
    }

    /**
     * 绑定监听端口。
     *
     * 7000 被占用时必须退让而不是报失败：电视上装了不止一个投屏 App 很常见，
     * 硬占端口会让 AirPlay 直接不可用。iPhone 是按 mDNS 的 SRV 记录找过来的，
     * 换端口对它完全透明。
     */
    private fun bind(): ServerSocket = try {
        ServerSocket(port).apply { reuseAddress = true }
    } catch (t: Throwable) {
        CastLogger.w(TAG, "端口 $port 不可用（${t.message}），改用系统分配的空闲端口")
        ServerSocket(0).apply {
            reuseAddress = true
            port = localPort
        }
    }

    fun stop() {
        running = false
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { server?.close() }
        server = null
        CastLogger.i(TAG, "AirPlay 控制服务已停止")
    }

    private fun acceptLoop(sock: ServerSocket) {
        while (running) {
            val client = try {
                sock.accept()
            } catch (_: Throwable) {
                if (running) CastLogger.d(TAG, "accept 结束")
                return
            }
            thread(name = "airplay-conn", isDaemon = true) { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        val peer = client.inetAddress?.hostAddress ?: ""
        clients.add(client)
        try {
            client.soTimeout = 0 // /reverse 是长连接，不能设超时
            val input = client.getInputStream()
            val output = client.getOutputStream()

            while (running && !client.isClosed) {
                val head = readUntilBlankLine(input) ?: return
                val lines = head.lines()
                if (lines.isEmpty()) return
                val requestLine = lines[0]
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val rawPath = parts[1]
                val path = rawPath.substringBefore('?')
                val query = rawPath.substringAfter('?', "")

                val headers = linkedMapOf<String, String>()
                for (i in 1 until lines.size) {
                    val c = lines[i].indexOf(':')
                    if (c > 0) {
                        headers[lines[i].substring(0, c).trim().lowercase(Locale.US)] =
                            lines[i].substring(c + 1).trim()
                    }
                }

                val body = if (headers["transfer-encoding"]?.contains("chunked", true) == true) {
                    // iOS 某些版本（尤其走中继代理时）用 chunked 发 /play，
                    // 只读 Content-Length 会拿到空 body，表现为"点了投屏没反应"
                    readChunked(input)
                } else {
                    val declared = headers["content-length"]?.toIntOrNull() ?: 0
                    // Content-Length 来自网络、不可信：直接按它分配数组会被一句超大头打 OOM。
                    // /play 的请求体（plist）实测只有几百字节，给足余量后一律拒绝。
                    if (declared > MAX_BODY_BYTES) {
                        CastLogger.w(TAG, "拒绝超大请求体：Content-Length=$declared")
                        writeText(output, 413, "Payload Too Large")
                        return
                    }
                    readBody(input, declared)
                }

                // /reverse 会把这条连接"升级"成事件通道，之后不再解析请求，直接持有
                if (path == "/reverse") {
                    writeRaw(
                        output,
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: PTTH/1.0\r\n" +
                            "Connection: Upgrade\r\n\r\n",
                    )
                    CastLogger.d(TAG, "已建立反向连接（/reverse）")
                    // 保持连接：靠读取阻塞，对端断开时 read 返回 -1
                    val buf = ByteArray(1024)
                    while (running && client.isInputShutdown.not()) {
                        if (input.read(buf) < 0) break
                    }
                    return
                }

                dispatch(method, path, query, body, output, peer, headers)

                // AirPlay 用 Connection: keep-alive 复用连接，但简单起见按请求关闭也可以；
                // 这里保持循环以支持 keep-alive
                if (headers["connection"]?.equals("close", true) == true) return
            }
        } catch (t: Throwable) {
            if (running) CastLogger.d(TAG, "连接处理结束：${t.message}")
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun dispatch(
        method: String,
        path: String,
        query: String,
        body: ByteArray,
        output: OutputStream,
        peer: String,
        headers: Map<String, String>,
    ) {
        CastLogger.d(TAG, "$method $path (from $peer)")

        when {
            path.equals("/server-info", true) -> {
                writePlist(output, serverInfo())
            }

            // AirPlay 2 客户端（iOS 16+ / macOS）会先探 /info 再决定连不连。
            // 字段口径与 mDNS TXT、/server-info 完全一致（同源于 AirPlayIdentity）；
            // 真机参照（乐播 SDK）的 /info 返回二进制 plist + AirTunes 版 Server 头，
            // 这里同样如此，避免因格式差异被解析方跳过。
            path.equals("/info", true) -> {
                writeBinaryPlist(output, infoResponse())
            }

            path.equals("/play", true) -> {
                val info = parsePlayRequest(body, headers)
                val url = info?.first
                if (url.isNullOrBlank()) {
                    // 找不到地址时把请求头也记下来：iOS 与安卓客户端放地址的位置不同，
                    // 光看"没解析出地址"根本无从判断是对方没发、还是我们没找对地方。
                    CastLogger.w(
                        TAG,
                        "/play 未解析出播放地址；body ${body.size} 字节，" +
                            "头中的 Content-Location=${headers["content-location"] ?: "(无)"}，" +
                            "全部头=${headers.keys.joinToString()}",
                    )
                    writeText(output, 400, "Bad Request")
                    return
                }
                CastLogger.i(TAG, "▶ /play ${url.take(120)}（起始 ${info.second}s）")
                sink?.onPlay(url, info.second, peer)
                writeText(output, 200, "OK")
            }

            path.equals("/playback-info", true) -> {
                val p = sink?.playbackInfo()
                writePlist(output, playbackInfoPlist(p))
            }

            path.equals("/scrub", true) -> {
                if (method == "GET") {
                    val p = sink?.playbackInfo()
                    writePlist(output, playbackInfoPlist(p))
                } else {
                    // 优先用 query（?position=），其次是请求体里的二进制 plist
                    val pos = query.substringAfter("position=", "")
                        .substringBefore('&').toDoubleOrNull()
                        ?: (Plist.parse(body) as? Map<*, *>)?.get("position")?.toString()
                            ?.toDoubleOrNull()
                        ?: 0.0
                    CastLogger.i(TAG, "⏩ /scrub -> ${pos}s")
                    sink?.onScrub(pos)
                    writeText(output, 200, "OK")
                }
            }

            path.equals("/rate", true) -> {
                val rate = query.substringAfter("value=", "")
                    .substringBefore('&').toDoubleOrNull() ?: 1.0
                CastLogger.i(TAG, "${if (rate > 0) "▶" else "⏸"} /rate -> $rate")
                sink?.onRate(rate)
                writeText(output, 200, "OK")
            }

            path.equals("/stop", true) -> {
                CastLogger.i(TAG, "⏹ /stop")
                sink?.onStop()
                writeText(output, 200, "OK")
            }

            path.equals("/volume", true) -> {
                val v = query.substringAfter("volume=", "")
                    .substringBefore('&').toDoubleOrNull()
                if (v != null) sink?.onVolume(v)
                writeText(output, 200, "OK")
            }

            // 事件通道的对端会向我们 POST /event；内容是状态，暂无用途
            path.equals("/event", true) -> writeText(output, 200, "OK")

            // iOS 在建立会话前后会探这些接口（配对 / 授权 / FairPlay）。
            // 我们确实做不到，**不能假装支持**：假装会让 iOS 走进一条走不通的流程，
            // 最后静默失败，用户只看到"点了没反应"，日志里什么线索都没有。
            // 所以这里明确回 404 并**把路径记下来** ——
            // 下次真机一测，看日志就知道 iOS 到底卡在哪一步。
            path.equals("/pair-setup", true) ||
                path.equals("/pair-verify", true) ||
                path.equals("/fp-setup", true) ||
                path.equals("/auth-setup", true) ||
                path.equals("/authorize", true) ||
                path.equals("/setup", true) -> {
                CastLogger.w(TAG, "⚠️ iOS 请求了 $path，本机不支持该能力（未实现配对/授权）")
                writeText(output, 404, "Not Found")
            }

            else -> {
                CastLogger.w(TAG, "未处理的请求：$method $path")
                writeText(output, 404, "Not Found")
            }
        }
    }

    // ───────────────────────── 请求体解析 ─────────────────────────

    /**
     * 从 `/play` 里取出播放地址与起始位置。
     *
     * ⚠️ 地址有**三种**送达方式，缺一种就会有客户端用不了：
     *  1. 请求体：二进制 / XML plist（多数安卓投屏 App、VLC、老版 iOS）
     *  2. 请求体：JSON（极少数客户端）
     *  3. **HTTP 请求头 `Content-Location` + `Start-Position`**（iOS，含 bilibili 走的 AirPlay）
     *
     * 第 3 种最容易被漏掉：那种请求**body 是空的**，
     * 早期实现一上来就 `if (body.isEmpty()) return null`，
     * 结果 iOS 点了投屏毫无反应 —— 我们回了 400，iOS 静默放弃，
     * 用户只看到"点了没反应"，日志也只有一句看不出所以然的"未解析出地址"。
     *
     * 所以这里**头部优先**：头部有就用头部的，没有再退回 body。
     * 顺序不能反 —— 有些客户端两者都带，body 里的可能已过期。
     *
     * @param body 请求体（可为空）
     * @param headers 已小写化的请求头
     */
    private fun parsePlayRequest(
        body: ByteArray,
        headers: Map<String, String> = emptyMap(),
    ): Pair<String, Double>? {
        // ① 请求头（iOS / bilibili 走这条）
        val headerUrl = headers["content-location"]?.takeIf { it.isNotBlank() }
        if (headerUrl != null) {
            val start = headers["start-position"]?.toDoubleOrNull() ?: 0.0
            return headerUrl to start
        }

        if (body.isEmpty()) return null

        // ② 二进制 / XML plist（dd-plist 自动识别格式，不要再手写 "bplist00" 前缀判断）
        val map = Plist.parse(body) as? Map<*, *>
        if (map != null) {
            val url = map["Content-Location"]?.toString()
                ?: map["content-location"]?.toString()
            if (!url.isNullOrBlank()) {
                val start = map["Start-Position"]?.toString()?.toDoubleOrNull() ?: 0.0
                return url to start
            }
        }

        // ③ JSON（极少数客户端）
        val text = String(body, Charsets.UTF_8)
        if (text.trimStart().startsWith("{")) {
            val url = Regex("\"Content-Location\"\\s*:\\s*\"([^\"]+)\"").find(text)
                ?.groupValues?.get(1)
            val start = Regex("\"Start-Position\"\\s*:\\s*([0-9.]+)").find(text)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return url?.let { it to start }
        }

        return null
    }

    // ───────────────────────── 响应构造 ─────────────────────────

    private fun serverInfo(): Map<String, Any?> = mapOf(
        "deviceid" to announce.deviceId,
        "features" to AirPlayIdentity.FEATURES_INT,
        "model" to AirPlayIdentity.MODEL,
        "name" to deviceName,
        "protovers" to AirPlayIdentity.PROTOVERS,
        "srcvers" to AirPlayIdentity.SRC_VERSION,
        "vv" to 2,
        "osvers" to "9.0",
        "macAddress" to announce.deviceId,
    )

    /**
     * `GET /info` 的响应。字段口径与 mDNS TXT、`/server-info` **三处一致**，
     * 全部来自 [AirPlayIdentity]，不可能再出现"TXT 说 220.68、/info 说 130.14"这种
     * 自相矛盾（错误码字段名也按真机参照来：/info 用 `deviceID`，/server-info 用 `deviceid`）。
     */
    private fun infoResponse(): Map<String, Any?> = mapOf(
        "deviceID" to announce.deviceId,
        "macAddress" to announce.deviceId,
        "name" to deviceName,
        "model" to AirPlayIdentity.MODEL,
        "features" to AirPlayIdentity.FEATURES_INT,
        "srcvers" to AirPlayIdentity.SRC_VERSION,
        "sourceVersion" to AirPlayIdentity.SRC_VERSION,
        "protovers" to AirPlayIdentity.PROTOVERS,
        "flags" to AirPlayIdentity.FLAGS_INT,
        "statusFlags" to AirPlayIdentity.FLAGS_INT,
        "vv" to 2,
        // 与 TXT 里发布的 pk/pi 保持一致：两处说法不同会让客户端判定设备异常
        "pk" to announce.publicKeyBytes,
        "pi" to announce.pairingId,
    )

    private fun playbackInfoPlist(p: PlaybackInfo?): Map<String, Any?> {
        val d = p?.durationSec ?: 0.0
        val pos = p?.positionSec ?: 0.0
        val rate = p?.rate ?: 0
        return mapOf(
            "duration" to d,
            "position" to pos,
            "rate" to rate,
            "readyToPlay" to (p?.ready ?: false),
            "playbackBufferEmpty" to true,
            "playbackBufferFull" to false,
            "playbackLikelyToKeepUp" to true,
            "loadedTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to pos)),
            "seekableTimeRanges" to listOf(mapOf("start" to 0.0, "duration" to d)),
        )
    }

    private fun writePlist(output: OutputStream, value: Map<String, Any?>) {
        val xml = Plist.toXml(value).toByteArray(Charsets.UTF_8)
        writeRaw(
            output,
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/x-apple-plist+xml\r\n" +
                "Content-Length: ${xml.size}\r\n" +
                "Connection: keep-alive\r\n" +
                SERVER_HEADER + "\r\n\r\n",
        )
        runCatching { output.write(xml); output.flush() }
    }

    /**
     * 二进制 plist 响应（`/info` 用）。
     *
     * 真机参照的 `/info` 就是二进制 plist；虽然 XML 也合法，但既然要消除变量，
     * 就与参照完全一致。转换失败时**退回 XML**而不是报错 —— /info 一旦失败，
     * 客户端会直接把本机从列表里划掉，不能因为格式偏好把功能搞挂。
     */
    private fun writeBinaryPlist(output: OutputStream, value: Map<String, Any?>) {
        val bytes = Plist.toBinary(value)
        if (bytes == null) {
            CastLogger.w(TAG, "二进制 plist 生成失败，退回 XML")
            writePlist(output, value)
            return
        }
        writeRaw(
            output,
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-apple-binary-plist\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: keep-alive\r\n" +
                SERVER_HEADER + "\r\n\r\n",
        )
        runCatching { output.write(bytes); output.flush() }
    }

    private fun writeText(output: OutputStream, code: Int, reason: String) {
        writeRaw(
            output,
            "HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n",
        )
        runCatching { output.flush() }
    }

    private fun writeRaw(output: OutputStream, text: String) {
        runCatching { output.write(text.toByteArray(Charsets.US_ASCII)) }
    }

    // ───────────────────────── 基础 IO ─────────────────────────

    /** 读到空行为止（HTTP 头结束）。连接关闭返回 null。 */
    private fun readUntilBlankLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        var lastWasLf = false
        var sawCr = false
        while (true) {
            val n = try {
                input.read(buf)
            } catch (_: Throwable) {
                return null
            }
            if (n < 0) return if (sb.isEmpty()) null else sb.toString()
            val c = buf[0].toInt().toChar()
            sb.append(c)
            if (c == '\r') {
                sawCr = true
                continue
            }
            if (c == '\n') {
                if (sawCr && sb.length >= 4 && sb.endsWith("\r\n\r\n")) return sb.toString()
                if (lastWasLf) return sb.toString()
                lastWasLf = true
                sawCr = false
                continue
            }
            lastWasLf = false
            sawCr = false
        }
    }

    private fun readBody(input: java.io.InputStream, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = try {
                input.read(out, read, length - read)
            } catch (_: Throwable) {
                break
            }
            if (n < 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    /** 读取 chunked 编码的请求体。格式：每行十六进制长度 + CRLF + 数据 + CRLF，以 0 长度收尾。 */
    private fun readChunked(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input) // 末块后的空行
                break
            }
            // 与 Content-Length 路径同样的上限：chunked 是"没有长度声明"的分块流，
            // 攻击面更大（可以无限发小块），必须自己封顶，否则同样 OOM。
            if (out.size() + size > MAX_BODY_BYTES) {
                CastLogger.w(TAG, "chunked 请求体超过上限（${MAX_BODY_BYTES} 字节），已截断")
                break
            }
            val buf = ByteArray(size)
            var off = 0
            while (off < size) {
                val n = input.read(buf, off, size - off)
                if (n < 0) break
                off += n
            }
            out.write(buf, 0, off)
            readLine(input) // 数据块后的 CRLF
        }
        return out.toByteArray()
    }

    /** 读一行（不含换行符）。连接关闭返回 null。 */
    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = try {
                input.read()
            } catch (_: Throwable) {
                return null
            }
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(Char(c))
        }
    }

    companion object {
        private const val TAG = "AirPlayHttp"

        /**
         * 与真机参照一致的 Server 头。AirPlay 接收端的惯例是 `AirTunes/<协议源版本>`，
         * 版本号必须与 TXT 的 `srcvers`、`/info` 的 `srcvers` 相同，
         * 三处对不上同样会被判为异常设备。
         */
        private const val SERVER_HEADER = "Server: AirTunes/${AirPlayIdentity.SRC_VERSION}"

        /**
         * 请求体上限（1MB）。
         *
         * `/play` 的请求体是几百字节的 plist，正常客户端远达不到这个量级；
         * 设上限是为了挡住"用超大 Content-Length / 无限 chunked 让接收端 OOM"的请求 ——
         * 这个端口对局域网开放，不能假设请求方一定是善意的 iPhone。
         */
        private const val MAX_BODY_BYTES = 1024 * 1024
    }
}

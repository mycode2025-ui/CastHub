package com.casthub.airplay.mdns

import android.content.Context
import android.net.wifi.WifiManager
import com.casthub.airplay.AirPlayIdentity
import com.casthub.airplay.Net
import com.casthub.core.CastLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketException
import kotlin.concurrent.thread

/**
 * mDNS（Bonjour）服务发布与响应。
 *
 * AirPlay 的发现层：iPhone 在控制面板里打开「屏幕镜像 / AirPlay」时，会向
 * `224.0.0.251:5353` 发 PTR 查询 `_airplay._tcp.local`，只有应答了才会出现在列表里。
 * 这和 DLNA 的 SSDP 是两套协议，无法复用。
 *
 * 两个必须注意的点：
 * 1. **必须持有 MulticastLock** —— Android 在 Wi-Fi 芯片层过滤入站多播包，
 *    没有这把锁时通告能发出去、但**一个查询都收不到**（DLNA 侧踩过同样的坑）。
 * 2. 查询的源端口若不是 5353，要**单播回该端口**（mDNS 的 QU 语义），
 *    否则 iOS 收不到应答，表现就是「设备时有时无」。
 */
internal class MdnsResponder(
    private val context: Context,
    private val deviceName: String,
    private val deviceId: String,
    private val airPlayPort: Int,
) {

    @Volatile
    private var running = false
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null

    private val localIp: String? = Net.localIpv4()

    /**
     * 主机名（SRV 指向的目标）。刻意用纯 ASCII：
     * 设备名可能是中文，而 mDNS 主机名要被对端拿去解析 A 记录，
     * 非 ASCII 标签在不同解析实现下行为不一致（有的按 UTF-8，有的按 Punycode）。
     * 展示用的名字走实例名（[airPlayInstance]），主机名只是寻址用。
     */
    private val hostName = "casthub-${deviceId.filter { it.isLetterOrDigit() }.take(12)}.local"
    private val airPlayInstance = "$deviceName._airplay._tcp.local"

    fun start(): Boolean {
        val ip = localIp
        if (ip == null) {
            CastLogger.e(TAG, "未找到局域网 IPv4 地址，mDNS 无法启动")
            return false
        }

        // Android 会过滤入站多播，必须先取锁（详见类注释）
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        lock = wifi?.createMulticastLock("casthub-airplay-mdns")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
                .onFailure { CastLogger.w(TAG, "获取 Wi-Fi 多播锁失败：${it.message}") }
        }

        return try {
            val sock = MulticastSocket(MDNS_PORT).apply {
                reuseAddress = true
                timeToLive = 255
                joinGroup(InetSocketAddress(InetAddress.getByName(MDNS_GROUP), MDNS_PORT), null)
            }
            socket = sock
            running = true

            thread(name = "airplay-mdns", isDaemon = true) { receiveLoop(sock) }
            thread(name = "airplay-mdns-announce", isDaemon = true) {
                // 上线时主动通告几次：iOS 不一定马上发查询，
                // 重复几次能显著缩短「打开面板才看到设备」的等待。
                repeat(ANNOUNCE_TIMES) {
                    if (!running) return@thread
                    announce(sock, ip)
                    Thread.sleep(ANNOUNCE_INTERVAL_MS)
                }
            }
            CastLogger.i(
                TAG,
                "mDNS 已启动：$airPlayInstance -> $ip:$airPlayPort（组播 $MDNS_GROUP:$MDNS_PORT）",
            )
            true
        } catch (t: Throwable) {
            CastLogger.e(TAG, "mDNS 启动失败：${t.message}")
            false
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { if (lock?.isHeld == true) lock?.release() }
        lock = null
        CastLogger.i(TAG, "mDNS 已停止")
    }

    private fun receiveLoop(sock: MulticastSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = try {
                DatagramPacket(buffer, buffer.size).also { sock.receive(it) }
            } catch (_: SocketException) {
                if (running) CastLogger.d(TAG, "mDNS socket 已关闭")
                return
            } catch (t: Throwable) {
                if (running) CastLogger.d(TAG, "mDNS 接收异常（已忽略）：${t.message}")
                continue
            }

            val data = packet.data.copyOf(packet.length)
            val questions = runCatching { DnsCodec.parseQuestions(data) }
                .onFailure { CastLogger.d(TAG, "无法解析 mDNS 查询：${it.message}") }
                .getOrNull() ?: continue

            val matched = questions.mapNotNull { buildAnswer(it) }
            if (matched.isEmpty()) continue

            val answers = matched.map { it.first }
            val additional = matched.flatMap { it.second }
            val response = DnsCodec.buildResponse(answers, additional)

            val out = DatagramPacket(response, response.size, packet.address, packet.port)
            runCatching { sock.send(out) }
                .onFailure { CastLogger.d(TAG, "mDNS 应答发送失败：${it.message}") }

            CastLogger.d(
                TAG,
                "应答 ${packet.address.hostAddress}:${packet.port} -> " +
                    questions.joinToString(",") { it.name },
            )
        }
    }

    /** 上线通告：把主要记录主动组播出去（不带 Question 段）。 */
    private fun announce(sock: MulticastSocket, ip: String) {
        val answers = listOf(
            DnsCodec.Record(SERVICE_AIRPLAY, DnsCodec.TYPE_PTR, DnsCodec.ptrRdata(airPlayInstance), TTL_PTR),
            DnsCodec.Record(SERVICE_ENUM, DnsCodec.TYPE_PTR, DnsCodec.ptrRdata(SERVICE_AIRPLAY), TTL_PTR),
        )
        val additional = listOf(
            DnsCodec.Record(airPlayInstance, DnsCodec.TYPE_SRV, DnsCodec.srvRdata(0, 0, airPlayPort, hostName)),
            DnsCodec.Record(airPlayInstance, DnsCodec.TYPE_TXT, DnsCodec.txtRdata(txtRecords())),
            DnsCodec.Record(hostName, DnsCodec.TYPE_A, InetAddress.getByName(ip).address),
        )
        val bytes = DnsCodec.buildResponse(answers, additional)
        val packet = DatagramPacket(bytes, bytes.size, InetAddress.getByName(MDNS_GROUP), MDNS_PORT)
        runCatching { sock.send(packet) }
            .onFailure { CastLogger.d(TAG, "mDNS 通告发送失败：${it.message}") }
    }

    /** @return (应答记录, 附加记录)，不匹配返回 null */
    private fun buildAnswer(q: DnsCodec.Question): Pair<DnsCodec.Record, List<DnsCodec.Record>>? {
        val name = q.name.trim('.')
        val ip = localIp ?: return null
        val ipBytes = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return null

        fun full() = listOf(
            DnsCodec.Record(airPlayInstance, DnsCodec.TYPE_SRV, DnsCodec.srvRdata(0, 0, airPlayPort, hostName)),
            DnsCodec.Record(airPlayInstance, DnsCodec.TYPE_TXT, DnsCodec.txtRdata(txtRecords())),
            DnsCodec.Record(hostName, DnsCodec.TYPE_A, ipBytes),
        )

        return when {
            // 「有哪些服务类型」的枚举查询
            name.equals(SERVICE_ENUM.trim('.'), ignoreCase = true) ->
                DnsCodec.Record(
                    SERVICE_ENUM, DnsCodec.TYPE_PTR,
                    DnsCodec.ptrRdata(SERVICE_AIRPLAY), TTL_PTR,
                ) to emptyList()

            // 查询 AirPlay 服务本身
            name.equals(SERVICE_AIRPLAY.trim('.'), ignoreCase = true) ->
                DnsCodec.Record(
                    SERVICE_AIRPLAY, DnsCodec.TYPE_PTR,
                    DnsCodec.ptrRdata(airPlayInstance), TTL_PTR,
                ) to full()

            // 查询本实例的 SRV / TXT / ANY
            name.equals(airPlayInstance.trim('.'), ignoreCase = true) -> when (q.type) {
                DnsCodec.TYPE_SRV ->
                    DnsCodec.Record(
                        airPlayInstance, DnsCodec.TYPE_SRV,
                        DnsCodec.srvRdata(0, 0, airPlayPort, hostName),
                    ) to emptyList()

                DnsCodec.TYPE_TXT ->
                    DnsCodec.Record(
                        airPlayInstance, DnsCodec.TYPE_TXT, DnsCodec.txtRdata(txtRecords()),
                    ) to emptyList()

                else -> DnsCodec.Record(
                    airPlayInstance, DnsCodec.TYPE_SRV,
                    DnsCodec.srvRdata(0, 0, airPlayPort, hostName),
                ) to listOf(
                    DnsCodec.Record(airPlayInstance, DnsCodec.TYPE_TXT, DnsCodec.txtRdata(txtRecords())),
                    DnsCodec.Record(hostName, DnsCodec.TYPE_A, ipBytes),
                )
            }

            // 查询主机名对应的 A 记录
            name.equals(hostName.trim('.'), ignoreCase = true) ->
                DnsCodec.Record(hostName, DnsCodec.TYPE_A, ipBytes) to emptyList()

            else -> null
        }
    }

    /**
     * TXT 记录。**取自 [AirPlayIdentity]** —— 与系统 NsdManager 通道、
     * 以及 HTTP 的 `/info`、`/server-info` 完全同源，杜绝"三处公告互相矛盾"。
     *
     * 早先这里自己拼过一份（features 只有 32 位、缺 pk/pi、model 写成 CastHub），
     * 结果 iPhone 隔空播放面板既不采纳也不报错 —— 详见 AirPlayIdentity 的类注释。
     */
    private fun txtRecords(): List<String> =
        AirPlayIdentity.txt(context).map { (k, v) -> "$k=$v" }

    companion object {
        private const val TAG = "AirPlayMdns"
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val SERVICE_AIRPLAY = "_airplay._tcp.local"
        private const val SERVICE_ENUM = "_services._dns-sd._udp.local"

        private const val TTL_PTR = 4500
        private const val ANNOUNCE_TIMES = 3
        private const val ANNOUNCE_INTERVAL_MS = 1_000L
    }
}

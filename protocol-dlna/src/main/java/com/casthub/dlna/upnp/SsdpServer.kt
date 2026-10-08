package com.casthub.dlna.upnp

import com.casthub.core.CastLogger
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * SSDP 服务：DLNA/UPnP 的设备发现层。
 *
 * 两个方向都要做：
 * 1. **被动响应** `M-SEARCH` —— 别人搜寻时回包；
 * 2. **主动通告** `ssdp:alive` —— 周期性广播自己的存在。
 *
 * 第 2 点是实测踩坑得到的结论：通告间隔从 600 秒降到 60 秒，本机才出现在夸克网盘的
 * 投屏列表里；进一步降到 1 秒后，设备出现速度肉眼可见变快。原因是很多投屏 App 靠
 * **被动监听**通告来建设备列表，而不主动发 M-SEARCH —— 广播越稀疏，
 * 用户从打开面板到看见设备要等的时间就越长，只广播一次等于没广播。
 */
class SsdpServer(
    private val context: android.content.Context,
    private val localIp: String,
    private val httpPort: Int,
    private val deviceName: String,
    private val udn: String,
) {

    /**
     * Wi-Fi 多播锁。**必须在开始接收前获取**，否则收不到任何 M-SEARCH，
     * 设备在投屏 App 里永远不会出现。详见 [MulticastLockHolder] 的说明。
     */
    private val multicastLock = MulticastLockHolder(context, "casthub-ssdp")

    private var socket: MulticastSocket? = null
    private var receiveThread: Thread? = null
    private var announceThread: Thread? = null

    @Volatile
    private var running = false

    private val location: String get() = "http://$localIp:$httpPort/device.xml"

    /** 通告/响应的目标类型。覆盖 DMR 相关全部 ST，少一个就可能搜不到。 */
    private val targets: List<String> = listOf(
        "upnp:rootdevice",
        DeviceDescription.DEVICE_TYPE,
        DeviceDescription.SERVICE_AV_TRANSPORT,
        DeviceDescription.SERVICE_RENDERING_CONTROL,
        DeviceDescription.SERVICE_CONNECTION_MANAGER,
    )

    val isRunning: Boolean get() = running

    fun start() {
        if (running) return
        try {
            // 先拿多播锁再开 socket，确保 M-SEARCH 能被收到
            multicastLock.acquire()

            val sock = MulticastSocket(null).also { socket = it }.apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 1_000
                joinGroup(
                    InetSocketAddress(InetAddress.getByName(SSDP_GROUP), SSDP_PORT),
                    pickInterface(),
                )
            }
            socket = sock
            running = true

            receiveThread = Thread({ receiveLoop(sock) }, "ssdp-rx").apply {
                isDaemon = true
                start()
            }
            announceThread = Thread({ announceLoop(sock) }, "ssdp-tx").apply {
                isDaemon = true
                start()
            }

            CastLogger.i(TAG, "SSDP 已启动：$location")
        } catch (t: Throwable) {
            running = false
            runCatching { socket?.close() }
            socket = null
            multicastLock.release()
            CastLogger.e(TAG, "SSDP 启动失败", t)
            throw t
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // 礼貌性告别，让对端立刻从列表里移除本设备
        runCatching { socket?.let { sendByebye(it) } }
        runCatching { socket?.let { if (!it.isClosed) it.leaveGroup(InetAddress.getByName(SSDP_GROUP)) } }
        runCatching { socket?.close() }
        socket = null
        receiveThread?.interrupt()
        announceThread?.interrupt()
        receiveThread = null
        announceThread = null
        multicastLock.release()
        CastLogger.i(TAG, "SSDP 已停止")
    }

    // ─────────────────────── 内部 ───────────────────────

    // 这里原本有一个 setDeviceName()：改名后触发一次立即通告。
    // 它是死代码 —— 改名走的是 DmrService.rename() → 整体 stop/start，
    // 新名字本来就会随新一轮通告发出去，那个入口从来没有被调用过。
    // 保留"看起来有用但没人调"的方法只会让后来者以为改名有两套路径。

    private fun pickInterface(): NetworkInterface? = runCatching {
        NetworkInterface.getByInetAddress(InetAddress.getByName(localIp))
    }.getOrNull()

    private fun receiveLoop(sock: MulticastSocket) {
        val buffer = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val message = String(packet.data, 0, packet.length, Charsets.UTF_8)
                if (!message.startsWith("M-SEARCH", ignoreCase = true)) continue

                val st = headerValue(message, "ST")
                CastLogger.d(TAG, "收到 M-SEARCH from ${packet.address.hostAddress} ST=$st")

                if (!matchesTarget(st)) continue

                // 立即回应，只留极小的抖动避免多台设备撞包。
                //
                // UPnP 规范原本要求在 0..MX 秒内随机延迟，以分散大量设备的集中应答。
                // 但实测这个延迟正是"打开投屏面板后要等一两秒才看到设备"的主因：
                // 家庭网络里设备数量很少，不存在风暴风险，响应速度远比遵守这条更重要的。
                val delayMs = (Math.random() * M_SEARCH_JITTER_MS).toLong()
                Thread {
                    Thread.sleep(delayMs)
                    respondToSearch(sock, packet.address, packet.port, st)
                }.start()
            } catch (_: java.net.SocketTimeoutException) {
                // 正常：用于响应 running 状态变化
            } catch (t: Throwable) {
                if (running) CastLogger.d(TAG, "SSDP 接收异常（已忽略）: ${t.message}")
            }
        }
    }

    private fun announceLoop(sock: MulticastSocket) {
        var rounds = 0
        while (running) {
            try {
                notifyAlive(sock)
                rounds++

                // 1 秒一轮的频率下，每轮都打日志会瞬间冲掉其它日志。
                // 按轮次抽样记录，既能确认广播仍在进行，又不淹没有用信息。
                if (rounds % LOG_EVERY_ROUNDS == 0) {
                    CastLogger.d(TAG, "已广播 ssdp:alive $rounds 次（间隔 ${ANNOUNCE_INTERVAL_MS}ms）")
                }
                Thread.sleep(ANNOUNCE_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                if (running) CastLogger.d(TAG, "SSDP 通告异常（已忽略）: ${t.message}")
            }
        }
    }

    /*
     * 这里原本有个「每 60 秒重新加入组播组」的逻辑，已删除 —— 两个方向都是坑：
     *
     *   - 直接重复 joinGroup：Android 上对同一个 socket 重复加入同一组播组会抛
     *     `SocketException: setsockopt failed: EADDRINUSE`。不但起不到恢复作用，
     *     还每 60 秒往日志里刷一次异常堆栈（真机抓到的堆栈见 git 历史）。
     *   - 改成先 leaveGroup 再 joinGroup：leave 会立刻发出 IGMP leave，
     *     AP 随即停止向本机转发该组播组，重新加入后转发关系还要重建 ——
     *     等于自己制造一段接收中断。
     *
     * 而当初促使我加这段逻辑的「长时间后收不到 M-SEARCH」，本身是个**误判**：
     * 本地测试脚本绑定了 1900 端口，而该端口在 Windows 上被系统 SSDP 服务占用，
     * 收不到包的是**测试端**，电视侧一直正常 —— 改用随机端口测试后每次都通
     * （见 tools/ssdp_speed_test.py 里的说明）。
     *
     * 教训：定位问题先确认测量工具本身是否可靠，别急着给被测对象加"补偿逻辑"。
     */

    private fun notifyAlive(sock: MulticastSocket) {
        for (st in targets) {
            val message = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
                append("LOCATION: $location\r\n")
                append("NT: $st\r\n")
                append("NTS: ssdp:alive\r\n")
                append("SERVER: ${serverHeader()}\r\n")
                append("USN: ${usnFor(st)}\r\n")
                append("\r\n")
            }
            send(sock, message, InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
        }
    }

    private fun sendByebye(sock: MulticastSocket) {
        for (st in targets) {
            val message = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_GROUP:$SSDP_PORT\r\n")
                append("NT: $st\r\n")
                append("NTS: ssdp:byebye\r\n")
                append("USN: ${usnFor(st)}\r\n")
                append("\r\n")
            }
            send(sock, message, InetAddress.getByName(SSDP_GROUP), SSDP_PORT)
        }
    }

    private fun respondToSearch(
        sock: MulticastSocket,
        address: InetAddress,
        port: Int,
        st: String?,
    ) {
        if (st == "ssdp:all") {
            // UPnP 规范：对 ssdp:all 必须按设备支持的**每一个**目标类型各回一条
            // （rootdevice / uuid / deviceType / 每个 serviceType），只回一条
            // 且 ST 照抄 ssdp:all 会让严格实现的控制点认为该设备不匹配、不列入。
            for (target in allSearchTargets()) {
                send(sock, buildSearchResponse(target), address, port)
            }
            return
        }
        if (st != null) send(sock, buildSearchResponse(st), address, port)
    }

    /** ssdp:all 应答时要覆盖的全部目标类型：rootdevice + uuid + 设备类型 + 各服务。 */
    private fun allSearchTargets(): List<String> =
        listOf("upnp:rootdevice", udn) + targets.filter { it != "upnp:rootdevice" }

    private fun buildSearchResponse(st: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=$MAX_AGE\r\n")
        append("EXT:\r\n")
        append("LOCATION: $location\r\n")
        append("ST: $st\r\n")
        append("USN: ${usnFor(st)}\r\n")
        append("SERVER: ${serverHeader()}\r\n")
        append("\r\n")
    }

    private fun send(sock: MulticastSocket, message: String, address: InetAddress, port: Int) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        runCatching { sock.send(DatagramPacket(bytes, bytes.size, address, port)) }
    }

    private fun usnFor(st: String): String = when (st) {
        "upnp:rootdevice" -> "$udn::upnp:rootdevice"
        udn -> udn
        else -> "$udn::$st"
    }

    private fun matchesTarget(st: String?): Boolean {
        if (st.isNullOrBlank()) return false
        if (st == "ssdp:all") return true
        if (st == udn) return true
        return targets.contains(st)
    }

    private fun headerValue(message: String, name: String): String? =
        message.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun serverHeader(): String =
        "${deviceName.replace(" ", "_")}/1.0 UPnP/1.0 CastHub/1.0"

    companion object {
        private const val TAG = "Ssdp"
        private const val SSDP_GROUP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_AGE = 1800

        /**
         * 通告间隔：**1 秒**。
         *
         * 演进过程：600 秒时设备压根不出现在投屏列表里 → 改 60 秒可被发现，
         * 但客户端仍反映"发现太慢"。原因是不少投屏 App 靠被动监听 NOTIFY
         * 来建设备列表，而不是主动发 M-SEARCH —— 此时设备能否出现、
         * 多快出现，完全取决于恰好撞上一次通告，平均要等半个间隔。
         *
         * 代价：每轮发 [targets] 条（5 条）NOTIFY，约 1.2KB/s 的组播流量。
         * 家庭网络完全可承受；若部署到设备密集的商用网络且出现拥塞，可上调此值。
         */
        private const val ANNOUNCE_INTERVAL_MS = 1_000L

        /** M-SEARCH 应答抖动上限。仅用于避免多设备同时应答撞包，越小响应越快。 */
        private const val M_SEARCH_JITTER_MS = 80L

        /** 通告日志抽样：每多少轮记录一次，避免 1 秒频率冲垮日志。 */
        private const val LOG_EVERY_ROUNDS = 60
    }
}

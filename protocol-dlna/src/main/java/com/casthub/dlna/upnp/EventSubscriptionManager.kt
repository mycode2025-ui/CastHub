package com.casthub.dlna.upnp

import com.casthub.core.CastLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * GENA 事件订阅与推送（UPnP Eventing）。
 *
 * ── 为什么必须做 ─────────────────────────────────────────────
 * SCPD 里把 `LastChange` 声明为 `sendEvents="yes"`，控制端据此订阅并**等待事件**。
 * 只返回合法的 SUBSCRIBE 响应却从不推送 NOTIFY，会让严格实现的控制端
 * 状态永远停在订阅那一刻 —— 界面上的播放状态、进度都不再更新。
 * DLNA 也把 DMR 的事件支持列为必需能力。
 *
 * 此前靠"发送端每秒轮询 GetPositionInfo"绕过了这件事，但那是**碰巧**
 * 夸克会轮询；只认事件、不轮询的控制端就会状态卡死。
 */
internal class EventSubscriptionManager {

    /**
     * SUBSCRIBE 的处理结果。
     *
     * 三种结果对应三个**不同**的 HTTP 状态码，不能合并成一个"失败"：
     * UPnP 对 GENA 的规定是"参数不合法 → 400"、"SID 不存在 → 412"。
     * 混着回会让控制端无法区分是自己构造错了请求、还是订阅已过期
     * （后者它应该重新订阅一次）。
     */
    internal sealed interface SubscribeOutcome {
        class Ok(val sid: String, val timeout: Int) : SubscribeOutcome

        /** SID 与 CALLBACK/NT 混用、或缺少必要头 —— 回 400。 */
        object BadRequest : SubscribeOutcome

        /** SID 本身不存在（多半是订阅已过期） —— 回 412，提示控制端重新订阅。 */
        object UnknownSid : SubscribeOutcome
    }

    /**
     * NOTIFY 是网络 IO，且绝不能阻塞控制路径，因此用独立的 IO 作用域。
     *
     * 生命周期跟 [start]/[stop] 走：stop 时把 scope 一起取消 —— 只取消
     * maintenanceJob 会让每次 DMR 重建都泄漏一个空 scope（job 对象与
     * 排队的协程）。stop 后若再次使用（改名触发的重启），用前重建。
     */
    private var scope: CoroutineScope = newScope()

    private fun newScope() = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("gena-notify")
    )

    /** stop 后 scope 已取消，任何使用点都要先确保拿到活着的 scope。 */
    @Synchronized
    private fun activeScope(): CoroutineScope {
        if (!scope.isActive) scope = newScope()
        return scope
    }

    /** 一次订阅（一个 SID 对应一个服务）。 */
    private class Subscription(
        val sid: String,
        val serviceId: String,
        val callbacks: List<URL>,
        /**
         * 已发出的序号。-1 表示一条还没发过，因此首条事件的 SEQ 是 0。
         * 必须用原子量：初始快照与常规 LastChange 可能并发推送，
         * 普通 var 的读-改-写在并发下会让两条事件拿到同一个 SEQ，
         * 控制端会把它当作重复包丢弃其中一条。
         */
        val seq: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(-1),
        var expiresAt: Long,
    )

    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    private var maintenanceJob: Job? = null

    /**
     * 各服务的当前状态快照，由 DMR 注入。
     *
     * UPnP 规定订阅成功后必须**立即推送一次**初始事件（SEQ=0），
     * 让控制端不必先问一遍就知道当前状态；否则控制端会一直显示订阅前的旧值。
     */
    var initialPayloadProvider: ((serviceId: String) -> String?)? = null

    /**
     * 处理 SUBSCRIBE。
     *
     * UPnP 规定两种形态互斥：
     *   - 新订阅：带 `CALLBACK` + `NT: upnp:event`，不带 SID；
     *   - 续订：带 `SID`，不带 CALLBACK / NT。
     * 两者混用时必须拒绝，否则控制端会误以为续订成功而实际拿到新 SID。
     *
     * @return 处理结果；[SubscribeOutcome.BadRequest] 回 400、
     *         [SubscribeOutcome.UnknownSid] 回 412。
     */
    fun subscribe(
        serviceId: String,
        callbackHeader: String?,
        nt: String?,
        sid: String?,
        timeoutHeader: String?,
    ): SubscribeOutcome {
        val timeout = parseTimeout(timeoutHeader)

        if (sid != null) {
            if (callbackHeader != null || nt != null) {
                CastLogger.w(TAG, "SUBSCRIBE 参数冲突：SID 与 CALLBACK/NT 同时出现")
                return SubscribeOutcome.BadRequest
            }
            val existing = subscriptions[sid] ?: run {
                CastLogger.d(TAG, "续订失败：未知 SID $sid")
                return SubscribeOutcome.UnknownSid
            }
            existing.expiresAt = System.currentTimeMillis() + timeout * 1000L
            CastLogger.d(TAG, "续订 $serviceId $sid，超时 ${timeout}s")
            return SubscribeOutcome.Ok(sid, timeout)
        }

        if (callbackHeader == null || nt == null || !nt.equals("upnp:event", ignoreCase = true)) {
            CastLogger.w(TAG, "SUBSCRIBE 参数不完整：CALLBACK=$callbackHeader NT=$nt")
            return SubscribeOutcome.BadRequest
        }
        val callbacks = parseCallbacks(callbackHeader)
        if (callbacks.isEmpty()) {
            CastLogger.w(TAG, "SUBSCRIBE 的 CALLBACK 无法解析：$callbackHeader")
            return SubscribeOutcome.BadRequest
        }

        val newSid = "uuid:${UUID.randomUUID()}"
        val created = Subscription(
            sid = newSid,
            serviceId = serviceId,
            callbacks = callbacks,
            expiresAt = System.currentTimeMillis() + timeout * 1000L,
        )
        subscriptions[newSid] = created
        CastLogger.i(TAG, "新订阅 $serviceId $newSid -> $callbacks")

        // 订阅成功后立刻推一次初始状态（UPnP 要求，且 SEQ 从 0 起）。
        // 稍作延迟是为了让 HTTP 200 响应先回到控制端 —— 若 NOTIFY 先到，
        // 部分控制端会因为还没记住 SID 而丢弃这条事件。
        activeScope().launch {
            delay(INITIAL_NOTIFY_DELAY_MS)
            if (subscriptions.containsKey(newSid)) {
                initialPayloadProvider?.invoke(serviceId)?.let { sendNotify(created, it) }
            }
        }
        return SubscribeOutcome.Ok(newSid, timeout)
    }

    /** 处理 UNSUBSCRIBE。返回是否为已存在的订阅。 */
    fun unsubscribe(sid: String?): Boolean {
        if (sid == null) return false
        val removed = subscriptions.remove(sid) ?: return false
        CastLogger.d(TAG, "取消订阅 ${removed.serviceId} ${removed.sid}")
        return true
    }

    /** 启动过期订阅清理。 */
    fun start() {
        if (maintenanceJob != null) return
        maintenanceJob = activeScope().launch {
            while (isActive) {
                delay(MAINTENANCE_INTERVAL_MS)
                val now = System.currentTimeMillis()
                subscriptions.entries.removeIf { it.value.expiresAt < now }
            }
        }
    }

    fun stop() {
        maintenanceJob?.cancel()
        maintenanceJob = null
        subscriptions.clear()
        scope.cancel()
    }

    /**
     * 推送一次 LastChange。异步执行，不阻塞调用方（控制端可能已离线）。
     *
     * @param payload LastChange 的**内层** XML（未转义，由本方法负责按 UPnP 要求转义）。
     */
    fun notifyLastChange(serviceId: String, payload: String) {
        val targets = subscriptions.values.filter { it.serviceId == serviceId }
        if (targets.isEmpty()) return
        activeScope().launch { targets.forEach { sendNotify(it, payload) } }
    }

    private fun sendNotify(sub: Subscription, payload: String) {
        // SEQ 是该订阅内的**事件序号**：首条（订阅后的初始快照）为 0，之后 1、2、3…
        // 控制端靠它识别丢包与重复，所以每发一条都必须前进。
        // （曾把判断写成 `sub.seq == 0 -> 0`，结果序号永远停在 0 —— 很隐蔽的错。）
        // updateAndGet 保证并发推送时序号不重不漏。
        val seq = sub.seq.updateAndGet { s ->
            when {
                s < 0 -> 0
                s >= Int.MAX_VALUE -> 1
                else -> s + 1
            }
        }

        val body = buildPropertySet(payload).toByteArray(Charsets.UTF_8)

        for (callback in sub.callbacks) {
            runCatching { sendRawNotify(callback, sub.sid, seq, body) }
                .onFailure {
                    // 控制端可能已退出，属于正常情况，不升级为错误
                    CastLogger.d(TAG, "NOTIFY 未送达 $callback（${it.message}）")
                }
        }
    }

    /**
     * 用裸 Socket 发 NOTIFY。
     *
     * ⚠️ 这里**不能**用 `HttpURLConnection`：它只接受标准 HTTP 方法
     * （OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH），而 UPnP 的 `NOTIFY`
     * 是自定义方法，会直接抛
     * `ProtocolException: Expected one of [...] but was NOTIFY`。
     * 实测踩到过一次 —— 订阅成功、却一个事件都到不了对端。
     */
    private fun sendRawNotify(callback: URL, sid: String, seq: Int, body: ByteArray) {
        val port = if (callback.port > 0) callback.port else HTTP_PORT
        val path = callback.file.ifEmpty { "/" }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(callback.host, port), NOTIFY_TIMEOUT_MS)
            socket.soTimeout = NOTIFY_TIMEOUT_MS

            val head = buildString {
                append("NOTIFY $path HTTP/1.1\r\n")
                append("HOST: ${callback.host}:$port\r\n")
                append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                append("CONTENT-LENGTH: ${body.size}\r\n")
                append("NT: upnp:event\r\n")
                append("NTS: upnp:propchange\r\n")
                append("SID: $sid\r\n")
                append("SEQ: $seq\r\n")
                append("CONNECTION: close\r\n")
                append("\r\n")
            }

            socket.getOutputStream().apply {
                write(head.toByteArray(Charsets.UTF_8))
                write(body)
                flush()
            }

            // 读走响应头即可：控制端应回 200，内容本身不需要解析
            runCatching { socket.getInputStream().read(ByteArray(256)) }
        }
        CastLogger.d(TAG, "NOTIFY #$seq -> $callback（$sid）")
    }

    private fun buildPropertySet(payload: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">\n")
        append("<e:property><LastChange>")
        // LastChange 的值是一段 XML，必须转义后嵌入
        append(Soap.escape(payload))
        append("</LastChange></e:property>\n")
        append("</e:propertyset>\n")
    }

    /** `CALLBACK: <http://a/> <http://b/>` —— 可能带多个备选地址。 */
    private fun parseCallbacks(header: String): List<URL> =
        Regex("<([^>]+)>").findAll(header)
            .mapNotNull { runCatching { URL(it.groupValues[1].trim()) }.getOrNull() }
            .toList()

    /** `TIMEOUT: Second-1800` 或 `Second-infinite`。 */
    private fun parseTimeout(header: String?): Int {
        val raw = header?.trim()?.removePrefix("Second-")?.lowercase() ?: return DEFAULT_TIMEOUT_S
        if (raw == "infinite") return MAX_TIMEOUT_S
        return raw.toIntOrNull()?.coerceIn(1, MAX_TIMEOUT_S) ?: DEFAULT_TIMEOUT_S
    }

    companion object {
        private const val TAG = "GenA"
        private const val DEFAULT_TIMEOUT_S = 1800
        private const val MAX_TIMEOUT_S = 1800
        private const val NOTIFY_TIMEOUT_MS = 3_000
        private const val MAINTENANCE_INTERVAL_MS = 30_000L

        /** CALLBACK 里省略端口时的默认端口。 */
        private const val HTTP_PORT = 80

        /** 订阅后首次推送的延迟：让 SUBSCRIBE 的 200 响应先回到控制端。 */
        private const val INITIAL_NOTIFY_DELAY_MS = 120L
    }
}

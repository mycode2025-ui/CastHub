package com.casthub.airplay.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.casthub.core.CastLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 用 Android 系统自带的 `NsdManager` 发布 `_airplay._tcp` 服务。
 *
 * ── 为什么不自己拼 DNS 报文 ────────────────────────────────────────
 * mDNS 的服务**注册**（宣告自己）是系统已经提供的能力：`NsdManager` 负责
 * 组播收发、重名冲突处理、随网络变化重新通告。自己用 `MulticastSocket` 写一遍，
 * 要自己处理压缩指针、cache-flush 位、QU 单播回包 —— 这些边界全靠猜，
 * 是纯粹的自找麻烦。
 *
 * 注意与 [MdnsResponder] 的分工不同：
 * - [NsdRegistrar] 只做**注册**（对外宣告），靠系统；
 * - [MdnsResponder] 自己收包并应答，是**注册 + 应答**一体。
 *
 * ── 为什么还要保留手写那份 ────────────────────────────────────────
 * 部分电视/盒子的 `NsdManager` 实现不完整：注册回调不回来，或注册成功但
 * 对 iOS 的查询不应答。这不是理论风险 —— 不少投屏项目至今仍手写 mDNS，
 * 原因就在这里。所以策略是**优先用系统，失败回退手写**，
 * 而不是二选一：能用现成的就用，用不了也不能让功能挂掉。
 */
internal class NsdRegistrar(
    private val context: Context,
    private val serviceName: String,
    private val port: Int,
    private val txt: Map<String, String>,
) {

    /** 实际注册成功的名字。系统可能因为重名自动改名（追加 " (2)"）。 */
    @Volatile
    var registeredName: String = serviceName
        private set

    private var listener: NsdManager.RegistrationListener? = null

    /**
     * 注册服务并**同步等待结果**（最多 [timeoutMs]）。
     *
     * 注册本身是异步回调的，但调用方要立刻知道"能不能用"来决定是否回退，
     * 所以这里等一下；超时按失败处理。
     */
    fun start(timeoutMs: Long = REGISTER_TIMEOUT_MS): Boolean {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            CastLogger.w(TAG, "系统不支持 NsdManager，回退到自实现 mDNS")
            return false
        }

        val info = NsdServiceInfo().apply {
            this.serviceName = this@NsdRegistrar.serviceName
            this.serviceType = SERVICE_TYPE
            this.port = this@NsdRegistrar.port
            txt.forEach { (k, v) ->
                // key/value 各有 255 字节上限，超长的直接丢弃而不是让整个注册失败
                runCatching { setAttribute(k, v) }
                    .onFailure { CastLogger.w(TAG, "TXT 字段 $k 设置失败，已忽略") }
            }
        }

        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)

        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registered: NsdServiceInfo) {
                registeredName = registered.serviceName
                ok.set(true)
                done.countDown()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                CastLogger.w(TAG, "NsdManager 注册失败，错误码 $errorCode，回退到自实现 mDNS")
                done.countDown()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }

        return try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            done.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (ok.get()) {
                listener = l
                CastLogger.i(TAG, "已用系统 NsdManager 注册：${registeredName} :$port")
                true
            } else {
                runCatching { nsd.unregisterService(l) }
                false
            }
        } catch (t: Throwable) {
            CastLogger.w(TAG, "NsdManager 注册异常：${t.message}，回退到自实现 mDNS")
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        listener = null
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        runCatching { nsd.unregisterService(l) }
            .onFailure { CastLogger.w(TAG, "取消注册失败：${it.message}") }
    }

    companion object {
        private const val TAG = "AirPlayNsd"

        /** NsdManager 的 serviceType 不带 ".local" 后缀。 */
        private const val SERVICE_TYPE = "_airplay._tcp"

        private const val REGISTER_TIMEOUT_MS = 4_000L
    }
}

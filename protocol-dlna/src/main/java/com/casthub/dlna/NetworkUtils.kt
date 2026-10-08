package com.casthub.dlna

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

/** DLNA 模块用到的网络工具。 */
internal object NetworkUtils {

    /**
     * 取本机在局域网中的 IPv4 地址。
     *
     * 会被写进 SSDP 的 LOCATION 与 device.xml，必须是**局域网内可访问**的地址，
     * 因此要排除回环地址与虚拟网卡。
     */
    fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { iface -> iface.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /** 申请一个空闲的本地端口（用于 DMR 的 HTTP 服务）。 */
    fun findFreePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(DEFAULT_DMR_PORT)

    private const val DEFAULT_DMR_PORT = 39200
}

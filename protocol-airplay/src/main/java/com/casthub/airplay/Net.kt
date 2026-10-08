package com.casthub.airplay

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 局域网信息：本机 IPv4 地址与空闲端口。
 *
 * AirPlay 的监听端口按惯例是 7000，但电视/盒子上常见被其它投屏 App 占用
 * （乐播、当贝投屏之类）。占用时不能启动失败就了事 —— 换一个空闲端口，
 * 并把真实端口写进 mDNS 的 SRV 记录里，iPhone 是按 SRV 找过来的，
 * 用哪个端口它并不关心。硬绑 7000 失败就报「不可用」才是缺陷。
 */
internal object Net {

    /**
     * 本机局域网 IPv4。优先选 **site-local**（192.168.x / 10.x / 172.16-31.x）：
     * 多网卡时（电视常见：Wi-Fi + 有线 + VPN 虚拟口）`firstOrNull` 不挑的话，
     * 可能选到 link-local（169.254.x）或虚拟网卡地址 —— 把它写进 mDNS 的 A 记录，
     * iPhone 拿着一个不可路由的地址去连，表现就是"搜得到、投不上"。
     * 实在没有 site-local 才退而求其次（有线直连等特殊场景）。
     */
    fun localIpv4(): String? = runCatching {
        val addresses = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .toList()
        (addresses.firstOrNull { it.isSiteLocalAddress } ?: addresses.firstOrNull())
            ?.hostAddress
    }.getOrNull()

    /** 由系统分配一个空闲端口。失败返回 0（调用方按“未知”处理）。 */
    fun findFreePort(): Int = runCatching {
        ServerSocket(0).use { it.localPort }
    }.getOrDefault(0)
}

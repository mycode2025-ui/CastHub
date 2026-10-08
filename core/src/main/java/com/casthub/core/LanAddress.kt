package com.casthub.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Select a physical LAN, never a VPN or a stale interface address. */
object LanAddress {
    fun ipv4(context: Context): String? = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.allNetworks.sortedBy { if (it == manager.activeNetwork) 0 else 1 }
            .firstNotNullOfOrNull { network ->
                val caps = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                    return@firstNotNullOfOrNull null
                }
                manager.getLinkProperties(network)?.linkAddresses
                    ?.map { it.address }?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
            }
    }.getOrNull()
}

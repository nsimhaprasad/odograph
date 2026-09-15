package `in`.odograph.tracker.server

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The device's usual LAN address, for telling a laptop on the same network where to pull from.
 *
 * Enumerating the interfaces needs no permission; the walk prefers a site-local IPv4 the way a
 * phone hotspot's Wi-Fi client would see it. Null means no usable LAN interface (offline, or
 * only VPN/tethering links up).
 */
object LanInfo {
    fun lanIpv4(): String? {
        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses) {
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress && address.isSiteLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }
        }
        return null
    }

    /** The base URL a laptop on the same network would reach this box at, or null when unaddressable. */
    fun baseUrl(port: Int = DashboardServer.PORT): String? =
        lanIpv4()?.let { "http://$it:$port" }
}
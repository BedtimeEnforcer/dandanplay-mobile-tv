package com.xyoye.storage_component.utils.dlna

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 轻享版新增：挑出用于投屏的网卡 / 本机 IP。
 *
 * 两种使用场景都要覆盖：
 *  1. 手机连家里 WiFi，电视也在同一 WiFi
 *  2. **手机开热点，电视连手机热点**（这时候没有 WiFi 客户端网络，
 *     承载的是热点网卡，接口名通常是 ap0 / swlan0 / wlan1）
 *
 * 所以不能只看 "wlan"，要按「热点 > 无线 > 有线」的优先级挑，
 * 并且必须是局域网地址（site-local），这样能自动跳过移动数据 / VPN 网卡。
 */
object LocalIpUtils {

    fun findLocalIpAddress(): String? = candidates().firstOrNull()?.second?.hostAddress

    /** SSDP 组播要用的网卡（保证 M-SEARCH 从电视能收到的那个网卡发出去） */
    fun findCastInterface(): NetworkInterface? = candidates().firstOrNull()?.first

    private fun candidates(): List<Pair<NetworkInterface, Inet4Address>> {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && it.isLoopback.not() }
                .mapNotNull { networkInterface ->
                    // 蜂窝 / VPN 网卡直接排除：它们的地址也可能落在这个网段，
                    // 但组播从它们发出去电视永远收不到
                    if (priority(networkInterface.name) < 0) {
                        return@mapNotNull null
                    }
                    val address = networkInterface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isLoopbackAddress.not() && it.isSiteLocalAddress }
                        ?: return@mapNotNull null
                    Triple(networkInterface, address, priority(networkInterface.name))
                }
                .sortedByDescending { it.third }
                .map { it.first to it.second }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun priority(name: String): Int {
        val lower = name.lowercase()
        return when {
            // 手机热点
            lower.startsWith("ap") ||
                lower.startsWith("swlan") ||
                lower.startsWith("softap") -> 4
            // 普通 WiFi / 热点部分机型会叫 wlan1
            lower.startsWith("wlan") -> 3
            // 有线
            lower.startsWith("eth") -> 2
            // USB 共享等
            lower.startsWith("rndis") || lower.startsWith("usb") -> 1
            // 蜂窝数据 / VPN —— 明确排除（返回负分）
            lower.startsWith("rmnet") ||
                lower.startsWith("ccmni") ||
                lower.startsWith("tun") ||
                lower.startsWith("utun") ||
                lower.startsWith("ppp") ||
                lower.startsWith("vgate") -> -1
            else -> 0
        }
    }
}

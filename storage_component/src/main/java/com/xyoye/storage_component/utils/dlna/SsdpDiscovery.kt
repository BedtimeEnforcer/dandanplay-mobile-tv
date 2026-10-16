package com.xyoye.storage_component.utils.dlna

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Xml
import com.xyoye.common_component.utils.DDLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 轻享版新增：DLNA（UPnP）设备发现。
 *
 * 流程：
 *  1. 往 239.255.255.250:1900 组播发 M-SEARCH，ST = MediaRenderer（电视/盒子/投影都实现这个）
 *  2. 先只收集响应里的 LOCATION（设备描述 XML 地址），避免被某台慢设备阻塞住整个搜索
 *  3. 再逐个拉设备描述 XML，解析出 friendlyName（电视名字）和 AVTransport 的 controlURL
 *
 * 两个 Android 上的关键点：
 *  - 收组播必须先拿 MulticastLock
 *  - **必须把组播出口指定到「电视所在的那块网卡」**：手机连 WiFi 时是 wlan0/wlan2，
 *    手机开热点让电视连的时候是 ap0/swlan0。不指定的话 M-SEARCH 可能从移动数据或
 *    VPN 网卡发出去，电视根本收不到（表现就是"怎么都搜不到电视"）。
 */
object SsdpDiscovery {

    private const val TAG = "SsdpDiscovery"
    private const val SSDP_HOST = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val RECEIVE_BUFFER_SIZE = 4096

    /** 标准搜索目标：绝大多数电视/盒子/投影都实现 MediaRenderer */
    private const val SEARCH_TARGET_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** 兜底搜索目标：有些电视不认上面的 ST，但对 ssdp:all 会响应 */
    private const val SEARCH_TARGET_ALL = "ssdp:all"

    /** 解析设备描述要多留一点时间，但别让用户等太久 */
    private const val PARSE_EXTRA_TIMEOUT_MS = 4000L

    /** 搜索设备；[timeoutMs] 内没搜到就返回已有结果（不抛异常） */
    suspend fun discover(context: Context, timeoutMs: Long = 4000L): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            // 第一轮：标准 MediaRenderer 搜索
            val locations = LinkedHashSet<String>()
            locations.addAll(collectLocations(context, timeoutMs, SEARCH_TARGET_RENDERER))

            // 第二轮：第一轮一台都没搜到，再用 ssdp:all 兜一次
            // （部分电视只对 ssdp:all 有响应，这是"明明开着却搜不到"的常见原因）
            if (locations.isEmpty()) {
                DDLog.i(TAG, "MediaRenderer 没搜到设备，改用 ssdp:all 再搜一轮")
                locations.addAll(collectLocations(context, timeoutMs, SEARCH_TARGET_ALL))
            }
            if (locations.isEmpty()) {
                return@withContext emptyList()
            }

            val devices = LinkedHashMap<String, DlnaDevice>()
            val parseDeadline = System.currentTimeMillis() + PARSE_EXTRA_TIMEOUT_MS
            for (location in locations) {
                if (System.currentTimeMillis() > parseDeadline) {
                    DDLog.w(TAG, "解析设备描述超时，已解析 ${devices.size} 台")
                    break
                }
                val device = parseDeviceDescription(location) ?: continue
                // 同一台设备可能通过多个网卡/多个 LOCATION 出现，优先用 UDN 去重
                val key = device.udn.ifBlank { device.location }
                if (devices.containsKey(key).not()) {
                    DDLog.i(TAG, "发现投屏设备: ${device.displayName} @ ${device.ip}")
                    devices[key] = device
                }
            }
            devices.values.toList()
        }

    /**
     * 阶段一：只收 SSDP 响应里的 LOCATION，不做网络请求，保证搜索窗口内能收到尽量多的设备
     */
    private fun collectLocations(context: Context, timeoutMs: Long, searchTarget: String): List<String> {
        val locations = LinkedHashSet<String>()
        var socket: MulticastSocket? = null
        var lock: WifiManager.MulticastLock? = null
        try {
            lock = acquireMulticastLock(context)

            val targetInterface = LocalIpUtils.findCastInterface()
            if (targetInterface == null) {
                DDLog.w(TAG, "没找到局域网网卡（WiFi/热点都没开？），搜索大概率会失败")
            } else {
                DDLog.i(TAG, "SSDP 使用网卡: ${targetInterface.name}")
            }

            socket = MulticastSocket().apply {
                soTimeout = 400
                // 指定组播出口网卡：连 WiFi 时是 wlan，开热点时是 ap/swlan
                targetInterface?.let { nif ->
                    try {
                        networkInterface = nif
                    } catch (e: Exception) {
                        DDLog.e(TAG, "设置组播网卡失败: ${e.message}", e)
                    }
                }
            }

            val request = buildSearchRequest(searchTarget).toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(SSDP_HOST)
            val deadline = System.currentTimeMillis() + timeoutMs
            var lastSendAt = 0L
            var sentTimes = 0
            var otherPacketLogged = 0

            while (System.currentTimeMillis() < deadline) {
                // 每隔 700ms 重发一次，避免丢包
                if (System.currentTimeMillis() - lastSendAt > 700) {
                    socket.send(DatagramPacket(request, request.size, group, SSDP_PORT))
                    lastSendAt = System.currentTimeMillis()
                    sentTimes++
                }

                val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                val text = try {
                    socket.receive(packet)
                    String(packet.data, 0, packet.length)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    // 例如 ICMP port unreachable 会抛 PortUnreachableException，
                    // 不能因此中断整个搜索
                    DDLog.w(TAG, "接收 SSDP 响应失败: ${e.message}")
                    continue
                }

                val location = parseLocation(text)
                if (location != null) {
                    locations.add(location)
                } else if (otherPacketLogged < 8) {
                    // 把非响应包（例如电视周期性发的 NOTIFY）记下来：
                    // 排查"搜不到电视"时，能一眼看出电视到底有没有在发 SSDP
                    val firstLine = text.lineSequence().firstOrNull().orEmpty().trim()
                    if (firstLine.isNotEmpty() && firstLine.contains("M-SEARCH").not()) {
                        DDLog.i(TAG, "收到其他SSDP包(来自 ${packet.address?.hostAddress}): $firstLine")
                        otherPacketLogged++
                    }
                }
            }
            DDLog.i(
                TAG,
                "ST=$searchTarget：发过 $sentTimes 次 M-SEARCH，收到 ${locations.size} 个设备描述地址"
            )
        } catch (e: Exception) {
            DDLog.e(TAG, "搜索投屏设备失败: ${e.message}", e)
        } finally {
            closeQuietly(socket)
            releaseQuietly(lock)
        }
        return locations.toList()
    }

    private fun buildSearchRequest(searchTarget: String): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST:$SSDP_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: 2\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }

    /** 从 SSDP 响应里取 LOCATION */
    private fun parseLocation(response: String): String? {
        return response.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * 拉取并解析设备描述 XML。
     *
     * 逐个 `<service>` 结算，不假设 `serviceType` 一定出现在 `controlURL` 之前；
     * AVTransport 的版本号也不写死（1/2/3 都认，只认服务名）。
     */
    private fun parseDeviceDescription(location: String): DlnaDevice? {
        val xml = httpGet(location) ?: return null
        var friendlyName = ""
        var modelName = ""
        var udn = ""
        var deviceType = ""
        var controlUrl: String? = null
        var selectedServiceType: String? = null

        // 当前 <service> 块里的信息
        var serviceType = ""
        var serviceControlUrl = ""

        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.substringAfterLast(':')) {
                        "friendlyName" -> if (friendlyName.isEmpty()) {
                            friendlyName = parser.nextText().trim()
                        }
                        "modelName" -> modelName = parser.nextText().trim()
                        "deviceType" -> if (deviceType.isEmpty()) {
                            deviceType = parser.nextText().trim()
                        }
                        "UDN" -> if (udn.isEmpty()) udn = parser.nextText().trim()
                        "service" -> {
                            serviceType = ""
                            serviceControlUrl = ""
                        }
                        "serviceType" -> serviceType = parser.nextText().trim()
                        "controlURL" -> serviceControlUrl = parser.nextText().trim()
                    }

                    XmlPullParser.END_TAG -> if (parser.name.substringAfterLast(':') == "service") {
                        if (controlUrl == null && serviceType.startsWith("urn:schemas-upnp-org:service:AVTransport:") &&
                            serviceControlUrl.isNotEmpty()
                        ) {
                            controlUrl = serviceControlUrl
                            selectedServiceType = serviceType
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            DDLog.e(TAG, "解析设备描述失败($location): ${e.message}", e)
            return null
        }

        // ssdp:all 那一轮会把路由器、NAS 之类的设备也带出来，
        // 这里必须确认它确实是一台「投屏渲染设备」
        if (deviceType.isNotEmpty() && deviceType.contains("MediaRenderer").not()) {
            DDLog.i(TAG, "不是投屏渲染设备($deviceType)，跳过: $location")
            return null
        }

        val relativeControlUrl = controlUrl ?: run {
            DDLog.w(TAG, "设备没有 AVTransport 服务，跳过: $location")
            return null
        }
        val absoluteControlUrl = try {
            URL(URL(location), relativeControlUrl).toString()
        } catch (e: Exception) {
            return null
        }
        val ip = try {
            URL(location).host
        } catch (e: Exception) {
            ""
        }

        return DlnaDevice(
            friendlyName = friendlyName.ifBlank { modelName },
            udn = udn,
            location = location,
            controlUrl = absoluteControlUrl,
            ip = ip,
            serviceType = selectedServiceType ?: return null
        )
    }

    private fun httpGet(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            DDLog.e(TAG, "获取设备描述失败($url): ${e.message}", e)
            null
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun acquireMulticastLock(context: Context): WifiManager.MulticastLock? {
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            manager.createMulticastLock("dandanplay_cast_dlna").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            DDLog.e(TAG, "获取组播锁失败: ${e.message}", e)
            null
        }
    }

    private fun closeQuietly(socket: MulticastSocket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun releaseQuietly(lock: WifiManager.MulticastLock?) {
        try {
            lock?.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}

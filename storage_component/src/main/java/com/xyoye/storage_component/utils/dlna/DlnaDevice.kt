package com.xyoye.storage_component.utils.dlna

/**
 * 一个可以被 DLNA 投屏的接收设备（电视 / 盒子 / 投影）。
 *
 * @param friendlyName 设备在网络上显示的名字，例如 "TCL 电视"
 * @param udn          设备唯一标识，用于去重
 * @param location     设备描述 XML 的地址（SSDP 响应里的 LOCATION）
 * @param controlUrl   AVTransport 服务的控制地址（绝对地址，由 location + 相对 controlURL 拼出来）
 * @param ip           设备 IP
 */
data class DlnaDevice(
    val friendlyName: String,
    val udn: String,
    val location: String,
    val controlUrl: String,
    val ip: String,
    val serviceType: String = "urn:schemas-upnp-org:service:AVTransport:1"
) {
    /** 电视上显示的那一行文案 */
    val displayName: String
        get() = if (friendlyName.isBlank()) ip else friendlyName
}

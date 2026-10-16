package com.xyoye.storage_component.utils.screencast.receiver

import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.storage.helper.ScreencastConstants
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.EntropyUtils
import com.xyoye.common_component.utils.IOUtils
import com.xyoye.common_component.utils.JsonHelper
import com.xyoye.data_component.bean.UDPDeviceBean
import kotlinx.coroutines.delay
import java.net.DatagramPacket
import java.net.InetAddress
import com.xyoye.storage_component.utils.dlna.LocalIpUtils
import java.net.MulticastSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * <pre>
 *     author: xyoye1997@outlook.com
 *     time  : 2022/7/21
 *     desc  :
 * </pre>
 */

object UdpServer {

    private var TAG = UdpServer::class.java.simpleName

    //UDPSocket
    private var multicastSocket: MulticastSocket? = null

    //组播地址
    private var multicastAddress: InetAddress? = null

    //组播次数
    private var multicastCount = 0

    private var isRunning = AtomicBoolean(false)

    /**
     * 启动组播发送
     */
    suspend fun startMulticastEmit(httpPort: Int, needPassword: Boolean) {
        stopMulticastEmit()

        if (initMulticastSocket().not()) {
            return
        }

        isRunning.set(true)
        multicastCount = 0
        while (isRunning.get()) {
            sendMulticast(httpPort, needPassword)
            delay(timeMillis = ScreencastConstants.Multicast.intervalMs)
        }
    }

    /**
     * 停止组播发送
     */
    fun stopMulticastEmit() {
        isRunning.set(false)
        IOUtils.closeIO(multicastSocket)
        multicastSocket = null
    }

    /**
     * 初始化组播Socket
     */
    private fun initMulticastSocket(): Boolean {
        try {
            multicastAddress = InetAddress.getByName(ScreencastConstants.Multicast.host)
            if (!multicastAddress!!.isMulticastAddress) {
                DDLog.e(TAG, "${ScreencastConstants.Multicast.host}不是组播地址")
                return false
            }
            multicastSocket = MulticastSocket().apply {
                // 轻享版：显式指定组播出口网卡。
                // 电视上通常同时有 WiFi 和有线两个接口，不指定的话 Android 可能从
                // 没接线的那个发出去，结果就是手机端"搜索连接"永远搜不到这台设备
                LocalIpUtils.findCastInterface()?.let { nif ->
                    this.networkInterface = nif
                    DDLog.i(TAG, "投屏组播使用网卡: ${nif.name}")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return multicastSocket != null
    }

    /**
     * 发送组播
     */
    private fun sendMulticast(httpPort: Int, needPassword: Boolean) {
        try {
            val udpDeviceBean = UDPDeviceBean(
                httpPort = httpPort,
                deviceName = getDeviceName(),
                count = multicastCount,
                needPassword = needPassword
            )
            val msg = JsonHelper.toJson(udpDeviceBean) ?: return

            //组播内容加密
            val entropyMsg = EntropyUtils.aesEncode(ScreencastConstants.Multicast.secret, msg)
            if (TextUtils.isEmpty(entropyMsg)) {
                return
            }
            DDLog.i(UdpServer::class.java.simpleName, entropyMsg!!)

            val data = entropyMsg.toByteArray()

            val sendPacket =
                DatagramPacket(data, data.size, multicastAddress, ScreencastConstants.Multicast.port)
            multicastSocket?.send(sendPacket)

            multicastCount++
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 获取设备名称
     */
    private fun getDeviceName(): String {
        var deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            Settings.Global.getString(
                BaseApplication.getAppContext().contentResolver,
                Settings.Global.DEVICE_NAME
            )
        } else {
            null
        }
        if (TextUtils.isEmpty(deviceName)) {
            deviceName = "${Build.BRAND}_${Build.DEVICE}"
        }
        return deviceName!!
    }
}
package com.xyoye.storage_component.services

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.extension.aesEncode
import com.xyoye.common_component.extension.authorizationValue
import com.xyoye.common_component.extension.isServiceRunning
import com.xyoye.common_component.network.repository.ScreencastRepository
import com.xyoye.common_component.notification.Notifications
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.helper.ScreencastConstants
import com.xyoye.common_component.utils.DDLog
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.UDPDeviceBean
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData
import com.xyoye.storage_component.utils.dlna.DlnaController
import com.xyoye.storage_component.utils.dlna.DlnaDevice
import com.xyoye.storage_component.utils.dlna.DlnaMimeTypes
import com.xyoye.storage_component.utils.dlna.LocalIpUtils
import com.xyoye.storage_component.utils.screencast.provider.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Created by xyoye on 2022/9/14
 *
 * 轻享版改造：这里原来是"弹弹play 自研投屏"的投送端（需要另一台设备跑弹弹play 接收端）。
 * 现在同时支持两条投屏路径（由 KeyCastType 区分，互不干扰）：
 *
 * 一、DLNA 投送（CAST_TYPE_DLNA，默认）：
 *  1. 在手机上起一个本地 HTTP 服务，把视频文件流式提供给电视（HttpServer + ServerController，支持 Range）
 *  2. 用 UPnP AVTransport 把 http://<手机IP>:<端口>/video/<文件名>?uniqueKey=xxx 交给电视
 *  3. 电视自己去拉流播放 —— 电视端不需要装任何东西
 *  注意：DLNA 电视只会来拉 /video，不会请求 /danmu、/subtitle，所以这条路带不了弹幕字幕。
 *
 * 二、弹弹play 接收端投送（CAST_TYPE_SCREENCAST）：
 *  1. 同样起本地 HTTP 服务（端点更全：/video、/danmu、/subtitle、/callback）
 *  2. 用弹弹play 自家协议 POST http://<电视IP>:<端口>/play，body 是 ScreencastData
 *     （relatedVideos 里带上弹幕、字幕文件名，接收端判空后回来拉 /danmu、/subtitle）
 *  3. 电视端 App 自己拉流 + 拉弹幕字幕并渲染 —— 能带弹幕字幕
 */

interface ScreencastProvideHandler {
    fun onProvideVideo(videoSource: BaseVideoSource)
}

class ScreencastProvideService : Service(), ScreencastProvideHandler {
    private var httpPort = 20000
    private var httpServer: HttpServer? = null
    private var castJob: Job? = null

    private var device: DlnaDevice? = null
    private var castNotified = false

    // 弹弹play 接收端（电视端 App）投屏目标
    private var screencastDevice: UDPDeviceBean? = null
    private var screencastPassword: String? = null

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notifier: ScreencastProvideNotifier
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ScreencastProvide"
        private const val KEY_DEVICE_NAME = "key_dlna_device_name"
        private const val KEY_DEVICE_UDN = "key_dlna_device_udn"
        private const val KEY_DEVICE_LOCATION = "key_dlna_device_location"
        private const val KEY_DEVICE_CONTROL_URL = "key_dlna_device_control_url"
        private const val KEY_DEVICE_IP = "key_dlna_device_ip"
        private const val KEY_DEVICE_SERVICE_TYPE = "key_dlna_service_type"

        // 投屏方式：DLNA 电视 / 弹弹play 接收端
        private const val KEY_CAST_TYPE = "key_cast_type"
        private const val CAST_TYPE_DLNA = "dlna"
        private const val CAST_TYPE_SCREENCAST = "screencast"

        private const val KEY_SCREENCAST_IP = "key_screencast_ip"
        private const val KEY_SCREENCAST_PORT = "key_screencast_port"
        private const val KEY_SCREENCAST_NAME = "key_screencast_name"
        private const val KEY_SCREENCAST_PASSWORD = "key_screencast_password"

        fun isRunning(context: Context): Boolean {
            return context.isServiceRunning(ScreencastProvideService::class.java)
        }

        /** 投屏到指定的 DLNA 设备 */
        fun start(context: Context, device: DlnaDevice) {
            val intent = Intent(context, ScreencastProvideService::class.java).apply {
                putExtra(KEY_CAST_TYPE, CAST_TYPE_DLNA)
                putExtra(KEY_DEVICE_NAME, device.friendlyName)
                putExtra(KEY_DEVICE_UDN, device.udn)
                putExtra(KEY_DEVICE_LOCATION, device.location)
                putExtra(KEY_DEVICE_CONTROL_URL, device.controlUrl)
                putExtra(KEY_DEVICE_IP, device.ip)
                putExtra(KEY_DEVICE_SERVICE_TYPE, device.serviceType)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * 投屏到弹弹play 接收端（电视端 App），可带弹幕、字幕
         *
         * @param device 组播搜到的接收端设备
         * @param password 接收端设置的连接密码，没设密码传 null
         */
        fun startScreencast(context: Context, device: UDPDeviceBean, password: String? = null) {
            val intent = Intent(context, ScreencastProvideService::class.java).apply {
                putExtra(KEY_CAST_TYPE, CAST_TYPE_SCREENCAST)
                putExtra(KEY_SCREENCAST_IP, device.ipAddress)
                putExtra(KEY_SCREENCAST_PORT, device.httpPort)
                putExtra(KEY_SCREENCAST_NAME, device.deviceName)
                putExtra(KEY_SCREENCAST_PASSWORD, password)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreencastProvideService::class.java))
        }

        private fun readDevice(intent: Intent?): DlnaDevice? {
            val controlUrl = intent?.getStringExtra(KEY_DEVICE_CONTROL_URL)
            if (controlUrl.isNullOrEmpty()) {
                return null
            }
            return DlnaDevice(
                friendlyName = intent.getStringExtra(KEY_DEVICE_NAME).orEmpty(),
                udn = intent.getStringExtra(KEY_DEVICE_UDN).orEmpty(),
                location = intent.getStringExtra(KEY_DEVICE_LOCATION).orEmpty(),
                controlUrl = controlUrl,
                ip = intent.getStringExtra(KEY_DEVICE_IP).orEmpty(),
                serviceType = intent.getStringExtra(KEY_DEVICE_SERVICE_TYPE)
                    ?: "urn:schemas-upnp-org:service:AVTransport:1"
            )
        }

        private fun readScreencastDevice(intent: Intent?): UDPDeviceBean? {
            val ip = intent?.getStringExtra(KEY_SCREENCAST_IP)
            val httpPort = intent?.getIntExtra(KEY_SCREENCAST_PORT, 0) ?: 0
            if (ip.isNullOrEmpty() || httpPort <= 0) {
                return null
            }
            return UDPDeviceBean(
                ipAddress = ip,
                httpPort = httpPort,
                deviceName = intent.getStringExtra(KEY_SCREENCAST_NAME).orEmpty()
                    .ifEmpty { "弹弹play设备" }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        notifier = ScreencastProvideNotifier(this)

        startForeground(Notifications.Id.SCREENCAST_PROVIDE, notifier.notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val videoSource = VideoSourceManager.getInstance().getSource() as? StorageVideoSource?
        //必须存在视频资源
        if (videoSource == null) {
            ToastCenter.showError("投屏失败：没有正在播放的视频源")
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        }

        // 弹弹play 接收端投屏（走 POST /play，可带弹幕字幕）
        if (intent != null && intent.getStringExtra(KEY_CAST_TYPE) == CAST_TYPE_SCREENCAST) {
            val screencastTarget = readScreencastDevice(intent)
            if (screencastTarget == null) {
                stopSelf()
                return super.onStartCommand(intent, flags, startId)
            }

            ServiceLifecycleBridge.onScreencastProvideLifeChange(null)

            screencastPassword = intent.getStringExtra(KEY_SCREENCAST_PASSWORD)
            castToScreencastDevice(videoSource, screencastTarget)
            return START_STICKY
        }

        //必须存在投屏设备（DLNA）
        val targetDevice = readDevice(intent)
        if (targetDevice == null) {
            stopSelf()
            return super.onStartCommand(intent, flags, startId)
        }

        ServiceLifecycleBridge.onScreencastProvideLifeChange(null)

        castToDevice(videoSource, targetDevice)
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceLifecycleBridge.onScreencastProvideLifeChange(null)

        // 通知电视停止播放，否则电视端会一直转圈 / 报错
        device?.let { target ->
            runBlocking {
                withTimeoutOrNull(1500) {
                    DlnaController.stop(target)
                }
            }
        }

        castJob?.cancel()
        stopHttpServer()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 起本地 HTTP 服务，然后把视频地址交给电视去播
     */
    private fun castToDevice(videoSource: StorageVideoSource, targetDevice: DlnaDevice) {
        // 重复点投屏时，先取消上一次的任务，避免两个服务实例抢端口
        castJob?.cancel()
        device = targetDevice
        // 两条投屏路径互斥，清掉另一条的目标，避免 onDestroy / 成功提示串台
        screencastDevice = null
        castNotified = false

        castJob = serviceScope.launch {
            acquireLocks()

            stopHttpServer()

            val newHttpServer = createHttpServer(videoSource, httpPort)
            if (newHttpServer == null) {
                ToastCenter.showError("启用投屏服务失败")
                stopSelf()
                return@launch
            }
            httpServer = newHttpServer

            val localIp = LocalIpUtils.findLocalIpAddress()
            if (localIp.isNullOrEmpty()) {
                ToastCenter.showError("投屏失败：请先连接 WiFi")
                stopSelf()
                return@launch
            }

            val storageFile = videoSource.getStorageFile()
            val fileName = storageFile.fileName()
            val uniqueKey = videoSource.getUniqueKey()
            // 带上文件名后缀：不少电视靠后缀判断容器格式、挑解码器
            val videoUrl = "http://$localIp:${newHttpServer.listeningPort}" +
                "/video/${Uri.encode(fileName)}?${ScreencastConstants.Param.uniqueKey}=${Uri.encode(uniqueKey)}"
            val mimeType = DlnaMimeTypes.of(fileName)

            DDLog.i(TAG, "投屏地址: $videoUrl")

            val result = DlnaController.playVideo(
                device = targetDevice,
                videoUrl = videoUrl,
                title = videoSource.getVideoTitle(),
                mimeType = mimeType,
                fileLength = storageFile.fileLength()
            )

            if (result.isFailure) {
                ToastCenter.showError("投屏失败：${result.exceptionOrNull()?.message ?: "电视拒绝了播放请求"}")
                stopSelf()
                return@launch
            }

            notifier.showProvideVideo(videoSource.getVideoTitle())
            // 这里只表示"电视接受了播放请求"，真正投屏成功以电视回来拉流为准（见 onProvideVideo）
            DDLog.i(TAG, "电视已接受播放请求: ${targetDevice.displayName}")
        }
    }

    /**
     * 起本地 HTTP 服务，然后把投屏数据 POST 给弹弹play 接收端（电视端 App）
     *
     * 这条路和 DLNA 的区别：接收端拿到弹幕/字幕文件名后会主动来请求 /danmu、/subtitle，
     * 所以电视上能显示弹幕和字幕；DLNA 电视只会拉 /video。
     */
    private fun castToScreencastDevice(
        videoSource: StorageVideoSource,
        targetDevice: UDPDeviceBean
    ) {
        // 重复点投屏时，先取消上一次的任务，避免两个服务实例抢端口
        castJob?.cancel()
        screencastDevice = targetDevice
        // 两条投屏路径互斥，清掉 DLNA 目标，避免 onDestroy 时给上一台电视发停止播放
        device = null
        castNotified = false

        castJob = serviceScope.launch {
            acquireLocks()

            stopHttpServer()

            val newHttpServer = createHttpServer(videoSource, httpPort)
            if (newHttpServer == null) {
                ToastCenter.showError("启用投屏服务失败")
                stopSelf()
                return@launch
            }
            httpServer = newHttpServer

            // 接收端要回连本机拿视频/弹幕/字幕，没有局域网地址就没法投
            if (LocalIpUtils.findLocalIpAddress().isNullOrEmpty()) {
                ToastCenter.showError("投屏失败：请先连接 WiFi")
                stopSelf()
                return@launch
            }

            val videoData = createScreencastVideoData(videoSource)
            val screencastData = ScreencastData(
                port = newHttpServer.listeningPort,
                relatedVideos = listOf(videoData),
                playUniqueKey = videoSource.getUniqueKey(),
                httpHeader = null
            )

            val baseUrl = "http://${targetDevice.ipAddress}:${targetDevice.httpPort}"
            val authorization = screencastPassword
                ?.takeIf { it.isNotEmpty() }
                ?.aesEncode()
                ?.authorizationValue()

            DDLog.i(
                TAG,
                "弹弹play投屏: ${baseUrl}${ScreencastConstants.ReceiverApi.play}" +
                    "，弹幕=${videoData.danmuFileName ?: "无"}，字幕=${videoData.subtitleFileName ?: "无"}"
            )

            val result = ScreencastRepository.play(baseUrl, authorization, screencastData)
            if (result.isFailure) {
                ToastCenter.showError("投屏失败：${result.exceptionOrNull()?.message ?: "接收端拒绝了播放请求"}")
                stopSelf()
                return@launch
            }

            notifier.showProvideVideo(videoSource.getVideoTitle())
            // 这里只表示"接收端接受了播放请求"，真正投屏成功以接收端回来拉流为准（见 onProvideVideo）
            DDLog.i(TAG, "弹弹play接收端已接受播放请求: ${targetDevice.deviceName}")
        }
    }

    /**
     * 组装投屏数据
     *
     * 关键点：接收端以「弹幕文件名非空」作为「有弹幕」的判据（字幕同理），
     * 文件名给了它才会回来请求 /danmu、/subtitle；文件名本身只用于判空和命名，不需要真实存在。
     */
    private fun createScreencastVideoData(videoSource: StorageVideoSource): ScreencastVideoData {
        val danmu = videoSource.getDanmu()
        val playHistory = videoSource.getStorageFile().playHistory

        return ScreencastVideoData(
            title = videoSource.getVideoTitle(),
            uniqueKey = videoSource.getUniqueKey(),
            episodeId = danmu?.episodeId,
            danmuFileName = danmu?.danmuPath
                ?.let { getFileName(it) }
                ?.takeIf { it.isNotEmpty() },
            subtitleFileName = videoSource.getSubtitlePath()
                ?.let { getFileName(it) }
                ?.takeIf { it.isNotEmpty() },
            position = playHistory?.videoPosition ?: 0L,
            duration = playHistory?.videoDuration ?: 0L
        )
    }

    private fun createHttpServer(
        videoSource: StorageVideoSource,
        port: Int,
        retry: Int = 5
    ): HttpServer? {
        return try {
            val newHttpServer = HttpServer(port, videoSource, this)
            newHttpServer.start(2000)
            // 记下真正可用的端口（端口被占用时会换端口重试）
            httpPort = port
            newHttpServer
        } catch (e: Exception) {
            DDLog.e(TAG, "启动 HTTP 服务失败(端口 $port): ${e.message}", e)
            if (retry > 0) {
                createHttpServer(videoSource, port + 1, retry - 1)
            } else {
                null
            }
        }
    }

    private fun stopHttpServer() {
        try {
            httpServer?.stop()
        } catch (e: Exception) {
            // ignore
        }
        httpServer = null
    }

    /**
     * 投屏期间别让 WiFi 睡过去（否则息屏一会儿就断流）、也别让 CPU 睡
     */
    @SuppressLint("WakelockTimeout", "MissingPermission")
    private fun acquireLocks() {
        if (wifiLock == null) {
            try {
                val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = manager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "dandanplay_cast_wifi"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                DDLog.e(TAG, "获取 WifiLock 失败: ${e.message}", e)
            }
        }
        if (wakeLock == null) {
            try {
                val powerManager =
                    applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "dandanplay_cast_wake"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } catch (e: Exception) {
                DDLog.e(TAG, "获取 WakeLock 失败: ${e.message}", e)
            }
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        wifiLock = null
        wakeLock = null
    }

    /**
     * 接收端真的回来拉流了 —— 到这一刻才算投屏成功
     */
    override fun onProvideVideo(videoSource: BaseVideoSource) {
        notifier.showProvideVideo(videoSource.getVideoTitle())

        if (castNotified.not()) {
            castNotified = true
            // 两条路只会有一条生效，这里取当前生效的那台设备名
            val targetName = device?.displayName ?: screencastDevice?.deviceName ?: return
            ToastCenter.showSuccess("已投屏到 $targetName")
        }
    }
}

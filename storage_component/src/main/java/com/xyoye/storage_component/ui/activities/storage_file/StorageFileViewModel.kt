package com.xyoye.storage_component.ui.activities.storage_file

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.base.app.BaseApplication
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.aesEncode
import com.xyoye.common_component.extension.authorizationValue
import com.xyoye.common_component.network.repository.ScreencastRepository
import com.xyoye.common_component.network.request.NetworkException
import com.xyoye.common_component.source.VideoSourceManager
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.UDPDeviceBean
import com.xyoye.storage_component.utils.dlna.DlnaDevice
import com.xyoye.storage_component.utils.dlna.SsdpDiscovery
import com.xyoye.storage_component.utils.screencast.provider.UdpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StorageFileViewModel : BaseViewModel() {
    val playLiveData = MutableLiveData<Any>()

    // 轻享版：投屏改成 DLNA，不再需要预先在媒体库里"添加投屏设备"，用的时候现搜
    // 搜到多个设备 -> 交给界面选
    val selectCastDeviceLiveData = MutableLiveData<DlnaDeviceList>()
    // 选好设备 -> 界面去启动投送服务
    val castDeviceLiveData = MutableLiveData<DlnaCastTarget>()
    // 一个都没搜到
    val noCastDeviceLiveData = MutableLiveData<Long>()

    // 轻享版新增：点「投屏」后先让用户选投屏方式（DLNA 电视 / 弹弹play 接收端）
    val selectCastMethodLiveData = MutableLiveData<Long>()
    // 搜到多个弹弹play 接收端 -> 交给界面选
    val selectScreencastDeviceLiveData = MutableLiveData<ScreencastDeviceList>()
    // 选好弹弹play 接收端 -> 界面去启动投送服务
    val castScreencastDeviceLiveData = MutableLiveData<ScreencastCastTarget>()
    // 接收端设置了连接密码 -> 界面弹输入框
    val screencastPasswordLiveData = MutableLiveData<ScreencastPasswordRequest>()
    // 一个弹弹play 接收端都没搜到
    val noScreencastDeviceLiveData = MutableLiveData<Long>()

    private data class CastRequest(val id: Long, val file: StorageFile)
    @Volatile private var pendingCastRequest: CastRequest? = null
    private var nextRequestId = 0L

    private var dlnaScanJob: Job? = null
    private var screencastScanJob: Job? = null
    private var castPreparationJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 下面几个都只是给 LiveData 包一层：LiveData 在值 equals 时不会回调，
     * 普通 class 用引用相等，保证每次搜索/每次投屏界面都能收到通知
     */
    class DlnaDeviceList(val requestId: Long, val devices: List<DlnaDevice>)
    class DlnaCastTarget(val requestId: Long, val device: DlnaDevice)
    class ScreencastDeviceList(val requestId: Long, val devices: List<UDPDeviceBean>)

    class ScreencastCastTarget(val requestId: Long, val device: UDPDeviceBean, val password: String?)

    class ScreencastPasswordRequest(val requestId: Long, val device: UDPDeviceBean)

    companion object {
        /** 接收端每 2s 组播播报一次，等两个周期，尽量把多台设备都收全 */
        private const val SCREENCAST_SCAN_TIMEOUT_MS = 4000L

        fun parseManualAddress(input: String): UDPDeviceBean? {
            val address = input.trim().removePrefix("http://")
            val parts = address.split(':')
            if (parts.size != 2) return null
            val octets = parts[0].split('.')
            if (octets.size != 4 || octets.any { it.toIntOrNull()?.let { n -> n in 0..255 } != true }) return null
            val port = parts[1].toIntOrNull() ?: return null
            if (port !in 1..65535) return null
            return UDPDeviceBean(ipAddress = parts[0], httpPort = port, deviceName = parts[0])
        }
    }

    fun playItem(file: StorageFile) {
        viewModelScope.launch(Dispatchers.IO) {
            if (setupVideoSource(file)) {
                playLiveData.postValue(Any())
            }
        }
    }

    /**
     * 点「投屏」：先选投屏方式（DLNA 电视 / 弹弹play 接收端）
     */
    fun requestCast(file: StorageFile) {
        dlnaScanJob?.cancel()
        screencastScanJob?.cancel()
        castPreparationJob?.cancel()
        val request = CastRequest(++nextRequestId, file)
        pendingCastRequest = request
        selectCastMethodLiveData.value = request.id
    }

    fun isCurrentCastRequest(requestId: Long): Boolean = pendingCastRequest?.id == requestId

    /**
     * 投到 DLNA 电视：先在局域网里搜 DLNA 投屏设备
     */
    fun castToDlnaDevice(requestId: Long) {
        val request = pendingCastRequest?.takeIf { it.id == requestId } ?: return
        dlnaScanJob?.cancel()
        dlnaScanJob = viewModelScope.launch {
            showLoading("正在搜索投屏设备…")
            val devices = try {
                SsdpDiscovery.discover(BaseApplication.getAppContext())
            } finally { hideLoading() }
            if (pendingCastRequest != request) return@launch

            when {
                devices.isEmpty() -> noCastDeviceLiveData.postValue(requestId)
                devices.size == 1 -> startCast(request, devices.first())
                else -> selectCastDeviceLiveData.postValue(DlnaDeviceList(requestId, devices))
            }
        }
    }

    /**
     * 用户在设备列表里选了一台电视
     */
    fun castItem(requestId: Long, device: DlnaDevice) {
        val request = pendingCastRequest?.takeIf { it.id == requestId } ?: return
        startCast(request, device)
    }

    /**
     * 投到弹弹play 接收端（电视端 App）：先在局域网里搜接收端设备
     */
    fun castToScreencastDevice(requestId: Long) {
        val request = pendingCastRequest?.takeIf { it.id == requestId } ?: return
        screencastScanJob?.cancel()
        screencastScanJob = viewModelScope.launch {
            showLoading("正在搜索弹弹play设备…")
            val devices = try { scanScreencastDevices() } finally { hideLoading() }
            if (pendingCastRequest != request) return@launch

            when {
                devices.isEmpty() -> noScreencastDeviceLiveData.postValue(requestId)
                devices.size == 1 -> startScreencastCast(request, devices.first())
                else -> selectScreencastDeviceLiveData.postValue(ScreencastDeviceList(requestId, devices))
            }
        }
    }

    /**
     * 用户在弹弹play设备列表里选了一台
     *
     * @param password 接收端设置了连接密码时，由界面输入后回传
     */
    fun castToScreencastDevice(requestId: Long, device: UDPDeviceBean, password: String? = null) {
        val request = pendingCastRequest?.takeIf { it.id == requestId } ?: return
        startScreencastCast(request, device, password)
    }

    fun castToManualScreencast(requestId: Long, address: String) {
        val device = parseManualAddress(address) ?: return
        castToScreencastDevice(requestId, device)
    }

    private fun startScreencastCast(
        request: CastRequest,
        device: UDPDeviceBean,
        password: String? = null
    ) {
        castPreparationJob?.cancel()
        castPreparationJob = viewModelScope.launch(Dispatchers.IO) {
            if (device.needPassword && password.isNullOrEmpty()) {
                screencastPasswordLiveData.postValue(ScreencastPasswordRequest(request.id, device))
                return@launch
            }
            val address = "http://${device.ipAddress}:${device.httpPort}"
            val auth = password?.takeIf { it.isNotEmpty() }?.aesEncode()?.authorizationValue()
            val result = ScreencastRepository.init(address, auth)
            if (pendingCastRequest != request) return@launch
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error is NetworkException && error.code == 401) {
                    if (!password.isNullOrEmpty()) ToastCenter.showWarning("连接密码错误，请重新输入")
                    screencastPasswordLiveData.postValue(ScreencastPasswordRequest(request.id, device))
                } else {
                    ToastCenter.showError("连接投屏设备失败：${error?.message ?: "请检查电视地址和密码"}")
                }
                return@launch
            }
            if (setupVideoSource(request.file) && pendingCastRequest == request) {
                castScreencastDeviceLiveData.postValue(ScreencastCastTarget(request.id, device, password))
            }
        }
    }

    /**
     * 组播搜索弹弹play 接收端
     *
     * UdpClient 是全局单例、且会一直阻塞接收，所以必须在超时后 stop，
     * 否则下一次搜索起不来（stop 会让它的接收循环退出）
     */
    private suspend fun scanScreencastDevices(): List<UDPDeviceBean> {
        val deviceList = mutableListOf<UDPDeviceBean>()
        val deviceLock = Any()

        acquireMulticastLock()
        val receiveJob = kotlinx.coroutines.CoroutineScope(kotlin.coroutines.coroutineContext).launch(Dispatchers.IO) {
            UdpClient.startMulticastReceive { device ->
                val ip = device.ipAddress
                if (!ip.isNullOrEmpty() && device.httpPort > 0) {
                    synchronized(deviceLock) {
                        // 接收端会重复播报，同一台设备只保留最新的一条
                        deviceList.removeAll { it.ipAddress == ip && it.httpPort == device.httpPort }
                        deviceList.add(device)
                    }
                }
            }
        }

        try {
            delay(SCREENCAST_SCAN_TIMEOUT_MS)
        } finally {
            // 先 stop 再 cancel：只 cancel 的话 UdpClient 会卡在 receive 里空转
            UdpClient.stopMulticastReceive()
            receiveJob.cancel()
            releaseMulticastLock()
        }

        return synchronized(deviceLock) { deviceList.toList() }
    }

    @SuppressLint("MissingPermission")
    private fun acquireMulticastLock() {
        if (multicastLock != null) {
            return
        }
        try {
            val manager = BaseApplication.getAppContext()
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = manager.createMulticastLock("dandanplay_cast_search").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            // 拿不到锁不影响流程，只是部分机型可能收不到组播
            multicastLock = null
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.release()
        } catch (e: Exception) {
            // ignore
        }
        multicastLock = null
    }

    fun quicklyPlay(storage: Storage) {
        viewModelScope.launch(Dispatchers.IO) {
            val history = DatabaseManager.instance.getPlayHistoryDao().gitStorageLastPlay(
                storageId = storage.library.id
            )
            if (history == null) {
                ToastCenter.showError("当前媒体库暂无播放记录")
                return@launch
            }

            val storageFile = storage.historyFile(history)
            if (storageFile == null) {
                ToastCenter.showError("播放失败，找不到上一次观看记录")
                return@launch
            }

            playItem(storageFile)
        }
    }

    private fun startCast(request: CastRequest, device: DlnaDevice) {
        castPreparationJob?.cancel()
        castPreparationJob = viewModelScope.launch(Dispatchers.IO) {
            if (setupVideoSource(request.file) && pendingCastRequest == request) {
                castDeviceLiveData.postValue(DlnaCastTarget(request.id, device))
            }
        }
    }

    override fun onCleared() {
        screencastScanJob?.cancel()
        dlnaScanJob?.cancel()
        castPreparationJob?.cancel()
        // UdpClient 是全局单例，页面销毁时一定要停掉组播接收
        UdpClient.stopMulticastReceive()
        releaseMulticastLock()
        super.onCleared()
    }

    private suspend fun setupVideoSource(file: StorageFile): Boolean {
        showLoading()
        val mediaSource = StorageVideoSourceFactory.create(file)
        hideLoading()

        if (mediaSource == null) {
            ToastCenter.showError("播放失败，找不到播放资源")
            return false
        }
        VideoSourceManager.getInstance().setSource(mediaSource)
        return true
    }
}

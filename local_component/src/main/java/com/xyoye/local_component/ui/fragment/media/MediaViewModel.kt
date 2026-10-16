package com.xyoye.local_component.ui.fragment.media

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.viewModelScope
import com.xyoye.common_component.base.BaseViewModel
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.aesEncode
import com.xyoye.common_component.extension.authorizationValue
import com.xyoye.common_component.extension.toastError
import com.xyoye.common_component.network.repository.ScreencastRepository
import com.xyoye.common_component.utils.getFileName
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Created by xyoye on 2020/7/27.
 */

class MediaViewModel : BaseViewModel() {

    val mediaLibWithStatusLiveData = MediatorLiveData<MutableList<MediaLibraryEntity>>().apply {
        val mediaLibrariesLiveData = DatabaseManager.instance.getMediaLibraryDao().getAll()
        val serviceStatusLiveData = ServiceLifecycleBridge.getScreencastProvideLiveData()
        //媒体库数据源
        addSource(mediaLibrariesLiveData) { libraries ->
            // 轻享版：隐藏「磁链播放 / BT」入口（只隐藏入口，代码保留可恢复）。
            // 这里同时兜住数据库里已存在的历史记录，装过旧版本也不会漏出来。
            // 投屏改成 DLNA 之后，「远程投屏设备」这类媒体库项也一并隐藏
            // （DLNA 用的时候现搜电视，不需要预先添加设备）
            val visibleLibraries = libraries
                .filter {
                    it.mediaType != MediaType.MAGNET_LINK &&
                        it.mediaType != MediaType.SCREEN_CAST
                }
                .toMutableList()
            visibleLibraries.onEach {
                it.running =
                    it.mediaType == MediaType.SCREEN_CAST && it == serviceStatusLiveData.value
            }
            this.postValue(visibleLibraries)
        }
        //投屏服务状态数据源
        addSource(serviceStatusLiveData) { running ->
            val newData = this.value?.onEach {
                it.running = it.mediaType == MediaType.SCREEN_CAST && it == running
            } ?: mutableListOf()
            this.postValue(newData)
        }
    }

    fun initLocalStorage() {
        viewModelScope.launch(context = Dispatchers.IO) {
            //播放历史首条记录
            DatabaseManager.instance.getPlayHistoryDao().gitLastPlay(
                MediaType.LOCAL_STORAGE,
                MediaType.OTHER_STORAGE,
                MediaType.FTP_SERVER,
                MediaType.SMB_SERVER,
                MediaType.REMOTE_STORAGE,
                MediaType.WEBDAV_SERVER
            )?.apply {
                MediaLibraryEntity.HISTORY.url = url
            }

            //磁链播放首条记录
            DatabaseManager.instance.getPlayHistoryDao().gitLastPlay(MediaType.MAGNET_LINK)?.apply {
                MediaLibraryEntity.TORRENT.describe = getFileName(torrentPath)
            }

            //串流播放首条记录
            DatabaseManager.instance.getPlayHistoryDao().gitLastPlay(MediaType.STREAM_LINK)?.apply {
                MediaLibraryEntity.STREAM.describe = url
            }

            DatabaseManager.instance.getMediaLibraryDao()
                .insert(
                    MediaLibraryEntity.LOCAL,
                    MediaLibraryEntity.STREAM,
                    // 轻享版：不再创建「磁链播放」入口（保留 MediaLibraryEntity.TORRENT 本身）
                    // MediaLibraryEntity.TORRENT,
                    MediaLibraryEntity.HISTORY
                )
        }
    }

    fun deleteStorage(data: MediaLibraryEntity) {
        viewModelScope.launch(context = Dispatchers.IO) {
            DatabaseManager.instance.getMediaLibraryDao()
                .delete(data.url, data.mediaType)
        }
    }

    fun checkScreenDeviceRunning(receiver: MediaLibraryEntity) {
        viewModelScope.launch {
            showLoading()
            val result = ScreencastRepository.init(
                "http://${receiver.screencastAddress}:${receiver.port}",
                receiver.password?.aesEncode()?.authorizationValue()
            )
            hideLoading()

            if (result.isFailure) {
                result.exceptionOrNull()?.message?.toastError()
                return@launch
            }

            ToastCenter.showSuccess("投屏设备连接正常，请前往其它媒体库选择文件投屏")
        }
    }
}
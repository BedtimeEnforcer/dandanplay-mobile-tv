package com.xyoye.common_component.source.factory

import com.xyoye.common_component.config.AppConfig
import com.xyoye.common_component.config.DanmuConfig
import com.xyoye.common_component.config.SubtitleConfig
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageSortOption
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.file.impl.ScreencastStorageFile
import com.xyoye.common_component.utils.DDLog
import com.xyoye.data_component.bean.LocalDanmuBean
import java.io.File

/**
 * Created by xyoye on 2023/1/2.
 */

object StorageVideoSourceFactory {

    private const val TAG = "VideoSourceFactory"

    suspend fun create(file: StorageFile): StorageVideoSource? {
        val storage = file.storage
        val videoSources = getVideoSources(storage)
        val playUrl = storage.createPlayUrl(file) ?: return null
        val danmu = findLocalDanmu(file, storage)
        val subtitlePath = getSubtitlePath(file, storage)
        val audioPath = file.playHistory?.audioPath
        return StorageVideoSource(
            playUrl,
            file,
            videoSources,
            danmu,
            subtitlePath,
            audioPath
        ).also {
            //排查「弹幕/字幕没自动加载、投屏没带弹幕字幕」时看这一行
            DDLog.i(
                TAG,
                "${file.fileName()} 弹幕=${danmu?.danmuPath ?: "无"} 字幕=${subtitlePath ?: "无"}"
            )
        }
    }

    private suspend fun findLocalDanmu(file: StorageFile, storage: Storage): LocalDanmuBean? {
        val history = file.playHistory
        val screencastFile = file as? ScreencastStorageFile
        if (screencastFile != null) {
            // 私有协议投屏以本次请求为准，不受电视端的同名自动加载开关影响。
            if (screencastFile.getRealFile().danmuFileName.isNullOrEmpty()) return null
            val cachedPath = history?.danmuPath
            if (isReadableFile(cachedPath)) {
                return LocalDanmuBean(cachedPath!!, history.episodeId)
            }
            return storage.cacheDanmu(file)
        }

        //从播放记录读取弹幕
        if (history?.danmuPath?.isNotEmpty() == true) {
            return LocalDanmuBean(history.danmuPath!!, history.episodeId)
        }

        //是否匹配同文件夹内同名弹幕
        if (DanmuConfig.isAutoLoadSameNameDanmu()) {
            return storage.cacheDanmu(file)
        }

        return null
    }

    private suspend fun getSubtitlePath(file: StorageFile, storage: Storage): String? {
        val subtitleNotFound = null

        val screencastFile = file as? ScreencastStorageFile
        if (screencastFile != null) {
            // 旧播放记录中的字幕若已删除，重新从手机端获取。
            if (screencastFile.getRealFile().subtitleFileName.isNullOrEmpty()) return null
            val cachedPath = file.playHistory?.subtitlePath
            if (isReadableFile(cachedPath)) {
                return cachedPath
            }
            return storage.cacheSubtitle(file)
        }

        //从播放记录读取弹幕
        if (file.playHistory?.subtitlePath?.isNotEmpty() == true) {
            return file.playHistory?.subtitlePath
        }

        //是否匹配同文件夹内同名字幕
        if (SubtitleConfig.isAutoLoadSameNameSubtitle()) {
            return storage.cacheSubtitle(file)
                ?: subtitleNotFound
        }

        return subtitleNotFound
    }

    private fun getVideoSources(storage: Storage): List<StorageFile> {
        return storage.directoryFiles
            .filter { it.isVideoFile() }
            .filter { AppConfig.isShowHiddenFile() || !it.fileName().startsWith(".") }
            .sortedWith(StorageSortOption.comparator())
    }

    private fun isReadableFile(path: String?): Boolean =
        path?.let { File(it).isFile && File(it).canRead() } == true
}

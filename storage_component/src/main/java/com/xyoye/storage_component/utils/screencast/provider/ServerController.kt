package com.xyoye.storage_component.utils.screencast.provider

import android.net.Uri
import com.xyoye.common_component.database.DatabaseManager
import com.xyoye.common_component.extension.resourceType
import com.xyoye.common_component.network.config.HeaderKey
import com.xyoye.common_component.source.base.BaseVideoSource
import com.xyoye.common_component.source.factory.StorageVideoSourceFactory
import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.helper.ScreencastConstants
import com.xyoye.common_component.storage.helper.ScreencastConstants.ProviderApi
import com.xyoye.common_component.utils.RangeUtils
import com.xyoye.common_component.utils.getFileExtension
import com.xyoye.data_component.enums.ResourceType
import com.xyoye.storage_component.services.ScreencastProvideHandler
import com.xyoye.storage_component.utils.dlna.DlnaMimeTypes
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Date

/**
 * Created by xyoye on 2022/9/15
 */

class ServerController(
    private val videoSource: StorageVideoSource,
    private val listener: ScreencastProvideHandler
) {
    private val resourceNotFound: Response by lazy {
        NanoHTTPD.newFixedLengthResponse(
            Response.Status.NOT_FOUND,
            NanoHTTPD.MIME_HTML,
            "资源不存在"
        )
    }

    suspend fun handleSession(session: IHTTPSession): Response {
        val targetVideoSource = session.parameters[ScreencastConstants.Param.uniqueKey]
            ?.firstOrNull()
            ?.let { findVideoSource(it) }
            ?: return resourceNotFound

        val api = ProviderApi.fromPath(session.uri)
            ?: return resourceNotFound

        return when (api) {
            ProviderApi.VIDEO -> createVideoResponse(targetVideoSource, session)
            ProviderApi.DANMU -> createDanmuResponse(targetVideoSource)
            ProviderApi.SUBTITLE -> createSubtitleResponse(targetVideoSource)
            ProviderApi.CALLBACK -> handleScreencastCallback(targetVideoSource, session)
        }
    }

    /**
     * 创建视频资源响应结果
     */
    private suspend fun createVideoResponse(
        videoSource: StorageVideoSource,
        session: IHTTPSession
    ): Response {
        val videoUrl = videoSource.getVideoUrl()

        return if (videoUrl.resourceType() == ResourceType.URL) {
            createVideoResponseRedirect(videoSource, session)
        } else {
            createVideoResponseDirect(videoSource, session)
        }.also {
            considerInvokeCallback(it)
        }
    }

    /**
     * 创建重定向的视频资源响应结果
     */
    private fun createVideoResponseRedirect(
        videoSource: BaseVideoSource,
        session: IHTTPSession
    ): Response {
        var redirectUrl = videoSource.getVideoUrl()

        // 如果链接是localHost，且请求头存在host，使用host的值替换localHost
        val localhost = "127.0.0.1"
        val isLocalhostUrl = redirectUrl.contains(localhost)
        if (isLocalhostUrl) {
            val sessionHost = session.headers["host"]
            if (!sessionHost.isNullOrEmpty()) {
                val realHost = Uri.parse("http://$sessionHost").host ?: sessionHost
                redirectUrl = redirectUrl.replace(localhost, realHost)
            }
        }

        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.REDIRECT,
            getContentType(redirectUrl),
            "redirect to real source"
        ).apply {
            addHeader("Location", redirectUrl)
            addHeader(HeaderKey.AUTH_REDIRECT, "redirect")
        }
    }

    /**
     * 创建视频资源响应结果
     */
    private suspend fun createVideoResponseDirect(
        videoSource: StorageVideoSource,
        session: IHTTPSession
    ): Response {
        val storageFile = videoSource.getStorageFile()
        val fileLength = storageFile.fileLength()

        // 先解析 Range —— 此时还没打开文件流，非法 Range 直接返回 416，不会漏 fd
        val rangeText = session.headers["range"] ?: ""
        val range = RangeUtils.parseRange(rangeText, fileLength)

        // 带有Range头，但解析失败，返回416
        if (rangeText.isNotEmpty() && range == null) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.RANGE_NOT_SATISFIABLE,
                NanoHTTPD.MIME_HTML,
                "请求资源位置不合法"
            ).apply {
                addHeader("Content-Range", "bytes */$fileLength")
            }
        }

        val inputStream = storageFile.storage.openFile(storageFile)
            ?: return resourceNotFound

        val contentType = getContentType(storageFile.fileName())

        // Range不存在，返回200（用定长而不是 chunked：部分电视不接受分块传输）
        if (range == null) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.OK,
                contentType,
                inputStream,
                fileLength
            ).apply {
                addHeader("Accept-Ranges", "bytes")
                addHeader("transferMode.dlna.org", "Streaming")
            }
        }

        // 跳过 Range 起始位置。skip() 不保证一次跳完，必须循环跳
        var skipFailed = false
        var remaining = range.first
        try {
            while (remaining > 0) {
                val skipped = inputStream.skip(remaining)
                if (skipped <= 0) {
                    break
                }
                remaining -= skipped
            }
        } catch (e: IOException) {
            e.printStackTrace()
            skipFailed = true
        }

        // 跳不满就不能硬发，否则是「正确的 206 头 + 错误的数据偏移」，电视会花屏/播放失败
        if (skipFailed || remaining > 0) {
            try {
                inputStream.close()
            } catch (e: Exception) {
                // ignore
            }
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                NanoHTTPD.MIME_HTML,
                "读取数据失败"
            )
        }

        // 计算Range长度；Content-Range 的斜杠后面必须是【整个文件长度】
        val rangeLength = range.second - range.first + 1
        val contentRange = "bytes ${range.first}-${range.second}/$fileLength"

        // 返回206
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            contentType,
            inputStream,
            rangeLength
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", contentRange)
            addHeader("transferMode.dlna.org", "Streaming")
        }
    }

    /**
     * 获取内容类型
     *
     * 轻享版：改用标准 MIME（原来的 `video/<扩展名>` 会得到 video/mkv 这种非标准值，
     * DLNA 电视可能直接拒绝）。HTTP 响应和 DIDL-Lite 的 protocolInfo 用同一套映射。
     */
    private fun getContentType(filePath: String): String {
        if (filePath.isEmpty()) {
            return "video/*"
        }
        return DlnaMimeTypes.of(filePath)
    }

    /**
     * 创建弹幕资源响应结果
     */
    private fun createDanmuResponse(
        videoSource: BaseVideoSource
    ): Response {
        //弹幕文件路径为空
        val danmuPath = videoSource.getDanmu()?.danmuPath
        if (danmuPath.isNullOrEmpty()) {
            return resourceNotFound
        }
        //弹幕文件不存在
        val danmuFile = File(danmuPath)
        if (danmuFile.exists().not() || danmuFile.canRead().not()) {
            return resourceNotFound
        }

        return NanoHTTPD.newChunkedResponse(
            Response.Status.OK,
            "*/xml",
            FileInputStream(danmuFile)
        )
    }

    /**
     * 创建字幕资源响应结果
     */
    private fun createSubtitleResponse(
        videoSource: BaseVideoSource
    ): Response {
        //字幕文件路径为空
        val subtitlePath = videoSource.getSubtitlePath()
        if (subtitlePath.isNullOrEmpty()) {
            return resourceNotFound
        }
        //字幕文件路径为空
        val subtitleFile = File(subtitlePath)
        if (subtitleFile.exists().not() || subtitleFile.canRead().not()) {
            return resourceNotFound
        }
        val fileNameExtension = getFileExtension(subtitleFile)

        return NanoHTTPD.newChunkedResponse(
            Response.Status.OK,
            "*/$fileNameExtension",
            FileInputStream(subtitleFile)
        )
    }

    /**
     * 处理视频播放回调
     */
    private suspend fun handleScreencastCallback(
        videoSource: StorageVideoSource,
        session: IHTTPSession
    ): Response {
        val position = session.parameters[ScreencastConstants.Param.position]?.firstOrNull()?.toLongOrNull()
        val duration = session.parameters[ScreencastConstants.Param.duration]?.firstOrNull()?.toLongOrNull()
        if (position == null || duration == null) {
            return NanoHTTPD.newFixedLengthResponse(
                Response.Status.PRECONDITION_FAILED,
                NanoHTTPD.MIME_HTML,
                "参数错误"
            )
        }

        // 修改本地播放记录中的进度
        val newHistory = videoSource.getStorageFile().playHistory
            ?.copy(videoPosition = position, videoDuration = duration, playTime = Date())
            ?: return resourceNotFound

        // 更新本地播放记录
        DatabaseManager.instance.getPlayHistoryDao().insert(newHistory)

        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            NanoHTTPD.MIME_HTML,
            "播放记录已更新"
        )
    }

    /**
     * 根据Key获取目标视频资源
     */
    private suspend fun findVideoSource(uniqueKey: String): StorageVideoSource? {
        if (videoSource.getUniqueKey() == uniqueKey) {
            return videoSource
        }

        var targetFile: StorageFile? = null
        for (index in 0 until videoSource.getGroupSize()) {
            val storageFile = videoSource.indexStorageFile(index)
            if (storageFile.uniqueKey() == uniqueKey) {
                targetFile = storageFile
                break
            }
        }
        return targetFile?.let { StorageVideoSourceFactory.create(it) }
    }

    /**
     * 考虑执行视频投屏成功回调
     */
    private fun considerInvokeCallback(response: Response) {
        if (response.status == Response.Status.OK ||
            response.status == Response.Status.PARTIAL_CONTENT ||
            response.status == Response.Status.REDIRECT
        ) {
            // 200、206、301的响应视为投屏成功
            listener.onProvideVideo(videoSource)
        }
    }
}
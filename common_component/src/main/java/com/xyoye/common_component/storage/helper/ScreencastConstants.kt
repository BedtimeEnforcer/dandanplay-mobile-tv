package com.xyoye.common_component.storage.helper

import android.net.Uri
import com.xyoye.data_component.data.screeencast.ScreencastData
import com.xyoye.data_component.data.screeencast.ScreencastVideoData

/**
 * Created by xyoye on 2024/1/31
 * 投屏相关常量
 */

object ScreencastConstants {

    // 投屏版本
    const val version = 1

    // 组播
    object Multicast {
        const val port = 12333
        const val host = "239.254.254.254"
        const val secret = "03YSdjQY7q3bDdnq"
        const val intervalMs = 2000L
    }

    // 请求头
    object Header {
        const val versionKey = "screencast-version"
    }

    // 请求参数
    object Param {
        const val uniqueKey = "uniqueKey"

        const val position = "position"
        const val duration = "duration"
    }

    // 投屏资源提供端API
    enum class ProviderApi(private val path: String) {
        VIDEO("/video"),
        DANMU("/danmu"),
        SUBTITLE("/subtitle"),
        CALLBACK("/callback");

        companion object {
            /**
             * 轻享版：视频地址现在会带上文件后缀（/video/xxx.mkv?uniqueKey=...）。
             * 因为很多 DLNA 电视靠 URL 后缀判断容器格式、挑解码器，没有后缀会直接拒绝播放。
             * 所以这里同时支持「精确匹配」和「带子路径」两种形式。
             */
            fun fromPath(path: String) = values().firstOrNull {
                it.path == path || path.startsWith(it.path + "/")
            }
        }

        fun buildUrl(screencast: ScreencastData, video: ScreencastVideoData): String {
            val host = screencast.ip.orEmpty()
            val port = screencast.port
            val uniqueKey = video.uniqueKey
            return Uri.Builder()
                .scheme("http")
                .encodedAuthority("$host:$port")
                .path(path)
                .appendQueryParameter(Param.uniqueKey, uniqueKey)
                .build()
                .toString()
        }
    }

    // 投屏资源接收端API
    object ReceiverApi {
        const val init = "/init"

        const val play = "/play"

        const val config = "/remote/config"

        const val control = "/remote/control"
    }
}
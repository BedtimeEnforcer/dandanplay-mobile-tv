package com.xyoye.storage_component.utils.screencast.provider

import com.xyoye.common_component.source.media.StorageVideoSource
import com.xyoye.storage_component.services.ScreencastProvideHandler
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * <pre>
 *     author: xyoye1997@outlook.com
 *     time  : 2022/7/21
 *     desc  : 投屏内容提供方（Phone）HTTP服务器
 *
 *     轻享版：投屏改成 DLNA 后，这个服务就是「手机给电视提供视频文件」的本地 HTTP 服务。
 * </pre>
 */

class HttpServer(
    port: Int,
    private val videoSource: StorageVideoSource,
    private val listener: ScreencastProvideHandler
) : NanoHTTPD(port) {

    private val controller: ServerController by lazy {
        ServerController(videoSource, listener)
    }

    override fun serve(session: IHTTPSession?): Response {
        // 支持 GET 和 HEAD：有些电视会先发 HEAD 探测文件大小 / 是否支持 Range，
        // 原来只处理 GET，HEAD 会落到 super.serve() 变成 404，导致电视直接放弃投屏
        if (session != null && (session.method == Method.GET || session.method == Method.HEAD)) {
            // 用自己的 IO 上下文，不要挂在 service 的 Job 上 ——
            // service 停止时 cancel 掉 serviceScope 会打断正在发送的响应
            return runBlocking {
                withContext(Dispatchers.IO) {
                    controller.handleSession(session)
                }
            }
        }
        return super.serve(session)
    }
}

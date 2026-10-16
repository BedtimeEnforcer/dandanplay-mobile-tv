package com.xyoye.storage_component.utils.dlna

import com.xyoye.common_component.utils.DDLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * 轻享版新增：DLNA 播放控制（UPnP AVTransport 的 SOAP 调用）。
 *
 * 只管几件事：把视频地址塞给电视（SetAVTransportURI）→ 让电视播（Play）→ 需要时暂停/停止/拖进度。
 * 视频文件本身由手机的本地 HTTP 服务提供，电视自己去拉流。
 */
object DlnaController {

    private const val TAG = "DlnaController"
    private const val INSTANCE_ID = "0"

    /** 投屏主流程：设置地址 + 开始播放 */
    suspend fun playVideo(
        device: DlnaDevice,
        videoUrl: String,
        title: String,
        mimeType: String,
        fileLength: Long
    ): Result<Unit> {
        val metadata = buildMetadata(title, videoUrl, mimeType, fileLength)
        val setResult = setAvTransportUri(device, videoUrl, metadata)
        if (setResult.isFailure) {
            return setResult
        }
        return play(device)
    }

    /** 告诉电视要播放哪个地址 */
    suspend fun setAvTransportUri(
        device: DlnaDevice,
        videoUrl: String,
        metadata: String
    ): Result<Unit> {
        val body = buildString {
            append("<InstanceID>$INSTANCE_ID</InstanceID>")
            append("<CurrentURI>${escapeXml(videoUrl)}</CurrentURI>")
            append("<CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>")
        }
        return invoke(device, "SetAVTransportURI", body)
    }

    suspend fun play(device: DlnaDevice): Result<Unit> {
        val body = "<InstanceID>$INSTANCE_ID</InstanceID><Speed>1</Speed>"
        return invoke(device, "Play", body)
    }

    suspend fun pause(device: DlnaDevice): Result<Unit> {
        val body = "<InstanceID>$INSTANCE_ID</InstanceID>"
        return invoke(device, "Pause", body)
    }

    /** 停止播放（退出投屏时用，否则电视端会一直转圈） */
    suspend fun stop(device: DlnaDevice): Result<Unit> {
        val body = "<InstanceID>$INSTANCE_ID</InstanceID>"
        return invoke(device, "Stop", body)
    }

    /** 按毫秒拖进度 */
    suspend fun seek(device: DlnaDevice, positionMs: Long): Result<Unit> {
        val body = buildString {
            append("<InstanceID>$INSTANCE_ID</InstanceID>")
            append("<Unit>REL_TIME</Unit>")
            append("<Target>${formatDuration(positionMs)}</Target>")
        }
        return invoke(device, "Seek", body)
    }

    private suspend fun invoke(
        device: DlnaDevice,
        action: String,
        body: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val payload = buildEnvelope(device.serviceType, action, body).toByteArray(Charsets.UTF_8)
            connection = (URL(device.controlUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
                setRequestProperty("SOAPAction", "\"${device.serviceType}#$action\"")
                setRequestProperty("User-Agent", "Android/UPnP/1.0 DandanplayCast/1.0")
                setRequestProperty("Connection", "close")
            }
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            // 少数设备返回的 Fault 不带 s: 前缀，所以只判断 "Fault"
            if (code in 200..299 && responseText.contains("Fault").not()) {
                DDLog.i(TAG, "$action 成功 -> ${device.displayName}")
                Result.success(Unit)
            } else {
                val message = "$action 失败(HTTP $code ${describeFault(responseText)})"
                DDLog.e(TAG, "$message -> ${device.displayName}")
                Result.failure(IOException(message))
            }
        } catch (e: Exception) {
            DDLog.e(TAG, "$action 异常: ${e.message} -> ${device.displayName}", e)
            Result.failure(e)
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun buildEnvelope(serviceType: String, action: String, body: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\r\n")
        append("<s:Body>\r\n")
        append("<u:$action xmlns:u=\"$serviceType\">\r\n")
        append(body)
        append("\r\n</u:$action>\r\n")
        append("</s:Body>\r\n")
        append("</s:Envelope>\r\n")
    }

    /**
     * 构造 DIDL-Lite 元数据。电视靠它判断媒体类型和大小；
     * size 缺失时部分电视不肯拖进度，所以带上。
     */
    private fun buildMetadata(
        title: String,
        videoUrl: String,
        mimeType: String,
        fileLength: Long
    ): String {
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>${escapeXml(title)}</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("<res protocolInfo=\"http-get:*:$mimeType:*\"")
            if (fileLength > 0) {
                append(" size=\"$fileLength\"")
            }
            append(">${escapeXml(videoUrl)}</res>")
            append("</item>")
            append("</DIDL-Lite>")
        }
    }

    /** 从 SOAP Fault 里挑一句人话出来，方便排查电视为什么拒绝 */
    private fun describeFault(responseText: String): String {
        if (responseText.isBlank()) {
            return "无响应内容"
        }
        val description = Regex("<errorDescription>(.*?)</errorDescription>", RegexOption.DOT_MATCHES_ALL)
            .find(responseText)?.groupValues?.getOrNull(1)?.trim()
        if (!description.isNullOrEmpty()) {
            return description
        }
        val code = Regex("<errorCode>(.*?)</errorCode>").find(responseText)?.groupValues?.getOrNull(1)
        if (!code.isNullOrEmpty()) {
            return "UPnP错误码 $code"
        }
        return responseText.take(120)
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    /** UPnP 的 REL_TIME 必须是 ASCII 的 HH:mm:ss，所以固定用 Locale.US */
    private fun formatDuration(positionMs: Long): String {
        val totalSeconds = (positionMs / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

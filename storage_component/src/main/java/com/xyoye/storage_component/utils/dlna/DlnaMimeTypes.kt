package com.xyoye.storage_component.utils.dlna

/**
 * 轻享版新增：视频文件的 MIME 类型。
 *
 * 原来投屏用的是 `video/<扩展名>`（例如 video/mkv），那不是标准写法，DLNA 电视可能直接拒绝。
 * 这里按扩展名给出标准 MIME，HTTP 响应和 DIDL-Lite 的 protocolInfo 都用它。
 */
object DlnaMimeTypes {

    fun of(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "ts", "m2ts", "mts", "tp" -> "video/mp2t"
            "flv" -> "video/x-flv"
            "wmv", "asf" -> "video/x-ms-wmv"
            "mpg", "mpeg", "vob" -> "video/mpeg"
            "3gp" -> "video/3gpp"
            "rm", "rmvb" -> "application/vnd.rn-realmedia"
            // 认不出扩展名时不要返回 video/*（非标准值，DLNA 电视可能直接拒绝），
            // 用一个最常见的合法值兜底
            else -> "video/mp4"
        }
    }
}

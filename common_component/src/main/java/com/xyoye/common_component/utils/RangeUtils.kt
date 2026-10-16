package com.xyoye.common_component.utils

object RangeUtils {

    fun getRange(rangeText: String, contentLength: Long): Array<Long> {
        val rangArray = Array<Long>(3) { 0 }

        val range = parseRange(rangeText, contentLength)
            ?: return rangArray

        rangArray[0] = range.first
        rangArray[1] = range.second
        rangArray[2] = contentLength
        return rangArray
    }

    /**
     * 解析Range请求头的值
     *
     * 轻享版修正（DLNA 投屏要用，原来这几处不合 HTTP 规范，会让电视拖不动进度）：
     *  1. `bytes=-N`（**最后 N 字节**）原来被当成"从 0 取到 N"，语义错了 ——
     *     播放器读 MP4 的 moov / MKV 索引就是用这种请求，拿错数据就 seek 不了
     *  2. 起点超出文件长度时原来会返回 start > end 的非法区间（响应头里出现
     *     `bytes 999999999999-372458302/...` 这种东西），现在返回 null → 上层回 416
     *  3. 终点超出文件长度时按规范**截断**到文件末尾，而不是判非法
     *  4. `bytes=500-500`（单字节区间）是合法的，原来被 `start >= end` 误判为非法
     */
    fun parseRange(rangeValue: String, contentLength: Long): Pair<Long, Long>? {
        if (contentLength <= 0) {
            return null
        }

        val maxRange = contentLength - 1
        val separator = "-"
        val header = "bytes="
        val range = rangeValue.trim().replace(header, "", ignoreCase = true).trim()

        // e.g. "" or "-"
        if (range.length < 2) {
            return null
        }
        // e.g. "abc"
        val separatorIndex = range.indexOf(separator)
        if (separatorIndex == -1) {
            return null
        }

        // e.g. "-499"：最后 499 字节（HTTP 规范的后缀 Range）
        if (separatorIndex == 0) {
            val suffixLength = range.substring(1).toLongOrNull() ?: return null
            if (suffixLength <= 0) {
                return null
            }
            return if (suffixLength >= contentLength) {
                0L to maxRange
            } else {
                (contentLength - suffixLength) to maxRange
            }
        }

        val start = range.substring(0, separatorIndex).toLongOrNull() ?: return null
        // 起点超出文件范围 -> 非法，交给上层返回 416
        if (start < 0 || start > maxRange) {
            return null
        }

        // e.g. "500-"：从 500 到文件结尾
        if (separatorIndex == range.length - 1) {
            return start to maxRange
        }

        // e.g. "500-999"
        val rawEnd = range.substring(separatorIndex + 1).toLongOrNull() ?: return null
        // 终点超出文件范围：按规范截断到文件末尾
        val end = if (rawEnd > maxRange) maxRange else rawEnd

        return if (end < start) {
            null
        } else {
            start to end
        }
    }
}
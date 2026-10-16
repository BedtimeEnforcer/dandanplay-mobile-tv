package com.xyoye.common_component.extension

import com.xyoye.data_component.enums.MediaType

/**
 * Created by xyoye on 2023/1/3
 */

val MediaType.deletable: Boolean
    get() = when (this) {
        MediaType.LOCAL_STORAGE,
        MediaType.STREAM_LINK,
        MediaType.MAGNET_LINK,
        MediaType.OTHER_STORAGE,
        // 轻享版：投屏改成 DLNA 后，不再需要手动「添加投屏设备」，
        // 所以它不该出现在「新增媒体库」的可选列表里
        MediaType.SCREEN_CAST -> false
        else -> true
    }
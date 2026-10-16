package com.xyoye.common_component.network.repository

import com.xyoye.common_component.network.Retrofit

/**
 * Created by xyoye on 2024/1/6.
 *
 * 轻享版说明：账号系统（登录 / 注册 / 找回 / 改昵称 / 改密码）已整体移除，
 * 上游也早已关闭这些用户接口。这里只保留与账号无关的「开发者凭证校验」。
 */

object UserRepository : BaseRepository() {

    /**
     * 校验凭证（弹弹play 开放平台的 appId / appSecret，非账号系统）
     */
    suspend fun checkAuthenticate(appId: String, appSecret: String) = request()
        .doGet {
            Retrofit.danDanService.checkAuthenticate(appId, appSecret, 1)
        }
}

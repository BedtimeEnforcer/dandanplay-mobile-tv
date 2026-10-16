package com.xyoye.dandanplay.tv

import android.content.Context
import androidx.multidex.MultiDex
import com.xyoye.common_component.base.app.BaseApplication

/**
 * 电视端 Application。
 *
 * 和手机端一样继承 BaseApplication（MMKV、ARouter、崩溃上报等初始化都在父类里），
 * 只是额外处理一下 MultiDex。
 */
class TvApplication : BaseApplication() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}

package com.xyoye.dandanplay.ui.splash

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.app.AppCompatDelegate
import com.gyf.immersionbar.ImmersionBar
import com.xyoye.common_component.base.BaseAppCompatActivity
import com.xyoye.common_component.config.AppConfig
import com.xyoye.dandanplay.R
import com.xyoye.dandanplay.databinding.ActivitySplashBinding
import com.xyoye.dandanplay.ui.main.MainActivity

/**
 * Created by xyoye on 2020/7/27.
 */

class SplashActivity : BaseAppCompatActivity<ActivitySplashBinding>() {
    override fun getLayoutId() = R.layout.activity_splash

    override fun initStatusBar() {
        ImmersionBar.with(this)
            .transparentBar()
            .statusBarDarkFont(false)
            .init()
    }

    override fun initView() {
        window.run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            statusBarColor = Color.TRANSPARENT
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        }

        val mode = AppConfig.getDarkMode()
        AppCompatDelegate.setDefaultNightMode(mode)

        // 轻享版：跳过启动动画，打开即进入媒体库主界面
        launchActivity()
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        return if (event!!.keyCode == KeyEvent.KEYCODE_BACK) {
            true
        } else {
            super.dispatchKeyEvent(event)
        }
    }

    override fun onDestroy() {
        dataBinding.textPathView.cancelAnim()
        super.onDestroy()
    }

    private fun launchActivity() {
        val intent = Intent(this@SplashActivity, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(R.anim.anim_activity_enter, R.anim.anim_activity_exit)
        finish()
    }
}
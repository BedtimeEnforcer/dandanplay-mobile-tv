package com.xyoye.dandanplay.tv.ui

import android.Manifest
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.alibaba.android.arouter.launcher.ARouter
import com.xyoye.common_component.application.DanDanPlay
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.config.ScreencastConfig
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.dandanplay.tv.R
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.data_component.enums.MediaType
import com.xyoye.storage_component.services.ScreencastReceiveService
import com.xyoye.storage_component.utils.dlna.LocalIpUtils
import kotlin.random.Random

/**
 * 电视端首页。
 *
 * 只做两件事：本地媒体库播放 + 投屏接收端。
 * 交互全部按遥控器设计：方向键移动焦点、确认键进入，进页面默认聚焦第一个入口。
 */
class TvMainActivity : AppCompatActivity() {

    /** 投屏接收服务是否在跑（绿色状态提示） */
    private val runningColor = Color.parseColor("#1BA351")
    private var receiverReady = false
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshReceiverAddress()
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(networkReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    override fun onStop() {
        unregisterReceiver(networkReceiver)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 电视上没有触摸操作，不需要 ActionBar
        supportActionBar?.hide()
        setContentView(R.layout.activity_tv_main)

        // 电视端体验：启动就把投屏接收服务打开，手机投过来直接能看，
        // 不用先拿遥控器跑到接收端页面去点"启动"
        applyFirstRunDefaults()
        if (ScreencastConfig.isStartReceiveWhenLaunch()) {
            ensureScreencastReceiverRunning()
        }

        observeReceiverStatus()
        initEntry()
    }

    /**
     * 权限申请必须放到 Activity 完全就绪之后：
     * 在 onCreate 里调 `supportFragmentManager` 提交请求会静默失败（实测首次启动不弹框）。
     */
    override fun onPostResume() {
        super.onPostResume()
        refreshReceiverAddress()
        requestStoragePermissionIfNeeded()
    }

    /**
     * 电视上先把存储权限要来。
     *
     * 遥控器操作系统的权限弹窗本来就不如触摸方便，一次问完，
     * 后面进媒体库、收投屏都不会再被打断。
     */
    private fun requestStoragePermissionIfNeeded() {
        try {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                return
            }
            DanDanPlay.permission.storage.request(this) {
                onGranted { }
                onDenied { }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 电视端的默认值，**只在首次启动时设置一次**，之后尊重用户在接收端页面里的改动。
     *
     * 其中「投屏接收时需弹窗确认播放」必须默认关掉：投屏时人坐在沙发上，
     * 不该还要拿起遥控器点一下"播放"才算收下（实测确认过会弹这个框）。
     */
    private fun applyFirstRunDefaults() {
        try {
            val prefs = getSharedPreferences("tv_first_run", Context.MODE_PRIVATE)
            if (prefs.getBoolean("applied", false)) {
                return
            }
            ScreencastConfig.putReceiveNeedConfirm(false)
            ScreencastConfig.putStartReceiveWhenLaunch(true)
            prefs.edit().putBoolean("applied", true).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun ensureScreencastReceiverRunning() {
        try {
            if (ScreencastReceiveService.isRunning(this)) {
                return
            }
            var port = ScreencastConfig.getReceiverPort()
            if (port <= 0) {
                port = Random.nextInt(20000, 30000)
                ScreencastConfig.putReceiverPort(port)
            }
            val password = ScreencastConfig.getReceiverPassword()
                .takeIf { ScreencastConfig.isUseReceiverPassword() }
            ScreencastReceiveService.start(this, port, password)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 在"投屏接收端"卡片上显示实时状态 —— 电视上用户需要一眼确认能不能收
     */
    private fun observeReceiverStatus() {
        val statusTv = findViewById<TextView>(R.id.screencast_receiver_status_tv)
        ServiceLifecycleBridge.getScreencastReceiveObserver().observe(this) { running ->
            receiverReady = running
            if (running) {
                statusTv.text = getString(R.string.tv_screencast_receiver_running)
                statusTv.setTextColor(runningColor)
            } else {
                statusTv.text = getString(R.string.tv_screencast_receiver_stopped)
                statusTv.setTextColor(ContextCompat.getColor(this@TvMainActivity, R.color.tv_text_secondary))
            }
            refreshReceiverAddress()
        }

        // 把投屏地址直接摆出来：手机端"搜索连接"在手机热点这类网络下可能搜不到设备
        // （实测 TCL 电视 + 手机热点就是如此），这时用户可以照这个地址手动"输入连接"
        refreshReceiverAddress()
    }

    private fun refreshReceiverAddress() {
        val addrTv = findViewById<TextView>(R.id.screencast_receiver_addr_tv)
        val ip = LocalIpUtils.findLocalIpAddress()
        val port = ScreencastConfig.getReceiverPort()
        if (receiverReady && !ip.isNullOrEmpty() && port > 0) {
            addrTv.text = getString(R.string.tv_screencast_addr, ip, port)
            addrTv.visibility = View.VISIBLE
        } else {
            addrTv.visibility = View.GONE
        }
    }

    private fun initEntry() {
        val mediaLibraryCard = findViewById<View>(R.id.media_library_card)
        mediaLibraryCard.setOnClickListener { requestStorageThenOpenMediaLibrary() }
        // 关键：一进页面就把焦点给第一个入口，否则遥控器按下去没有任何反应
        mediaLibraryCard.post { mediaLibraryCard.requestFocus() }

        findViewById<View>(R.id.screencast_receiver_card).setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.Stream.ScreencastReceiver)
                .navigation()
        }

        findViewById<View>(R.id.play_history_btn).setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.Local.PlayHistory)
                .withSerializable("typeValue", MediaType.OTHER_STORAGE.value)
                .navigation()
        }

        findViewById<View>(R.id.scan_manager_btn).setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.ScanManager)
                .navigation()
        }

        findViewById<View>(R.id.player_setting_btn).setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.SettingPlayer)
                .navigation()
        }

        findViewById<View>(R.id.app_setting_btn).setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.SettingApp)
                .navigation()
        }
    }

    /**
     * 打开本地媒体库前先要存储权限 ——
     * 手机端是在"媒体库首页"点击时才申请的，电视端直接跳到文件列表，
     * 不申请的话进去会是空的。
     */
    private fun requestStorageThenOpenMediaLibrary() {
        DanDanPlay.permission.storage.request(this) {
            onGranted { openLocalMediaLibrary() }
            onDenied { ToastCenter.showError(getString(R.string.tv_permission_storage_denied)) }
        }
    }

    /**
     * 直接进本地媒体库的文件列表（跳过手机上那层"媒体库首页"）
     */
    private fun openLocalMediaLibrary() {
        ARouter.getInstance()
            .build(RouteTable.Stream.StorageFile)
            .withParcelable("storageLibrary", MediaLibraryEntity.LOCAL)
            .navigation()
    }
}

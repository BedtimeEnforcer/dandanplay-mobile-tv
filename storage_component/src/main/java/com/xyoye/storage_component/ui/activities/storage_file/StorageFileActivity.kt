package com.xyoye.storage_component.ui.activities.storage_file

import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.alibaba.android.arouter.facade.annotation.Autowired
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.shape.ShapeAppearanceModel
import com.xyoye.common_component.base.BaseActivity
import com.xyoye.common_component.config.RouteTable
import com.xyoye.common_component.extension.horizontal
import com.xyoye.common_component.extension.setData
import com.xyoye.common_component.storage.Storage
import com.xyoye.common_component.storage.StorageFactory
import com.xyoye.common_component.storage.file.StorageFile
import com.xyoye.common_component.storage.impl.FtpStorage
import com.xyoye.common_component.utils.SupervisorScope
import com.xyoye.common_component.weight.BottomActionDialog
import com.xyoye.common_component.weight.dialog.CommonDialog
import com.xyoye.common_component.weight.dialog.CommonEditDialog
import com.xyoye.common_component.weight.ToastCenter
import com.xyoye.data_component.bean.EditBean
import com.xyoye.data_component.bean.SheetActionBean
import com.xyoye.data_component.bean.StorageFilePath
import com.xyoye.data_component.bean.UDPDeviceBean
import com.xyoye.data_component.entity.MediaLibraryEntity
import com.xyoye.storage_component.services.ScreencastProvideService
import com.xyoye.storage_component.utils.dlna.DlnaDevice
import com.xyoye.storage_component.BR
import com.xyoye.storage_component.R
import com.xyoye.storage_component.databinding.ActivityStorageFileBinding
import com.xyoye.storage_component.ui.fragment.storage_file.StorageFileFragment
import com.xyoye.storage_component.ui.weight.StorageFileMenus
import com.xyoye.storage_component.utils.storage.StorageFilePathAdapter
import com.xyoye.storage_component.utils.storage.StorageFileStyleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Route(path = RouteTable.Stream.StorageFile)
class StorageFileActivity : BaseActivity<StorageFileViewModel, ActivityStorageFileBinding>() {

    @Autowired
    @JvmField
    var storageLibrary: MediaLibraryEntity? = null

    lateinit var storage: Storage
        private set

    // 当前所处文件夹
    var directory: StorageFile? = null
        private set

    // 标题栏菜单管理器
    private var mMenus: StorageFileMenus? = null

    // 文件Fragment列表
    private val mRouteFragmentMap = mutableMapOf<StorageFilePath, StorageFileFragment>()

    // 标题栏样式工具
    private val mToolbarStyleHelper: StorageFileStyleHelper by lazy {
        StorageFileStyleHelper(this, dataBinding)
    }

    // 全局焦点变化监听，用于控制标题栏展开/收缩
    private val focusChangeListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        handleGlobalFocusChange(newFocus)
    }

    var shareStorageFile: StorageFile? = null

    override fun initViewModel() =
        ViewModelInit(
            BR.viewModel,
            StorageFileViewModel::class.java
        )

    override fun getLayoutId() = R.layout.activity_storage_file

    override fun onCreate(savedInstanceState: Bundle?) {
        ARouter.getInstance().inject(this)

        if (checkBundle().not()) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        mToolbarStyleHelper.observerChildScroll()
        dataBinding.coordinatorLayout.viewTreeObserver
            .addOnGlobalFocusChangeListener(focusChangeListener)
        title = storageLibrary?.displayName
        updateToolbarSubtitle(0, 0)

        initPathRv()
        initListener()
        updateFloatingButtonStyle()
        openDirectory(null)
    }

    private fun initPathRv() {
        dataBinding.pathRv.apply {
            layoutManager = horizontal()

            adapter = StorageFilePathAdapter.build(this@StorageFileActivity) {
                backToRouteFragment(it)
            }
        }
    }

    private fun initListener() {
        mToolbar?.setNavigationOnClickListener {
            if (popFragment().not()) {
                finish()
            }
        }

        dataBinding.quicklyPlayBt.setOnClickListener {
            viewModel.quicklyPlay(storage)
        }

        dataBinding.quicklyPlayBt.setOnFocusChangeListener { _, _ ->
            updateFloatingButtonStyle()
        }

        dataBinding.pathRv.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // 将RecyclerView焦点转移到子View
                currentFocus?.requestFocus(View.FOCUS_UP)
            }
        }

        // 系统无法正确分发快速播放按钮的焦点，需要手动分发
        dataBinding.quicklyPlayBt.setOnKeyListener(object : View.OnKeyListener {
            override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
                if (event?.action != KeyEvent.ACTION_DOWN || v?.isFocused != true) {
                    return false
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    dispatchFocus(reversed = true)
                    return true
                } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    dispatchFocus()
                    return true
                }
                return false
            }
        })

        viewModel.playLiveData.observe(this) {
            ARouter.getInstance()
                .build(RouteTable.Player.Player)
                .navigation()
        }

        viewModel.castDeviceLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it.requestId)) {
                ScreencastProvideService.start(this, it.device)
            }
        }

        viewModel.selectCastDeviceLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it.requestId)) {
                showSelectCastDeviceDialog(it.requestId, it.devices)
            }
        }

        // 轻享版：DLNA 一台设备都没搜到时的提示
        viewModel.noCastDeviceLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it)) showNoCastDeviceDialog()
        }

        // 轻享版新增：点投屏先选投屏方式
        viewModel.selectCastMethodLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it)) showSelectCastMethodDialog(it)
        }

        // 搜到多个弹弹play 接收端，让用户选一台
        viewModel.selectScreencastDeviceLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it.requestId)) {
                showSelectScreencastDeviceDialog(it.requestId, it.devices)
            }
        }

        // 接收端设置了连接密码，先让用户输入
        viewModel.screencastPasswordLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it.requestId)) {
                showScreencastPasswordDialog(it.requestId, it.device)
            }
        }

        viewModel.castScreencastDeviceLiveData.observe(this) { target ->
            if (viewModel.isCurrentCastRequest(target.requestId)) {
                ScreencastProvideService.startScreencast(this, target.device, target.password)
            }
        }

        // 一台弹弹play 接收端都没搜到
        viewModel.noScreencastDeviceLiveData.observe(this) {
            if (viewModel.isCurrentCastRequest(it)) showNoScreencastDeviceDialog(it)
        }

        if (storage is FtpStorage) {
            lifecycle.coroutineScope.launchWhenResumed {
                withContext(Dispatchers.IO) {
                    (storage as FtpStorage).completePending()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        mMenus = StorageFileMenus.inflater(this, menu).apply {
            onSearchTextChanged { onSearchTextChanged(it) }
            onSortTypeChanged { onSortOptionChanged() }
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        mMenus?.onOptionsItemSelected(item)
        return super.onOptionsItemSelected(item)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && mMenus?.handleBackPressed() == true) {
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && popFragment()) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        shareStorageFile = null
        if (this::storage.isInitialized) {
            SupervisorScope.IO.launch {
                storage.close()
            }
        }
        val observer = dataBinding.coordinatorLayout.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnGlobalFocusChangeListener(focusChangeListener)
        }
        super.onDestroy()
    }

    private fun checkBundle(): Boolean {
        storageLibrary
            ?: return false
        val storage = StorageFactory.createStorage(storageLibrary!!)
            ?: return false

        this.storage = storage
        return true
    }

    private fun pushFragment(path: StorageFilePath) {
        val fragment = StorageFileFragment.newInstance()
        mRouteFragmentMap[path] = fragment

        supportFragmentManager.beginTransaction().apply {
            // 添加前的最后一个Fragment，设置为STARTED状态
            supportFragmentManager.fragments.lastOrNull()?.let {
                setMaxLifecycle(it, Lifecycle.State.STARTED)
            }

            setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            add(dataBinding.fragmentContainer.id, fragment, path.route)

            // 当前添加的Fragment，设置为RESUMED状态
            setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            commit()
        }

        onDisplayFragmentChanged()
    }

    private fun popFragment(): Boolean {
        if (mRouteFragmentMap.entries.size <= 1) {
            return false
        }
        val lastRoute = mRouteFragmentMap.keys.last()
        val fragment = mRouteFragmentMap.remove(lastRoute)
            ?: return true
        removeFragment(listOf(fragment))
        onDisplayFragmentChanged()
        return true
    }

    private fun backToRouteFragment(target: StorageFilePath) {
        val fragments = mutableListOf<Fragment>()
        for (path in mRouteFragmentMap.keys.reversed()) {
            if (path == target) {
                break
            }
            mRouteFragmentMap.remove(path)?.let { fragment ->
                fragments.add(fragment)
            }
        }
        removeFragment(fragments)
        onDisplayFragmentChanged()
    }

    private fun removeFragment(fragments: List<Fragment>) {
        supportFragmentManager.beginTransaction().apply {
            setCustomAnimations(R.anim.fragment_fade_in, R.anim.fragment_fade_out)
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE)

            fragments.forEach {
                remove(it)
                // 当前移除的Fragment，设置为CREATED状态
                setMaxLifecycle(it, Lifecycle.State.CREATED)
            }

            // 非移除的最后一个Fragment，设置为RESUMED状态
            supportFragmentManager.fragments
                .lastOrNull { it !in fragments }
                ?.let { setMaxLifecycle(it, Lifecycle.State.RESUMED) }
            commit()
        }
    }

    private fun onDisplayFragmentChanged() {
        val newPathData = StorageFilePathAdapter.buildPathData(mRouteFragmentMap)
        dataBinding.pathRv.setData(newPathData)
        dataBinding.pathRv.post {
            dataBinding.pathRv.smoothScrollToPosition(newPathData.size - 1)
        }
    }

    /**
     * 轻享版：局域网里没搜到可投屏的 DLNA 设备
     */
    private fun showNoCastDeviceDialog() {
        CommonDialog.Builder(this)
            .apply {
                content = "没有搜到可投屏的设备。\n\n请确认：\n1. 电视和手机连的是同一个 WiFi\n" +
                    "2. 电视已开机（TCL 等智能电视默认支持 DLNA 投屏）\n" +
                    "3. 路由器没开「AP 隔离」"
                positiveText = "知道了"
                addPositive { dialog -> dialog.dismiss() }
            }.build().show()
    }

    /**
     * 轻享版新增：选择投屏方式
     *
     * DLNA 电视只拉视频流，带不了弹幕字幕；弹弹play 接收端走自家协议，能带弹幕字幕。
     */
    private fun showSelectCastMethodDialog(requestId: Long) {
        val actionData = listOf(
            SheetActionBean(
                CAST_METHOD_DLNA,
                "投到电视（DLNA）",
                com.xyoye.common_component.R.drawable.ic_video_cast,
                "电视自带投屏，不需要装 App，但不带弹幕、字幕"
            ),
            SheetActionBean(
                CAST_METHOD_SCREENCAST,
                "投屏到禁止熬夜投屏专用电视端",
                com.xyoye.common_component.R.drawable.ic_dandanplay,
                "电视端安装本项目应用，可带弹幕、字幕"
            ),
            SheetActionBean(
                CAST_METHOD_MANUAL,
                "输入电视端地址",
                com.xyoye.common_component.R.drawable.ic_dandanplay,
                "搜索不到时，输入电视屏幕上的 IP:端口"
            )
        )
        BottomActionDialog(
            title = "选择投屏方式",
            activity = this,
            actionData = actionData
        ) { action ->
            when (action.actionId) {
                CAST_METHOD_DLNA -> viewModel.castToDlnaDevice(requestId)
                CAST_METHOD_SCREENCAST -> viewModel.castToScreencastDevice(requestId)
                CAST_METHOD_MANUAL -> showManualScreencastDialog(requestId)
            }
            return@BottomActionDialog true
        }.show()
    }

    /**
     * 轻享版新增：搜到多台弹弹play 接收端，让用户选一台
     */
    private fun showSelectScreencastDeviceDialog(requestId: Long, devices: List<UDPDeviceBean>) {
        val drawable = com.xyoye.common_component.R.drawable.ic_dandanplay
        val actionData = devices.map {
            SheetActionBean(it, it.deviceName, drawable, it.getDisplayAddress())
        }
        BottomActionDialog(
            title = "选择禁止熬夜投屏专用电视端",
            activity = this,
            actionData = actionData
        ) { action ->
            (action.actionId as? UDPDeviceBean)?.let {
                viewModel.castToScreencastDevice(requestId, it)
            }
            return@BottomActionDialog true
        }.show()
    }

    /**
     * 轻享版新增：接收端设置了连接密码
     */
    private fun showScreencastPasswordDialog(requestId: Long, device: UDPDeviceBean) {
        val editBean = EditBean(
            title = "连接密码",
            emptyWarningMsg = "密码不能为空",
            hint = "请输入电视端设置的连接密码",
            inputTips = "在禁止熬夜投屏专用电视端的「投屏接收」里可以查看或修改密码"
        )
        CommonEditDialog(this, editBean) { password ->
            viewModel.castToScreencastDevice(requestId, device, password)
        }.show()
    }

    /**
     * 轻享版新增：局域网里没搜到弹弹play 接收端
     */
    private fun showNoScreencastDeviceDialog(requestId: Long) {
        CommonDialog.Builder(this)
            .apply {
                content = "没有搜到禁止熬夜投屏专用电视端。\n\n请确认：\n1. 电视和手机连的是同一个 WiFi\n" +
                    "2. 电视端应用已打开，「投屏接收」处于已启动状态\n" +
                    "3. 路由器没开「AP 隔离」"
                addNegative("输入地址") { dialog ->
                    dialog.dismiss()
                    showManualScreencastDialog(requestId)
                }
                positiveText = "知道了"
                addPositive { dialog -> dialog.dismiss() }
            }.build().show()
    }

    private fun showManualScreencastDialog(requestId: Long) {
        val editBean = EditBean(
            title = "输入电视端地址",
            emptyWarningMsg = "请输入电视屏幕显示的 IP:端口",
            hint = "例如 192.168.1.8:27780",
            inputTips = "电视端首页的“投屏接收端”卡片下方可查看地址"
        )
        CommonEditDialog(this, editBean,
            checkBlock = {
                val valid = StorageFileViewModel.parseManualAddress(it) != null
                if (!valid) ToastCenter.showWarning("地址格式错误，请输入电视显示的 IP:端口")
                valid
            }
        ) { address ->
            viewModel.castToManualScreencast(requestId, address)
        }.show()
    }

    /**
     * 搜到多台 DLNA 设备，让用户选一台
     */
    private fun showSelectCastDeviceDialog(requestId: Long, devices: List<DlnaDevice>) {
        val drawable = com.xyoye.common_component.R.drawable.ic_screencast_device
        val actionData = devices.map {
            SheetActionBean(it, it.displayName, drawable, it.ip)
        }
        BottomActionDialog(
            title = "选择投屏设备",
            activity = this,
            actionData = actionData
        ) { action ->
            (action.actionId as? DlnaDevice)?.let {
                viewModel.castItem(requestId, it)
            }
            return@BottomActionDialog true
        }.show()
    }

    /**
     * 更新标题栏副标题
     */
    private fun updateToolbarSubtitle(videoCount: Int, directoryCount: Int) {
        supportActionBar?.subtitle = when {
            videoCount == 0 && directoryCount == 0 -> {
                "0视频"
            }

            directoryCount == 0 -> {
                "${videoCount}视频"
            }

            videoCount == 0 -> {
                "${directoryCount}文件夹"
            }

            else -> {
                "${videoCount}视频  ${directoryCount}文件夹"
            }
        }
    }

    /**
     * 搜索文案
     */
    private fun onSearchTextChanged(text: String) {
        mRouteFragmentMap.values.lastOrNull()?.search(text)
    }

    /**
     * 改变文件排序
     */
    private fun onSortOptionChanged() {
        mRouteFragmentMap.values.onEach { it.sort() }
    }

    /**
     * 根据焦点状态修改悬浮按钮样式
     */
    private fun updateFloatingButtonStyle() {
        val floatingButton = dataBinding.quicklyPlayBt
        val shapeAppearanceRes = if (floatingButton.isFocused)
            R.style.ShapeAppearance_DanDanPlay_FloatingButton_Focused
        else
            R.style.ShapeAppearance_DanDanPlay_FloatingButton
        floatingButton.shapeAppearanceModel = ShapeAppearanceModel.builder(
            this, 0, shapeAppearanceRes
        ).build()
    }

    /**
     * 焦点切换时，处理标题栏展开/收缩
     */
    private fun handleGlobalFocusChange(newFocus: View?) {
        if (newFocus == null) {
            return
        }
        when {
            // 焦点在标题栏区域
            hasAncestorOfType(newFocus, AppBarLayout::class.java) -> setToolbarExpanded(true)

            // 焦点在内容区列表
            hasAncestorOfType(newFocus, RecyclerView::class.java) -> setToolbarExpanded(false)
        }
    }

    /**
     * 根据焦点归属切换标题栏展开/收缩
     */
    private fun setToolbarExpanded(expand: Boolean) {
        val isExpanded = mToolbarStyleHelper.isToolbarCollapsed().not()
        if (expand && isExpanded) {
            return
        }
        dataBinding.appbarLayout.setExpanded(expand, true)
    }

    /**
     * 判断view是否存在指定类型的祖先
     */
    private fun hasAncestorOfType(view: View, clazz: Class<out View>): Boolean {
        var current: View? = view
        while (current != null) {
            if (clazz.isInstance(current)) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    /**
     * 分发焦点到最后一个Fragment
     */
    fun dispatchFocus(reversed: Boolean = false) {
        mRouteFragmentMap.values.lastOrNull()?.requestFocus(reversed)
    }

    fun openDirectory(file: StorageFile?) {
        directory = file

        val route = file?.filePath() ?: "/"
        val name = file?.fileName() ?: "根目录"
        pushFragment(StorageFilePath(name, route))
    }

    fun onDirectoryOpened(fileList: List<StorageFile>) {
        val videoCount = fileList.count { it.isFile() }
        val directoryCount = fileList.count { it.isDirectory() }
        updateToolbarSubtitle(videoCount, directoryCount)
    }

    fun openFile(file: StorageFile) {
        viewModel.playItem(file)
    }

    fun castFile(file: StorageFile) {
        viewModel.requestCast(file)
    }

    companion object {
        // 投屏方式
        private const val CAST_METHOD_DLNA = "cast_method_dlna"
        private const val CAST_METHOD_SCREENCAST = "cast_method_screencast"
        private const val CAST_METHOD_MANUAL = "cast_method_manual"
    }
}

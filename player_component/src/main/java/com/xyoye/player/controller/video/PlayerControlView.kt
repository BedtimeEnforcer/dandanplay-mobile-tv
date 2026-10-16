package com.xyoye.player.controller.video

import android.content.Context
import android.graphics.Point
import android.view.LayoutInflater
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.xyoye.data_component.enums.PlayState
import com.xyoye.data_component.enums.SettingViewType
import com.xyoye.player.utils.MessageTime
import com.xyoye.player.wrapper.ControlWrapper
import com.xyoye.player_component.R
import com.xyoye.player_component.databinding.LayoutPlayerControllerBinding

/**
 * Created by xyoye on 2022/11/13.
 */

class PlayerControlView(context: Context): InterControllerView {

    private val viewBinding = DataBindingUtil.inflate<LayoutPlayerControllerBinding>(
        LayoutInflater.from(context),
        R.layout.layout_player_controller,
        null,
        false
    )

    /**
     * 两侧按钮（锁屏、截图）滑出屏幕的位移。
     * 电视端按钮更大（values-television/dimens.xml），位移也必须同步变大，
     * 否则收起后会有一条边露在屏幕上。
     */
    private val sideButtonOffset: Float =
        context.resources.getDimensionPixelSize(R.dimen.player_control_side_btn_offset).toFloat()

    private lateinit var mControlWrapper: ControlWrapper

    init {
        viewBinding.playerLockIv.setOnClickListener {
            mControlWrapper.toggleLockState()
        }
        viewBinding.playerShotIv.setOnClickListener {
            mControlWrapper.showSettingView(SettingViewType.SCREEN_SHOT)
        }
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
    }

    override fun getView() = viewBinding.root

    override fun onVisibilityChanged(isVisible: Boolean) {
        updateLockVisible(isVisible)
        if (mControlWrapper.isLocked().not()) {
            updateShotVisible(isVisible)
        }
    }

    override fun onPlayStateChanged(playState: PlayState) {

    }

    override fun onProgressChanged(duration: Long, position: Long) {

    }

    override fun onLockStateChanged(isLocked: Boolean) {
        viewBinding.playerLockIv.isSelected = isLocked
        updateShotVisible(!isLocked)
    }

    override fun onVideoSizeChanged(videoSize: Point) {

    }

    override fun onPopupModeChanged(isPopup: Boolean) {

    }

    fun showMessage(text: String, time: MessageTime) {
        viewBinding.messageContainer.showMessage(text, time)
    }

    fun clearMessage() {
        viewBinding.messageContainer.clearMessage()
    }

    private fun updateLockVisible(isVisible: Boolean) {
        if (isVisible) {
            viewBinding.playerLockIv.isVisible = true
            ViewCompat.animate(viewBinding.playerLockIv)
                .translationX(0f)
                .setDuration(300)
                .start()
        } else {
            ViewCompat.animate(viewBinding.playerLockIv)
                .translationX(-sideButtonOffset)
                .setDuration(300)
                .start()
        }
    }

    private fun updateShotVisible(isVisible: Boolean) {
        if (isVisible) {
            viewBinding.playerShotIv.isVisible = true
            ViewCompat.animate(viewBinding.playerShotIv)
                .translationX(0f)
                .setDuration(300)
                .start()
        } else {
            ViewCompat.animate(viewBinding.playerShotIv)
                .translationX(sideButtonOffset)
                .setDuration(300)
                .start()
        }
    }
}
package com.xyoye.user_component.ui.fragment.personal

import androidx.core.view.isVisible
import com.alibaba.android.arouter.facade.annotation.Route
import com.alibaba.android.arouter.launcher.ARouter
import com.alibaba.sdk.android.feedback.impl.FeedbackAPI
import com.xyoye.common_component.base.BaseFragment
import com.xyoye.common_component.bridge.ServiceLifecycleBridge
import com.xyoye.common_component.config.RouteTable
import com.xyoye.user_component.BR
import com.xyoye.user_component.R
import com.xyoye.user_component.databinding.FragmentPersonalBinding

/**
 * Created by xyoye on 2020/7/28.
 *
 * 轻享版说明：账号系统（登录 / 注册 / 找回 / 用户信息 / 头像）已整体移除。
 * 布局里保留了 user_info_cl 这一段（`visibility=gone`），以后想恢复时把 gone 去掉即可。
 */

@Route(path = RouteTable.User.PersonalFragment)
class PersonalFragment : BaseFragment<PersonalFragmentViewModel, FragmentPersonalBinding>() {

    override fun initViewModel() = ViewModelInit(
        BR.viewModel,
        PersonalFragmentViewModel::class.java
    )

    override fun getLayoutId() = R.layout.fragment_personal

    override fun initView() {
        // 轻享版：投屏已改成 DLNA（手机直接投到电视），不再需要"本机当接收端"，
        // 所以「投屏接收端」入口和它的状态监听都去掉了（布局里也已 visibility=gone）
        initClick()
    }

    private fun initClick() {

        dataBinding.playerSettingLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.SettingPlayer)
                .navigation()
        }

        dataBinding.scanManagerLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.ScanManager)
                .navigation()
        }

        dataBinding.cacheManagerLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.CacheManager)
                .navigation()
        }

        dataBinding.commonlyManagerLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.CommonManager)
                .navigation()
        }

        dataBinding.bilibiliDanmuLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.Local.BiliBiliDanmu)
                .navigation()
        }

        dataBinding.shooterSubtitleLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.Local.ShooterSubtitle)
                .navigation()
        }

        dataBinding.feedbackLl.setOnClickListener {
            FeedbackAPI.openFeedbackActivity()
        }

        dataBinding.appSettingLl.setOnClickListener {
            ARouter.getInstance()
                .build(RouteTable.User.SettingApp)
                .navigation()
        }
    }
}

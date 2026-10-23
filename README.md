# 禁止熬夜投屏专用

自己用的投屏版弹弹play。在 [弹弹play概念版](https://github.com/xyoye/DanDanPlayForAndroid) v4.1.4 的基础上改的，分手机端和电视端两个 App，跟官方没关系。原项目是 Apache-2.0，LICENSE 和原说明在 `docs/UPSTREAM_README.md` 里保留着。

- 手机端 `com.lz.dandanplay.mobile`：看本地视频、绑弹幕字幕、发起投屏
- 电视端 `com.lz.dandanplay.tv`：电视上本地播放，也能接收手机端投过来的

投屏有两种：

- DLNA：电视支持 DLNA 就能收，但只有视频，没弹幕没字幕。
- 投到自家电视端：视频、弹幕、字幕都能传。搜不到电视就手动输电视首页上显示的 IP:端口。

安装包在 Releases 里下。手机端选 `mobile_universal`，电视端选 `tv_universal`。都是调试签名，自用测试包，别指望跟正式版一样稳。

自己编译：

要 JDK 17、Android SDK 33，Gradle Wrapper 7.4。手机端在 `app/`，电视端在 `tv_component/`，其余组件共用。

```bash
./gradlew :app:assembleDebug :tv_component:assembleDebug
```

Windows 用 `gradlew.bat`。注意仓库路径别带中文，带了容易构建失败，挪到纯英文路径下再编。APK 在各模块的 `build/outputs/apk/` 里。`local.properties` 和密钥文件不在仓库里，要自己配。

现状：2026-10-16 编过一版，装到我的 OPPO 手机和 TCL 电视上试过，DLNA 投屏确认能用。电视端协议那条链路（完整播放、传弹幕字幕、连 NAS）还没测全，问题记录在 `docs/审查与优化方案-20261016.md`。

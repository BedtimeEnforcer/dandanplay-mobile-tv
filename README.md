# 禁止熬夜投屏专用

基于 [弹弹play概念版 Android](https://github.com/xyoye/DanDanPlayForAndroid) v4.1.4 改造的手机端与电视端。原项目采用 Apache-2.0 许可证；本仓库保留其 [LICENSE](LICENSE) 和 [原项目说明](docs/UPSTREAM_README.md)。本项目与弹弹play官方无关联。

## 两个应用

| 应用 | 包名 | 用途 |
| --- | --- | --- |
| 禁止熬夜投屏专用 手机端 | `com.lz.dandanplay.mobile` | 本地视频、弹幕和字幕绑定，以及发起投屏 |
| 禁止熬夜投屏专用 电视端 | `com.lz.dandanplay.tv` | 电视本地播放与接收手机端投屏 |

手机端的投屏按钮提供两种方式：

1. **投到电视（DLNA）**：电视支持 DLNA 即可接收，视频投屏不携带弹幕和字幕。
2. **投屏到禁止熬夜投屏专用电视端**：电视需安装本项目电视端，可传送视频及已绑定的弹幕、字幕。搜索不到电视时，可输入电视首页显示的 `IP:端口`。

## 下载与安装

安装包放在 [Releases](https://github.com/BedtimeEnforcer/dandanplay-mobile-tv/releases)。手机端优先选 `mobile_universal`；电视端选 `tv_universal`。这些是调试签名的自用测试包。

## 从源码构建

使用 JDK 17、Android SDK 33 与 Gradle Wrapper 7.4。源码分别在 `app/` 和 `tv_component/`，共用其他组件。在仓库根目录运行：

```bash
./gradlew :app:assembleDebug :tv_component:assembleDebug
```

Windows 可运行 `gradlew.bat`。首次构建会下载 Android Gradle 插件和依赖。若 Windows 上中文目录导致构建失败，请将仓库放到纯英文路径。调试 APK 由各模块的 `build/outputs/apk/` 生成。本地 `local.properties`、密钥文件与构建缓存不在仓库内。

## 当前验证范围

2026-10-16 的两端调试包已构建，并分别安装到 OPPO 手机和 TCL 电视。用户确认 DLNA 投屏可用。电视端协议的完整真机播放、弹幕字幕传输及各类 NAS 连接仍需继续验证。已发现的问题和修复记录见 [检查与优化方案](docs/审查与优化方案-20261016.md)。

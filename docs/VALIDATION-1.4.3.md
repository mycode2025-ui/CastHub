# CastHub 1.4.3 本地验证记录

日期：2026-10-08。源代码目录：`D:\BatteryMonitor\CastHub\CastHub`。
本轮结果为本地候选版本，尚未提交或发布到 GitHub / Gitee。

安装包：`dist/CastHub-1.4.3.apk`（versionCode 20）。
SHA-256：`bf3923c5913e66f6409e9bf0ee1a622519615774ba1ac22a3b2d0dfbf7627374`。

## 修复与新增

| 问题或缺口 | 本轮处理 |
|---|---|
| AirPlay 播完后循环条件永远不成立 | 在更新结束缓存前保存旧状态，验证真实视频重新播放 |
| AirPlay Start-Position 比例被当作秒数 | 媒体就绪并取得时长后按比例定位；重试采用绝对时间 |
| 外部视频客户端的 text/parameters 请求无法播放 | 支持文本、JSON、XML/binary plist，并校验 URL 与起播范围 |
| 服务部分启动失败后资源残留或错误显示运行 | 提前登记 socket / 服务对象，启动异常向上传递，完整清理 |
| 断网启动失败后不自动恢复 | 网络变更防抖、序列化重启与失败服务定期重试 |
| VPN / 无关网卡地址被用于投屏 | 优先实际 Wi-Fi / 有线 IPv4，过滤 VPN、环回与链路本地地址 |
| 跨协议视频或声音重叠、Surface 绑定错误 | 主线程串行接管，停止竞争协议，只绑定当前播放方 |
| 新视频沿用前一个视频请求头 | 每次打开媒体刷新 HTTP 请求头 |
| 播放进度显示旧值 | 按当前协议的进度事件刷新 OSD |
| 遥控器操作和故障恢复入口不足 | 确定键暂停 / 继续，菜单或 ↑ 打开重试、音轨、字幕选项 |
| 字幕缺少画面输出 | 两个协议提供字幕状态，SubtitleView 随视频比例布局；修正 DLNA 同名回调误调用 |
| 现场排障困难 | 网络诊断、复制 / 分享脱敏日志、重启接收服务 |
| DLNA 发送功能缺少页面 | 设备搜索、手动描述地址、视频地址、播放 / 暂停 / 继续 / 停止 |
| DLNA 控制地址解析及 HTTP 清理不完整 | 使用标准相对 URL 解析，成功和异常路径都关闭连接 |
| Android TV 清单不完整 | 可选触屏 / Leanback 声明、320×180 启动横幅 |
| 镜像 / 音频能力缺口 | 按用户选择增加独立 AirPlay Server 入口，启动时关闭内置 AirPlay 避免冲突 |

## 验证环境及证据

- JDK 17、Android SDK 34、签名 Release APK；从旧签名版本覆盖安装成功。
- Android Emulator AVD `te34`，Android 14 / API 34，x86_64，软件 GPU。
- 真实 30 秒 H.264 MP4；另生成双 AAC 音轨、英文 mov_text 字幕的 12 秒 MP4。
- 主机通过 ADB TCP 转发访问虚拟机；虚拟机通过 `10.0.2.2` 访问独立 HTTP Range 媒体服务器。

| 检查 | 结果 | 证据（仓库内路径） |
|---|---|---|
| Release 构建及模块单元测试 | 构建成功，105 / 105 | `dist/validation/final-build.log`、各模块 `build/test-results/testDebugUnitTest` |
| Android lintRelease | 0 errors，55 warnings | `app/build/reports/lint-results-release.html` |
| 视频与恢复回归 | 8 / 8 | `dist/validation/regression-results.json`、`regression-logcat.txt` |
| 功能 UI | 5 / 5 | `dist/validation/feature-results.json` |
| DLNA HTTP / SOAP 套件 | 19 / 19 | `dist/validation/dmr-suite.log` |
| AirPlay TCP 套件 | 14 / 14 | `dist/validation/airplay-suite.log` |
| 未修改的独立 AirPlay 发送客户端 | 9 / 9 | `dist/validation/independent-airplay.json` |
| APK 签名 | v1 / v2 验证通过 | `dist/validation/apk-signature.txt` |

8 项播放回归包括冷启动、实际视频进度、遥控暂停 / 继续、DLNA → AirPlay → DLNA 接管、两个协议单曲循环、断网启动后恢复以及日志无应用崩溃。
5 项 UI 验证包括两个协议的音轨 / 字幕切换、系统分享诊断文件、DLNA 发送控制、独立 AirPlay 应用启动。字幕文字另外通过实际截图人工核验。
DLNA 发送页使用独立 HTTP 接收端记录 SetAVTransportURI / Play / Pause / Play / Stop，验证实际请求和相对 controlURL，不把本机内部函数调用当成投屏成功。

截图：`dist/validation/dlna-playback.png`、`airplay-playback.png`、`dlna-subtitles.png`、`airplay-subtitles.png`、`external-airplay-entry.png`。

## 现成 AirPlay 工具

1. [cnelson/python-airplay](https://github.com/cnelson/python-airplay)：独立视频发送客户端，本轮实际测试使用未修改的源码，提交 `d56e6ae2f7c60aecb5c8e748f3856fbd16a1733c`。它是较老的 alpha 项目，适合视频 HTTP 兼容性测试，不能证明镜像或 RAOP 音频兼容。
2. [android-airplay-server](https://github.com/jqssun/android-airplay-server)：GPL-3.0 独立 Android 接收器，项目提供镜像、音频、视频功能，要求 Android 7.0+。本轮安装官方 GitHub 0.0.31 APK 并验证入口启动；代码与 APK 均未并入 CastHub 安装包。
   [F-Droid 安装页面](https://f-droid.org/packages/io.github.jqssun.airplay/)。测试 APK 签名 SHA-256：`64ca1fa39ddf9e0e89c57d73fb681d2a5b35ae20f071771e2eb401b5032ef1a9`。

## 复现

在已启动的测试 AVD 上安装签名包并运行 `adb root`，以 `tools/range_http_server.py` 提供测试媒体，再依次运行：

```powershell
python tools/emulator_features.py
python tools/emulator_regression.py
python tools/dmr_client_test.py --host 127.0.0.1 --port 38080 --media http://10.0.2.2:8899/test.mp4
python tools/airplay_client_test.py --ip 127.0.0.1 --port 37000 --skip-discovery --media http://10.0.2.2:8899/test.mp4
python tools/independent_airplay_test.py --source dist/validation/python-airplay
```

UI 脚本要求上述字幕媒体服务器在 8901、外部接收器已安装。所有脚本的地址均为测试环境，不用于真机自动配置。
当前 Windows JDK 环境的 Unix-domain socket 临时目录过长导致 Gradle worker 无法创建连接；本轮只给构建进程设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\BatteryMonitor\CastHub\CastHub\.gradle\sockets`，没有修改系统环境变量。

## 未覆盖范围

虚拟机 NAT 不代表真实局域网的 mDNS / SSDP 发现通过。没有 iPhone / Mac 真机镜像、RAOP 音频、实体电视遥控器与硬件解码、长时间稳定性或 DRM 内容的验收证据。独立接收器的上游能力描述不能替代这些实测。
lint 仍保留依赖更新、目标 SDK、界面资源等 55 条警告；本轮没有把依赖整体升级到新版本，也没有宣称软件不存在其他问题。

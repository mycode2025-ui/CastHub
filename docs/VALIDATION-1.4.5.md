# CastHub 1.4.5 验证记录

2026-10-08；versionCode 22。源代码和安装包保存在 `D:\BatteryMonitor\CastHub\CastHub`。发布页：[GitHub v1.4.5](https://github.com/mycode2025-ui/CastHub/releases/tag/v1.4.5)、[Gitee v1.4.5](https://gitee.com/mycode2025-ui/CastHub/releases/tag/v1.4.5)。

## 新增功能

| 功能 | 行为与入口 |
|---|---|
| 卡住检测 | 默认开启，设置 → 播放与接管设置可关闭。连续缓冲 30 秒、READY 状态进度冻结 15 秒触发恢复；暂停、系统播放抑制、正常结束和拖动排除。直播使用同样的连续缓冲检测。复用原有最多三次、2 / 4 / 8 秒重试预算。 |
| DLNA 控制兼容 | SCPD 声明并实现 GetCurrentTransportActions、SetPlayMode。NORMAL 对应队列顺序，REPEAT_ONE / REPEAT_ALL 对应循环设置；不支持的模式返回 UPnP 712。Next / Previous 实际切换本地队列，边界返回 711。 |
| 播放质量诊断 | 播放中 ↑ / 菜单 → 播放质量诊断。显示真实视频格式、尺寸、音频格式、视频解码器、可用缓冲、累计缓冲等待、丢帧与应用重试次数；统计仅在本机。 |
| 系统媒体控制 | 活跃投屏发布 Android 媒体会话；通知显示标题、暂停 / 继续、可用时下一集及结束投屏。系统媒体键支持播放、暂停、定位、上一集 / 下一集和停止。待机时继续显示接收服务通知。 |
| 播放历史与续播 | 设置 → 播放与接管设置 → 查看 / 清空播放历史；可关闭记录与续播。最多 50 条，约每 5 秒及换媒体 / 停止时保存。重新投送同一地址时询问是否续播；发送端明确指定进度时优先执行发送端指令。直播和已播完的媒体不提示续播。 |
| 音轨字幕偏好与外部字幕 | 设置或播放菜单 → 音轨与字幕偏好。记住语言及字幕开关，跨协议与重启生效。播放菜单 → 外部字幕，支持 HTTP / HTTPS 的 SRT / VTT 或系统文件选择器；更换字幕保留进度和暂停意图。 |
| 独立 AirPlay 联动 | 启动已安装的独立 AirPlay Server 时暂停 CastHub AirPlay 视频服务；返回后提示先退出独立接收器再恢复视频服务。设置入口显示已切换状态。启动失败恢复原开关。没有打包或链接独立 GPL 应用。 |

## 设计边界

历史保存地址 SHA-256 哈希、脱敏标题、进度及更新时间，不保存媒体地址、请求头或访问凭据。因此无法从历史直接重放已过期的地址；手机须重新投送。地址中的签名参数变化后不会当成同一条地址，避免错误匹配不同内容。关闭记录不会删除已有历史，可通过清空按钮删除。

语言偏好是语言优先级，不是固定音轨序号。具体媒体不存在所选语言时由播放器回退。外部字幕只在当前媒体生效，本地文件使用系统文档授权，内容必须是有效的 UTF-8 SRT / VTT；失败仍通过播放器错误界面反馈。统计采样精度约 0.5–1 秒；解码器名称无法保证设备确实启用了硬件加速。续播询问仅用于默认从零起播的请求；发送端非零起播位置优先。

独立接收器的进程和服务归另一应用管理。CastHub 不擅自终止它，也不声称仅靠返回页面就知道它已停止接收；恢复按钮明确要求先退出，以避免端口与广播冲突。

## 验证环境与证据

Android 14 / API 34 `te34` x86_64 rooted AVD，软件 GPU。签名 Release 覆盖安装。测试使用真实 MP4、双音轨与内嵌字幕，以及 SRT / VTT 文件。持续缓冲用 HTTP 服务每秒发送一个字节，保持 socket 可读而让解封装不能完成，验证检测机制独立于网络读超时报错。

```powershell
python tools/emulator_145.py
python tools/emulator_144.py --output dist/validation-1.4.5
python tools/emulator_regression.py --output dist/validation-1.4.5
```

测试仅允许 AVD，不允许实体设备；媒体服务 8899 / 8901、故障注入 8903 / 8904。测试过程中改变 AVD 偏好并恢复默认值。结果和截图在 `dist/validation-1.4.5`。

实现依据：[Media3 播放统计](https://developer.android.com/media/media3/exoplayer/analytics)、[外部字幕支持](https://developer.android.com/media/media3/exoplayer/supported-formats)、[UPnP AVTransport 服务定义及错误码](https://www.upnp.org/specs/av/UPnP-av-AVTransport-v3-Service.pdf)。SDK 调用以本地 Media3 1.2.0 的编译和实际播放验证为准。

## 已验证结果与安装包

| 验证 | 结果 |
|---|---|
| 签名 Release 构建、覆盖安装 | 通过；沿用 CastHub 原证书，APK v1 / v2 签名有效 |
| 单元测试 | 115 / 115，通过；包含暂停 / 定位 / 正常结束排除、直播缓冲检测、模式映射、历史哈希与续播边界 |
| 新增七项功能的最终 APK AVD 验收 | 16 / 16，通过；包含真实 HTTP 字节滴流、后台系统媒体键、六轮连续 AirPlay → DLNA 接管、SRT / VTT、本地文档选择、续播、明确起播位置和已安装的独立 AirPlay Server 联动 |
| 基础播放回归 | 8 / 8，通过；冷启动、真实播放、遥控、跨协议接管、两个协议单曲循环、断网恢复及无应用崩溃 |
| 原有接管、队列、画面与恢复验收 | 10 / 10，通过；三种接管规则、队列操作、画面字幕设置、两个协议真实断流恢复进度、三次重试上限、永久错误及关闭重试 |
| 最终 APK 的 DLNA 动作 / 模式 / 队列与停止后可用动作 | 通过 |
| Android Lint | 0 个错误、57 个警告；未宣称全部警告已消除 |

功能验收后收紧了停止 / 错误状态下的 DLNA 可用动作，最终 APK 再验证了控制流程及停止后的空动作列表。初轮测试记录保存在 `initial-acceptance.log` / `initial-acceptance-results.json`；最终结果以 `acceptance-145-results.json` 和 `final-acceptance-145.log` 为准。虚拟机重启后初次启动 Activity 的暂时不可用提示没有造成验收失败，启动完成后所有用例通过。

回归还发现并修复了真实的间歇停止问题：接管时旧协议停止，媒体会话短暂空闲；立即移除 MediaStyle 通知会触发 SystemUI 的延迟 `onStop`，误停刚启动的新协议。`trace-0.log` 保存实际调用栈。空闲发布延后 750 毫秒，新播放到来即取消；最终包的六轮连续切换和实际停止均通过。早期失败结果保存在 `initial-regression-results.json` / `initial-144-results.json`，不是最终通过记录。

安装包 `dist/CastHub-1.4.5.apk`，7,914,550 字节。SHA-256：

```text
0885d8556e66375d4ee688e08258afd630f2bafc97f0d0216af184a5d949ce3d
```

证据：`final-build.log`、`apk-signature.txt`、`acceptance-145-results.json`、`acceptance-results.json`、`regression-results.json`、`final-acceptance-145.log`、`final-acceptance-144.log`、`final-transport.log`、`media-session.txt`、`notification.txt`。`quality-diagnostics.png`、`local-srt.png`、两个协议的外部字幕和续播截图已人工核对或由 UI / 实际进度验收。此前协议脚本通过 DLNA 19 / 19、AirPlay 视频接口 14 / 14 和独立 python-airplay 客户端 9 / 9；最后的媒体通知修复未改动协议解析。

## 未验证范围

当前未连接实体 Android TV、iPhone 或 Mac。实体电视硬件解码、厂商遥控器 / 休眠唤醒、真实 Wi-Fi 组播发现、真实 AirPlay 镜像与音频及长时间稳定性仍需真机验证。独立接收器联动验收不等于镜像 / 音频协议验收。

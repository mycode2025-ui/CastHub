# CastHub · 多协议投屏接收端

一个在 Android TV 上同时提供 **DLNA/UPnP DMR** 与 **AirPlay** 接收能力的应用，
`minSdk 23`（Android 6.0）/ `targetSdk 34`，Kotlin 1.9.22 + AGP 8.2.2。

协议模块相互解耦、可独立启停；任一模块启动失败或被关闭，都不影响另一个模块。

当前版本：**1.4.0**（`versionCode` 17）

## 下载

| 来源 | 地址 |
|---|---|
| GitHub Releases | https://github.com/mycode2025-ui/CastHub/releases |
| Gitee Releases | https://gitee.com/mycode2025-ui/CastHub/releases |

两侧发布同一个包。国内直连 GitHub 时常超时，Gitee 更稳。

应用内自带升级检测（**设置 → 关于 → 检查更新**）：同时查这两处的 Release，
取版本更高的一个，可直接下载并调起系统安装器 —— 详见第九节。

---

## 一、模块结构

```
CastHub/
├── core/              协议无关的抽象层（无任何协议依赖）
│   ├── ProtocolModule          协议模块统一契约（生命周期 / 事件 / 可选能力）
│   ├── LocalPlaybackControl    接收端本地控制（遥控器快进快退、结束投屏）
│   ├── CastModels              跨协议统一模型（设备、媒体、会话、状态、事件）
│   ├── ModuleRegistry          模块注册与启停编排（故障隔离、幂等）
│   ├── SessionCoordinator      会话聚合（多协议并行接收）
│   ├── ModuleEnabledStore      开关与设备名持久化
│   ├── VolumeGovernor          投屏音量闸门
│   ├── VideoOutput             可选能力：需要 Surface 的模块
│   ├── CastLogger              统一日志（环形缓冲 + 监听）
│   └── update/                 升级检测（协议无关，纯 Kotlin + HttpURLConnection）
│       ├── Version             版本解析与比较（semver 子集）
│       ├── ReleaseParser       GitHub / Gitee Releases JSON → 统一模型
│       ├── UpdateChecker       两源并行查询、择优、降级
│       └── UpdatePrefs         检查节流与被忽略的版本
│
├── protocol-dlna/     DLNA / UPnP 模块（**自研协议栈**，零第三方依赖）
│   ├── DlnaModule                  RECEIVER（DMR）+ SENDER（DMC）门面
│   ├── upnp/SsdpServer             SSDP：ssdp:alive 广播 + M-SEARCH 响应
│   ├── upnp/UpnpHttpServer         HTTP：device.xml / SCPD / SOAP / GENA 订阅
│   ├── upnp/EventSubscriptionManager GENA：SUBSCRIBE / UNSUBSCRIBE / NOTIFY
│   ├── upnp/DeviceDescription      设备描述与三份 SCPD
│   ├── upnp/DeviceIcon             设备图标（代码生成，不塞二进制进仓库）
│   ├── upnp/MulticastLockHolder    Wi-Fi 多播锁（**必须**，见第九节）
│   ├── dmr/DmrService              把本机注册为 MediaRenderer
│   ├── dmp/DmcClient               控制点：发现并驱动其它渲染设备
│   └── renderer/MediaRendererController  Media3 播放（含 HLS）
│
├── protocol-airplay/  AirPlay 模块（**纯 Kotlin，无 native 依赖**）
│   ├── AirPlayModule           RECEIVER + 接收端本地控制
│   ├── AirPlayHttpServer       /server-info /play /playback-info /rate /scrub
│   │                           /volume /stop /info /reverse
│   ├── AirPlayPlayer           Media3 播放 + 500ms 进度取样
│   ├── mdns/NsdRegistrar       系统 NsdManager 注册
│   ├── mdns/MdnsResponder      自实现 mDNS 兜底（NsdManager 不可靠时）
│   └── plist/Plist.kt          二进制 plist 编解码（基于 dd-plist，MIT）
│
└── app/               应用外壳
    ├── CastHubApplication      唯一的模块装配点
    ├── MainActivity            待机屏 / 投屏播放 / 遥控器控制
    ├── SettingsActivity        协议开关、设备改名、网络信息、运行日志
    ├── ModuleRowView            模块行渲染，只依赖 core 抽象
    ├── update/                  应用内升级检测
    │   ├── UpdateCoordinator    仓库坐标 + 节流 + "忽略此版本"
    │   ├── UpdateFlow           检查 → 对话框 → 下载 → 校验 → 调起安装器
    │   └── ApkDownloader        受信域名下载、签名校验、安装意图
    └── CastForegroundService   前台服务，保证后台可被发现
```

**解耦要点**：`app` 只依赖 `core` 的接口，新增协议只需在 `CastHubApplication`
的模块列表里加一行；UI 完全不感知底层是 UPnP SOAP 还是 AirPlay plist。

---

## 二、协议能力

| 模块 | 接收端 (RECEIVER) | 发送端 (SENDER) | 依赖 | minSdk |
|---|---|---|---|---|
| DLNA / UPnP | ✅ DMR | ✅ DMC（无界面入口） | 自研 UPnP 栈 + Media3，零协议依赖 | 23 |
| AirPlay | ✅ 视频 URL 模式 | — | dd-plist + Media3，**无 native** | 23 |

AirPlay **只实现视频投屏**：AirPlay 视频投屏的协议面很窄 —— 它只把播放地址交给接收端，
解码由接收端用 Media3 完成，所以 mDNS 发现 + 一个 HTTP 服务 + 播放器就够，全部用 Kotlin 写。
屏幕镜像（H.264 over TCP 推流）与音频（RAOP + RSA 配对 + AES + ALAC）是另外两套协议栈，
未实现，且在 mDNS 的 `features` 里**刻意不声明**（bit7 屏幕镜像、bit9 音频）。
声明了却连不上，比不支持更糟。

### DLNA 标准符合度

对照 UPnP AV 1.0 + DLNA 规范逐项核对的结果（`tools/dlna_conformance_check.py` 可随时复跑）：

| 项 | 状态 | 说明 |
|---|---|---|
| AVTransport / RenderingControl / ConnectionManager 必需动作 | ✅ | 见上文动作清单 |
| GENA 事件推送（`LastChange`） | ✅ | `EventSubscriptionManager`：新订阅 / 续订 / 退订 / 过期清理 / 初始事件 / SEQ 递增 |
| `SinkProtocolInfo` 的 DLNA 扩展段 | ✅ | `DLNA.ORG_OP=01` + `DLNA.ORG_FLAGS` |
| `device.xml` 的 DLNA 标识 | ✅ | `xmlns:dlna` + `X_DLNADOC=DMR-1.50` + `iconList` |
| 拉流时的 DLNA 请求头 | ❌ | 未带 `getcontentFeatures.dlna.org` / `transferMode.dlna.org` |
| 时间 seek（`TimeSeekRange.dlna.org`） | ❌ | 只用字节 Range，故 `OP` 诚实写 `01` 而非 `11` |
| `GetCurrentTransportActions` / `SetPlayMode` | ❌ | 控制器靠前者决定按钮灰显，暂未实现 |

两处刻意的"不写"：

- **`DLNA.ORG_OP` 写 `01` 不写 `11`** —— 该字段是两位十六进制位域：
  `01`=支持 Range 请求头（字节 seek），`10`=支持 `TimeSeekRange.dlna.org`，`11`=两者。
  我们实际只发 Range，声明了做不到的能力比不声明更容易让进度条失灵。
- **不写 `DLNA.ORG_PN`** —— 那是"我能解某个具体 DLNA 规格"的声明
  （如 `AVC_MP4_MP_SD_AAC_LTP`），而我们并不按 profile 校验，
  乱写会让严格的发送端拿它去比对、反而拒绝推流。

### 关于 DLNA 协议栈：为何没有直接依赖 Cling

原计划基于 Cling 实现 DLNA-Cast，执行中遇到两个硬约束：

1. **Cling 已不可获取** —— 项目停止维护，官方 Maven 源 `4thline.org` 已下线，
   Maven Central 亦无产物，依赖根本拉不下来。
2. **其继任者 jUPnP 的 Kotlin 适配成本过高** —— jUPnP 的泛型模型
   （`Action<S>`、`Service<D,S>`、`StateVariableTypeDetails`）导致手写 Action 时
   实测产生 40+ 处类型错误，强行适配既脆弱又难以维护。

因此改为**自研轻量 UPnP 栈**（`upnp/` 目录，约 600 行，零第三方依赖）：

| 文件 | 职责 |
|---|---|
| `upnp/SsdpServer` | SSDP：`ssdp:alive` 通告 + `M-SEARCH` 响应 |
| `upnp/UpnpHttpServer` | HTTP：device.xml / SCPD / SOAP 控制 / GENA 订阅 |
| `upnp/Soap` | SOAP 报文编解码 |
| `upnp/DeviceDescription` | 设备描述与三份 SCPD |

**协议行为不是凭空实现的**：先写了一个 Python 探针在真实设备上跑通
（能被夸克网盘发现、成功收到 `SetAVTransportURI` 并推流），
再按验证过的报文结构移植到 Kotlin。分层模型仍与 Cling 保持一致：
DMR（接收端）与 DMC（发送端）由 `DmrService` / `DmcClient` 分离实现。

### 实测驱动的三个实现决策

以下结论来自对真实设备（夸克网盘 App → 本机探针）的抓包分析：

1. **必须持续广播**
   DLNA 的 `ssdp:alive` 与 mDNS 的 announcement 都必须周期性重发。
   实测：SSDP 广播间隔 600 秒时设备**不会出现在投屏列表**，改成 60 秒后立刻出现。
   很多 App 是被动监听广播建设备列表的。

2. **`GetProtocolInfo` 的 Sink 列表必须完整**
   发送端先查询本机支持的格式，声明不全则不会列入设备列表。
   见 `RendererDeviceFactory.SINK_PROTOCOLS`。

3. **不要相信 `protocolInfo`**
   实测夸克 DLNA 通道把 **m3u8 标成 `video/mp4` + `DLNA.ORG_PN=MP3`**。
   播放格式一律按 URL 后缀推断（`MediaInfo.effectiveMimeType`）。

另外，夸克直链带时效 token，收到 `SetAVTransportURI` 后必须**立即起播**，不能缓存 URL。

### 为什么用 Media3 而不是系统 MediaPlayer

Android 6.0 的系统 `MediaPlayer` 从 **API 26** 起才支持 HLS，而实测发送端推的是 m3u8。
Media3(ExoPlayer) 的 minSdk 为 21，可覆盖 API 23 下限。

---

## 三、界面

界面分三个页面，各司其职 —— 主页只放普通用户需要的信息，配置与诊断收进设置页。

| 页面 | 面向 | 内容 |
|---|---|---|
| **主页（待机屏）** | 所有用户 | 设备名（超大字号）、运行状态、支持的投屏方式、投屏引导、本机地址 |
| **投屏播放** | 所有用户 | 视频全屏 + 顶部信息浮层（OSD） |
| **设置页** | 进阶用户 | 设备改名、接收服务开关、网络信息、运行日志 |

### 为什么主页不放协议开关

「DLNA / UPnP」「AirPlay」这些术语对普通用户没有意义，而开关一旦被误关，
用户在手机投屏列表里就再也搜不到本设备，且无从排查。
所以开关全部收进设置页，并在旁边写明后果：「关闭某项后，对应方式的投屏将无法搜索到本设备」。

### 投屏播放（OSD）

收到投屏后自动切到全屏，顶部浮层显示「正在投屏 / 媒体名 / 来自哪台设备 / 进度条」，
4.5 秒后自动淡出；之后按遥控器返回键或点击画面可再次唤出。

**播放控制权在发送端，但接收端必须留出口**：播放、暂停这些照手机端执行，
但用户手里是遥控器，所以方向键 ←/→ 直接映射为快退/快进 10 秒；
**返回键连按两次（3 秒内）才结束投屏** —— 单次按键只提示"再按一次返回键，结束本次投屏"，
避免误触（投屏停了要回手机上重新点一遍）。

> ⚠️ 早期版本把返回键完全拦截、只弹信息浮层，没有任何出口，等于把用户锁在全屏里
> （发送端 App 被杀或锁屏时，除了拔电源没法退出）。这是把"协议分工"推到极致的反常识设计。

### 投屏音量闸门

发送端一开始播放几乎总是报满音量（DLNA `SetVolume(100)`、AirPlay `/volume?volume=1.0`）。
照字面执行，就是把**发送端的音量刻度**当成了**接收端的刻度**。

做法（`core/VolumeGovernor.kt`）：起播瞬间把当时的系统音量锁为本次投屏的上限，
之后发送端的音量只作为 0..1 的比例映射到 `[0, 上限]`。
用户在投屏期间用遥控器手动调高**或调低**音量，上限都会跟随调整
（靠记录"上次由闸门设置的值"来区分音量是谁动的）。

### 交互上的几个刻意选择

- **只展示真实能力**：主页「支持的投屏方式」按模块实际状态动态生成，
  不可用的协议不会出现，避免"界面写着支持、实际用不了"。
- **播完 ≠ 投屏结束**：视频播完只是这一条放完了，会话仍在（用户可能点下一集），
  界面保持投屏态、不会退回主页。只有发送端主动 Stop 才结束会话。
  （踩坑：ExoPlayer 的 `STATE_ENDED` 曾被映射为 `STOPPED`，导致播完就跳回主页，
  用户会以为投屏断了。正解是映射为 `PAUSED`。）
- **投屏优先于一切**：用户停在设置页、或把 App 切到后台时收到投屏，
  会自动切回播放界面 —— 否则会出现"只有声音、没有画面"的困惑。
- **设备名全局唯一**：改名对所有协议统一生效，不会在手机里看到本设备以不同名字出现多次。
- **开关整行可点**：电视遥控器只有一个焦点，让整行承载焦点比让小开关承载更好操作。

### 遥控器操作

| 按键 | 行为 |
|---|---|
| 方向键 ← / → | 投屏中：快退 / 快进 **10 秒** |
| 返回键 | 投屏中：第一次唤出浮层并提示"再按一次结束投屏"，3 秒内第二次**结束投屏**；其它页面：返回上一级 |
| 停止键（■） | 投屏中：同返回键（仍需连按两次确认） |
| 确定键 | 选中当前聚焦项 |

**为什么接收端可以做快进**：DLNA 的控制权本在发送端，但接收端跑在电视上、用户手里是
遥控器 —— 为了快进而专门去掏手机并不合理。本地定位之后，发送端会通过周期性轮询
`GetPositionInfo` 读到新进度并自动同步，两端不会脱节（实测：本地 13s→3s 后，
发送端下一次轮询即读到 4s）。
直播流等拿不到总时长的内容会提示「当前内容不支持拖动进度」，而不是按了没反应。

### 焦点管理（电视端的三个必踩坑）

都会让遥控器"看起来坏了"：

1. **铺满全屏的可点击元素 = 焦点陷阱**
   `video_layer` 一旦 `setOnClickListener` 就隐式变为可聚焦，而它没有任何焦点视觉。
   焦点被它吸走后，用户按确定键等于点在一片看不见的全屏区域上 ——
   表现就是"设置按钮怎么按都没反应"。
   解法：待机态显式 `isClickable = false; isFocusable = false`，仅投屏态打开。

2. **`ScrollView` 的 focusable 用 XML 关不掉**
   `ScrollView.initScrollView()` 里**硬编码调用了 `setFocusable(true)`**，
   会覆盖 XML 上的 `android:focusable="false"`。后果是焦点停在这个没有任何视觉反馈的
   容器上，到页面最底部就再也下不去（实测：按第 3 次「下」起焦点卡死）。
   解法：**必须在代码里再关一次** —— `findViewById<ScrollView>(id).isFocusable = false`。

3. **几何焦点查找会"跳项"**
   从左上角的「返回」按「下」时，因为「返回」在左、「修改」在右、水平方向不重叠，
   焦点会直接跳到横向铺满的模块行，**跳过「修改」按钮**。
   解法：用 `android:nextFocusDown` 显式指定。
   另外，不可用的项（如未接入的 AirPlay）不参与焦点是**有意为之**，
   所以焦点从 DLNA 行直接跳到「展开」属于正确行为。

> 排查手法：`adb shell uiautomator dump` + 解析 XML 中 `focused="true"` 的节点，
> 直接看焦点落在哪个 View 上 —— 比截图猜测靠谱得多。上面三个问题都是这样定位的。

### 遥控器操作的实测结论

| 验证项 | 结果 |
|---|---|
| 主页方向键 → 焦点落在「设置」（蓝色高亮） | ✅ |
| 确定键 → 进入设置页 | ✅ |
| 设置页：返回 → 修改 → 模块行 → 展开（顺序正确） | ✅ |
| 确定键 → 弹出「修改设备名」对话框，焦点自动落到输入框 | ✅ |
| 遥控器 ← / → → 快退 / 快进 10 秒，且发送端同步 | ✅ |
| 投屏中按一次返回键 → 弹「再按一次返回键，结束本次投屏」，**播放继续** | ✅ |
| 3 秒内再按一次 → 会话结束、播放器停止、退回待机屏 | ✅ |

### 图文层级与遥控器适配

- 深色主题（电视多为暗环境，大面积白底刺眼）
- 设备名 44sp（用户要拿着手机在投屏列表里对照这个名字），正文 17–18sp（10-foot UI 下限）
- 大屏加大内容边距（`values-w600dp`，160dp），避免正文铺满 1280dp 后单行塞进 60+ 汉字
  用 `w600dp` 而非 `sw720dp`：1080p 电视通常是 320dpi，可用宽度只有 960dp，`sw720dp` 会失效
- 所有可点元素都有焦点高亮（描边 + 背景变化），可用方向键导航
- 播放期间 `keepScreenOn` 常亮，防止电视休眠

---

## 四、设备发现速度

客户端反映"发现太慢"，实测定位到两个原因，都已修掉：

| 原因 | 原实现 | 现实现 | 实测效果 |
|---|---|---|---|
| 通告太稀疏 | `ssdp:alive` 每 60 秒一次 | **每 1 秒一次** | 60 次 / 60 秒，设备出现速度肉眼可见变快 |
| 应答被人为拖慢 | 按 UPnP 规范在 `0..MX` 秒内随机延迟后才回 `M-SEARCH` | **立即应答**（仅 80ms 抖动防撞包） | 应答 11–250ms，平均约 110ms |

第二条是"打开投屏面板后要等一两秒才看到设备"的直接原因。规范里那条延迟是为了分散
**大量设备**的集中应答，家庭网络设备很少，不存在该风险，响应速度远比遵守它重要。

### 一个不能做的"优化"

排查过程中曾加过"每 60 秒 `leaveGroup` + 重新 `joinGroup` + 释放并重取多播锁"的刷新逻辑，
结果**应答反而断掉**：`leaveGroup` 会立刻发出 IGMP leave，AP 随即停止向本机转发该组播组，
重新加入后转发关系还要重建，等于自己制造了一段接收中断。

现在**任何形式的组播刷新都已删除**，包括"幂等的重复 `joinGroup`"：
Android 上对同一 socket 重复加入同一组播组并不幂等，会抛
`SocketException: setsockopt failed: EADDRINUSE`，实测每 60 秒刷一次异常堆栈。

> 而当初促使加这段逻辑的"长时间后收不到 M-SEARCH"，本身是**测试工具误判**：
> 测试脚本绑定了 1900 端口，而该端口在 Windows 上被系统 SSDP 服务占用，
> 收不到包的是**测试端**，电视侧一直正常。
> **教训：定位问题先确认测量工具本身是否可靠，别急着给被测对象加"补偿逻辑"。**

> Windows 上验证时注意：`1900` 被系统 SSDP 服务（`svchost`）占用，本机绑定它收不到组播
> （系统允许多个 socket 绑定同一端口，但只会把包投给其中一个），很容易误判成"设备没广播"。
> 测 `M-SEARCH` 用随机端口即可 —— 应答本来就是单播回请求方源端口的。
> 见 `tools/ssdp_speed_test.py`。

---

### 兼容性边界：哪些客户端能被搜到

**「支持 DLNA」不等于「所有手机的投屏都能找到本设备」** —— 不同客户端走的是不同通道。

实测（`tools/mdns_scan.py` 监听 mDNS，配合 App 侧统计 SSDP）：

| 客户端 | 发现通道 | 能否被发现 |
|---|---|---|
| **夸克网盘** | mDNS(`_leboremote`/`_lyra-mdns`) **+ DLNA(SSDP)** | ✅ 能（DLNA 那条通） |
| 爱奇艺 / 腾讯视频 / B站等 App 内投屏 | 多为 DLNA | ✅ 通常能 |
| **小米手机「投屏」（系统级）** | **只走 mDNS 私有服务**：`_leboremote`、`_lyra-mdns`、`_mi-connect`、`_airkan` | ❌ 不能 |
| iPhone「屏幕镜像」 | AirPlay | ❌ 未接入（见第六节） |
| 安卓「无线显示」/ Miracast | Wi-Fi Direct（不是 IP 局域网） | ❌ 第三方 App 做不到（需系统权限） |

实测数据：小米手机停在投屏搜索界面 10 秒以上，本机 mDNS 侧能看到它的查询，
而 **App 收到的 SSDP M-SEARCH 数量为 0**。

原因很直接：小米手机的投屏面板**压根不搜 DLNA**，只搜它自己那套私有服务名。
要覆盖它，必须实现 **乐播 LeLink**（协议已实测还原，见 `docs/lelink-protocol.md`）
或 **AirKan** —— 两者都是私有协议。

> 为什么没做：冒用 `_leboremote` 服务名有商标与不正当竞争风险。
> 稳妥路线是自研私有协议 + 配套发送端 App。

#### 关于 Miracast：为什么第三方 App 一定做不到

小米手机「投屏」的底层还有 **Miracast**（Wi-Fi Direct 屏幕镜像），这一条**无法用 App 承接**，
原因在 Android 的权限模型，不在工程量：

- 系统里管这件事的是 `WifiDisplayAdapter` / `WifiDisplayController`，跑在 **SystemServer** 内，
  需要的 `CONFIGURE_WIFI_DISPLAY` 是 **signature|privileged** 级权限，普通 App 拿不到。
- 更前置的一步也过不去：Miracast 的**设备发现**依赖 Wi-Fi Direct 探测帧里的 WFD IE，
  由 **Wi-Fi 固件 + framework** 填充，App 无法让本机在 P2P 探测中宣告“我是 Miracast sink”。

**旁证（很硬）**：这台测试电视上预装了「当贝投屏」（`com.dangbei.screencast`）——
一个成熟的专业投屏产品，但看它声明的权限：`CHANGE_WIFI_MULTICAST_STATE`（DLNA/mDNS）、
`RECORD_AUDIO`、华为 Cast+ 系列 —— **完全没有 `CONFIGURE_WIFI_DISPLAY`，
也没有任何 Wi-Fi P2P 相关权限**。即：**连它都不实现 Miracast 接收端**。

这台电视的系统层面是支持的（`settings get global wifi_display_on` 存在，实测值为 `0`），
但那是**系统 framework 的能力**，只能在电视的系统设置里手动开启；
开启后由**系统**接收，与任何第三方 App 无关。且该设置项属于 secure settings，
adb shell 无 `WRITE_SECURE_SETTINGS` 权限时也改不了。

---

## 五、构建

```bash
# 环境要求：JDK 17、Android SDK（platform 34 + build-tools 34.0.0）
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk

./gradlew --console=plain assembleDebug    # 开发用
./gradlew --console=plain assembleRelease  # 给用户装这个
# 产物：app/build/outputs/apk/{debug,release}/app-{debug,release}.apk
```

`local.properties` 中的 `sdk.dir` 需指向本机 SDK（该文件未纳入版本管理）。

### 发布签名

发布包用**专用 keystore** 签名，不是 debug 密钥 —— 否则任何人都能伪造同签名的"升级包"。

私钥与口令都不入库（见 `.gitignore`）。要构建正式包，在仓库根目录放两个文件：

```
keystore/casthub-release.jks     # 私钥，务必备份；丢了就无法再覆盖升级已发布的版本
keystore.properties              # 口令，格式见下
```

```properties
storeFile=keystore/casthub-release.jks
storePassword=<口令>
keyAlias=casthub
keyPassword=<口令>
```

两者缺失时 `assembleRelease` 会**自动退回 debug 签名**，让 clone 下来的人也能构建 ——
代价是产物签名不同、无法覆盖安装到正式版上（升级校验会明确拒绝，见第九节）。

一旦正式发布过，**这个 keystore 就不能再换**：Android 只允许同签名的包覆盖安装，
换密钥等于所有用户必须卸载重装。

生成应用图标（已内置，如需重新生成）：

```bash
python tools/make_icon.py
```

### 新增协议模块

在 `CastHubApplication.modules` 里加一行即可，UI 与 core 层都不需要改动 ——
它们只依赖 `core` 的抽象（见第一节）。

### 发布流程

```bash
# 1) 改 app/build.gradle.kts 里的 appVersionCode / appVersionName（版本号唯一来源）
# 2) 构建 + 跑单测
./gradlew assembleRelease test
# 3) 打 tag 并推送（tag 必须与 versionName 一致，升级检测靠它比较）
git tag v1.4.0 && git push origin v1.4.0 && git push gitee v1.4.0
# 4) 在 GitHub 与 Gitee 各自创建 Release，挂上 app-release.apk
```

---

## 六、AirPlay：为什么是自研而不是接 UxPlay

常见的 AirPlay 接收端实现是 UxPlay（C），依赖 GStreamer、libplist、libsodium、OpenSSL，
需要为各 ABI 交叉编译 —— 本机没有 NDK/cmake，也装不下 GStreamer，于是走了自研路线。

### 关于第三方实现的调研

曾评估 `alexfansz/airplay_dlna_googlecast`（自称「兔兔投屏」，标称 LGPL-2.1），
结论是**不能采用**：

| 检查项 | 结果 |
|---|---|
| 仓库性质 | 商业产品（basicgo.net）的**分发渠道**，README 是营销特性表，不是源码仓库 |
| AirPlay 核心 | `libs/*/libairplay.so` —— **预编译二进制，无源码** |
| DLNA / UPnP | `libDLNADMRClass.so` / `libupnp3.so` —— 同样无源码 |
| Java 部分 | 只有 `com/xindawn/*`（player / music / center / DLAN）= **UI 与业务壳** |
| LGPL 声明 | **不成立** —— LGPL 要求提供可修改、可重新链接的源码，而核心是闭源 `.so`，无法重新链接 |
| 额外风险 | 依赖 `libfdk-aac.so`（Fraunhofer FDK-AAC，**许可非自由、商用需付费**） |

**但它证明了一件有价值的事**：AirPlay 接收端**不需要 GStreamer** ——
用 Android `MediaCodec` 硬解 + 自研协议层，`minSdk 23` 可行。
协议本身仍要自己写，本项目的视频投屏部分就是这么落地的。

它的特性表里还写着：安卓手机镜像投屏**需要安装配套的发送端 App**。
这印证了本项目的判断：**覆盖安卓手机系统投屏，绕不开自有协议 + 配套发送端**。

### 踩过的坑

1. **手写二进制 plist 解析器把 trailer 偏移算错**（26/27/28/36/44，实际是 6/7/8/16/24），
   表现为 iPhone 点投屏毫无反应。现已改用 `dd-plist`（Apache/MIT）。
2. **`HttpURLConnection` 不支持 `NOTIFY` 自定义方法**（DLNA 侧同类问题），
   GENA 推送必须用裸 Socket。
3. **mDNS 优先用系统 `NsdManager`**，注册失败或超时（4 秒）再回退到自实现响应器，
   两者对外宣称的能力完全一致。

---

## 七、已知限制

| 限制 | 说明 |
|---|---|
| 无 AirPlay 屏幕镜像 / RAOP 音频 | 视频 URL 模式已实现；镜像与音频是另外两套协议栈，且 mDNS 里不声明 |
| 无 Miracast | 权限模型所致（`CONFIGURE_WIFI_DISPLAY` 为 signature\|privileged 级），App 无法实现 |
| 小米系统级「投屏」搜不到本机 | 走 mDNS 私有协议（`_leboremote` / `_mi-connect` 等），不发 SSDP 探测 |
| DMC（投到其它设备）无界面入口 | 协议链路已通并修好会话映射，UI 未做 |
| 未做 DRM | AirPlay 侧 DRM 内容（Apple TV+ 等）任何开源接收端都无法解密 |
| DLNA 协议栈为自研 | 未经 Cling 那样的长期生态验证；协议行为按真实抓包还原，边界场景需实测打磨 |

---

## 八、构建产物

```
app/build/outputs/apk/debug/app-debug.apk      # 开发调试用
app/build/outputs/apk/release/app-release.apk  # 给用户装这个（见下方启动速度对比）
```

- `applicationId`: `com.casthub.app`
- `versionName`: `1.4.0`（`versionCode` 17）
- `minSdkVersion`: **23**（Android 6.0）
- `targetSdkVersion`: 34
- 签名：**专用发布 keystore**（`keystore/casthub-release.jks`，不入库；缺失时退回 debug 签名，见第五节）
- 图标：`mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher(.round).png` + `mipmap-anydpi-v26` 自适应图标
- 版本号同时显示在待机页底部与设置页，便于报问题时确认版本

重新生成图标：`python tools/make_icon.py`

### 冷启动速度：debug 与 release 差近 3 倍

同一台电视（MediaTek / Android 9）实测 `am start -W` 冷启动：

| 包 | 冷启动 | 说明 |
|---|---|---|
| debug | **4.3 秒** | 未做 dex 预编译与压缩，类加载占了 ~2.7 秒 |
| release | **1.5 秒** | 同样没开 R8，仅 dex 预编译就省掉大半 |

如果觉得"打开 App 黑屏很久"，先确认装的是不是 debug 包。
日常开发用 debug 没问题；给电视上长期装用的，用 `assembleRelease`。

启动观感上还做了两件事（详见代码注释）：

1. **启动画面**（`drawable/splash.xml`）：主题 `windowBackground` 若是纯色，
   首帧之前窗口就是一片漆黑；现在首帧前显示深色底 + 品牌图标。
2. **模块启动移出主线程**：`Application` 里启动 DLNA（绑 socket、取多播锁、
   监听 HTTP）原本会被 `Main.immediate` 同步压在启动路径上，已切到 `Dispatchers.IO`。

---

## 九、应用内升级检测

**设置 → 关于 → 检查更新**；冷启动也会静默查一次。版本信息取自 GitHub 与 Gitee 的
Release，取版本更高的一个，可直接下载安装包并调起系统安装器。

### 为什么两个源都要查

国内直连 GitHub 常超时，而且未鉴权接口是**每出口 IP 每小时 60 次** ——
实测共享出口 IP 上该额度长期为 0，直接返回 403。只看 GitHub 会遇到大量
"检查更新失败"，只看 Gitee 又可能漏掉只在 GitHub 上发过的版本。

所以两源**并行**发出（串行等待会让检查卡一二十秒），结论取版本更高的一条。
若某一边没取到，结论里会带一句告警 —— 因为此时"已是最新"可能是漏判的：

```
⚠️ 部分数据源未取到，结论可能不完整：GitHub：访问受限（403），稍后再试
```

版本与安装包允许来自**不同的源**：两站 Release 常不同步，若坚持同源，
就会出现"Gitee 发了版本但没挂包、于是 GitHub 上明明有包却不给下载"。

### 版本比较

tag 走轻量 semver：支持 `v` 前缀、按**数值**比较数字段（`1.10.0 > 1.9.0`）、
缺省段补零（`1.4 == 1.4.0`）、预发布版本更旧（`1.4.0-rc1 < 1.4.0`）、
`+` 后的构建元数据不参与比较。解析不出数字段的 tag（如 `nightly-20260101`）
整条跳过，而不是从中抠出一段数字当版本。

草稿（GitHub `draft`）与预发布（`prerelease`）不参与升级。

### 安全约束

应用内下载并安装 APK 是**唯一"下载并执行外部代码"的通道**，因此按不可信输入处理：

| 措施 | 作用 |
|---|---|
| 只接受 https，且域名限定在 `github.com` / `githubusercontent.com` / `gitee.com` / `gitee.io` | 即便 Release 信息被篡改指向别处，也走不出这一步 |
| 下载完成后**校验签名与本机一致**才交给安装器 | 真正的关口。不一致时删除安装包并明确说明"这不是官方构建"，而不是把包留在那让人手动装 |
| 先写 `.part` 再改名、长度不符即判失败 | 避免截断的包被当成"安装包损坏"这种无解错误 |
| 手动检查才有强反馈；启动检查失败一律静默 | 离线设备不会每次开机弹"检查更新失败" |

首次安装会要求用户在系统里允许「安装未知应用」（Android 8+ 的硬性要求），
未授权时直接引导去对应设置页，而不是静默失败。

### 节流

启动时的自动检查有 **6 小时**最小间隔，手动检查不受限。
"忽略此版本"只影响自动提示，之后手动检查仍会如实显示。

> 不给节流的话，用户多开关几次应用就能把 GitHub 的匿名额度耗光 ——
> 那之后不只是本应用，整个出口 IP 上的检查都会 403。

---

## 十、权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 局域网通信 |
| `CHANGE_WIFI_MULTICAST_STATE` | **SSDP / mDNS 组播收发，缺失或未加锁则设备永远搜不到** |
| `WAKE_LOCK` | 播放期间保持唤醒 |
| `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)` | 后台持续可被发现 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `ACCESS_COARSE_LOCATION`（maxSdk 28） | Android 6.0 读取 Wi-Fi 名称所需 |
| `REQUEST_INSTALL_PACKAGES` | 应用内升级：安装本应用自己的升级包（安装包必须通过签名校验） |

> ⚠️ `CHANGE_WIFI_MULTICAST_STATE` **光在 Manifest 里声明是不够的**。
> Android 会在 Wi-Fi 芯片层过滤入站多播包，必须由代码持有
> `WifiManager.MulticastLock`（见 `upnp/MulticastLockHolder.kt`）。
> 缺这把锁的典型症状是：SSDP 通告能发出，但对端发来的 `M-SEARCH` 一个都收不到，
> 投屏 App 的设备列表里永远没有本机。

> ⚠️ `REQUEST_INSTALL_PACKAGES` 只用于安装本应用的升级包，且安装前会校验签名
> 与本机一致。`FileProvider` 的授权范围也限定在 `cacheDir/updates/` 一个子目录
> （见 `res/xml/file_paths.xml`），没有放开整个缓存目录。

---

## 十一、实测验证

在真机 **Android 9 智能电视**（MediaTek / armeabi-v7a / 1280×720）上完成验证。
下表是 1.4.0 的复跑结果：

| 验证项 | 工具 | 结果 |
|---|---|---|
| 协议逻辑单元测试 | `./gradlew test` | ✅ 全过（含升级检测的版本比较 / JSON 解析 / 择优逻辑） |
| 设备发现（SSDP） | `tools/ssdp_discover_test.py` | ✅ 各 ST 全部响应，device.xml 可访问 |
| DMR 端到端投屏 | `tools/dmr_client_test.py` | **19/19 通过** |
| DLNA 合规性 | `tools/dlna_conformance_check.py` | **15/15 通过** |
| AirPlay 端到端 | `tools/airplay_client_test.py` | **20/20 通过** |
| 音量闸门 | `tools/volume_test.py` | ✅ 投屏前 5 → 发送端拉满仍 5 / 减半 3 / 静音 0 |
| 遥控器退出投屏 | `tools/exit_test.py` | ✅ 单击只提示、连按两次真退出 |
| 设备身份稳定性 | 重启进程后比对 device.xml | ✅ UDN 不变（不会在设备列表里堆同名条目） |
| 实际播放 | 进度轮询 + 截图 | ✅ `PLAYING`，进度真实递增，画面可见 |
| 升级检测 | 设置 → 检查更新 | ✅ 正确识别两站版本、能下载并校验签名 |
| 待机页一屏可见 | `tools/home_layout_fit_check.py --presets` | ✅ 手机 / 720p 电视 / 1080p 电视 × 服务全开 / 关一项，共 6 档全过 |

> ⚠️ 测音量必须用 `dumpsys audio` 读真实值。`settings get system volume_music`
> 在这台电视上不反映 `AudioManager` 的实际状态，会得出"音量指令没生效"的错误结论。
> 同理，`uiautomator dump` 在全屏 SurfaceView 播放中会返回 `null root node`，
> 验证投屏态界面要改用 `screencap` 截图。**先确认测量工具本身可靠，再下结论。**

```
#6 状态轮询（10 次，间隔 1s）
    #1  state=TRANSITIONING      position=00:00:00
    #2  state=PLAYING            position=00:00:00
    #4  state=PLAYING            position=00:00:02
    #10  state=PLAYING           position=00:00:08
测试结果：19/19 通过
```

### 真机测试暴露并修复的问题

这些都是**单元测试无法覆盖、只有真机才能发现**的：

1. **ExoPlayer 跨线程访问**
   DLNA 的 SOAP 动作运行在 HTTP 工作线程，直接调用播放器会抛
   `IllegalStateException: Player is accessed on the wrong thread`。
   现已统一经主线程 Handler 投递，HTTP 线程只读 `@Volatile` 缓存。

2. **缺少 Wi-Fi 多播锁**（最关键）
   未加锁时 `M-SEARCH` 收到次数为 **0**，设备无法被任何投屏 App 发现。
   加入 `MulticastLockHolder` 后立即恢复。

3. **二进制 plist trailer 偏移算错** → iPhone 点投屏毫无反应，改用 dd-plist 后解决。

4. **播完被映射成 `STOPPED`** → 用户以为投屏断了。正解是映射为 `PAUSED`。

5. **投屏页没有退出出口** → 发送端不主动断开时用户被锁死，改为返回键连按两次结束。

6. **遥控器焦点陷阱与 `ScrollView` 焦点关不掉** → 表现为"设置按钮怎么按都没反应"。

7. **AirPlay 播完拖回去重看被判卡死** → `STATE_ENDED` 后进度取样器停了且不重启，
   `/playback-info` 位置冻结，iPhone 主动断连。

8. **设置页在主线程做阻塞 IO** → 切换协议开关、改名会同步绑 socket、取多播锁、
   等 `NsdManager` 注册（最长 4 秒）。实测 `Choreographer: Skipped 37 frames`，
   遥控器按键肉眼可见地迟滞。现已全部切到 `Dispatchers.IO`。

9. **`wrap_content` 的 RecyclerView 嵌在 ScrollView 里只渲染一行**
   → 接收服务列表只显示 DLNA，AirPlay 那行凭空消失。这类嵌套下 RecyclerView
   的高度会按**一个条目**收敛，换成 `LinearLayout` 从根上不存在这个问题。

10. **`DnsCodec.readName` 的结束偏移算错** → 多标签域名的结束位置只算到第一个标签之后，
    于是 `parseQuestions` 从错误位置读 QTYPE，`QDCOUNT ≥ 2` 时越界崩溃。
    单条查询看不出来，是**新写的单元测试**把它咬出来的。

11. **HTTP 请求体按不可信的 `Content-Length` 无上限分配** → 同网段主机只要声明一个
    2GB 的请求体就能把接收端打 OOM。现已限制上限并回 413。

12. **投屏态 `video_layer` 被设成可聚焦** → 它是全屏唯一的可聚焦视图，必然拿到焦点，
    然后把遥控器 ←/→ **吞掉**，表现为"快进时灵时不灵"。
    实测对照：无视图持有焦点时按下即 0s→12s；持焦点时连按 3 次一条定位日志都没有。
    可点击 ≠ 可聚焦 —— 触摸唤出 OSD 只需要 `clickable`。

验证方法见 `tools/` 下的脚本，可直接复用。

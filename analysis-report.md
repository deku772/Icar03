---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '0d65abd2-5415-479d-a9b8-dd5d7e99f987'
  PropagateID: '0d65abd2-5415-479d-a9b8-dd5d7e99f987'
  ReservedCode1: 'c4b815b6-2567-4915-87b2-a45d8ebe5587'
  ReservedCode2: 'c4b815b6-2567-4915-87b2-a45d8ebe5587'
---

# iCAR 03 车机第三方应用生态分析

> 基于 9.9Studio 03 系列（03歌词 / 03桌面 / 03投屏 / 03车机助手）的静态逆向分析整理。
> 本文档只记录**公开可观察的架构知识**与**车机适配方法论**，不含任何反编译源码。
> 逆向分析仅用于个人学习研究；商业软件的付费授权不应被绕过。

## 1. 背景

iCAR 03（奇瑞旗下品牌）车机系统为 Android 9，原厂桌面为 `com.mengbo.launcher3`（梦博桌面）。
系统未提供第三方应用入口与开发者文档，所有第三方适配都是围绕"普通用户态 App + ADB 一次性授权"完成的。

9.9Studio 的 03 系列是目前该机型生态最完整的第三方套件，其适配手法有很高的参考价值。

## 2. 四个应用的分工

| 应用 | 包名 | 职责 |
|---|---|---|
| 03歌词 | `com.ninepointnine.desktoplyrics` | 悬浮歌词渲染（收费，7天试用） |
| 03桌面 | `com.ninepointnine.desktop` | 侧边面板启动器，第三方应用入口（免费） |
| 03投屏 | `com.ninepointnine.desktopcast` | 手机投屏（走全屏占用租约） |
| 03车机助手 | `com.ninepointnine.helper` | 手机端安装器，通过无线 ADB 连车机装应用 |

## 3. 车机适配三板斧（核心方法论）

原厂不给接口，第三方应用能靠的是三样东西：

### 3.1 无障碍服务监听原厂桌面

- 通过 view ID `com.mengbo.launcher3:id/adas_handler_view` 定位侧边 Dock 把手
- 实时读取 Dock 的展开状态（左/中/右三区域）与滑动手势方向
- 用途：歌词/面板在侧栏弹出时主动避让，不遮挡
- 关键：这个 view ID 没有公开文档，是实机摸索出来的

### 3.2 监听厂商私有 Settings 键

车机把大量系统状态写进 `Settings.Global` / `Settings.Secure`，键名均为厂商私有命名：

| 键 | 含义 | 用途 |
|---|---|---|
| `com.mengbo.launcher3.SETTINGS_KEY_LAUNCHER_STATE` | 桌面当前形态 | 切换顶栏/壁纸歌词模式 |
| `global_setting_ac_page_status` | 空调页状态 | 空调页打开时歌词避让 |
| `com.mengbo.launcher3.settings.secure.window_mode` | 窗口模式 | 判断显示区域 |
| `com.chery.carsettings.action.SENTRY_MODEL` | 哨兵模式 | 避让全屏提示 |
| `com.mengbo.provider.SAFE_PEDESTRIAN` | 行人安全提示 | 避让 |
| `com.mengbo.provider.wireless_charging_state` | 无线充电状态 | 视觉避让 |
| `com.mb.provider.usb_sd_mounted` | U盘/SD卡挂载 | 避让 |
| `com.mb.provider.theme_key` | 系统主题色 | 歌词跟随原厂主题色 |

实现方式：`ContentResolver.registerContentObserver()` 监听变化，配合 `MEDIA_MOUNTED` 等系统广播。

### 3.3 应用间"表面占用租约"协议

原厂没有任何多应用窗口协调机制，作者自定义了一套协议（命名空间 `com.tcrrry.icar.surface.*`）：

- 03桌面/03投屏需要占用屏幕时，通过 `bindService` 绑定 03歌词暴露的服务
- 服务 action：`com.tcrrry.icar.surface.action.ACQUIRE_OCCUPANCY_LEASE`
- 通过 meta-data `OCCUPANCY_PROTOCOL_VERSION=1` 协商版本
- 歌词收到绑定即收缩/隐藏，解绑恢复；绑定死亡自动重连
- 03投屏用全屏版本：`ACQUIRE_FULL_DISPLAY_OCCUPANCY_LEASE`

这是一个完全自洽的第三方生态协议，任何遵守该 action 的 App 都能接入协调。

## 4. 悬浮窗实现

- 窗口类型：`TYPE_APPLICATION_OVERLAY`（系统悬浮窗）
- 渲染：**WebView 加载本地 `lyrics_overlay.html`**（约 78KB），Canvas 逐字渲染动画
- 数据注入：JS 桥 `LobstaNativeLyrics`
- 权限来源：ADB 授权 `SYSTEM_ALERT_WINDOW` appop（一次性，重启不失效）

用 WebView 而非原生 View 的理由：歌词动画（逐字卡拉OK、模糊、阴影、行间距）用 Web 技术开发效率远高于原生。

## 5. 歌词链路（联网部分）

### 5.1 播放信息获取（不需要联网）

车机蓝牙栈会把手机蓝牙音乐暴露为标准 MediaSession（包名 `com.android.bluetooth`），含歌名/歌手/专辑/时长/播放进度。

- 通过 `MediaSessionManager` + `NotificationListenerService` 授权拿到全部活跃会话
- 优先选择蓝牙栈会话 → 这就是"任意蓝牙音乐源"的真相，不需要逐个适配音乐 App
- 备选：MediaBrowserService 浏览（Android 9 上最后稳定通道）

### 5.2 歌词源（三源降级）

| 源 | 搜索/获取接口 | 备注 |
|---|---|---|
| LRCLIB | `lrclib.net/api/get` + `/api/search` | 开源歌词库，精确+模糊两路 |
| 网易云音乐 | `music.163.com/api/search/get/web` + `/api/song/lyric` | 老版 web 接口 |
| QQ音乐 | `c.y.qq.com/soso/fcgi-bin/search_for_qq_cp` + `lyric/fcgi-bin/fcg_query_lyric_new.fcg` | nobase64 参数 + GBK 回退解码 |

匹配策略：按歌名/歌手/专辑/时长打分选最佳版本，失败自动降级到下一源。
请求头需伪装浏览器 UA 并带对应平台 Referer。

### 5.3 商业化（仅描述，勿破解）

- 服务器 `api.9.9studio.fun`，端点 `/v1/products/03lyrics/device-access/`
- 设备本地生成 EC P-256 密钥对 → 上报公钥+设备指纹+APK 签名哈希 → 服务器签发离线许可证（含有效期/离线宽限/试用截止/设备绑定）→ App 本地验签
- 许可证与设备公钥、官方签名强绑定，改包分发会验签失败

## 6. 授权流程（ADB 面向）

03车机助手代跑的授权命令，本质是往车机写三项：

| 应用 | 授权 |
|---|---|
| 03歌词 | 悬浮窗 appop + 通知使用权（Secure 设置）+ 无障碍服务 |
| 03桌面 | 悬浮窗 + 静默安装包权限 + 无障碍服务 |
| 文件管理器 | 读写存储 + 安装包权限 |

03车机助手使用 DAdb（无线 ADB 协议）在手机端直接连车机，无需电脑。

## 7. 对自研方案的启示

- 车机端不需要 root：无障碍 + Settings 监听 + 悬浮窗，三个用户态能力就够
- "任意蓝牙音乐源"本质是系统 MediaSession 的能力，人人可用
- 歌词源接口都是公开的 web API，自研调用无障碍
- 若要车机零流量：歌词在手机端获取，通过蓝牙传给车机渲染（见 bluetooth-lyrics-plan.md）
- 表面占用租约协议可以自实现一套，也可以接入作者的 `com.tcrrry.icar.surface.*` action 与其生态兼容

## 8. 法律与伦理边界

- 本仓库内容为架构学习笔记，非源码搬运
- 03歌词是付费软件，请尊重开发者权益，勿破解、勿分发修改版
- 逆向分析限个人学习，请遵守当地法律与软件许可协议
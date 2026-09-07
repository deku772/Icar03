---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '43d55785-1ba2-4328-99f3-bc4cf5ec09da'
  PropagateID: '43d55785-1ba2-4328-99f3-bc4cf5ec09da'
  ReservedCode1: '814f8786-f443-49fc-b05b-932464bfe637'
  ReservedCode2: '814f8786-f443-49fc-b05b-932464bfe637'
---

# Icar03

iCAR 03 车机第三方应用研究与自研方案仓库

## 内容

- `analysis-report.md` — 03系列车机应用（9.9Studio）架构分析：车机适配三板斧、悬浮窗实现、歌词链路、授权机制
- `bluetooth-lyrics-plan.md` — 自研方案：手机端取词 + BLE 蓝牙推送 + 车机渲染，车机零流量
- `app/` — **IcarLyrics v1.0** 车机端（自研，100% 原创代码，零第三方依赖）
- `phone/` — **IcarLyrics 手机端 v1.0**（自研，播放监控 + 三源取词 + BLE 推送）

## IcarLyrics 车机端（已完成 v1.0）

里程碑 M1+M2 已落地：悬浮窗渲染 + BLE GATT 接收端。

```
app/src/main/
├── assets/lyrics_overlay.html    渲染前端（Canvas 逐字卡拉OK，双模式/主题/翻译/预览，
│                                 含浏览器演示模式——直接双击即可看效果）
└── java/com/icarme/lyrics/
    ├── OverlayService.java       悬浮窗服务：TYPE_APPLICATION_OVERLAY + WebView
    ├── BleService.java           BLE GATT Server：广播/接收/MTU 协商
    ├── Reassembler.java          分片重组协议（frameId+seq+total 头）
    ├── MainActivity.java         授权引导 + 服务开关
    ├── BootReceiver.java         开机自启（有悬浮窗权限时）
    └── IcarApp.java / AdbHelper.java
```

### 协议（BLE GATT）

| 项 | UUID | 说明 |
|---|---|---|
| Service | `0000A100-CA21-...` | IcarLyrics 主服务 |
| lyrics_push | `0000A101-...` | Write，歌词分片（4字节头：frameId u16 + seq u8 + total u8，小端） |
| progress_sync | `0000A102-...` | Write，进度 JSON 单包 `{"type":"progress","positionMs":..,"playing":..}` |
| command | `0000A103-...` | Write，控制 JSON `{"type":"cmd","action":"clear/setMode/setTheme/ping"}` |
| ctrl_notify | `0000A104-...` | Notify，预留车机→手机事件 |

### 车机安装（ADB）

```bash
adb install app-debug.apk
adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow
adb shell am start -n com.icarme.lyrics/.MainActivity
```

### 构建

```bash
gradle assembleDebug   # Java 17+，AGP 8.13，minSdk/targetSdk 28（车机 Android 9）
```

### 手机端（已完成 v1.0）

手机 App 读本机播放状态（MediaSessionManager，优先蓝牙会话）→ 三源降级取词（lrclib/网易云/QQ，内存缓存）→ 作为 GATT Client 连接车机推送歌词与进度。**支持首次手动选择车机设备并记住，之后默认自动连接、断线自动重连。**

```
phone/src/main/java/com/icarme/lyrics/phone/
├── PlaybackService.java       总调度前台服务：监控→取词→推送循环
├── NotificationListener.java  通知使用权凭证 + MediaSession 轮询
├── LyricsFetcher.java         三源降级取词 + 内存缓存
├── BleClient.java             GATT Client：扫描/选择/记住设备/MTU/分片推送/自动重连
├── BlePacketizer.java         分片打包（frameId+seq+total 头）
└── PhoneMainActivity.java     权限引导 + 选择车机 + 服务开关
```

**手机端使用**：装 APK → 授权（蓝牙/定位/通知使用权）→ 主界面「选择车机」从列表选中（只需一次，以后默认自动连接）→ 启动推送服务 → 任意音乐 App 放歌。

**手机安装（ADB）**：
```bash
adb install IcarLyrics-Phone-v1.0-debug.apk
# 通知使用权需在系统设置里手动开启（跳转由 App 引导）
```

## 项目目标

做一个车机完全不用流量的歌词方案：

```
手机播放音乐 → 手机 App 读播放状态 + 联网取词
            → BLE GATT 推送歌词/进度
            → 车机 App 悬浮窗渲染
```

## 声明

- 分析部分仅用于个人学习研究，不包含任何第三方软件的反编译源码
- 03歌词是付费商业软件，请尊重开发者权益，勿破解、勿分发修改版
- 遵守当地法律法规与软件许可协议
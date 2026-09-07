---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'ea9fa3db-9228-43e7-8b54-47511df2a18f'
  PropagateID: 'ea9fa3db-9228-43e7-8b54-47511df2a18f'
  ReservedCode1: 'b735a5e4-5e6e-4f89-b344-743da2706636'
  ReservedCode2: 'b735a5e4-5e6e-4f89-b344-743da2706636'
---

# Icar03

iCAR 03 车机第三方应用研究与自研方案仓库

## 内容

- `analysis-report.md` — 03系列车机应用（9.9Studio）架构分析：车机适配三板斧、悬浮窗实现、歌词链路、授权机制
- `bluetooth-lyrics-plan.md` — 自研方案：手机端取词 + BLE 蓝牙推送 + 车机渲染，车机零流量
- `app/` — **IcarLyrics v1.0** 车机端（自研，100% 原创代码，零第三方依赖）

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

### 手机端（下一步 M3）

手机 App 读本机播放状态（NotificationListener / MediaSessionManager）→ lrclib/网易云/QQ 取词 → 作为 GATT Client 连接车机推送。协议已就绪，等手机端实现。

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
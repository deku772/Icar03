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

# Icar03 · IcarLyrics

iCAR 03 车机第三方应用研究与自研方案仓库。

**IcarLyrics**：手机取词 → BLE 推送 → 车机悬浮窗渲染多行滚动歌词。车机全程零流量、不需要热点。

## 架构（v2.x 角色对调）

```
手机播放音乐（任意 App，含经车机蓝牙 A2DP 播放）
  └─ 手机端 App：MediaSession 监控 → 三源降级取词（lrclib/网易云/QQ）
       └─ BLE GATT Server（Peripheral）：Notify 推送歌词分片/进度
            └─ 车机 App：GATT Client（Central）按服务 UUID 扫描发现手机
                 └─ 悬浮窗 WebView 多行滚动渲染 + 本地蓝牙时间轴对齐
```

### 为什么角色对调（v2.0 核心变更）

v1.x 手机作 GATT Client 主动连车机，被手机厂商车联组件（欢太配件/数字钥匙/Carlink/官方车 App 等）
扫描到车机广播后逐个探查、强断 ACL，造成 3.5s 一次的循环断连。该行为是厂商系统行为，
OPPO/小米/华为各有实现，App 层无法根治。

v2.0 对调后：**车机主动连手机，手机只做被动服务端**。厂商组件探查的是"车机广播"，
手机不再发起对车机的 GATT 连接，问题从根上消失（实测各厂商车联组件全部启用时连接稳定）。

附带收益：
- MTU 由车机发起（247），绕开手机栈把 requestMtu(512) 发成 517 被老高通栈拒绝的问题；
- 手机端不再需要扫描/定位权限，不再持续扫描，更省电；
- 手机外设地址随机化（RPA）导致 MAC 不可靠 → 车机改按 **IcarLyrics 服务 UUID 扫描发现**，
  断线自动重新扫描重连（3→5→8→12→20s 退避）。

### 歌词不慢的关键（v2.1）

音频经 A2DP 推到车机播出，车机听到的声音比手机 MediaSession 进度晚数百毫秒到数秒。
车机端 `CarMediaListener` 轮询本机蓝牙栈（`com.android.bluetooth`）的 MediaSession 进度
作为可信时间轴，覆盖 BLE 推来的进度（换歌 4s 保护窗 + 3.5s 快照过期兜底），与发声精确对齐。

### 渲染层（v2.1）

`app/src/main/assets/lyrics_overlay.html`：DOM + GPU 合成器动画（官方 03歌词 同款架构），
车机 Android 9 低端 WebView 上流畅。
- 一次性构建歌词行 DOM，切行只 toggle class；滚动 = 容器 `translate3d` + CSS transition
- 当前行居中放大高亮（FLIP 字号过渡）、上下边缘渐隐、暂停呼吸动画
- 行级同步（无逐字填充，流畅的关键取舍）；`position + 90ms` 提前量
- 顶部兼容老 WebView（不用 `inset` 等 Chrome 87+ 语法），前奏阶段首行即定位显示
- 支持翻译行对齐（±300ms 最近邻）、深/浅色主题、浏览器演示模式（直接双击打开）

## 目录

```
app/    车机端（GATT Client + 悬浮窗渲染 + 本地时间轴）
phone/  手机端（GATT Server + 播放监控 + 三源取词）
.github/workflows/release.yml   打 v* tag 自动构建双端 APK 发 GitHub Release
analysis-report.md              03系列（9.9Studio）架构分析
bluetooth-lyrics-plan.md        自研方案原始设计
```

## 协议（BLE GATT，手机→车机 Notify）

| 项 | UUID | 说明 |
|---|---|---|
| Service | `0000A100-CA21-4B58-9C2F-6B1F3C0E9A01` | IcarLyrics 主服务（车机按此扫描） |
| lyrics | `0000A101-...` | Notify 歌词分片（4字节头：frameId u16 + seq u8 + total u8，小端；乱序/重复/丢片容错） |
| progress | `0000A102-...` | Notify 进度 JSON `{"type":"progress","positionMs":..,"playing":..}`（可合并，仅最新生效） |
| cmd | `0000A103-...` | Notify 控制 JSON `{"type":"cmd","action":"clear/setTheme/ping"}` |

MTU：车机连接后 `requestMtu(247)`；歌词分片 payload = MTU-3-4。

## 部署

### 车机端（ADB，一次性授权）

```bash
adb install out/IcarLyrics-Car-*.apk
adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow   # 悬浮窗
adb shell pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION  # BLE 扫描
adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener  # 本地时间轴
adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver
```

日常启停：`am broadcast -a com.icarme.lyrics.START/STOP`；主界面可扫描选择手机（可选，
不选则自动连接扫到的第一台）。`SET_PHONE --es mac <mac>` 仍可用于诊断。

### 手机端

```bash
adb install out/IcarLyrics-Phone-*.apk
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_SCAN
adb shell cmd notification allow_listener com.icarme.lyrics.phone/com.icarme.lyrics.phone.NotificationListener
adb shell am broadcast -a com.icarme.lyrics.phone.START -n com.icarme.lyrics.phone/.AdbReceiver
```

放歌即推词。主界面显示本机蓝牙 MAC 与全链路监控（媒体→取词→推送→BLE ACK）。

### 构建

```bash
# Java 17+，Gradle 8.14+，Android SDK 36 / build-tools 35
gradle assembleDebug
```

### 发布

推送 `v*` tag 触发 GitHub Actions：自动构建双端 APK 并发布到 GitHub Release（附自动变更说明）。

```bash
git tag v2.1.0 && git push origin v2.1.0
```

## 版本史

- **v2.1** — 渲染层重写（DOM+合成器，修卡顿与不显示）；车机本地蓝牙时间轴对齐（修歌词慢几秒）；车机 UI 美化（图标/卡片/状态灯）；GitHub Actions 自动发布
- **v2.0** — BLE 角色对调：手机 GATT Server/车机 Client，UUID 扫描发现 + 自动重连，根治厂商车联组件断连循环；多行滚动歌词（壁纸模式默认）
- **v1.8.x** — 写队列串行化、MTU 自适应阶梯、分片重组容错、ADB 直控广播
- **v1.0–v1.7** — 初始链路：手机取词推送、车机悬浮窗渲染、端到端诊断

## 声明

- 分析部分仅用于个人学习研究，不包含任何第三方软件的反编译源码
- 03歌词是付费商业软件，请尊重开发者权益，勿破解、勿分发修改版
- 遵守当地法律法规与软件许可协议

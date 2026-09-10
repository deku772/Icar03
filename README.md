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

# IcarLyrics

[![Release](https://img.shields.io/badge/release-v2.2-blue)](../../releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

iCAR 03 车机多行滚动歌词：**手机取词 → BLE 推送 → 车机悬浮窗渲染**。
车机全程零流量、不需要热点；手机厂商车联组件（数字钥匙/Carlink 等）再也不会掐断连接。

```
手机播放音乐（任意 App）
  └─ 手机端 App：MediaSession 监控 → 三源降级取词（lrclib / 网易云 / QQ音乐）
       └─ BLE GATT Server（Peripheral）：Notify 推送歌词分片与进度
            └─ 车机 App：GATT Client（Central）按服务 UUID 扫描发现手机，自动连接
                 └─ 悬浮窗多行滚动歌词（当前行居中放大 · 翻译 · 渐隐）
                      └─ 车机本地蓝牙时间轴对齐 + 用户自定义偏移
```

## 特性

- **品牌无关的稳定连接**：车机主动连手机（角色对调），手机厂商车联组件探查的是
  "车机广播"，与本链路无关——OPPO/小米/华为实测通吃
- **手机外设地址随机化免疫**：按 IcarLyrics 服务 UUID 扫描识别，不存 MAC；断线自动重连
- **歌词不慢**：车机读本机蓝牙栈 MediaSession 进度（与发声对齐），
  消除 A2DP 缓冲延迟；±5s、250ms 步进的**用户偏移**随时微调并持久化
- **流畅渲染**：DOM + GPU 合成器动画（切行 toggle class、translate3d 滚动、FLIP 缩放），
  老WebView 兼容（无 Chrome 87+ 语法），Android 9 车机不掉帧
- **优雅空转**：暂停且未 seek 时不推进度包，BLE 无空转写入
- **容错分片协议**：乱序/重复/丢片容错，丢帧超时自动作废等下一帧
- 深色车机风格 UI、状态指示灯、歌词预览、深/浅色主题、翻译对齐

## 模块

| 目录 | 说明 |
|---|---|
| `app/` | 车机端：GATT Client + 悬浮窗渲染 + 本地时间轴 + 偏移调节 |
| `phone/` | 手机端：GATT Server + 播放监控 + 三源取词 + 推送 |
| `.github/workflows/release.yml` | 打 `v*` tag 自动构建双端 APK 并发布 GitHub Release |

双端均为 100% 原创代码，零第三方依赖（纯 Android SDK）。

## BLE 协议

| 项 | UUID | 说明 |
|---|---|---|
| Service | `0000A100-CA21-4B58-9C2F-6B1F3C0E9A01` | 主服务（车机按此扫描） |
| lyrics | `0000A101-…` | Notify 歌词分片（头：frameId u16 + seq u8 + total u8，小端） |
| progress | `0000A102-…` | Notify 进度 JSON（可合并，仅最新生效） |
| cmd | `0000A103-…` | Notify 控制 JSON（clear / setTheme / ping） |

MTU 247 由车机发起；歌词分片 payload = MTU−3−4。

## 部署（ADB，一次性授权）

**车机端**

```bash
adb install IcarLyrics-Car-*.apk
adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow            # 悬浮窗
adb shell pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION # BLE 扫描
adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener # 时间轴（强烈建议）
adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver
```

**手机端**

```bash
adb install IcarLyrics-Phone-*.apk
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_SCAN
adb shell cmd notification allow_listener com.icarme.lyrics.phone/com.icarme.lyrics.phone.NotificationListener
adb shell am broadcast -a com.icarme.lyrics.phone.START -n com.icarme.lyrics.phone/.AdbReceiver
```

放歌即出词。歌词快慢不合适：车机主界面「歌词偏移」行按 − / ＋ 调节（250ms 步进，±5s，自动记忆）。

## 构建与发布

```bash
# Java 17+ / Gradle 8.14+ / Android SDK 36 / build-tools 35
gradle assembleDebug          # 本地构建：app + phone 双 APK

git tag v2.2.0 && git push origin v2.2.0   # 触发 Actions 自动构建并发布 Release
```

## 许可

本项目采用 [MIT License](LICENSE) 开源。

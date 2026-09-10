# IcarLyrics

[![Release](https://img.shields.io/badge/release-v2.3-blue)](../../releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

iCAR 03 车机多行滚动歌词：**手机取词 → BLE 推送 → 车机悬浮窗渲染**。  
车机播放歌词全程零公网流量；手机厂商车联组件也不会掐断连接。

```
手机播放音乐（任意 App）
  └─ 手机端：MediaSession 监控 → 三源取词（lrclib / 网易云 / QQ音乐）
       └─ BLE GATT Server（Peripheral）Notify 推送歌词 / 进度
            └─ 车机端：GATT Client 按服务 UUID 扫描发现手机并自动连接
                 └─ 悬浮窗滚动歌词（中间三行分级放大 · 翻译 · 渐隐）
                      └─ 车机蓝牙栈时间轴对齐 + 用户偏移 / 对齐 / 颜色
```

## 特性

**连接**
- 车机主动连手机（v2.0 角色对调），不存 MAC、按服务 UUID 发现
- 断线自动重扫重连（3→5→8→12→20s 退避）
- MTU 247 协商 + 容错分片协议（乱序/重复/丢片可恢复）

**同步**
- 车机读本机蓝牙栈 MediaSession 进度，对齐实际发声
- 僵尸会话检测：position 停滞 / 与 BLE 偏差过大自动降级
- 自然切歌立即清词，取词序号防串歌；进度回跳也会触发清词
- 歌词偏移 ±15s（250ms 步进），车机悬浮与手机预览均可调

**渲染**
- 固定行高 + 中间三行分级放大（当前最大、相邻次之），滚动不抖
- 居左 / 居中 / 居右；白 / 蓝 / 绿 / 琥珀 / 粉五色
- DOM + 合成器 translate3d 滚动，Android 9 WebView 可跑

**安装与自启**
- 车机内嵌手机端 APK，本地 HTTP（`:18765`）+ 二维码分发
- 尝试读取车机 SoftAP SSID/密码，生成「连热点 + 打开下载页」合并码
- 两端开机 / 覆盖安装自启；手动停止后不再自启
- ADB 授权帮助可折叠，勾选「不再显示」后收起

## 模块

| 目录 | 说明 |
|---|---|
| `app/` | 车机端：GATT Client + 悬浮窗 + 本地时间轴 + 偏移/对齐/颜色 + 扫码装机 |
| `phone/` | 手机端：GATT Server + 播放监控 + 取词推送 + 滚动预览 |
| `.github/workflows/release.yml` | 打 `v*` tag 自动构建双端 APK 并发布 Release |

双端零第三方依赖（纯 Android SDK）。

## BLE 协议

| 项 | UUID | 说明 |
|---|---|---|
| Service | `0000A100-CA21-4B58-9C2F-6B1F3C0E9A01` | 主服务（车机按此扫描） |
| lyrics | `0000A101-…` | Notify 歌词分片（frameId u16 + seq u8 + total u8，小端） |
| progress | `0000A102-…` | Notify 进度 JSON |
| cmd | `0000A103-…` | Notify 控制 JSON（clear / setOffset / setAlign / setColor / setTheme） |

## 部署

### 车机扫码装手机端（推荐）

1. 车机主界面点「扫码安装手机端」
2. 手机连车机热点，扫码下载安装
3. 手机端授权通知使用权 + 启动推送服务
4. 车机点「扫描发现手机」→ 连接后放歌即出词

### ADB 一次性授权（车机）

```bash
adb install app-debug.apk
adb shell appops set com.icarme.lyrics SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.icarme.lyrics android.permission.ACCESS_FINE_LOCATION
adb shell cmd notification allow_listener com.icarme.lyrics/.CarMediaListener
adb shell am broadcast -a com.icarme.lyrics.START -n com.icarme.lyrics/.AdbReceiver
```

### ADB（手机）

```bash
adb install phone-debug.apk
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_CONNECT
adb shell pm grant com.icarme.lyrics.phone android.permission.BLUETOOTH_SCAN
adb shell cmd notification allow_listener com.icarme.lyrics.phone/com.icarme.lyrics.phone.NotificationListener
adb shell am broadcast -a com.icarme.lyrics.phone.START -n com.icarme.lyrics.phone/.AdbReceiver
```

无线 ADB 示例：

```bash
adb connect <车机IP>:5555
adb connect <手机IP>:<port>
```

## 构建

```bash
# Java 17+ / Gradle 8.14+ / Android SDK 36 / build-tools 35
gradle assembleDebug
# 车机包会自动内嵌 phone-debug.apk（assets/icarlyrics-phone.apk）

git tag v2.3.0 && git push origin v2.3.0   # Actions 发布 Release
```

## 许可

[MIT License](LICENSE)

---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '2c17596c-7566-4156-bee1-cbecedaf82a4'
  PropagateID: '2c17596c-7566-4156-bee1-cbecedaf82a4'
  ReservedCode1: 'df832a47-b046-4cdc-9b56-df6343920bdd'
  ReservedCode2: 'df832a47-b046-4cdc-9b56-df6343920bdd'
---

# 蓝牙推送歌词方案（车机零流量）

## 目标

复用"手机端识别歌曲 → 云端取词"的链路，但歌词不通过车机网络获取，而是**手机经蓝牙推给车机渲染**。车机全程不联网、不开热点、不动用车机流量。

## 核心思路对比

### 现状（03歌词方案）

```
手机蓝牙放歌 → 车机蓝牙栈暴露 MediaSession（歌名/歌手/进度）
车机 App 读 MediaSession → 车机联网请求 lrclib/网易云/QQ → 车机渲染
（车机必须联网）
```

### 本方案

```
手机蓝牙放歌 → 手机 App 读本机 MediaSession/通知（NotificationListener）
手机联网请求歌词（lrclib/网易云/QQ）→ 蓝牙通道推送 → 车机 App 渲染
（只有手机联网，车机零流量）
```

两端的"识别"来源不同：他在车机上读蓝牙 MediaSession，我们在手机上读本机播放状态，数据本质一样。

## 蓝牙传输通道选型

| 通道 | 优劣 | 结论 |
|---|---|---|
| **BLE GATT 自定义服务** | 每包最大约 512B，MTU 协商后约 244B 有效载荷；一首歌词几 KB～几十 KB，分包传输完全够 | 推荐：真正零流量、实现干净 |
| 经典蓝牙 RFCOMM（SPP） | 带宽高但车机端无 API 暴露给第三方，Android 的蓝牙 SPP 接收端只能被动等连接，车机系统不支持 | 排除 |
| 蓝牙 OPP/文件推送 | 面向文件传输，无法实时同步播放进度 | 不适合实时 |
| 蓝牙 Audio Gateway 扩展 | 协议层无法承载自定义数据 | 排除 |

**结论：BLE GATT 自定义服务**。手机做 GATT Client，车机 App 做 GATT Server（Android 9 支持，无需任何特殊权限，只需 BLUETOOTH 权限）。

## 协议设计（草案）

### GATT 服务

```
Service UUID: 待定（自定义 128-bit）
├─ Characteristic: lyrics_push       （Write，手机→车机，歌词文件推送，分片）
├─ Characteristic: progress_sync     （Notify，车机→手机 or 手机→车机，进度对齐）
└─ Characteristic: command           （Write + Notify，控制指令）
```

### 消息格式（JSON over 分片）

```json
// 歌词推送完成一帧
{"type":"lyrics","track":"歌名","artist":"歌手","album":"专辑","durationMs":213000,
 "lrc":"[00:12.00]...", "tlyric":"[00:12.00]...", "source":"lrclib"}

// 播放进度（手机→车机，约1秒一次，或车机按需请求）
{"type":"progress","positionMs":45300,"playing":true}

// 控制
{"type":"cmd","action":"clear"}    // 清除当前歌词
{"type":"cmd","action":"ping"}
```

### 分片协议

- 每片前加 4 字节头：`[序号 uint16][总片数 uint16]`（或 `frameId` + `index`）
- 接收端拼满校验 JSON 后渲染
- 一首歌词约 5~50KB，BLE 实际吞吐 1~2KB/s，全量推送 3~30 秒内完成
- 播放中途换歌：新帧直接作废旧分片（frameId 递增）

### 进度同步（关键难点）

歌词渲染需要"当前播放到哪"。两种方案：

1. **手机单推（推荐起步）**：手机每 500ms~1s 推一次 positionMs，车机平滑插值显示
   - 优点：实现简单；缺点：BLE 忙，耗电略增
2. **起点 + 车机自走**：换歌时手机推 `positionMs` 起点，车机用本地时钟自行推进，仅在暂停/seek 时手机补发校正
   - 优点：信道占用极小；推荐稳定后切换

进度数据来源（手机端）：`MediaSessionManager` 读本机音乐 App 的 PlaybackState，或 `NotificationListenerService` 读媒体通知（与车机端读蓝牙会话同源）。

## 车机端 App 设计

- 悬浮窗 + WebView 渲染：**渲染层可以完全自研**（参考分析报告第 4 节的实现思路）
- 监听厂商 Settings 键 + 无障碍避让 Dock：方法论已在 analysis-report.md 第 3 节整理，可直接套用
- BLE GATT Server：`BluetoothGattServer` 挂一个服务，等手机连接写入
- 唯一前置：ADB 授权（悬浮窗 + 无障碍 + 通知使用权），与 03 系列相同的安装流程

## 手机端 App 设计

- `NotificationListenerService` 或 `MediaSessionManager` 读当前播放
- 联网取词：lrclib（优先，无鉴权）→ 网易云 → QQ音乐（降级链）
- BLE GATT Client 扫描/连接车机（可按设备名前缀过滤），推送歌词与进度
- 无 UI 常驻前台服务即可

## 关于"复用他那一套前端"

需要明确：**他 App 里的 lyrics_overlay.html 和 JS 桥属于他的代码资产，直接拿来用不合适**（也过不了他后续的签名校验与授权）。
但**渲染思路不受版权限制**：WebView + Canvas 逐字高亮 + 深浅主题跟随，是通用技术方案。
建议自研前端页面，动画效果可以做到同等甚至更好（参考 CSS 变量做主题、requestAnimationFrame 做逐字进度）。

## 里程碑

1. M1：车机端最小验证——悬浮窗显示一行静态文字（验证授权链路 + 悬浮窗）
2. M2：手机→车机 BLE 打通——推一个字符串显示到悬浮窗
3. M3：手机端取词链路——NotificationListener 读歌名 → lrclib 取词
4. M4：歌词渲染 + 进度同步——逐字高亮 + 手机进度推送
5. M5：完整体验——避让 Dock/空调页、主题跟随、开机自启
6. M6（可选）：接入 `com.tcrrry.icar.surface.*` 租约协议，与 03桌面共存

## 风险清单

- BLE 在部分车机上的蓝牙芯片兼容性需实测（Android 9 GATT Server 一般没问题）
- 无障碍 view ID `adas_handler_view` 依赖梦博桌面版本，OTA 后可能变化
- Settings 键同样依赖系统版本，需做好缺省降级
- 手机厂商后台杀进程：手机 App 需前台服务 + 电池优化白名单引导
- 歌词接口属于第三方公开接口，注意频控，建议本地缓存已取歌词
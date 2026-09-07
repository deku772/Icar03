---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: '838c1696-9c0e-4515-aaa0-ae49e6915e92'
  PropagateID: '838c1696-9c0e-4515-aaa0-ae49e6915e92'
  ReservedCode1: '9a1fd188-ae9c-4833-806d-19c4ba22db89'
  ReservedCode2: '9a1fd188-ae9c-4833-806d-19c4ba22db89'
---

# Icar03

iCAR 03 车机第三方应用研究与自研方案仓库

## 内容

- `analysis-report.md` — 03系列车机应用（9.9Studio）架构分析：车机适配三板斧、悬浮窗实现、歌词链路、授权机制
- `bluetooth-lyrics-plan.md` — 自研方案：手机端取词 + BLE 蓝牙推送 + 车机渲染，车机零流量

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
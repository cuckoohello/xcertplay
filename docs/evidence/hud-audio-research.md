# evidence · 高德 HUD 与导航声道预研

未启动阶段的静态分析记录。**本项目当前不承诺任何 HUD / 导航声道适配**，此文档只在未来启动相关工作时被引用为背景。

本文档把 `Auto_9.1.0.600087_release_signed.apk` 的静态分析线索转化为实车测试点。当前阶段只收集证据，不修改 xcertplay，不发送伪造的 HUD 数据，也不更改车机音频策略。

适配范围锁定为吉利几何 C 当前这套 GKUI/E01 车机，不要求抽象成通用车型方案。

## 1. APK 样本基线

| 项目 | 值 |
| --- | --- |
| 文件 | `Auto_9.1.0.600087_release_signed.apk` |
| 包名 | `com.autonavi.amapauto` |
| 版本名 | `9.1.0.600087` |
| 版本码 | `25000001` |
| minSdk | 15 |
| targetSdk | 23 |
| ABI | `armeabi-v7a` |
| SHA-256 | `2418635818cd3e3294cfaa66a1fcb2d94d3a903dee81956e399a960e67f07d9f` |

现场先确认已安装 APK 与该样本一致：

```sh
adb shell pm path com.autonavi.amapauto
adb shell dumpsys package com.autonavi.amapauto
adb pull <上一步输出的APK路径> amap-installed.apk
shasum -a 256 amap-installed.apk
```

- [ ] 包名一致；
- [ ] 版本一致；
- [ ] SHA-256 一致；
- [ ] 如果不一致，先提取实车版本 APK 再重新静态分析。

## 2. 当前静态分析结论

### 2.1 高德通用导航数据出口

高德收到导航引擎的 `GuideInfoProtocolData` 后，会同时分发给内部模块、AIDL 客户端和广播客户端。

已经确认的广播为：

```text
Action:   AUTONAVI_STANDARD_BROADCAST_SEND
KEY_TYPE: 10001
```

主要 extras 包括：

```text
TYPE
CUR_ROAD_NAME
NEXT_ROAD_NAME
ICON
NEW_ICON
ROUTE_REMAIN_DIS
ROUTE_REMAIN_TIME
SEG_REMAIN_DIS
SEG_REMAIN_TIME
CAR_DIRECTION
LIMITED_SPEED
CUR_SPEED
ROUNG_ABOUT_NUM
ROUND_ALL_NUM
NEXT_NEXT_ROAD_NAME
NEXT_NEXT_TURN_ICON
NEXT_SEG_REMAIN_DIS
NEXT_SEG_REMAIN_TIME
SEG_ASSISTANT_ACTION
EXIT_NAME_INFO
EXIT_DIRECTION_INFO
ETA_TEXT
NEXT_ROAD_PROGRESS_PERCENT
```

这条广播是目前最有希望复用的几何 C HUD 数据入口。它包含普通 HUD 所需的转向图标、下一道路和剩余距离等信息。

### 2.2 高德导出的 Binder/AIDL 服务

APK Manifest 中存在三个未声明权限保护的 exported service：

```text
Action: com.autonavi.amapauto.aidl
Class:  com.autonavi.amapauto.adapter.internal.AmapAutoService

Action: com.autonavi.amapauto.protocolService
Class:  com.autonavi.amapauto.protocol.service.ProtocolService

Action: com.autonavi.amapauto.aidl.json_protocol_service
Class:  com.autonavi.amapauto.protocol.service.JsonProtocolService
```

通用 GuideInfo 的 AIDL model protocol ID 为：

```text
30407
```

高德还包含 AR HUD 数据模型：

| ID | 模型 | 内容 |
| --- | --- | --- |
| `80159` | `RspArHudRouteInfoOutputModel` | 路线点 |
| `80176` | `RspArHudTBTInfoOutputModel` | TBT 点、方向 |
| `80177` | `RspArHudGuideLineOutputModel` | 引导线点 |
| `80178` | `RspArHudArrivePointOutputModel` | 途经点、终点坐标 |
| `80180` | `RspArHudChangeLineOutputModel` | 变道线点 |

但这些 AR HUD 模型在该 APK 的部分转换方法中表现为桩代码，不能仅凭类名断定几何 C 正在使用。实车测试优先观察通用 `10001/30407` GuideInfo。

### 2.3 广播接收入口

高德还导出了接收广播：

```text
AUTONAVI_STANDARD_BROADCAST_RECV
```

这用于外部系统向高德发送协议请求，不是高德向 HUD 输出的主要方向。

### 2.4 仪表图标 Provider 线索

代码中存在 `ClusterContentProvider`，可按 URI 请求尺寸生成导航图标文件；但在当前 APK Manifest 中未找到该 Provider 的注册项。因此暂不把它认定为几何 C 的有效外部接口，需要实车 `dumpsys package` 再确认。

## 3. HUD 测试目标

要回答以下问题：

1. 几何 C 的 HUD/仪表进程是否接收 `AUTONAVI_STANDARD_BROADCAST_SEND`；
2. 是否绑定 `ProtocolService` 或 `JsonProtocolService`；
3. 实际使用的是通用 GuideInfo，还是 AR HUD 专用模型；
4. HUD 数据是否再由车厂进程通过 D-Bus、Binder、Socket 或 CAN 下发；
5. 普通第三方 APK 是否可以发送同样的数据；
6. 是否要求系统 UID、平台签名、SELinux domain 或来源包名白名单。

## 4. HUD 第一阶段：只读盘点

### 4.1 记录相关包和进程

```sh
adb shell pm list packages -f | grep -i -E 'autonavi|amap|hud|cluster|navi|mcu|vehicle|ecarx|gkui'
adb shell ps | grep -i -E 'autonavi|amap|hud|cluster|navi|mcu|vehicle|dbus'
adb shell service list | grep -i -E 'hud|cluster|navi|mcu|vehicle|car'
adb shell cat /proc/net/unix | grep -i -E 'dbus|hud|cluster|navi|mcu|vehicle'
```

- [ ] 记录 HUD/仪表进程包名和 UID；
- [ ] 记录高德进程 UID；
- [ ] 查找 D-Bus socket；
- [ ] 查找车辆 Binder 服务；
- [ ] 查找导航中间件进程。

### 4.2 检查高德导出组件

```sh
adb shell dumpsys package com.autonavi.amapauto
adb shell dumpsys activity services com.autonavi.amapauto
adb shell dumpsys activity broadcasts
```

- [ ] `AmapAutoService` 存在且可绑定；
- [ ] `ProtocolService` 存在且可绑定；
- [ ] `JsonProtocolService` 存在且可绑定；
- [ ] 确认服务是否被固件追加权限；
- [ ] 确认是否存在 `ClusterContentProvider`；
- [ ] 查找哪些进程注册了 `AUTONAVI_STANDARD_BROADCAST_SEND`；
- [ ] 查找哪些进程绑定了高德协议服务。

## 5. HUD 第二阶段：监听真实高德导航

先清空日志：

```sh
adb logcat -c
```

开启日志采集：

```sh
adb logcat -v threadtime | grep -i -E \
  'AndroidProtocolExe|GuideInfo|BroadcastConnection|AidlModelConnection|ProtocolDistributeManager|HUD|Cluster|TBT|Navi|MCU|DBus'
```

按顺序进行：

1. 打开高德但不开始导航，记录 30 秒；
2. 开始一条短路线；
3. 等待 HUD 出现导航；
4. 接近一次普通左转或右转；
5. 观察距离从数百米递减；
6. 如果方便，经过环岛或车道提示；
7. 主动偏航触发路线重算；
8. 结束导航；
9. 保存完整日志与准确时间点。

每个时间点记录：

```text
车机时间
高德主屏显示内容
HUD显示内容
当前道路
下一道路
转向类型
转向距离
剩余路线距离/时间
```

通过证据：日志中的 GuideInfo 更新与 HUD 显示在时间和值上对应。

## 6. HUD 第三阶段：独立广播监听器

后续准备一个独立的“几何 C 能力探针 APK”，动态注册：

```text
AUTONAVI_STANDARD_BROADCAST_SEND
```

探针只记录收到的 extras，不向车机发送任何消息。

检查：

- [ ] 导航开始后能收到 `KEY_TYPE=10001`；
- [ ] `CUR_ROAD_NAME` 与高德当前道路一致；
- [ ] `NEXT_ROAD_NAME` 与下一道路一致；
- [ ] `ICON`/`NEW_ICON` 随转向变化；
- [ ] `SEG_REMAIN_DIS` 随行驶递减；
- [ ] `ROUTE_REMAIN_DIS` 和 `ROUTE_REMAIN_TIME` 合理；
- [ ] 结束导航后收到停止/无路线状态；
- [ ] 更新频率不会造成系统明显负载。

若上述全部成立，说明 xcertplay 后续可直接把 CarPlay 导航模型转换为这套广播，不必先逆向 CAN。

## 7. HUD 第四阶段：确认真正消费者

通过 root 检查候选 HUD 进程：

```sh
adb shell su -c 'cat /data/system/packages.xml'
adb shell su -c 'ls -l /proc/<HUD进程PID>/fd'
adb shell su -c 'cat /proc/<HUD进程PID>/maps'
```

必要时只提取候选系统 APK/SO，分析其是否包含：

```text
AUTONAVI_STANDARD_BROADCAST_SEND
com.autonavi.amapauto.protocolService
com.autonavi.amapauto.aidl.json_protocol_service
KEY_TYPE
CUR_ROAD_NAME
NEXT_ROAD_NAME
SEG_REMAIN_DIS
ICON
```

判定：

- 若 HUD 进程监听 `AUTONAVI_STANDARD_BROADCAST_SEND`：优先复用广播；
- 若 HUD 进程绑定 ProtocolService：复用 AIDL model；
- 若 HUD 进程收到数据后再调用 D-Bus：xcertplay仍优先复用高德上层接口；
- 只有上层接口有包名或签名限制时，才继续研究下游 D-Bus；
- 不直接发送原始 CAN 帧。

## 8. HUD 第五阶段：最小无害回放

完成只读验证后，再决定是否允许探针发送一条最小测试消息。必须满足：

- 车辆保持 P 挡；
- 只使用导航显示字段；
- 不接触任何车辆控制接口；
- 使用明显的测试道路名和短距离；
- 发送后立即发送导航结束状态；
- 记录 HUD 是否出现及多久消失。

通过标准：

```text
独立探针发送高德同格式数据
→ 原车 HUD 出现一致的箭头/道路/距离
→ 停止消息后 HUD 正常清除
```

只有达到该标准，才开始设计 xcertplay 的几何 C HUD 输出代码。

## 9. 导航声道静态分析结论

已知实车行为：高德地图车机版提供“声道选择”，在几何 C 上选择正确的声道后，导航播报可以命中原车导航声道。因此后续目标不是猜测一套通用 Android 音频参数，而是查明“当前有效选项”最终映射出的数值和伴随通知，并在独立探针中一对一复现。

高德 9.1.0 的语音链路不是简单写死到一个声道，而是读取设备适配配置 `AudioConfigData`，其中包括：

```text
audioChannel
audioMode
audioAttrUsage
audioContentType
isNeedAudioRequestFocus
isPlayWarningSoundNeedRequestFocus
isUserHighVersionAudioApi
isUseAudioTrack
audioTrackBuffersize
```

API 21 以上可以使用 `AudioAttributes`；旧系统使用 legacy stream type 和 `requestAudioFocus(listener, streamType, durationHint)`。

APK 内还有一条设备适配路径读取：

```text
ro.autonavi.streamtype
```

该适配器默认值为 `4`。在标准 Android 中 `4` 对应 `STREAM_ALARM`，但几何 C 的音频策略可能重新路由或别名化，必须以实车结果为准。不能仅根据常量名称判定它就是报警音通道。

代码还提供了运行时入口：

```text
AndroidAudioControl.setStreamType(int)
→ AudioConfigData.setStreamType(int)
→ AudioTrack / MediaPlayer 使用该 stream type
→ AudioManager.requestAudioFocus 使用同一 stream type
```

因此，高德设置页中的声道选项很可能通过 native 层调用 `setStreamType(int)`，或者写入车型适配配置后得到相同结果。

高德还可能通过动态加载的车型适配 DEX 覆盖：

```text
getAudioStreamType()
requestFocus(durationHint, audioType)
abandonFocus()
getSystemVolume()
setSystemVolume()
```

因此实车中的动态适配文件优先级高于 APK 内默认值。

## 10. 导航声道第一阶段：配置与动态模块

### 10.1 记录高德当前有效选项

现场首先不要改变当前已经匹配成功的声道。记录：

- [ ] 高德设置页中声道选项的准确名称；
- [ ] 当前选中的序号或名称；
- [ ] 可供选择的全部声道；
- [ ] 当前选项下，导航音从哪些扬声器发出；
- [ ] 播报时音乐/收音机是压低、暂停还是混音；
- [ ] 方向盘音量键控制哪个音量条；
- [ ] 熄火重启后该选项是否保留。

拍摄设置页照片，并记录车机时间，以便和日志对齐。

### 10.2 读取落地配置

```sh
adb shell getprop ro.autonavi.streamtype
adb shell getprop | grep -i -E 'autonavi|audio|navi'
adb shell dumpsys package com.autonavi.amapauto
adb shell su -c "find /data/data/com.autonavi.amapauto -type f | grep -E '\\.(dex|jar|apk)$'"
```

- [ ] 记录 `ro.autonavi.streamtype`；
- [ ] 记录高德安装路径与数据目录；
- [ ] 查找动态加载的车型适配 DEX/JAR/APK；
- [ ] 如存在，提取后检查 `DynamicFuncImpl`；
- [ ] 确认实际 `getAudioStreamType()` 返回值；
- [ ] 确认是否有厂商专用 `requestFocus()`。

在不改变声道选项时，先备份高德配置目录的文件名、大小和时间：

```sh
adb shell su -c 'find /data/data/com.autonavi.amapauto -type f -maxdepth 5 -exec ls -l {} \;'
```

如需比较选项前后差异，只提取配置文件、XML、数据库 schema 和动态适配 DEX；不要提取账号、搜索记录、收藏、位置历史或其他个人数据。

## 11. 导航声道第二阶段：高德播报现场采样

导航开始前保存：

```sh
adb shell dumpsys audio > audio-before.txt
adb shell dumpsys media.audio_flinger > audio-flinger-before.txt
adb shell dumpsys media.audio_policy > audio-policy-before.txt
```

开始实际导航并等待语音播报，同时采集：

```sh
adb logcat -v threadtime | grep -i -E \
  'AndroidAudioControl|AutoAudioManager|AudioTrackFactory|AudioConfigData|AudioFocus|TTS|VOICE_PLAY'
```

在语音正在播放时保存：

```sh
adb shell dumpsys audio > audio-during-navigation.txt
adb shell dumpsys media.audio_flinger > audio-flinger-during-navigation.txt
adb shell dumpsys media.audio_policy > audio-policy-during-navigation.txt
```

播报结束后再保存一次。

期望从日志中取得：

```text
streamType
audioMode / focus gain
audioType
audioAttrUsage
audioContentType
AudioTrack session ID
输出设备
播报期间音乐是 duck、pause 还是 mix
```

在日志中重点寻找高德自身已经打印的这些行：

```text
AudioConfigData ... audioChannel=... audioMode=...
getStreamType ... useStreamType=...
AudioTrackFactory ... audioType=... defaultStreamType=...
requestAudioFocus ... streamType=... durationHint=... audioType=...
```

它们比根据设置页名称猜测声道更可靠。

同时观察以下广播是否出现：

```text
com.autonavi.xm.action.VOICE_PLAY_STARTED
com.autonavi.xm.action.VOICE_PLAY_STOPPED
com.mxnavi.broadcast.startplaysound
com.mxnavi.broadcast.stopplaysound
android.NaviOne.voiceprotocol
```

这些广播可能只是通知厂商音频管理器“导航音开始/结束”，也可能决定功放通道或媒体压低；需要通过时间相关性验证。

## 12. 导航声道第三阶段：音频策略文件

只读提取：

```sh
adb shell su -c 'cat /system/etc/audio_policy.conf'
adb shell su -c 'cat /vendor/etc/audio_policy.conf'
adb shell su -c 'find /system /vendor -maxdepth 4 -type f | grep -Ei "audio.*(conf|xml)|mixer_paths"'
```

检查：

- [ ] stream type 是否被映射到独立导航输出；
- [ ] 是否存在 `navigation`、`navi`、`tts`、`guidance` 等设备或策略；
- [ ] 是否存在厂商自定义 stream type；
- [ ] 是否依赖特定 Audio HAL 参数；
- [ ] 是否通过功放/MCU 在播报开始时切换通道；
- [ ] 是否存在只有平台签名应用才能调用的音频服务。

## 13. 导航声道第四阶段：独立音频探针

只在前述观察完成后构建独立探针 APK，不修改 xcertplay。探针使用固定、低音量、短提示音依次验证候选配置：

1. 首先仅测试高德当前有效选项对应的 stream type；
2. 复现高德实际使用的 audio focus gain；
3. 复现高德播报开始/结束时发出的厂商广播（仅在证实必要后）；
4. 如果仍不能命中导航声道，再测试动态适配模块返回的 stream type；
5. 只有上述证据仍不足时，才有限枚举 `STREAM_MUSIC`、`STREAM_ALARM`、`STREAM_NOTIFICATION` 和厂商自定义值；
6. API 21 以上再测试高德实测 AudioAttributes；Android 4.4 主要看 legacy stream type。

每项记录：

- [ ] 从哪个扬声器或功放通道出声；
- [ ] 方向盘音量键控制的是哪个音量组；
- [ ] 原车音乐是否压低；
- [ ] 收音机是否压低或暂停；
- [ ] 提示音结束后原音源是否恢复；
- [ ] 倒车、电话和系统告警是否仍有正确优先级；
- [ ] 连续快速提示是否丢音或卡住焦点。

通过标准：

```text
独立探针使用高德同样的 stream/focus 参数
→ 从预期声道播放
→ 原车媒体正确 duck/pause
→ 播放结束后媒体恢复
→ 不影响电话、倒车和安全提示
```

## 14. 决策门槛

### 14.1 HUD

满足以下任一条件后才开发：

- [ ] 探针能监听高德 `KEY_TYPE=10001`，且字段与 HUD 同步；
- [ ] 已确认原车 HUD 进程绑定高德 AIDL 并得到 GuideInfo；
- [ ] 已找到明确的下游 D-Bus/Binder 接口和权限要求。

优先级：

```text
高德标准广播 > 高德 AIDL > 车厂 D-Bus/Binder > 原始 CAN
```

### 14.2 导航声道

满足以下条件后才修改 xcertplay：

- [ ] 已取得高德实际 stream type；
- [ ] 已取得实际 audio focus 参数；
- [ ] 已确认是否需要播放开始/停止广播；
- [ ] 独立探针能复现正确的导航声道路由；
- [ ] 音乐压低与恢复行为正确；
- [ ] 不影响电话、倒车和安全音。

不要通过修改 `/system/etc/audio_policy.conf` 来验证。先证明普通 App 使用同一 stream/focus/广播序列即可复现；只有普通 App 无法命中导航声道时，才分析平台签名或 Audio HAL 权限。

### 14.3 实现范围

确认接口后只实现几何 C 专用适配：

```text
CarPlay RouteGuidance
→ 几何 C 高德兼容 GuideInfo
→ 原车 HUD

CarPlay navigation audio
→ 几何 C 实测 stream/focus/通知序列
→ 原车导航声道
```

不在验证前设计通用车型抽象，不伪造未经实车确认的参数，不直接写 CAN。

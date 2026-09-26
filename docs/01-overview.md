# 01 · xcertplay 项目总览

面向新加入的开发者的第一份文档。回答三个问题：**做什么、代码怎么组织、下一步做什么**。

- 车机连接与共同基线 → [02-vehicle-setup.md](02-vehicle-setup.md)
- 有线 / 无线执行手册 → [03-wired-carplay.md](03-wired-carplay.md)、[04-wireless-carplay.md](04-wireless-carplay.md)
- 实车事实 / 固件底盘 / HUD 预研 → [evidence/](evidence/)

## 1. 项目边界

| 项目 | 值 |
|---|---|
| 定位 | Android 车机端 CarPlay 接收器（有线 + 无线） |
| MFi 认证 | 支持 CH341/I2C 本地 MFi，或 Remote MFi/BAA HTTP 服务 |
| 目标车机 | 几何 C E01（Android 5.1 / API 22），并保留 API 28+ 通用车机 |
| 分支 | 主线 `master`（API 28+），E01 分支 `e01-wired-carplay`（API 22） |
| 构建入口 | Gradle 8+，Android Gradle Plugin 9.3.0，Kotlin 2.2.10 |
| 打包产物 | `mobile-debug.apk`、`automotive-debug.apk`、`e01-debug.apk`、`api17probe` |

## 2. Gradle 模块设计（现状）

`settings.gradle.kts` 声明的模块：`:common`、`:mobile`、`:automotive`、`:shared`、`:e01shared`、`:e01`；仓库中还有独立未跟踪的 `api17probe/`。依赖关系：

```text
:mobile        ──> :common ──> :shared
:automotive    ──> :common ──> :shared
:e01           ──> :e01shared (源码集复用 :shared 的 java/kotlin，通过 build.gradle exclude 裁剪无线/CH341/NDK)
api17probe/    ──> 独立工程（Java，minSdk=17/22 探针 APK）
```

各模块职责：

| 模块 | 类型 | minSdk / 说明 |
|---|---|---|
| `:shared` | Android library | 28+，包含 NDK（可 `-Pxcertplay.skipNative=true` 跳过），承载全部协议、传输、AirPlay、MFi、无线编排 |
| `:common` | Android library | 28+，承载 UI Activity（`CarPlayHostActivity`、设置、剪切、主题）与持久化 |
| `:mobile` | Android application | 28+，Play/侧载移动应用 |
| `:automotive` | Android application | 28+，Android Automotive 应用 |
| `:e01shared` | Android library | 22，从 `:shared/src/main/java` 复用源码，`java.srcDirs` 指向同一目录，通过 `exclude` 排除无线控制器、CH341、NDK、Bonjour、Hotspot 等在 Android 5.1 不可用的模块 |
| `:e01` | Android application | 22，几何 C E01 专用 side-load APK，`applicationId=com.shilapi.xcertplay.e01`，无 native 库、无蓝牙/录音/定位权限 |
| `api17probe/` | 独立 Android application | 22，只读诊断探针 APK，`applicationId=com.shilapi.xcertplay.api17probe` |

## 3. 目录设计（现状）

### 3.1 顶层

```text
xcertplay/
├── shared/                     iAP2/AirPlay/USB/Wi-Fi/MFi 协议实现
├── common/                     UI 与持久化，供 mobile/automotive 共享
├── mobile/                     手机侧 CarPlay Host App
├── automotive/                 Android Automotive App
├── e01shared/                  E01 (API 22) 精简源集，复用 shared 源码
├── e01/                        E01 独立 APK 入口
├── api17probe/                 只读诊断探针（独立工程）
├── docs/                       中文运行/评估文档
├── upgrade_unpack/             GE13 固件解压产物与逆向工件（本地证据，不入库）
├── build.gradle.kts / settings.gradle.kts / gradle/*
└── README.md / README.zh-CN.md
```

### 3.2 `:shared` 包

```text
shared/src/main/java/com/shilapi/xcertplay/
├── airplay/     AirPlay Session、Pair-Setup/Verify、SRP6a、Info.plist、屏幕/音频/触控 Codec、Tlv8/Bplist
├── iap2/
│   ├── body/    body 编码解码
│   ├── catalog/ 消息目录
│   ├── message/ 高层消息构造/解析
│   ├── session/ 会话状态机
│   ├── trace/   frame 序列化格式化
│   └── wire/    Frame/CSM/字节层
├── location/    CarPlay 位置提供器
├── media/       AudioChannelMapping、MediaCodec、MicrophoneUplink、OpusEncoder、触控映射
├── mfi/         MfiAuthenticationClient、RemoteMfiAuthenticationClient、Iap2MfiAuthenticationClient、Self-check、Scanner
├── network/     WirelessHotspotManager、WifiP2p、LocalOnlyHotspot、Manual、Bonjour、CarPlayVpnService、Ipv6NcmBridge
├── orchestration/ CarPlayController、CarPlayRuntimeConfig、MfiRuntime
├── shared/      MyCarAppService/Screen/Session（Android Auto Car App API）
├── transport/   USB/USBMUX/Lockdown/NCM/CH341/I2C/BluetoothRfcomm/GocSpp/Iap2*Client/TlsDuplexChannel
└── util/        Base64Compat 等通用工具
```

### 3.3 `:common` 包

```text
common/src/main/java/com/shilapi/xcertplay/
├── CarPlayHostActivity.kt      主 Activity：有线/无线开关、Remote MFi 配置、状态显示、剪切图标
├── MainActivity.kt             首页
├── AirPlayPersistence.kt       身份、配对、Remote MFi、无线选项持久化
├── SafeAreaEditorView.kt       安全区可视化
├── ImageCropActivity.kt        自定义应用图标裁剪
├── SessionLogFile.kt           会话日志
├── BootReceiver.kt             系统启动接收
├── DarkMode.kt
└── ui/theme/{Color,Theme,Type}.kt   Compose 主题
```

### 3.4 `:e01` 与 `:e01shared`

```text
e01/src/main/
├── AndroidManifest.xml         仅声明 USB Host feature 与 INTERNET；无蓝牙/录音/定位
├── java/com/shilapi/xcertplay/e01/
│   ├── E01CarPlayActivity.kt   全屏 Surface + 设置齿轮 + 状态栏
│   ├── E01WiredCarPlayController.kt  组合 shared 的有线路径
│   └── E01Persistence.kt       独立 SharedPreferences，含 Remote MFi/无 MFi 诊断开关
└── res/{drawable,values,xml/usb_device_filter}

e01shared/build.gradle
  ├── java.srcDirs=['../shared/src/main/java']    复用 shared 源码
  └── java.exclude(                               API 22 不兼容或本轮不启用的模块
        'com/shilapi/xcertplay/location/**',
        'com/shilapi/xcertplay/network/CarPlayBonjour.kt',
        'com/shilapi/xcertplay/network/LocalOnlyHotspotManager.kt',
        'com/shilapi/xcertplay/network/ManualHotspotManager.kt',
        'com/shilapi/xcertplay/network/WifiP2pGroupManager.kt',
        'com/shilapi/xcertplay/network/WirelessHotspotManager.kt',
        'com/shilapi/xcertplay/orchestration/**',
        'com/shilapi/xcertplay/shared/**',
        'com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStream.kt',
        'com/shilapi/xcertplay/transport/Ch341*.kt',
        'com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt',
        'com/shilapi/xcertplay/transport/LinuxI2cTransport.kt',
      )
```

注意：`GocSppTransport` 未被 exclude，因此在 `:e01shared`（API 22）与 `:shared`（API 28+）两侧同时编译。

### 3.5 `api17probe/`

```text
api17probe/src/main/java/com/shilapi/xcertplay/api17probe/
├── MainActivity.java                  聚合入口
├── ProbeLog.java                      只读探针日志
├── CapabilityProbe.java               能力/权限盘点
├── UsbDiagnosticsProbe.java           USB Host / 供电 / 描述符
├── WiredCarPlayUsbProbe.java          USBMUX/Lockdown/NCM 通道读探
├── WiredCarPlayDualChannelProbe.java  双通道验证
├── WiredLockdownPairingProbe.java     Lockdown Pair/TLS 读探
├── WirelessCarPlayProbe.java          无线前置盘点
├── GocBluetoothBinderProbe.java       NForetek/GOC Bluetooth Binder 只读
├── GocSppBinderProbe.java             GOC SPP Binder 只读
└── ProbeVpnService.java               仅用于观察 VPN 权限流程
```

### 3.6 `docs/`

`docs/` 内所有中文文档如 §0 所列；不引入未列出的新文档，除非 §7 指定新阶段生成新记录。

## 4. 已完成能力（现状）

- 有线 CarPlay 完整链路（USBMUX / Lockdown / carkit / iAP2 / NCM / VPN / AirPlay / H.264 / 触控）
  在 `:shared` 与 `:e01shared` 均可编译，`e01-debug.apk` 已构建。
- 无 MFi 诊断模式：`iap2 rx=0xaa00` 后主动停止，实现在
  [Iap2WiredDiagnosticClient.kt](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WiredDiagnosticClient.kt)。
- MFi 三条通路并存：CH341 USB、Linux I2C、Remote MFi HTTP。
- 无线 CarPlay 控制流：
  - 蓝牙 bootstrap 通过 [BluetoothRfcommDuplexStream.kt](../shared/src/main/java/com/shilapi/xcertplay/transport/BluetoothRfcommDuplexStream.kt)（标准 Framework 路径）；
  - E01 独有 [GocSppTransport.kt](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt)（RESERVED LocalSocket，绕过空 Binder）；
  - Wi-Fi 热点 + Bonjour + AirPlay tunnel（`WifiP2p`/`LocalOnlyHotspot`/`Manual` 三选一）。
- GE13 固件静态分析已在
  [04-wireless-carplay.md](04-wireless-carplay.md) 落库。
- `api17probe` 提供只读盘点，可在 API 22 车机上先做无副作用取样。
- JVM 单测：`:shared` 102 项、`:e01shared` 77 项全部通过（含 `GocSppTransportTest` 7 项）；
  `:e01:lintDebug`、`:e01shared:lintDebug`、`:e01:assembleDebug` 均通过。

## 5. 待完成开发任务

任务按模块聚合，标注**依赖**与**验收出处**。序号仅用于引用，不代表严格执行顺序（见 §6 里的推荐顺序）。

### 5.1 无线（E01 首要缺口）

- **T-W1**：接入 `GocSppTransport` 到 E01 无线控制流
  - 目标：`E01WiredCarPlayController` 之外新增或复用无线控制器，允许 `Iap2Session.openWireless(gocSppTransport)`；
  - 依赖：无（transport 与单测已就位）；
  - 验收：`04-wireless-carplay.md §7.3`。
- **T-W2**：wireless bootstrap 编排
  - 目标：将 hotspot（`WifiP2p`/`LocalOnly`/`Manual`）与 GOC SPP transport 组合，生成 `Iap2WirelessCarPlayEndpoint`；
  - 依赖：T-W1；
  - 验收：`04-wireless-carplay.md §7.6`。
- **T-W3**：本机蓝牙 MAC 获取
  - 目标：Framework `BluetoothAdapter.getAddress()` 在 E01 返回全零；需要从 GOC/原厂配对表读取；
  - 依赖：无；
  - 验收：`04-wireless-carplay.md §7.2`。
- **T-W4**：无线 handoff
  - 目标：AirPlay type-130 tunnel iAP2 ready 后 `close()` `goc_spp`；
  - 依赖：T-W2；
  - 验收：`04-wireless-carplay.md §7.6`。
- **T-W5**：SELinux / DAC 探测与失败分流
  - 目标：`api17probe` 中扩展 `GocSppBinderProbe` 记录 `avc: denied`、`ls -lZ`；
  - 依赖：无；
  - 验收：`04-wireless-carplay.md §7.1`。

### 5.2 有线（E01 已可测试，待实车验证）

- **T-D1**：config 6 自动恢复
  - 目标：Lockdown 结束后 iPhone 自动回落 config 1 时的重回策略；
  - 依赖：实车窗口；
  - 验收：`03-wired-carplay.md §8.3`。
- **T-D2**：Remote MFi/BAA 完整鉴权
  - 目标：`RemoteMfiAuthenticationClient` 现网可用性；
  - 依赖：Remote MFi 服务；
  - 验收：`03-wired-carplay.md §7.2`。
- **T-D3**：H.264 / 音频 / 触控端到端
  - 目标：`AndroidMediaSink`、`CarPlayTouchMapper`、`AudioStream` 在 E01 上实车验证；
  - 依赖：T-D2；
  - 验收：`03-wired-carplay.md §8.2`。

### 5.3 通用 App（mobile / automotive）

- **T-A1**：无线控制流复用
  - 目标：`CarPlayController` 的无线路径在移动/汽车端与 E01 保持一致；
  - 依赖：T-W1 完成后的接口收敛；
  - 验收：`WirelessHandoffTest` 现有单测 + 后续实车。
- **T-A2**：UI 状态与 Remote MFi 配置同步
  - 目标：`CarPlayHostActivity` 与 `AirPlayPersistence` 增量兼容 GOC 无线模式（可选开关）；
  - 依赖：T-W1；
  - 验收：无线场景不影响原 API 28+ 路径。

### 5.4 工程与测试基础

- **T-E1**：`:shared:lintDebug` 权限基线
  - 现状：10 个既有 `MissingPermission` error 位于 `CarPlayController` / `LocalOnlyHotspotManager` /
    `WifiP2pGroupManager` / `MicrophoneUplink`；
  - 目标：或在这些无线/录音方法上补 `checkPermission` / `try-catch SecurityException`，或建立 `lint-baseline.xml`；
  - 依赖：无；
  - 验收：`./gradlew :shared:lintDebug` 通过或明确 baseline。
- **T-E2**：单测覆盖率
  - 目标：为无线 handoff、GOC transport 与 hotspot 组合补 JVM 用例；
  - 依赖：T-W2；
  - 验收：新增用例全通过，不掩盖已有失败。
- **T-E3**：CI（可选）
  - 目标：本地 `./gradlew` 命令固化到脚本或 CI；
  - 依赖：无。

### 5.5 文档与复盘

- **T-Doc1**：每次实车窗口后按
  [02-vehicle-setup.md §9](02-vehicle-setup.md#9-复盘规则) 更新证据与失败分流。
- **T-Doc2**：SPP/无线协议若在实车中出现与静态分析不一致的行为，回写
  [04-wireless-carplay.md](04-wireless-carplay.md)。

## 6. 推荐执行顺序

```text
Step 1  T-E1（stabilize shared lint）        ← 无外部依赖，先解决
Step 2  T-W5 + T-W3（探针 + MAC 来源）       ← 只读，不动实现
Step 3  T-W1（GocSpp 接入无线控制器）        ← 单测优先
Step 4  T-D1 / T-D2 / T-D3（实车有线闭环）   ← 优先在窗口内跑完
Step 5  T-W2 → T-W4（无线 bootstrap 与 handoff）
Step 6  T-A1 / T-A2（回灌通用 App）
Step 7  T-E2 / T-Doc*（覆盖率 + 复盘）
```

每一步在开工前必须：

1. 明确边界（改哪些文件、不改哪些）；
2. 生成变更矩阵（前/后/原因/影响），至少一行一模块；
3. 附回滚方案；
4. 定义验收 checklist（引用 §7 里的对应验收）；
5. 失败即停，等待人工判定。

## 7. 验收出处对照

| 任务 | 验收文档 / 章节 |
|---|---|
| T-D1、T-D2、T-D3 | [e01-wired-carplay-runbook §7–§8](03-wired-carplay.md#7-日志采集与逐阶段判据) |
| T-W1、T-W3、T-W5 | [ge13-goc-spp §7.1–§7.3](04-wireless-carplay.md#71-前置记录只读) |
| T-W2、T-W4 | [ge13-goc-spp §7.4–§7.7](04-wireless-carplay.md#74-iap2-marker-与-link) |
| T-A1、T-A2 | 通用 UI 手动回归 + `WirelessHandoffTest` |
| T-E1、T-E2 | `./gradlew :shared:lintDebug`、单测 XML 报告 |
| T-Doc1、T-Doc2 | [e01-vehicle-test-plan §9](02-vehicle-setup.md#9-复盘规则) |

## 8. 硬约束速查

- Android 5.1 (API 22)：`api17probe` 保持 `minSdk=22`；E01 APK 无 NDK、无蓝牙/录音/定位权限；`Base64` 使用 Bouncy Castle 兼容实现；`USB_RECIP_INTERFACE` 需硬编码 `0x01`。
- Wi-Fi ADB：端口固定 `5555`，开启需 `su -c setprop service.adb.tcp.port 5555; stop/start adbd`。
- GOC 蓝牙：App 不能通过 Android Framework RFCOMM 连接；只能走 `GocSppTransport`。
- 诊断模式：默认只读；判别性测量阶段禁止修改 `cmode` 或触发 `0x52`。
- 有线严格使用 USB config 6，`0x52` 指令用于重枚举轮询。
- GE13 系统 APK 无私钥，禁止重签；改动必须在 xcertplay 自身 APK 内闭环。

## 9. 变更矩阵模板

任何新任务动手前，先按下表列出改动，附到 PR 或工单：

| 项目 | 前 | 后 | 原因 | 影响 |
|---|---|---|---|---|
| 模块/文件 | | | | |
| 配置/字段 | | | | |
| 权限/manifest | | | | |
| 单测 | | | | |
| 文档 | | | | |
| 回滚步骤 | | | | |

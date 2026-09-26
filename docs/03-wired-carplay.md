# 03 · 有线 CarPlay 执行手册

E01 有线 CarPlay 的构建、安装、逐阶段判据与验收。执行前先完成 [02-vehicle-setup.md](02-vehicle-setup.md) §1–§3。

## 1. 范围与基线

本轮只提供几何 C E01 的独立有线 CarPlay APK，不修改系统分区、GOC 蓝牙、原车应用或无线 CarPlay。

| 项目 | 值 |
|---|---|
| 仓库 | `https://github.com/shilapi/xcertplay.git` |
| 本地分支 | `e01-wired-carplay` |
| 适配基线 | `793c21f`（含本轮 Local MFi 改动前） |
| 应用包名 | `com.shilapi.xcertplay.e01` |
| 应用版本 | `0.2.0` / versionCode `2` |
| Android | 5.1 / API 22 |
| APK | `e01/build/outputs/apk/debug/e01-debug.apk` |
| APK SHA-256（本轮 视频恢复 hook 构建） | `8501c3bcdad4a07c4aa606e7ab4abbb86d02f4a5b88503a4e09c709dd65cfd61` |

实车已证明的边界见
[`evidence/e01-runtime-2026-09-23.md`](evidence/e01-runtime-2026-09-23.md)：

- Apple `0x52` 可触发重枚举；
- config 6、USBMUX、CDC-NCM 曾在同一轮测试中工作；
- Lockdown `Pair`、`StartSession` 和 TLS 1.2 已通过；
- `com.apple.carkit.service`、iAP2 MFi、AirPlay 和媒体仍需本 APK 实车验证。

## 2. 数据流

```text
iPhone
  |
  | USB Host, vendor request 0x52, required configuration id 6
  |
  +-- USBMUX bulk endpoints
  |     -> TCP 62078
  |     -> Lockdown Pair / StartSession / TLS
  |     -> StartService(com.apple.carkit.service)
  |     -> iAP2 Identification / Remote MFi / 0x4301
  |
  +-- CDC-NCM bulk endpoints
        -> Android VpnService TUN, fe80::2/64
        -> AirPlay TCP 7000
        -> H.264 1280x720@30 + downlink audio + touch HID

Remote MFi service
  -> POST /mfi/reset
  -> GET  /mfi/certificate
  -> POST /mfi/sign

No-MFi diagnostics
  -> skip Remote MFi and VPN
  -> open config 6 / NCM / USBMUX / Lockdown / carkit
  -> complete iAP2 Identification
  -> receive AA00
  -> close all transports without sending AA01
```

## 3. 变更矩阵

| 项目 | 改造前 | 改造后 | 原因 | 影响 |
|---|---|---|---|---|
| Android 入口 | `mobile/common` 面向 API 28+ | 新增独立 `:e01` | 不降低原应用基线 | 原应用行为不变 |
| 协议模块 | `:shared` 包含 NDK、无线和本地 MFi | 新增无 NDK 的 `:e01shared` 有线源码集 | E01 暂无匹配 NDK，且无线代码使用 API 26+ | E01 APK 仅携带有线路径 |
| 最低 API | 原产品要求 API 28 | `minSdk=22`、`targetSdk=22` | 对齐 E01 Android 5.1 的旧行为 | 仅适合内部分发，不适合上架 Play |
| Base64 | `java.util.Base64` 需要 API 26 | Bouncy Castle 兼容实现 | API 22 可运行且 JVM 单测可执行 | Lockdown/Remote MFi 编码不变 |
| USB 配置 | 通用描述符匹配允许回退 | E01 严格要求 configuration ID `6` | 防止误选 config 5 | config 6 不完整时明确失败 |
| 重枚举 | 主要依赖 attach 广播 | attach + 750 ms 轮询，20 s 超时，最多 2 次 | E01 可能漏收重枚举广播 | 降低永久卡在等待态的概率 |
| iAP2 USB 接口号 | 固定值 | 从 config 6 的 NCM data interface 动态取得 | 避免猜测接口号 | 以实机描述符为准 |
| MFi | 本地 CH341/I2C 或 Remote | Local offline 或 Remote MFi | 无 CH341/I2C 硬件时也可完成 iAP2/AirPlay MFi | 参见 §Local MFi |
| 无 MFi 诊断 | 无 | 设置页提供持久化开关 | MFi 到位前验证前半链路 | 收到 AA00 后主动停止，不启动 CarPlay |
| 显示 | 可配置 | H.264、1280x720、30 fps | 首轮降低解码负担 | 暂不启用 HEVC |
| 麦克风 | 通用模块可启用 | E01 强制关闭且不申请录音权限 | 先收敛首轮风险 | Siri/电话上行本轮不验收 |
| 持久化 | 通用应用数据 | E01 独立 SharedPreferences | 不污染现有应用 | 保存身份、AirPlay 配对、Lockdown 记录和 MFi 配置 |
| UI | 通用多功能页面 | 原生全屏 Surface + 状态 + 设置侧栏 | 减少 E01 运行时依赖 | 齿轮入口可改配置，重连需人工触发 |
| Debug 面板 | 无 | 设置侧栏新增 DEBUG 分区（后端 / 版本 / Offline dir / 磁盘日志 / 详细日志 / 清理与 adb pull 提示） | 车机侧原地排障 | 见 §Debug 设置与日志 |
| 磁盘日志 | 无 | 双文件 rotate，`noBackupFilesDir/logs/e01.log` + `e01.log.1`，各 512 KiB，总 ≤ 1 MiB | 便于跨会话回溯 iAP2/AirPlay 事件 | 首次启动即建立；退出/清理不影响 APK |
| iAP2 控制超时 | 单一 `deadlineNanos: Long` 到期即失败 | `Iap2ControlDeadline`：握手前 60 s，`authenticated()` 后转 30 s 轮询；支持 `NO_TIMEOUT_MILLIS = Long.MAX_VALUE` | 迁自 DiPlay，避免长驾驶被固定 timeout 打断，同时保留握手上限 | `Iap2WiredControlClient` 与调用侧行为等价，E01 现有 24 h timeout 依然合法 |
| iAP2 identification 声明 | AA 消息未列入 `MESSAGES_SENT_BY_ACCESSORY` / `MESSAGES_RECEIVED_FROM_PHONE` | 追加 `0xAA00..0xAA05` | Local MFi 在 identification 后立刻进入 AA 循环，须在声明列表内 | `Iap2MfiAuthenticationClient` 帧不再被 identification 丢弃 |
| Lockdown carkit 服务打开 | `open(pairRecord, label)` 内联写死 `com.apple.carkit.service` | 抽出 `openService(pairRecord, label, serviceName)`，`open` 转为 wrapper | 迁自 DiPlay：为未来打开其他 Lockdown 服务（如 mobileactivationd 之类）留口；行为完全等价 | 现有 `open(...)` 调用签名不变 |
| Lockdown TLS 端点校验 | `SSLEngine` 默认可能开启 endpoint identification | `sslParameters.endpointIdentificationAlgorithm = null` | Lockdown 是 P2P TLS 无 SNI，默认端点校验会导致 P+ Android 拒绝连接 | E01 API 22 上 `SSLParameters` 及此 setter 均可用（已核 `javap`） |
| 视频回放骨架（准备中） | `AndroidMediaSink` 内私有 `VideoJob` sealed，`LinkedBlockingQueue` 直连；`MediaSink` 无恢复/诊断 hook | 新增公共 `VideoJob` / `VideoDecodeQueue` / `VideoReferenceChain` / `VideoInputPump`；`MediaSink` 新增 `setVideoRecoveryHandler` / `setVideoDiagnosticHandler` 默认空实现；`AirPlaySessionListener` 新增 `onVideoFrameRendered`，`AirPlaySession` 增加 `videoFrameRendered()`；`CarPlayMediaEngine.onScreen` 建立时接线，`report("first frame rendered")` → `session.videoFrameRendered()`，恢复 handler 发 `forceKeyFrame` sendCommand | 迁自 DiPlay，把关键帧/丢帧/背压诊断能力搬进 shared；`AndroidMediaSink` 本轮仅去掉旧 file-private `VideoJob`，实际接入下一轮 M6 完成 | 现有 sink 行为等价（`Resync` 分支被 no-op），M6 前不改变解码路径 |
| `MediaCodecSupport` 严格 NALU 边界 | 越界 / 输入未消费完时 break | 越界或有尾巴时直接返回 `ByteArray(0)`；新增 `isRandomAccess(annexB, codec)` | 迁自 DiPlay；错误 access unit 主动拒绝，避免 MediaCodec 拿到半包 | 老正常入包不受影响 |

## 4. 人工配置单

本方案不使用 TCC、Viking 或 Monad。唯一跨系统依赖是 Remote MFi HTTP 服务。

### 4.1 Remote MFi 服务

| 字段 | 路径 | 目标值 | 验证方式 |
|---|---|---|---|
| No-MFi diagnostics | E01 App 右上角齿轮 > `No-MFi diagnostics` | 无 MFi 测试时开启；正式运行时关闭 | 开启后不要求 URL、不弹 VPN，最终显示 AA00 PASS |
| Server URL | E01 App 右上角齿轮 > `Server URL` | `http://<host>:<port>` 或兼容 Android 5.1 的 HTTPS URL，不带末尾 `/` | App 显示 `Remote MFi ready` |
| Bearer token | E01 App 右上角齿轮 > `Bearer token` | 服务未鉴权则留空；否则填实际 token | 服务端收到 `Authorization: Bearer <token>` |
| Reset API | `<Server URL>/mfi/reset` | `POST {}`，2xx | App 启动时服务端有一次请求 |
| Certificate API | `<Server URL>/mfi/certificate` | `GET`，返回下表字段 | App 通过 SHA-256 校验 |
| Sign API | `<Server URL>/mfi/sign` | `POST` challenge 和 requestId，返回 signature | iAP2 和 AirPlay 鉴权均成功 |

证书响应字段：

| 字段 | 要求 |
|---|---|
| `type` | `mfi` 或 `baa`；省略时按 `mfi` |
| `protocolMajor` | `0..255` 的整数 |
| `certificate` | Base64 |
| `certificateSha256` | 证书原始字节的 64 位十六进制 SHA-256 |

签名请求必须对同一 `requestId` 幂等。token 以应用私有明文偏好保存；E01 已 root，建议使用可撤销、短期 token，日志和截图不得记录 token。

### 4.2 网络

Remote MFi 主机必须能从车机当前默认网络访问。VPN 只声明 `fe80::/64` 路由，不应接管 Remote MFi 的 IPv4/普通互联网流量。

测试前验证：

```bash
adb shell ping -c 3 <remote-mfi-host>
```

如果 ping 被服务器禁用，改用与服务端约定的健康检查。任何连通性检查失败时先停止，不进入 USB 验证。

## 5. 构建

```bash
cd /Users/bytedance/Projects/xcertplay
git rev-parse HEAD
git rev-parse origin/master

ANDROID_HOME=/Users/bytedance/Library/Android/sdk \
  ./gradlew -Pxcertplay.skipNative=true \
  :e01:assembleDebug \
  :e01:lintDebug \
  :e01shared:lintDebug \
  :e01shared:testDebugUnitTest

shasum -a 256 e01/build/outputs/apk/debug/e01-debug.apk
```

通过标准：

- Gradle 返回 `BUILD SUCCESSFUL`；
- `e01` 和 `e01shared` lint 均无 error；
- `e01shared` 70 个协议/媒体/Remote MFi/NCM 单测全部通过；
- APK manifest 为 `minSdkVersion=22`、`targetSdkVersion=22`；
- APK 不含 `lib/` native 库；
- APK 不声明 `RECORD_AUDIO`、蓝牙、定位或 Wi-Fi 管理权限。

## 6. 安装与启动

### 6.1 前置注意事项

1. 保持已经验证过的外部 VBUS 供电方案；避免反向供电，电源必须共地。
2. 停止所有可能占用 iPhone USB 接口的探针或旧版 xcertplay。
3. Remote MFi 服务先启动并保持在线。
4. 首轮测试保持 iPhone 解锁，准备确认“信任此电脑”和 CarPlay 提示。
5. 本 APK 不修改 `cmode`、系统分区、GOC 服务或启动脚本。

### 6.2 安装命令

```bash
adb connect <E01-IP>:5555
adb -s <E01-IP>:5555 shell am force-stop com.shilapi.xcertplay.api17probe
adb -s <E01-IP>:5555 install -r \
  e01/build/outputs/apk/debug/e01-debug.apk
adb -s <E01-IP>:5555 shell am start \
  -n com.shilapi.xcertplay.e01/.E01CarPlayActivity
```

首次启动：

1. 无 MFi 测试时开启 `No-MFi diagnostics`；正式模式保持关闭。
2. 正式模式填入 `Server URL` 和可选 `Bearer token`。
3. 点击 `Save and connect`。
4. 仅正式模式会请求 Android VPN 权限。
5. 连接 iPhone并允许 Android USB 权限。
6. iPhone 出现信任提示时确认并输入锁屏密码。
7. 失败后先保存日志，不连续点击重连；确认原因后再点顶部重连按钮。

## 7. 日志采集与逐阶段判据

```bash
adb -s <E01-IP>:5555 logcat -c
adb -s <E01-IP>:5555 logcat -v threadtime \
  xcertplay-e01:I xcertplay-usb:I '*:S' \
  > e01-wired-carplay.log
```

### 7.1 无 MFi 诊断

开启 `No-MFi diagnostics` 后按顺序核对：

| 阶段 | 必须看到的证据 |
|---|---|
| 模式 | `Remote MFi bypassed` |
| USB | `USB 0x52 sent`、`carplay config chosen=6` |
| NCM | `ncm NTB16 supported=true`，同时记录 `inMax`、`outMax` |
| USBMUX | `usbmux version accepted: 2` |
| Lockdown | 新建或加载 pair record |
| carkit | `carkit iAP2 control channel ready` |
| Identification | `diagnostic iap2 identification accepted` |
| 停止点 | `diagnostic iap2 rx=0xaa00 request-certificate; stopping before AA01` |
| 结果 | `diagnostic PASS received AA00` 和 UI `PASS: AA00 received; stopped before AA01` |

额外负向检查：

- [ ] Remote MFi 服务端没有收到 `/mfi/reset`、`/mfi/certificate` 或 `/mfi/sign`；
- [ ] 日志中没有 `iap2 mfi tx=0xaa01`；
- [ ] 没有弹出 Android VPN 授权；
- [ ] iPhone 不启动 CarPlay 画面，这是预期结果；
- [ ] 完成后探针仍能重新 claim USBMUX/NCM，证明接口已经释放。

### 7.2 正式模式

关闭 `No-MFi diagnostics` 后按顺序核对：

| 阶段 | 必须看到的证据 | 失败即停 |
|---|---|---|
| Remote MFi | `remote MFi ready protocolMajor=` | URL、路由、HTTP 状态、证书 SHA 错误 |
| USB 发现 | `iPhone discovered vid=0x5ac` | 无设备或 USB 权限拒绝 |
| 重枚举 | `USB 0x52 sent` | controlTransfer 长度不是 1 |
| config 6 | `carplay config chosen=6`，包含 USBMUX 与 NCM interface | 20 秒两次仍无完整 config 6 |
| USBMUX | `usbmux version accepted: 2` | version/setup 超时或 RST |
| Lockdown | 新建或加载 pair record | iPhone 拒绝信任、密码锁定 |
| carkit | `carkit iAP2 control channel ready` | `StartService` 返回错误 |
| NCM/VPN | `NCM/VPN AirPlay listener ready` | interface claim、TUN 或端口 7000 失败 |
| iAP2 Identity | `iap2 identification accepted` | 参数拒绝 |
| iAP2 MFi | `0xaa00`、`0xaa01`、`0xaa02`、`0xaa03`、`0xaa05` | Remote MFi 签名失败 |
| CarPlay 启动 | 收到 `0x4300`，发送 `0x4301` | control channel closed |
| AirPlay | `airplay connection accepted`、UI 显示 `CarPlay active` | 配对或 `/auth-setup` 失败 |
| 视频 | decoder configured、first input、first rendered frame | 黑屏、解码异常 |
| 触控 | `airplay touch report sent` | event channel 未就绪 |
| 音频 | audio decoder/track 与 first PCM 日志 | 无 PCM 或 AudioTrack 失败 |

## 8. 实车验收 Checklist

### 8.1 冷启动

- [ ] 先完成一次无 MFi 诊断并停在 AA00；
- [ ] 关闭 `No-MFi diagnostics`；
- [ ] 车机重启后 Remote MFi 可达；
- [ ] App 首次启动显示并保存两个配置字段；
- [ ] VPN 同意一次后后续启动不重复弹窗；
- [ ] iPhone 从普通 USB 状态切换到完整 config 6；
- [ ] config 6 的 USBMUX 和 NCM 都可 claim；
- [ ] 首次配对只出现一次有效信任流程；
- [ ] `com.apple.carkit.service` 成功打开；
- [ ] iAP2 Identification、MFi、订阅、`0x4301` 全部完成；
- [ ] AirPlay 会话 active，CarPlay 主画面可见。

### 8.2 媒体与交互

- [ ] H.264 协商为 1280x720、30 fps；
- [ ] 连续画面无绿屏、花屏、明显积帧；
- [ ] 单击、拖动和双指输入坐标正确；
- [ ] 音乐 AAC-LC 可播放；
- [ ] 导航和电话下行音频可播放；
- [ ] 原车音量键仍有效；
- [ ] 麦克风权限未声明，Siri/电话上行明确不在本轮验收。

### 8.3 恢复与持久化

- [ ] 拔插 iPhone 后点一次重连可恢复；
- [ ] App 杀进程后重新启动可复用 Lockdown 记录；
- [ ] 不再重复弹出 iPhone 信任提示；
- [ ] config 6 attach 广播漏收时，轮询仍能继续；
- [ ] config 6 未出现时，20 秒超时后只重试一次；
- [ ] `Clear iPhone pairing` 后 Lockdown 和 AirPlay 信任均清除；
- [ ] 清除后重新连接会再次进入完整配对流程。

### 8.4 稳定性

- [ ] 连续运行 30 分钟；
- [ ] 连续运行 2 小时；
- [ ] iPhone 锁屏、解锁后画面和音频恢复；
- [ ] 前后台切换后 Surface 恢复；
- [ ] 导航播报与音乐切换至少 10 次；
- [ ] USB 拔插至少 10 次；
- [ ] 无应用崩溃、ANR、持续内存上涨；
- [ ] iPhone 供电稳定，Hub、线束和车机无异常发热。

## 9. 回滚

本适配没有系统级写入，回滚只需卸载独立包：

```bash
adb -s <E01-IP>:5555 shell am force-stop com.shilapi.xcertplay.e01
adb -s <E01-IP>:5555 uninstall com.shilapi.xcertplay.e01
```

仅清除配置和配对数据：

```bash
adb -s <E01-IP>:5555 shell pm clear com.shilapi.xcertplay.e01
```

影响：

- 卸载或 `pm clear` 会删除 Remote MFi URL/token、Lockdown 记录和 AirPlay 配对；
- iPhone 下次连接会重新要求信任；
- 原 `mobile`、`automotive`、`api17probe` APK 和车机系统配置不受影响；
- USB configuration 6 是运行期选择，拔线或重启后由 iPhone/系统重新决定。

## 10. 当前未闭环项

由于生成 APK 时车机已离线，本轮只完成本地编译、lint、单测和 APK 静态检查。以下结论必须等下一次实车窗口：

- config 6 在当前 iPhone/固件组合上的自动重枚举稳定性；
- 无 MFi 诊断能否稳定到达 AA00 并释放全部 USB interface；
- `com.apple.carkit.service` 是否接受当前 identification；
- Remote MFi/BAA 的完整 iAP2 与 AirPlay 两次鉴权；
- NCM TUN 上的 IPv6 双向通信；
- H.264 解码、音频和触控；
- 断线恢复和 2 小时稳定性。

实车出现任一失败时，停止后续步骤并保留完整日志；以表格中的首个缺失阶段作为下一轮唯一排障边界。

## 11. 本轮复盘

- 外部共享源码目录的 Kotlin exclude 不会阻止 AGP lint 扫描，因此 E01 使用路径级 lint 规则隔离未编译的无线源码；有线 `NewApi` 检查仍开启。
- `android.util.Base64` 能运行在 API 22，但不能用于 JVM 单测；统一改为 Bouncy Castle 后两端一致。
- E01 必须锁定 config 6，不能采用通用配置回退，否则可能误选 config 5。
- 重枚举不能只依赖广播；E01 同时按描述符轮询，并限制总超时与重试次数。
- 物理屏幕尺寸没有实测依据，因此未写入 AirPlay 配置；只保留明确要求的 1280x720@30。

## Local MFi

Local MFi 使用 APK 内置附件私钥 + Apple 附件证书直接在 Android 进程内完成 iAP2 0xAA00–0xAA05 与 AirPlay MFi-SAP 的 P-256 签名。**仅供内部验收**：私钥进入 APK 后失去硬件不可导出保护，任何拿到 APK 的人都能提取同一私钥，并与所有装机实例共享同一附件身份。上生产或对外分发前必须回到 Remote MFi 或真硬件路径。

### 凭据来源

- 分析基线：[`/Users/bytedance/Projects/remote-mfi-for-xcertplay/docs/04-diplay-mfi-analysis.md`](file:///Users/bytedance/Projects/remote-mfi-for-xcertplay/docs/04-diplay-mfi-analysis.md)
- 只读来源：`DiPlay.apk`（SHA-256 `89466e01…820e`）
- `identity.pk8` SHA-256：`bd50eda2d8dd95a8464440f1aebca7068dcab46a2461ecaf826c7f98f5621a75`
- `certificate.p7b` SHA-256：`634a93dd6c4338080524025411752597ecb828591839cd062a8292fd7e9e844e`
- 证书信息：`serial=24ACBB48…60D2`、`Issuer=Apple Accessories Certification Authority - 00000002`、EC P-256、有效期 `2018-06-13 .. 2049-12-31`。

上述哈希在本轮构建前经 `unzip -p ... | shasum -a 256` 独立核验，与 APK 内实际字节一致。

### 部署步骤（每台构建机独立执行，不入 git）

```bash
# 一键脚本：提取 + 强制 SHA 校验 + 可选构建
./scripts/e01-install-offline-mfi.sh /path/to/DiPlay.apk         # 只部署
./scripts/e01-install-offline-mfi.sh /path/to/DiPlay.apk --build # 部署并触发 :e01:assembleDebug
```

脚本会先校验 APK 与解出的 identity/certificate 三个 SHA-256，与本节 §凭据来源 完全一致才落盘。任一不匹配会清空 staging 并 fail-closed。

如需手工执行，等价于：

```bash
mkdir -p e01/src/main/assets/offline-mfi
unzip -p /path/to/DiPlay.apk assets/offline-mfi/identity.pk8    > e01/src/main/assets/offline-mfi/identity.pk8
unzip -p /path/to/DiPlay.apk assets/offline-mfi/certificate.p7b > e01/src/main/assets/offline-mfi/certificate.p7b
shasum -a 256 e01/src/main/assets/offline-mfi/*  # 必须与上表哈希完全一致
./gradlew :e01:assembleDebug -Pxcertplay.skipNative=true
```

`.gitignore` 中已存在 `**/assets/offline-mfi/`。`git status` 不能显示这两个文件；若显示，立即回退。脚本自身也会二次调用 `git check-ignore`，若 `.gitignore` 规则被误改动会拒绝落盘。

### 运行时行为

- `E01Bootstrap.ensure()` 在 `E01CarPlayActivity.onCreate` 中优先调用：将 `assets/offline-mfi/*` 复制到 `getNoBackupFilesDir()/offline-mfi-staging/`，`LocalMfiAuthenticationClient.load()` 自检通过后原子重命名为 `offline-mfi/`，然后再次加载。任何失败都清理 staging 并抛出，UI 显示"Local MFi identity is missing"，禁止进入 CarPlay。
- APK 升级不会覆盖已落盘的 `offline-mfi/`；轮换凭据需要 `pm clear` 或卸载重装。
- 设置页新增开关 "Use local offline MFi identity"：打开则 `MfiTarget.LOCAL`，关闭且未开诊断模式则 `MfiTarget.REMOTE`。诊断模式独立，一旦开启无 MFi 判别流程仍绕过。
- `E01WiredCarPlayController.prepareLocalMfi()` 走 `LocalMfiAuthenticationClient`；`Iap2WiredControlClient` 与 `MfiSapAuthSetup` 直接使用同一 `MfiAuthenticator` 接口。
- 每次签名日志格式：`local MFi signature digestBytes=32`；启动日志：`local offline MFi ready protocolMajor=3 certificateBytes=<n>`。

### 失败模式

| 现象 | 处理 |
|---|---|
| `Offline MFi directory is missing or invalid` | assets 未落盘；确认构建前完成 §部署步骤 |
| `Expected one accessory certificate` | `certificate.p7b` 内证书数 ≠ 1；哈希不符则重取 |
| `Expected a P-256 accessory certificate` | 曲线不是 secp256r1；哈希不符则重取 |
| `Local private key does not match certificate` | 私钥与证书不匹配；同时更新两个文件 |
| iPhone 未回 `0xAA05` | 内置凭据被 iOS 拒绝；改走 Remote MFi 或换设备 |

### 回滚

- **本轮切换回 Remote**：设置页关闭 "Use local offline MFi identity"、填入 Remote server；然后 `adb shell su -c 'rm -rf /data/data/com.shilapi.xcertplay.e01/no_backup/offline-mfi'`，重启 App。
- **完全移除**：`adb uninstall com.shilapi.xcertplay.e01`；从工作区删除 `e01/src/main/assets/offline-mfi/`；确认 `git status` 无凭据文件。
- **代码回滚**：本轮改动集中在 [`e01/src/main/java/com/shilapi/xcertplay/e01/`](../e01/src/main/java/com/shilapi/xcertplay/e01/)、[`shared/src/main/java/com/shilapi/xcertplay/mfi/`](../shared/src/main/java/com/shilapi/xcertplay/mfi/) 和 [`shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt`](../shared/src/main/java/com/shilapi/xcertplay/orchestration/CarPlayController.kt)；`git checkout HEAD^ -- <路径>` 可退回。

### 验收

- `./gradlew :shared:testDebugUnitTest -Pxcertplay.skipNative=true` 全绿（含 4 项 `LocalMfiAuthenticationClientTest`）；
- `./gradlew :e01shared:testDebugUnitTest -Pxcertplay.skipNative=true` 全绿；
- `./gradlew :e01:assembleDebug -Pxcertplay.skipNative=true` 成功，APK SHA-256 与 §1 记录一致；
- `unzip -l e01/build/outputs/apk/debug/e01-debug.apk | rg offline-mfi` 显示 `identity.pk8` 与 `certificate.p7b` 且哈希与本节 §凭据来源一致；
- 实车运行时 logcat 首次启动含 `E01Bootstrap` 与 `local offline MFi ready protocolMajor=3 certificateBytes=<n>`，iPhone 返回 `0xAA05 AuthenticationSucceeded`。

## Debug 设置与日志

齿轮 → 设置侧栏 → `DEBUG` 分区：

- **Persist logs to disk**：默认开。开启后每条 `appendLog` 与 `updateStatus` 同步写入 [`E01LogFile`](../e01/src/main/java/com/shilapi/xcertplay/e01/E01LogFile.kt)，路径 `noBackupFilesDir/logs/e01.log`。文件超过 512 KiB 时轮转到 `e01.log.1`，总磁盘占用不超过 1 MiB。关闭开关会 flush 并关闭 writer，之前的两份文件保留在磁盘上。
- **Verbose runtime logging**：持久化到 SharedPreferences `verbose_log`，可被下游控制器读取（当前为占位开关，不改变运行时链路，避免误触发协议行为）。
- **状态卡**（`debugStatusLabel`，可长按选中复制）：
  - `Version`：APK versionCode；
  - `MFi backend`：`LOCAL` / `REMOTE`（无 MFi 诊断按 `MfiTarget` 判断）；
  - `Remote server`：Remote MFi URL 或 `(unset)`；
  - `Offline MFi dir`：`present` / `missing`（对应 `noBackupFilesDir/offline-mfi/`）；
  - `Identity pubKey`：AirPlay 身份公钥前 4 字节 hex，用于跨会话追踪同一附件身份；
  - `File log`：当前状态与磁盘上两个文件的字节数；
  - `Log path`：完整落盘路径；
  - `Last log error`：若 IO 写入曾失败，最后一次错误消息。
- **Print log path**：把 `log active=<path> size=<n>B` 与建议的 `adb pull` 命令追加到 UI/磁盘日志里；不会触碰凭据。
- **Clear logs**：删除 `e01.log` 与 `e01.log.1`，然后重新打开一份带 `cleared <ts>` 头的活动文件；不影响 SharedPreferences、Lockdown pair record 或 offline MFi 凭据。

### 磁盘日志布局

```text
/data/data/com.shilapi.xcertplay.e01/no_backup/logs/
  ├── e01.log       # active, ≤ 512 KiB
  └── e01.log.1     # rotated, ≤ 512 KiB
```

- 每行前缀 `yyyy-MM-dd HH:mm:ss.SSS`；
- 应用启动写入 `---- session <epoch-ms> ----`，方便切割不同会话；
- 关闭 App 后文件保留，直到手动 Clear 或 `pm clear`。

### 采集与回滚

```bash
# 拉走当前活动日志
adb -s <ip>:5555 pull /data/data/com.shilapi.xcertplay.e01/no_backup/logs/e01.log ./
# 拉走上一段轮转日志
adb -s <ip>:5555 pull /data/data/com.shilapi.xcertplay.e01/no_backup/logs/e01.log.1 ./
# UI 内一键清空
# 设置 → DEBUG → Clear logs
# 或强制清空整个应用数据
adb shell pm clear com.shilapi.xcertplay.e01
```

回滚：本节只新增文件与开关，不改协议链路。撤回时 `git checkout HEAD -- e01/src/main/java/com/shilapi/xcertplay/e01/ docs/03-wired-carplay.md` 即可。


# 几何 C E01 有线 CarPlay 运行与验收手册

## 1. 范围与基线

本轮只提供几何 C E01 的独立有线 CarPlay APK，不修改系统分区、GOC 蓝牙、原车应用或无线 CarPlay。

| 项目 | 值 |
|---|---|
| 仓库 | `https://github.com/shilapi/xcertplay.git` |
| 本地分支 | `master` |
| 适配基线 | `a5de8fdd06c7782981c1059a5246d80a8fe1c984` |
| 核对时远端 | `3ac55e34c6add69c13c90ef6d55a767391d3fbd5` |
| 基线差异 | 远端领先 2 个提交，均只修改 `README.md` |
| 应用包名 | `com.shilapi.xcertplay.e01` |
| Android | 5.1 / API 22 |
| APK | `e01/build/outputs/apk/debug/e01-debug.apk` |
| APK SHA-256 | `97dc95effa502fadedd0ea0d3906f2c1e1750450f779abc5042c27ac12c760ac` |

实车已证明的边界见
[`e01-runtime-evidence-2026-09-23.zh-CN.md`](e01-runtime-evidence-2026-09-23.zh-CN.md)：

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
| MFi | 本地 CH341/I2C 或 Remote | 仅 Remote MFi | 车机端不新增硬件依赖 | Remote MFi 服务必须在线 |
| 显示 | 可配置 | H.264、1280x720、30 fps | 首轮降低解码负担 | 暂不启用 HEVC |
| 麦克风 | 通用模块可启用 | E01 强制关闭且不申请录音权限 | 先收敛首轮风险 | Siri/电话上行本轮不验收 |
| 持久化 | 通用应用数据 | E01 独立 SharedPreferences | 不污染现有应用 | 保存身份、AirPlay 配对、Lockdown 记录和 MFi 配置 |
| UI | 通用多功能页面 | 原生全屏 Surface + 状态 + 设置侧栏 | 减少 E01 运行时依赖 | 齿轮入口可改配置，重连需人工触发 |

## 4. 人工配置单

本方案不使用 TCC、Viking 或 Monad。唯一跨系统依赖是 Remote MFi HTTP 服务。

### 4.1 Remote MFi 服务

| 字段 | 路径 | 目标值 | 验证方式 |
|---|---|---|---|
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
- `e01shared` 68 个协议/媒体/Remote MFi 单测全部通过；
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

1. 在设置侧栏填入 `Server URL` 和可选 `Bearer token`。
2. 点击 `Save and connect`。
3. Android 出现 VPN 确认时选择允许。
4. 连接 iPhone。
5. Android 出现 USB 权限时允许。
6. iPhone 出现信任提示时确认并输入锁屏密码。
7. 失败后先保存日志，不连续点击重连；确认原因后再点顶部重连按钮。

## 7. 日志采集与逐阶段判据

```bash
adb -s <E01-IP>:5555 logcat -c
adb -s <E01-IP>:5555 logcat -v threadtime \
  xcertplay-e01:I xcertplay-usb:I '*:S' \
  > e01-wired-carplay.log
```

按顺序核对：

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

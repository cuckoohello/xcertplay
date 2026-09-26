# 04 · 无线 CarPlay 执行手册

E01 无线 CarPlay 的协议逆向、代码接入、实车操作与验收。执行前先完成 [02-vehicle-setup.md](02-vehicle-setup.md) §1–§3。

无线路径核心是 GE13 固件里的 `gocsdk` LocalSocket 数据面；本手册同时是协议逆向报告与实车手册，避免拆成两个位置。

## 1. 基线与范围

- 分析日期：2026-09-24
- 仓库：`git@github.com:cuckoohello/xcertplay.git`
- 本地分支：`e01-wired-carplay`
- 本地 commit：`f124bee1d57de01795d556f669f3b9942eeedde0`
- 远端状态：当前不可达，本轮未核验远端分支是否仍指向该 commit
- 固件来源：仓库内本地解压的 GE13 固件
- 目标二进制：`upgrade_unpack/GE13/firmware/system_extracted/bin/gocsdk`
- SHA-256：`5edf2271550e7b1ee862ecc06528d94325d18c2040994cf53476db85bee0a58f`
- 文件属性：ARM 32-bit little-endian ELF，2,845,760 bytes

本报告只回答以下问题：

1. GE13 的 `goc_spp` LocalSocket 是否具备可恢复的客户端协议；
2. xcertplay 能否在不修改系统 APK、不依赖厂商签名和 native 库的前提下接入；
3. 下一次实车窗口需要如何逐项验收。

静态分析不能证明量产车上的 socket 正在监听、SELinux 放行、目标 RFCOMM channel
正确或 iAP2 已跑通。所有运行态结论仍标记为待验证。

## 2. 结论

本地固件已经给出足够证据实现最小客户端：

```text
Android LocalSocket (RESERVED, "goc_spp")
  -> daemon 累计读取恰好 12 个无分隔符的 Bluetooth MAC 字符
  -> 解析地址并直接调用 native spp_connect
  -> SPP 建链成功后，socket 与 SPP instance 关联
  -> client -> SPP：原始 byte stream
  -> SPP -> client：原始 byte stream
  -> socket EOF/close：释放对应 SPP 连接
```

因此，不再需要先实现空壳 `CommandSppImp`，也不需要重签
`Bluetooth-GocBtAPI.apk`。新增的
[`GocSppTransport`](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt)
可直接作为 [`BlockingDuplexByteStream`](../shared/src/main/java/com/shilapi/xcertplay/transport/BlockingDuplexByteStream.kt)
传给 `Iap2Session.openWireless()`。

仍未解决的运行态关键点：

- `/dev/socket/goc_spp` 在当前量产配置中是否持续存在；
- 普通 App 域是否可通过 DAC 和 SELinux 连接；
- GOC 的无 UUID `spp_connect(address)` 是否会选择 iPhone 的 iAP2 RFCOMM service；
- 连接、断开和重连是否干扰原厂 HFP/A2DP；
- iAP2 marker、link synchronization、Identification、MFi/BAA 和 Wi-Fi handoff。

## 3. 固件证据链

### 3.1 进程与权限

`init.rc` 将 `gocsdk` 作为 `class main` 服务，以 `root:root` 启动，且没有在 init
中预声明四个 GOC socket：

- [init.rc:L688-L692](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/init.rc#L688-L692)

这意味着 socket 由 `gocsdk` 自己创建，而不是 Android init 代建。

### 3.2 socket 创建

对上述固定 SHA-256 的 ELF 以 Thumb 模式反汇编：

```bash
objdump -d --triple=thumbv7-none-linux-gnueabi \
  --start-address=0x35364 --stop-address=0x35468 \
  upgrade_unpack/GE13/firmware/system_extracted/bin/gocsdk
```

恢复结果：

| 虚拟地址 | 行为 | 结论 |
|---|---|---|
| `0x35376-0x3537a` | `socket(1, 1, 0)` | `AF_UNIX + SOCK_STREAM` |
| `0x353bc-0x353c8` | 生成 `/dev/socket/<name>` | 对应 Android RESERVED namespace |
| `0x353cc` | `unlink(path)` | 启动时清理旧节点 |
| `0x353f8-0x35400` | `bind(fd, sockaddr, 0x6e)` | 绑定文件系统 Unix socket |
| `0x3542a-0x35434` | `chmod(path, mode)` | mode 由调用者传入 |

`goc_socket_start` 约位于 `0x35784`，四次调用创建
`goc_control`、`goc_data`、`goc_serial`、`goc_spp`。调用点将 mode 设为
`0x1b6`，即八进制 `0666`。通用 listener 约位于 `0x3526c`，调用
`listen(fd, 10)` 后循环 `accept()`。

字符串表也直接包含：

```text
0x145714 goc_control
0x145784 goc_data
0x1457c8 goc_serial
0x145834 goc_spp
0x14587c spp socket created
```

DAC 模式允许普通 UID 读写，但 Android 5.1 的 SELinux 仍可能拒绝连接，必须实车确认。

### 3.3 12 字节地址握手

SPP client handler 约位于 `0x35a1c`：

| 虚拟地址 | 行为 |
|---|---|
| `0x35a5e-0x35a82` | 读取当前已累计长度，并继续 `read(fd, ..., 12-current)` |
| `0x35a86-0x35a92` | 追加 NUL，累计不足 12 字节则返回等待 |
| `0x35aa8-0x35ab0` | 调用地址解析函数 `0x33e40` |
| `0x35b84` / `0x35c54` | 调用 native `spp_connect` 路径 `0x46d34` |

地址解析函数 `0x33e40` 在 `0x33e6a-0x33e72` 明确要求 `strlen == 12`，
然后按 `4 + 2 + 6` 个十六进制字符转换为内部 Bluetooth 地址结构。

Java 侧的 `droidAddr2Goc()` 同样只删除冒号，不反转地址字节序：

- [GocsdkService.java:L143-L156](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/GocsdkService.java#L143-L156)

因此客户端必须先单独写：

```text
AA:BB:CC:DD:EE:FF -> AABBCCDDEEFF
```

不能把 iAP2 marker 拼进同一个首次 write。Unix stream 不保留 write 边界，而 daemon
会先累计满 12 字节再切换到数据态。

### 3.4 初始化等待、槽位与连接

- handler 遍历索引 `0..9`，最多维护 10 个 client slot；
- 当 SPP 栈未初始化时，`0x35bb0-0x35bd2` 以 300 ms 为间隔等待约 6 秒；
- 字符串表对应 `spp_socket connect not inited, wait` 和
  `spp_socket connect %s error,not inited`；
- 建链回调约位于 `0x46b08`，将 native SPP instance 与 pending socket 关联；
- 断链回调约位于 `0x46b98`，调用 `0x35f90` 关闭并清理对应 socket。

这里没有 Binder 前置调用，也没有 `AT#SP` 前置命令。LocalSocket 的 12 字节地址握手
本身就是 native 连接入口。

### 3.5 双向数据面

客户端到 iPhone：

- `0x35c62-0x35c70`：从 client fd 最多读取 `0x3f4`，即 1012 bytes；
- `0x35c84-0x35c8a`：将读取长度和原始 buffer 交给 `0x46c90`；
- `0x46ce0-0x46d10`：复制 payload 并发送 SPP data request。

iPhone 到客户端：

- SPP data event handler 约位于 `0x46ea0`；
- `0x46ed4-0x46ee0` 将 payload 长度和地址交给 `0x46a50`；
- `0x46a50` 转入 `0x35f40`；
- `0x35f66-0x35f74` 根据 SPP index 找到 client fd，直接调用
  `write(fd, payload, length)`。

结论：`goc_spp` 数据态是双向原始字节流，没有 `SP/SG/SI` 文本命令头，也没有额外
长度帧。一次 `write` 可能被拆成多次 `read`，多次 `write` 也可能合并，调用者必须按
iAP2 自身 framing 解析。

### 3.6 文本命令和 Binder 不是该数据面

`config.ini` 中确实保留另一套串口命令：

- `SP/SH/SG/SY`：[config.ini:L83-L86](../upgrade_unpack/GE13/firmware/system_extracted/config.ini#L83-L86)
- `SI/SV/SS/SR`：[config.ini:L185-L188](../upgrade_unpack/GE13/firmware/system_extracted/config.ini#L185-L188)

但 Java `GocsdkService` 默认 `use_socket=false`，走 `/dev/goc_serial`；切换为 socket 时
也只连接 `goc_serial`：

- [GocsdkService.java:L894-L918](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/GocsdkService.java#L894-L918)
- [GocsdkService.java:L965-L987](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/GocsdkService.java#L965-L987)

`NfServiceSpp.onBind()` 返回的 `CommandSppImp` 八个方法全部固定 false 或 no-op：

- [NfServiceSpp.java:L25-L29](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/nforetek/bt/service/NfServiceSpp.java#L25-L29)
- [CommandSppImp.java:L14-L50](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/CommandSppImp.java#L14-L50)

因此本轮不修改 Binder，不依赖厂商签名。

### 3.7 Apple iAP 回调只作旁证

演示服务收到 `onSppAppleIapAuthenticationRequest` 后发送固定字节：

```text
55 04 00 38 00 01 C3
```

证据：

- [BtService.java:L2516-L2523](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/nforetek/bt/demo/service/BtService.java#L2516-L2523)

它证明旧 SDK 曾处理 Apple iAP 鉴权事件，但不能证明当前目标 channel 是 iAP2，也不能
替代 xcertplay 的 MFi/BAA 流程。

## 4. xcertplay 接入

### 4.1 数据流

```text
GocSppTransport.connect(iPhoneMac)
  -> LocalSocket(SOCK_STREAM)
  -> LocalSocketAddress("goc_spp", RESERVED)
  -> write("AABBCCDDEEFF")
  -> flush()
  -> 返回 BlockingDuplexByteStream
  -> Iap2Session.openWireless(stream)
  -> 独立发送 marker FF 55 02 00 EE 10
  -> 等待 iPhone 发起 wireless link synchronization
```

`Iap2Session.openWireless()` 的抽象入口：

- [Iap2Session.kt:L125-L130](../shared/src/main/java/com/shilapi/xcertplay/iap2/session/Iap2Session.kt#L125-L130)

iAP2 marker 与 wireless 被动同步逻辑：

- [Iap2LinkEngine.kt:L166-L173](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkEngine.kt#L166-L173)
- [Iap2LinkEngine.kt:L632](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkEngine.kt#L632)
- [Iap2LinkChannel.kt:L357-L363](../shared/src/main/java/com/shilapi/xcertplay/transport/Iap2LinkChannel.kt#L357-L363)

### 4.2 变更矩阵

| 对象 | 变更前 | 变更后 | 原因 | 影响 |
|---|---|---|---|---|
| SPP transport | 只有 `BluetoothSocket` wrapper | 新增 RESERVED `goc_spp` wrapper | E01 蓝牙不归 Android Framework 管理 | 不影响标准 RFCOMM |
| 目标地址 | 依赖 `BluetoothDevice.address` | 接受标准冒号 MAC 或 12 位 hex，统一大写 12 位 | 匹配 native parser | 非法地址在开 socket 前失败 |
| 首次写入 | 无 GOC 语义 | 地址独立 write + flush | 防止与 iAP2 marker 混写 | 握手成功后才返回 |
| socket 重试 | 无 | 仅 connect 前 3 次、间隔 200 ms | 容忍 daemon 节点短暂未就绪 | 握手开始后不重试，避免重复 SPP |
| I/O | RFCOMM wrapper | 原始 InputStream/OutputStream + `BlockingDuplexByteStream` | 兼容现有 iAP2 层与诊断工具 | 同一方向不得并发读写 |
| 关闭 | 依赖 BluetoothSocket | LocalSocket 幂等 close | EOF 触发 native SPP 清理 | close 后 send 明确失败 |
| E01 有线 APK | 排除无线编排 | 保持不变 | 遵守现有交付边界 | 本轮不改变有线行为 |

实现：

- 地址、握手与重试：
  [GocSppTransport.kt:L106-L176](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt#L106-L176)
- 阻塞收发与幂等关闭：
  [GocSppTransport.kt:L19-L104](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt#L19-L104)
- Android RESERVED LocalSocket：
  [GocSppTransport.kt:L197-L218](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt#L197-L218)
- 聚焦单测：
  [GocSppTransportTest.kt](../shared/src/test/java/com/shilapi/xcertplay/transport/GocSppTransportTest.kt)

`e01shared` 直接复用 `shared/src/main/java`，且没有排除该文件，所以同一实现会以
`minSdk=22` 编译；无线控制器仍被明确排除：

- [e01shared/build.gradle:L15-L53](../e01shared/build.gradle#L15-L53)

## 5. 注意事项与停止条件

1. `0666` 只证明 DAC 权限，不能推断 SELinux 一定放行。
2. 首次 12 字节握手可能触发真实 SPP 连接，不属于只读探测。
3. 地址写入开始后禁止自动重试；部分写入也可能已触发 daemon 状态变化。
4. 不得同时通过 `goc_spp` 和 `AT#SP/SG` 控制同一 iPhone。
5. 同一 transport 的 InputStream 只能有一个 reader，OutputStream 只能有一个 writer。
6. 不记录 iAP2/MFi payload，只记录长度、阶段、错误码和必要的 marker。
7. 出现原厂 HFP/A2DP 中断、gocsdk 重启、SELinux denial 或未知目标设备连接时立即 close，
   不继续 MFi/Wi-Fi 阶段。
8. 当前没有实车在线，本轮不得把单测或静态逆向标记为“无线 CarPlay 已通过”。

## 6. 回滚方案

本轮没有系统侧改动，代码回滚边界为：

1. 删除 `GocSppTransport.kt`；
2. 删除 `GocSppTransportTest.kt`；
3. 删除本报告；
4. 不改动用户已有的 staged 文档和无线兼容性修改。

实车 PoC 的运行时回滚：

1. 调用 transport `close()`，让 client fd EOF；
2. 确认 `/proc/net/unix` 中 client 连接消失；
3. 确认 `gocsdk` PID 未变化；
4. 在原厂 UI 重新确认 HFP、电话音频和 A2DP；
5. 如原厂连接未恢复，只重启原厂 Bluetooth/GOC 服务或整机，不替换分区文件。

## 7. 实车操作手册

### 7.1 前置记录，只读

所有命令输出保存到同一带时间戳目录。单条读取命令无匹配可继续，但必须在结果表记录
`not found`；写操作失败则停止。

```bash
adb shell 'date; getprop ro.build.fingerprint; getenforce'
adb shell su -c 'pidof gocsdk; ps | grep gocsdk'
adb shell su -c 'ls -lZ /dev/socket/goc_spp'
adb shell su -c 'cat /proc/net/unix | grep goc_spp'
adb shell su -c 'logcat -c'
```

验收：

- [ ] build fingerprint 与目标 GE13 固件一致；
- [ ] `gocsdk` 只有一个稳定 PID；
- [ ] `/dev/socket/goc_spp` 存在且 mode 为 `srw-rw-rw-`；
- [ ] `/proc/net/unix` 有 `goc_spp` listener；
- [ ] 记录 SELinux 模式和 socket label；
- [ ] 原厂 HFP 通话与 A2DP 播放均正常。

### 7.2 地址输入

从 GOC/原厂配对列表取得唯一目标 iPhone MAC，不使用 Android Framework 返回的
`00:00:00:00:00:00`。

样例：

```text
原始：AA:BB:CC:DD:EE:FF
握手：AABBCCDDEEFF
长度：12 ASCII bytes
```

验收：

- [ ] 地址来自当前已配对 iPhone，不是本机 Bluetooth MAC；
- [ ] 去冒号后仍为 12 个十六进制字符；
- [ ] 不反转字节序；
- [ ] 同一时刻只有一个 PoC client。

### 7.3 只连接 LocalSocket 和 SPP

该步骤首次具有副作用。开始前停止音乐和通话，并准备立即关闭 client。

验收：

- [ ] App 成功连接 RESERVED `goc_spp`；
- [ ] 地址握手恰好单独写入 12 bytes；
- [ ] logcat 出现 `spp_socket connect succeed,addr is:<target>` 或等价成功事件；
- [ ] gocsdk PID 不变；
- [ ] 没有 `avc: denied`；
- [ ] 原厂 UI 显示的目标设备与输入地址一致；
- [ ] close 后出现对应 SPP disconnect，socket client fd 被释放。

失败分流：

| 现象 | 结论 | 下一步 |
|---|---|---|
| `ENOENT` / `ECONNREFUSED` | daemon socket 未就绪 | 记录 listener/PID，不发送串口命令 |
| `EACCES` / `avc: denied` | SELinux 或 DAC 阻断 | 收集 denial；评估最小 sepolicy/root helper |
| 约 6 秒后 daemon 关闭 | SPP 栈未初始化 | 记录 GOC 初始化状态，不自动重连 |
| 连接到错误设备 | 地址来源错误或 native 选择异常 | 立即 close，回到配对列表核对 |
| HFP/A2DP 中断且不恢复 | 并发冲突 | 停止后续测试并执行运行时回滚 |

### 7.4 iAP2 marker 与 link

在同一个已握手 transport 上调用 `Iap2Session.openWireless()`。首个 SPP payload 应为：

```text
FF 55 02 00 EE 10
```

验收：

- [ ] marker 不与 12 字节地址处于同一个 write；
- [ ] 双向字节计数均增长；
- [ ] 能容忍 LocalSocket 拆包/合包；
- [ ] iPhone 返回 marker；
- [ ] link synchronization 完成并进入 writable；
- [ ] ACK、重传和超时没有持续失控；
- [ ] 关闭后 reader/writer 均退出。

本阶段失败时不进入 Identification 或 MFi。

### 7.5 无 MFi 判别

复用现有诊断原则，只跑：

```text
marker -> link sync -> Identification -> 收到 AA00 -> 主动停止
```

验收：

- [ ] Identification 使用真实车机 Bluetooth MAC；
- [ ] wireless transport component 携带正确热点 SSID；
- [ ] iPhone 接受 Identification；
- [ ] 收到 `0xAA00`；
- [ ] 不发送 `0xAA01`；
- [ ] 不发送 Wi-Fi credentials；
- [ ] 关闭后原厂蓝牙功能恢复。

### 7.6 完整无线 bootstrap

仅在 Remote MFi/BAA 可用后执行：

- [ ] `AA00 -> AA01 -> AA02 -> AA03 -> AA05` 完成；
- [ ] 五组 update subscription 全部发送；
- [ ] 收到 `0x5702`，发送 `0x5703`；
- [ ] 收到 `0x4300`，发送 `0x4301`；
- [ ] 收到 `0x4E0D` 与 `0x4E0E`；
- [ ] iPhone 加入目标热点，地址与接口记录完整；
- [ ] Bonjour 可发现 CarPlay/AirPlay 服务；
- [ ] AirPlay type-130 tunnel 建立；
- [ ] tunnel iAP2 ready 后才关闭 `goc_spp`；
- [ ] 蓝牙 bootstrap 关闭后音视频、触控和控制面持续运行。

### 7.7 稳定性与边界

- [ ] 连续冷启动 10 次，10 次均完成 `goc_spp` 握手；
- [ ] 连续 link sync 10 次，无残留 slot；
- [ ] 主动 close、iPhone 断开、daemon 断开三种路径均可恢复；
- [ ] 锁屏/解锁后重连；
- [ ] 来电前、中、后 HFP 与 CarPlay 状态正确；
- [ ] A2DP 播放切换不导致 gocsdk 重启；
- [ ] 超过 1012 bytes 的应用层输出可被流式拆分并正确重组；
- [ ] 两个并发 client 的行为已记录；正式实现仍限制单 client；
- [ ] 30 分钟运行无 fd、线程或内存持续增长。

## 8. 本轮代码验收

已覆盖的 JVM 用例：

- [x] 冒号 MAC 与 12 位 MAC 规范化；
- [x] 非法长度、分隔符和非 hex 在建 socket 前拒绝；
- [x] 地址握手先于首个 payload，且分别 flush；
- [x] connect 前 `ENOENT`/`ECONNREFUSED` 可有限重试；
- [x] `EACCES`/SELinux denial 不重试；
- [x] 握手写入开始后不重试；
- [x] 原始 InputStream/OutputStream 可取得；
- [x] read timeout、分段读取与幂等 close；
- [x] close 后 send 失败。

构建要求：

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" \
ANDROID_SDK_ROOT="$HOME/Library/Android/sdk" \
./gradlew :shared:testDebugUnitTest :shared:lintDebug \
  :e01shared:testDebugUnitTest :e01shared:lintDebug \
  :e01:assembleDebug \
  -Pxcertplay.skipNative=true \
  -Pkotlin.compiler.execution.strategy=in-process
```

2026-09-24 实际结果：

| 检查 | 结果 |
|---|---|
| `:shared:testDebugUnitTest` | 102 tests，0 skipped，0 failures，0 errors |
| `:e01shared:testDebugUnitTest` | 77 tests，0 skipped，0 failures，0 errors |
| 两模块 `GocSppTransportTest` | 各 7 tests，全部通过 |
| `:e01shared:lintDebug` | `No issues found` |
| `:e01:assembleDebug` | 通过，产出 `e01-debug.apk` SHA-256 `5470e248b3bdc2afaab2b4ba6ed44870944158eec0f4bb35cdb6dc74389a8d20` |
| `:shared:lintDebug` | 未通过：10 个既有 `MissingPermission` error，新增 GOC 文件无条目 |

`shared` lint 的 10 个错误位于当前 HEAD 已存在的
`CarPlayController`、`LocalOnlyHotspotManager`、`WifiP2pGroupManager` 和
`MicrophoneUplink` 权限调用。本轮没有为通过检查而改动这些无线/录音模块，也没有建立
lint baseline；完整报告在
`shared/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`。

## 9. 当前状态

| 节点 | 状态 | 依据 |
|---|---|---|
| LocalSocket 协议 | 静态恢复完成 | 固定哈希 ELF 的上述地址 |
| xcertplay transport | 已实现并有单测 | `GocSppTransport` |
| API 22 编译 | 已通过定向编译/测试 | `e01shared:testDebugUnitTest` |
| socket 实车权限 | 待验证 | 当前无远程连接 |
| 目标 iAP2 RFCOMM service | 待验证 | native API 无 UUID 参数 |
| iAP2 link/AA00 | 待验证 | 需要受控实车 PoC |
| 完整无线 CarPlay | 未验收 | MFi、Wi-Fi、AirPlay 尚未闭环 |

本轮把“协议未知”收敛为“客户端协议和数据面已恢复，运行态 channel 选择与权限待验证”。
下一次实车窗口应先完成 7.1 至 7.4，未通过时不要进入 MFi 或 Wi-Fi handoff。

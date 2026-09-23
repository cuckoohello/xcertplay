# 几何 C E01 CarPlay 实车事实记录

## 1. 文档边界

- 采集日期：2026-09-23
- 实车：几何 C E01
- 目的：保存实车命令、探针日志和用户现场操作已经证明的事实
- 不包含：未经实测的 CarPlay 完整可用性结论、MFi 结论、无线方案推测
- 敏感信息：iPhone 序列号、Wi-Fi 地址、HostID、SystemBUID、证书和私钥不在本文展开

## 2. 代码基线

| 字段 | 实测值 |
|---|---|
| 本地分支 | `master` |
| 本地 HEAD | `a5de8fdd06c7782981c1059a5246d80a8fe1c984` |
| 远端 `origin/master` | `3ac55e34c6add69c13c90ef6d55a767391d3fbd5` |
| 差异 | 远端领先 2 个提交，均只修改 `README.md` |
| 探针包名 | `com.shilapi.xcertplay.api17probe` |
| 当前探针版本 | `0.9.0` / versionCode `6` |
| APK SHA-256 | `9faac0ddb2b07f1c281f35188315f3d014d51d058111b1c0a9939339fb01257d` |
| v0.6 回滚 APK | `api17probe/xcertplay-api17probe-v0.6.0-device-backup.apk` |
| v0.6 SHA-256 | `d708c06d0fa7e2f8a44ed6cd21ddbc1b2c352a5ec5ce5cf3f3408cd76d23b05f` |

说明：`api17probe/` 是当前工作区中的独立未跟踪工程；本轮没有合并远端提交，也没有覆盖用户已有改动。

## 3. 车机系统

以下值来自实车 `getprop`：

| 字段 | 实测值 |
|---|---|
| 型号 | `E01` |
| 产品 | `full_ge13_qj_2wk` |
| 设备代号 | `GE13QJ` |
| Android | `5.1` |
| API | `22` |
| 系统版本 | `SW0GE130703H5070.00013` |
| SoC | `MT6735` |
| ABI | `arm64-v8a, armeabi-v7a, armeabi` |
| 内核 Bluetooth 栈属性 | `ro.btstack=blueangel` |
| SELinux | `Disabled` |
| ADB TCP 端口 | `5555` |

2026-09-23 采集时，`adb -s 192.168.43.1:5555 get-state` 返回 `device`。

## 4. 网络接口

| 接口 | 实测状态 |
|---|---|
| `ap0` | `UP, LOWER_UP`, `192.168.43.1/24` |
| `tbox0` | `UP, LOWER_UP`, `192.168.225.22/24` |
| `init.svc.iface_usb0` | `running` |

这些事实仅证明车机热点和 TBox 外网接口同时存在；未在本轮验证热点下 Remote MFi 的路由策略。

## 5. USB 与供电

### 5.1 拓扑和设备

- iPhone VID/PID：`05ac:12a8`
- iPhone 位于板载 USB Hub 下的 `1-1.2`
- Hub VID/PID：`0424:4940`
- 当前 Android USB Host 能读取 iPhone 的 1 至 6 号配置描述符
- 配置 4 包含 USBMUX `ff/fe/02`
- 配置 6 包含 USBMUX、Apple USB Ethernet 和两组 NCM 描述符

### 5.2 已验证的供电事实

- 加强 VBUS 供电前，iPhone 会反复掉线和重新枚举。
- 加强 VBUS 供电后，iPhone 可稳定保持 480 Mbps、500 mA 枚举。
- 因此此前高频掉线的主因已通过硬件对照实验定位为 Hub/VBUS 供电能力，而不是原厂 Apple daemon 抢占。

### 5.3 USB 配置状态注意事项

- `0x52` 曾成功触发 iPhone 从设备号 `001/011` 重枚举为 `001/012`，并暴露 6 个配置。
- config 6、USBMUX、NCM control/data alternate setting 和 `GET_NTB_PARAMETERS` 曾在同一轮实车测试通过。
- 后续 Lockdown 测试使用 config 4；结束时自动恢复 config 6 的 `0x52` 返回 `00`，15 秒内未恢复。
- 2026-09-23 22:xx 再采集时，iPhone 已变为新设备号 `001/017`，Framework 再次能读取完整 config 6 描述符，但 `bConfigurationValue` 当前为 `1`。

最后一项是当前状态，不否定此前 config 6 双通道通过；它说明配置切换后的自动恢复仍需独立修正。

## 6. USBMUX 与 Lockdown

### 6.1 只读 Lockdown

实车已通过：

1. USBMUX v2 version/setup。
2. TCP 62078 SYN、SYN-ACK、ACK。
3. `QueryType`，返回 `com.apple.mobile.lockdown`。
4. `GetValue(DevicePublicKey)`，返回 431 字节数据。
5. `GetValue(WiFiAddress)`，返回非空地址。
6. TCP FIN。

证据：

- [只读请求开始及 62078 握手](../api17probe/build/outputs/apk/debug/xcertplay-api22-lockdown-pair-tls.log#L89-L98)
- [`QueryType` 与两个 `GetValue`](../api17probe/build/outputs/apk/debug/xcertplay-api22-lockdown-pair-tls.log#L99-L111)

一次早期失败的直接原因也已实测定位：USBMUX TCP 有效数据错误使用 `PSH|ACK (0x18)` 时，iPhone 返回 RST；改为正式实现使用的纯 `ACK (0x10)` 后，同一请求成功。

### 6.2 Pair、StartSession 与 TLS

实车已通过：

| 步骤 | 结果 |
|---|---|
| `SetValue(UntrustedHostBUID)` | 成功 |
| `Pair` 第 1、2 次 | `PairingDialogResponsePending` |
| iPhone 人工确认信任 | 已完成 |
| `Pair` 第 3 次 | 成功 |
| `EscrowBag` | 返回 32 字节 |
| 本地配对记录 | 已保存到应用私有 SharedPreferences |
| `StartSession` | 成功 |
| `EnableSessionSSL` | `true` |
| TLS | `TLSv1.2` |
| Cipher suite | `TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384` |

证据：

- [配对输入与证书生成](../api17probe/build/outputs/apk/debug/xcertplay-api22-lockdown-pair-tls.log#L124-L132)
- [信任等待和三次 Pair](../api17probe/build/outputs/apk/debug/xcertplay-api22-lockdown-pair-tls.log#L133-L162)
- [`StartSession` 和 TLS](../api17probe/build/outputs/apk/debug/xcertplay-api22-lockdown-pair-tls.log#L164-L181)

应用私有记录只核对了文件存在：

```text
/data/data/com.shilapi.xcertplay.api17probe/shared_prefs/lockdown_pair_record.xml
```

没有读取或导出其中的私钥、证书和唯一标识。

### 6.3 尚未验证

- `StartService(com.apple.carkit.service)`
- service 端口及可选的 service TLS
- iAP2 link/session
- MFi/BAA
- CarPlay mode/resource negotiation
- 视频、音频、触控、电话和导航
- 长时间运行与断线恢复

因此目前已经证明 USB Host 到 Lockdown TLS 的链路可行，但尚未完成有线 CarPlay 端到端验收。

## 7. Bluetooth 运行时事实

### 7.1 Android Framework

`dumpsys bluetooth_manager`：

```text
enabled: false
state: 10
address: 00:00:00:00:00:00
name: CAR BT
Bluetooth Service not connected
```

同时：

```text
settings get global bluetooth_on = 1
pm path com.android.bluetooth = /system/app/Bluetooth/Bluetooth.apk
init.svc.mtkbt = stopped
```

这证明设置值为 1 不等于 Android Framework 蓝牙栈已经接管硬件。

### 7.2 厂商蓝牙

运行进程包括：

```text
/system/bin/gocsdk
com.nforetek.bt
ecarx.bluetooth.service
com.ecarx.btphone
com.neusoft.geely.btphone.nf
com.android.bluetooth
```

`getprop` 显示 `init.svc.gocsdk=running`。实时 `logcat` 连续出现：

```text
goc: a2dp sbc 168433..183820 B/s len:4096
```

因此厂商 GOC 链路正在实际承载 A2DP 音频；Android Framework 显示 OFF 与原厂蓝牙工作并不矛盾。

设备节点：

```text
/dev/goc_serial -> /dev/pts/1
/dev/btif       crw------- system system
/dev/stpbt      不存在
```

`/dev/socket` 与 shell 可见的 `/proc/net/unix` 在本次采样中未找到 `goc_spp`。这只能证明采样时未见该 socket，不能证明二进制永远不会按需创建它。

### 7.3 厂商包

| 包 | 实测 |
|---|---|
| `ecarx.bluetooth.service` | system UID、persistent，版本 `1.0.25-20191009.3227` |
| `com.nforetek.bt` | system/persistent，targetSdk 22，版本 `1.0` |
| NForetek SPP Service | Manifest 可解析 `com.nforetek.bt.service.NfServiceSpp` |

### 7.4 NForetek SPP Binder 动态结果

v0.9 探针只执行了 bind 和 transaction 1
`isSppServiceReady()`，未注册回调、连接设备或发送数据。

结果：

```text
bindService=true
descriptor=com.nforetek.bt.aidl.INfCommandSpp
isSppServiceReady=false
```

证据：

- [实车 Binder 日志](../api17probe/build/outputs/apk/debug/xcertplay-api22-goc-spp-binder.log#L182-L185)
- [SPP Service Manifest](../upgrade_unpack/GE13/analysis/gocbtapi/resources/AndroidManifest.xml#L63-L70)
- [SPP Binder 空实现](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/CommandSppImp.java#L14-L50)

## 8. SSH 状态

- TCP 22 可连接。
- 当前 dropbear 同时拒绝空密码和密码 `root`。
- ADB shell 中没有 `su` 命令。
- 因此本轮没有取得 root shell，也没有完成 root 视角的 `/proc/net/unix` 和进程 fd 核验。

这与此前运行实例允许 `root`/空密码的状态不同，应视为当前实车状态变化，而不是沿用旧结论。

## 9. 回滚

### 探针 APK

```sh
adb -s 192.168.43.1:5555 install -r \
  api17probe/xcertplay-api17probe-v0.6.0-device-backup.apk
```

### 本地 Lockdown 配对记录

```sh
adb -s 192.168.43.1:5555 shell pm clear \
  com.shilapi.xcertplay.api17probe
```

### iPhone 信任记录

在 iPhone 中执行：

```text
设置 > 通用 > 传输或还原 iPhone > 还原 > 还原位置与隐私
```

该操作会清除不止本车机的信任记录，执行前需人工确认。

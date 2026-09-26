# evidence · GE13 / E01 固件静态解析

只读参考。用于回答“GE13 固件里是否已经有 CarPlay/iAP2/SPP 客户端”这类底盘问题。不描述当前 xcertplay 代码。

## 1. 范围与基线

本文分析几何 C E01 的 GE13 固件：

```text
SW0GE130809H5180.00157
```

分析边界：

- 只解包、重建镜像、反编译和读取静态文件；
- 未运行 OTA 升级脚本；
- 未刷写车机；
- 未修改原始压缩包、`system.new.dat`、`boot.img` 或 `system.raw.img`；
- 结论只覆盖当前固件镜像，不能代替车机 `/data` 分区和运行时状态检查。

仓库基线：

| 字段 | 值 |
|---|---|
| 分支 | `master` |
| 本地 commit | `a5de8fdd06c7782981c1059a5246d80a8fe1c984` |
| `origin/master` | `a5de8fdd06c7782981c1059a5246d80a8fe1c984` |
| ahead / behind | `0 / 0` |

## 2. 解包结果

解包链：

```text
Bandizip SFX EXE
  -> 平台合集 RAR
  -> GE13 RAR
  -> 旧-MCU/SW0GE130809H5180.00157.zip
  -> Android OTA 内容
  -> system.raw.img
  -> system_extracted/
  -> boot_unpacked/ramdisk_extracted/
```

OTA 脚本使用：

- `block_image_update()` 写入 `system`；
- `write_raw_image()` 写入 `bootimg`、`logo`、`uboot`、`tee1`；
- `package_extract_dir()` 复制 `ivres`。

证据：

- [`updater-script:4-27`](../upgrade_unpack/GE13/firmware/META-INF/com/google/android/updater-script#L4-L27)
- [`build.prop:4-40`](../upgrade_unpack/GE13/firmware/system_extracted/build.prop#L4-L40)

固件身份：

| 字段 | 值 |
|---|---|
| 版本 | `SW0GE130809H5180.00157` |
| Android | `5.1` |
| API | `22` |
| 设备 | `E01 / GE13QJ` |
| 构建 | `full_ge13_qj_2wk-user` |
| SoC | `MT6735` |
| MTK BSP | `ALPS.L1.MP13.V1.84.1_EMX8816AA_P1` |
| Bluetooth 属性 | `ro.btstack=blueangel` |
| 热点接口 | `ap0` |

关键哈希：

| 文件 | SHA-256 |
|---|---|
| `system.raw.img` | `4766e5626a34d25c67b4fead7f916e0c7dab9cb84a4633e55ad756ccd2322e9f` |
| `boot.img` | `49d8a4264fdbff15a81f4a962ac9a8e5a7020761e02618d447fc26e88425cce6` |
| `Bluetooth-GocBtAPI.apk` | `aff5a4de709f95392e92158a348ad0c9a584d616d1e07203da307437ee009674` |
| `btphoneNF.apk` | `e387f0e12bfbc495bc210d30908d057b0e25c2148bb2719b995ac879f626667d` |
| `XCBTService.apk` | `034b0548ddb4288819a0ac0448fc628e1ff486fb3b42bad4901541ec6b92a71e` |
| `gocsdk` | `5edf2271550e7b1ee862ecc06528d94325d18c2040994cf53476db85bee0a58f` |

## 3. 核心结论

### 3.1 Bluetooth Framework 不可见是架构结果

这版固件的量产 Bluetooth 数据流绕过 Android 标准 `BluetoothAdapter`：

```text
XCBTPhone3 等 UI/业务
  -> XCBTService
  -> ecarx-adapter / BtImpl
  -> btphoneNF / BtManagerService
  -> com.nforetek.bt.NfService*
  -> GocsdkService
  -> /dev/goc_serial
  -> root gocsdk
  -> GOC/MTK Bluetooth transport
```

因此以下现象可以同时成立：

```text
原厂蓝牙 UI 已连接 iPhone
Android BluetoothAdapter enabled=false
Android bondedDevices 为空
```

关键证据：

- `gocsdk` 由 init 以 `root:root` 启动：[`init.rc:688-692`](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/init.rc#L688-L692)
- `XCBTService` 使用 `android.uid.system`，公开 `BtService`、`AdapterService`、HFP/A2DP/PBAP 服务：[`XCBTService AndroidManifest.xml:3-58`](../upgrade_unpack/GE13/analysis/xcbtservice-res/resources/AndroidManifest.xml#L3-L58)
- `BtImpl` 显式绑定 `com.neusoft.geely.btphone.nf.BtManagerService`：[`BtImpl.java:115-119`](../upgrade_unpack/GE13/analysis/ecarx-adapter/sources/com/ecarx/xui/adaptapi/bt/BtImpl.java#L115-L119)
- `BtManagerService` 再绑定 NForetek HFP、PBAP、Bluetooth 服务：[`BtManagerService.java:176-205`](../upgrade_unpack/GE13/analysis/btphoneNF/sources/com/neusoft/geely/btphone/nf/BtManagerService.java#L176-L205)
- `NfServiceBluetooth.onBind()` 返回 GOC Binder：[`NfServiceBluetooth.java:25-29`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/nforetek/bt/service/NfServiceBluetooth.java#L25-L29)
- GOC Java 层默认打开 `/dev/goc_serial`，波特率 `115200`：[`GocsdkService.java:963-984`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/GocsdkService.java#L963-L984)

### 3.2 厂商 Binder 可用于配对和普通车载蓝牙

`CommandBluetoothImp` 已实现：

- Bluetooth 开关；
- 可发现模式；
- 扫描；
- 配对与取消配对；
- HFP + A2DP 连接；
- 设备列表、名称、地址和状态回调。

它将 Binder 请求转成 `AT#` 命令写入 GOC transport：

- 开关、扫描、配对实现：[`CommandBluetoothImp.java:214-370`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/CommandBluetoothImp.java#L214-L370)
- HFP/A2DP 连接实现：[`CommandBluetoothImp.java:497-515`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/CommandBluetoothImp.java#L497-L515)
- 命令最终拼接为 `AT#<command>\r\n`：[`GocsdkService.java:1030-1036`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/GocsdkService.java#L1030-L1036)

`Bluetooth-GocBtAPI.apk` 的 NForetek 服务未声明组件级权限。对于 targetSdk 22，带 Intent Filter 且未显式设置 `exported` 的服务默认为导出。普通 App 理论上可直接绑定这些服务，不必依赖 Android Framework 的 `BluetoothAdapter`。

这条路径仍需在车机上做 Binder 实测，不能仅凭静态代码认定运行时允许访问。

### 3.3 SPP 接口存在，但当前固件实现被主动留空

底层 GOC 配置确实定义了 SPP：

| 功能 | 命令 |
|---|---|
| 连接 | `AT#SP...` |
| 断开 | `AT#SH...` |
| 发送 | `AT#SG...` |
| 查询 | `AT#SY` |

证据：[`config.ini:83-86`](../upgrade_unpack/GE13/firmware/system_extracted/config.ini#L83-L86)

Binder AIDL 还定义了：

- `reqSppConnect`
- `reqSppDisconnect`
- `reqSppSendData`
- `onSppDataReceived`
- `onSppAppleIapAuthenticationRequest`

证据：

- [`INfCommandSpp.java:10-25`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/nforetek/bt/aidl/INfCommandSpp.java#L10-L25)
- [`INfCallbackSpp.java:10-23`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/nforetek/bt/aidl/INfCallbackSpp.java#L10-L23)

但三个实际暴露层均未提供 SPP：

| 层 | 实际行为 |
|---|---|
| `BtImpl.getSpp()` | 返回 `null` |
| `BtManagerService.getSpp()` | 返回 `null` |
| `CommandSppImp` | 查询、连接、断开固定返回 `false`，收发为空实现 |

证据：

- [`BtImpl.java:90-113`](../upgrade_unpack/GE13/analysis/ecarx-adapter/sources/com/ecarx/xui/adaptapi/bt/BtImpl.java#L90-L113)
- [`BtManagerService.java:121-140`](../upgrade_unpack/GE13/analysis/btphoneNF/sources/com/neusoft/geely/btphone/nf/BtManagerService.java#L121-L140)
- [`CommandSppImp.java:14-50`](../upgrade_unpack/GE13/analysis/gocbtapi/sources/com/goodocom/gocsdkserver/CommandSppImp.java#L14-L50)

结论：

```text
普通 App 无法通过当前固件已交付的 AdaptAPI/NForetek Binder 获得可用 SPP。
```

`gocsdk` 二进制仍包含 `goc_spp` socket 和 SPP native 逻辑，因此 root 级直接复用 native socket 仍是研究方向，但其权限、帧格式和并发所有权尚未验证。

### 3.4 BLE/GATT 也未向量产 AdaptAPI 开放

`config.ini` 定义了 BLE 扫描、连接、断开和使能命令：

[`config.ini:110-114`](../upgrade_unpack/GE13/firmware/system_extracted/config.ini#L110-L114)

但 `BtImpl.getGattServer()` 和 `BtManagerService.getGattServer()` 均返回 `null`。因此不能仅根据 native 命令存在就认定第三方 App 可使用 BLE。

### 3.5 固件包含 CarPlay/iAP2 客户端 SDK，但缺少服务端

`XSFConfigService.odex` 内包含：

- `ecarx.carplay.CarPlayManager`
- `ecarx.carplay.ICarPlayManager`
- `ecarx.iap2.IAP2Manager`
- `ecarx.iap2.IIAP2Manager`
- `socket_carplay_*` 名称

客户端行为是：

```text
ServiceManager.getService("ecarx_carplay_service")
  -> 不存在时发送 START_CARPLAY_SERVICE Intent
  -> 等待最多约 2 秒

ServiceManager.getService("ecarx_iap2_service")
  -> 不存在时发送 START_IAP2_SERVICE Intent
  -> 等待最多约 2 秒
```

证据：

- [`CarPlayManager.java:401-431`](../upgrade_unpack/GE13/analysis/xsfconfig/sources/ecarx/carplay/CarPlayManager.java#L401-L431)
- [`IAP2Manager.java:291-321`](../upgrade_unpack/GE13/analysis/xsfconfig/sources/ecarx/iap2/IAP2Manager.java#L291-L321)

对完整 system 中 97 个 APK 的 Manifest 扫描结果：

| 检查项 | 匹配数 |
|---|---:|
| `START_CARPLAY_SERVICE` 服务组件 | 0 |
| `START_IAP2_SERVICE` 服务组件 | 0 |
| 包名 `com.ecarx.CarPlay.App` | 0 |

同时，E01 的设备能力实现直接返回：

```java
public boolean hasCarPlayOrCarLife() {
    return false;
}
```

证据：

- [`ModelHelper.java:141-143`](../upgrade_unpack/GE13/analysis/ecarx-adapter/sources/com/ecarx/xui/adaptapi/device/utility/ModelHelper.java#L141-L143)
- 设置组件据此隐藏 CarPlay/CarLife：[`DeviceBean.java:325-359`](../upgrade_unpack/GE13/analysis/ecarx-adapter/sources/com/ecarx/xui/adaptapi/device/specific/DeviceBean.java#L325-L359)

结论：

```text
当前固件保留了跨车型共享 SDK 的 CarPlay/iAP2 客户端接口，
但未交付对应服务端、CarPlay App 或 native 协议栈。
```

这不是“服务默认关闭”，而是静态镜像中未找到可响应启动 Intent 的组件。

### 3.6 USB Host 存在，Apple 协议栈不存在

USB Host 基础能力存在：

- 系统声明 `android.hardware.usb.host`；
- 创建 `/storage/usbotg`；
- `/dev/bus/usb/*` 权限为 `0660 root:usb`；
- Framework 启动 `UsbService host thread`；
- 探针曾枚举到 Apple `VID=0x05ac` 和 USBMUX interface。

证据：

- [`android.hardware.usb.host.xml:20`](../upgrade_unpack/GE13/firmware/system_extracted/etc/permissions/android.hardware.usb.host.xml#L20)
- [`init.mt6735.rc:16-20`](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/init.mt6735.rc#L16-L20)
- [`ueventd.rc:147-149`](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/ueventd.rc#L147-L149)
- [`UsbHostManager.java:159-190`](../upgrade_unpack/GE13/analysis/services/sources/com/android/server/usb/UsbHostManager.java#L159-L190)
- [探针日志：Apple 枚举、permission、open 与消失](../api17probe/build/outputs/apk/debug/xcertplay-api22-wireless-prob1e.log#L142-L197)

`UsbHostManager.openDevice()` 的静态路径为：

```text
检查受限总线
  -> 在 mDevices 中查找 deviceName
  -> 检查调用 UID 的 USB permission
  -> nativeOpenDevice(deviceName)
```

这版 `UsbSettingsManager.hasPermission()` 还包含厂商修改：对于非 system UID，会先调用 `grantDevicePermission(device, uid)` 再读取 permission map。因此普通 App 的 USB device permission 在此固件中会被自动授予；现有探针日志也明确记录 `permission=true`：

[`UsbSettingsManager.java:816-832`](../upgrade_unpack/GE13/analysis/services/sources/com/android/server/usb/UsbSettingsManager.java#L816-L832)

服务端的黑名单、设备查找和 permission 检查失败会抛出异常，但公开的 `UsbManager.openDevice()` 会捕获所有 `Exception` 并统一返回 `null`：

[`UsbManager.java:61-76`](../upgrade_unpack/GE13/analysis/framework-usb/UsbManager.java#L61-L76)

因此现有应用日志中的 `FAIL returned null` 不能区分“设备已消失”和“native open 失败”，但可以排除已记录为 `true` 的 permission。候选原因可缩小为：

1. iPhone 已重枚举，旧 `deviceName` 从 `mDevices` 消失并触发异常；
2. 设备节点在 Framework 查表后、native open 前消失；
3. native `usb_device_open()` 遇到底层节点、驱动或 I/O 异常并返回空。

固件内唯一声明 `USB_DEVICE_ATTACHED` 的 APK 是 `EngineerMode.apk`。其 `SystemUpgradeReceiver` 实际只处理 `MEDIA_MOUNTED` 和 `MEDIA_EJECT`，没有调用 `openDevice()`：

- [`EngineerMode AndroidManifest.xml:161-173`](../upgrade_unpack/GE13/analysis/engineermode-res/resources/AndroidManifest.xml#L161-L173)
- [`SystemUpgradeReceiver.java:13-31`](../upgrade_unpack/GE13/analysis/engineermode/sources/com/neusoft/optimus/megatron/specific/SystemUpgrade/SystemUpgradeReceiver.java#L13-L31)

所以目前没有证据支持“原厂 CarPlay/Apple daemon 抢占 iPhone”。更符合现有日志的解释是 USB 重枚举、供电/线材问题，或 Framework 查表与 native open 之间的竞态。

固件还携带 `ecarx.usbhost.UsbHostInfo` 客户端 API，但全量 APK Manifest 扫描未找到可响应 `android.intent.action.START_USBHOST_SERVICE` 的服务组件。这与 CarPlay/iAP2 情况相同，属于共享 SDK 接口存在、服务端未交付。

### 3.7 `/system/bin/ipod` 与 Apple iPod 无关

该进程是 MTK 的 IPO/IPO-H（In Power-Off）电源管理 daemon，字符串包含休眠、关机、充电动画和 alarm boot 逻辑。它不是 Apple iPod、USBMUX 或 iAP2 实现。

init 中该服务默认 `disabled`：

[`init.mt6735.rc:1129-1132`](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/init.mt6735.rc#L1129-L1132)

### 3.8 Device-mode USB 配置不能证明 Host-mode 不可用

`init.mt6735.usb.rc` 中大量 `sys.usb.config=*` 是车机作为 USB device 时的 gadget 配置。Accessory 组合被注释：

[`init.mt6735.usb.rc:314-369`](../upgrade_unpack/GE13/firmware/boot_unpacked/ramdisk_extracted/init.mt6735.usb.rc#L314-L369)

它与车机作为 USB Host 打开 iPhone 是两个不同方向，不能用来否定 Host 能力。Host 能力已由 `android.hardware.usb.host`、`usbotg` 和实际 Apple VID 枚举证明。

## 4. 对 CarPlay 路线的影响

| 路线 | 当前结论 | 主要缺口 |
|---|---|---|
| 原厂有线 CarPlay 服务 | 固件未交付 | CarPlay/iAP2 服务端、MFi 认证、USBMUX/iAP2、媒体链路 |
| 自研有线 CarPlay | USB Host 基础可用，协议栈缺失 | 稳定打开 iPhone、USBMUX、lockdown/iAP2、MFi、音视频 |
| 原厂无线 CarPlay | 固件未交付 | iAP2 over Bluetooth、Wi-Fi handoff、Bonjour、CarPlay service |
| 复用 GOC SPP | Binder 层不可用 | `getSpp()==null`、`CommandSppImp` 空实现 |
| root 直连 `goc_spp` | 尚未验证 | socket 权限、协议帧、并发所有权、模块能力 |
| 恢复 Android 标准蓝牙栈 | 风险高，不在本轮执行 | 匹配 E01 的驱动、HAL、Framework、平台签名和回滚链 |

当前最现实的下一步不是直接开发完整 CarPlay，而是先做两个独立 PoC：

1. **USB PoC**：证明 iPhone USBMUX interface 可稳定保持并成功 `openDevice()`。
2. **GOC Binder PoC**：不使用 Android `BluetoothAdapter`，直接绑定 NForetek Bluetooth Binder，验证扫描、配对和 HFP/A2DP。

SPP PoC 应单独立项，因为现有 Binder 明确未实现。

## 5. 分析产物变更矩阵

本轮没有修改固件输入，只新增解析 sidecar 和文档。

| 节点 | 前 | 后 | 原因 | 影响 |
|---|---|---|---|---|
| `system.raw.img` | 已重建 | 不变 | 只读分析 | 哈希不变 |
| `system_extracted/` | 原始提取内容，不含下列 sidecar | 当前 7040 文件 / 341 目录；新增 50 个已知 sidecar 文件和 2 个目录 | `oat2dex` 在输入文件附近输出 DEX/boot classpath 文件 | 不覆盖原文件，但目录不再是纯原始提取 |
| `framework/arm64/dex/` | 不存在 | 23 个文件 | boot.oat 解优化 classpath | 分析 sidecar |
| `framework/arm64/odex/` | 不存在 | 23 个文件 | boot.oat 原始 DEX 提取 | 分析 sidecar |
| `XCBTService.dex` | 不存在 | 新增 | 从 ODEX 恢复 | 分析 sidecar |
| `XSFConfigService.dex` | 不存在 | 新增 | 从 ODEX 恢复 | 分析 sidecar |
| `XCBTPhone3.dex` | 不存在 | 新增 | 从 ODEX 恢复 | 分析 sidecar |
| `upgrade_unpack/GE13/analysis/` | 不存在 | 约 116 MiB | JADX、服务源码和工具输出 | 不参与刷机 |
| 本报告 | 不存在 | 新增 | 汇总可追溯结论 | 文档变更 |

## 6. 回滚方案

不影响原始 OTA 的最小回滚范围：

```sh
rm -rf upgrade_unpack/GE13/analysis
rm -rf upgrade_unpack/GE13/firmware/system_extracted/framework/arm64/dex
rm -rf upgrade_unpack/GE13/firmware/system_extracted/framework/arm64/odex
rm -f upgrade_unpack/GE13/firmware/system_extracted/framework/arm64/services.dex
rm -f upgrade_unpack/GE13/firmware/system_extracted/app/XCBTService/arm64/XCBTService.dex
rm -f upgrade_unpack/GE13/firmware/system_extracted/app/XSFConfigService/arm64/XSFConfigService.dex
rm -f upgrade_unpack/GE13/firmware/system_extracted/app/XCBTPhone3/arm64/XCBTPhone3.dex
rm -f docs/evidence/ge13-firmware-static-analysis.md
```

注意：

- 上述命令仅用于人工确认后的分析产物清理；
- 不要删除 `*.odex` 原始文件；
- 不要删除 `system.raw.img`、`system.new.dat`、`boot.img`；
- 不要在车机上执行这些主机路径命令。

## 7. 车机端只读人工单

### 7.1 GOC 运行状态

```sh
adb shell su -c 'getprop init.svc.gocsdk'
adb shell su -c 'ps | grep -E "gocsdk|com.nforetek.bt|btphone.nf|ecarx.bluetooth.service"'
adb shell su -c 'ls -lZ /dev/goc_serial /dev/goc_stpbt /dev/stpbt 2>&1'
adb shell su -c 'ls -lZ /dev/socket/goc_* 2>&1'
adb shell su -c 'cat /proc/net/unix | grep -E "goc_(control|data|serial|spp)"'
```

记录字段：

| 字段 | 目标值/判定 |
|---|---|
| `init.svc.gocsdk` | `running` |
| `gocsdk` UID | `root` |
| `/dev/goc_serial` | 存在并记录 owner/mode/SELinux context |
| `/dev/socket/goc_spp` | 存在则记录 owner/mode；不存在则记录为未开放 |
| `com.nforetek.bt` | 进程存在或绑定服务后启动 |

### 7.2 Binder 与包状态

```sh
adb shell pm path com.nforetek.bt
adb shell pm path com.neusoft.geely.btphone.nf
adb shell pm path ecarx.bluetooth.service
adb shell dumpsys package com.nforetek.bt
adb shell dumpsys package com.neusoft.geely.btphone.nf
adb shell dumpsys package ecarx.bluetooth.service
adb shell service list | grep -i -E 'bluetooth|carplay|iap2|goc'
```

验证：

- 包路径与固件目录一致；
- `NfServiceBluetooth`、`NfServiceHfp`、`NfServiceA2dp`、`NfServicePbap` 可解析；
- 不预期 `getSpp()` 返回有效 Binder；
- 若运行时存在 `ecarx_carplay_service` 或 `ecarx_iap2_service`，必须记录服务 owner，说明 `/data/app` 或其他分区存在静态镜像外组件。

### 7.3 CarPlay/iAP2 运行时补充检查

```sh
adb shell pm list packages -f | grep -i -E 'carplay|iap2'
adb shell service list | grep -i -E 'carplay|iap2'
adb shell dumpsys activity services | grep -i -E 'START_CARPLAY_SERVICE|START_IAP2_SERVICE|CarPlay'
adb shell su -c 'find /data/app /system/app /system/priv-app -maxdepth 3 -type f | grep -i -E "carplay|iap2"'
```

判定：

- 全部为空：确认服务端未安装；
- 任一非空：保存包路径、版本、签名摘要和 service 名称后再更新结论。

### 7.4 USBMUX 稳定性

连接 iPhone 前启动日志：

```sh
adb shell su -c 'logcat -c'
adb shell su -c 'logcat -v threadtime | grep -i -E "UsbHostManager|UsbSettingsManager|usb_device|05ac|12a8|permission|openDevice"'
```

连接后另一个终端执行：

```sh
adb shell dumpsys usb
adb shell su -c 'find /sys/bus/usb/devices -maxdepth 2 \( -name idVendor -o -name idProduct \) -print -exec cat {} \;'
adb shell su -c 'ls -l /dev/bus/usb/*/*'
```

探针必须逐次记录：

| 字段 | 验收值 |
|---|---|
| `deviceName` | 打开前后保持一致 |
| `vendorId` | `0x05ac` |
| `hasPermission` | `true` |
| USBMUX interface | `class=0xff, subclass=0xfe, protocol=0x02` |
| Bulk OUT/IN | 两个 endpoint 均存在 |
| `openDevice()` | 非 `null` |
| 保持时间 | 连续至少 5 秒未 detach/re-enumerate |

## 8. 验收 Checklist

- [x] 原始 ZIP 可由 `unzip` 正常解包。
- [x] OTA 文件完整提取。
- [x] `system.new.dat` 按 transfer list v2 重建为 4 GiB ext image。
- [x] `system.raw.img` 哈希已记录。
- [x] system 文件系统完整提取。
- [x] boot ramdisk 完整拆包。
- [x] Android、API、平台和产品代号有 `build.prop` 证据。
- [x] GOC daemon、设备节点和 AT 命令协议有静态证据。
- [x] `Bluetooth-GocBtAPI.apk` 已完整反编译。
- [x] `XCBTService.odex` 已恢复为 DEX 并反编译。
- [x] `btphoneNF.apk` 已完整反编译。
- [x] 量产 Bluetooth Binder 数据流已闭环。
- [x] SPP 在三个暴露层的空实现已交叉验证。
- [x] CarPlay/iAP2 客户端 SDK 与服务端缺失已区分。
- [x] 97 个 APK 的 CarPlay/iAP2 Manifest 服务声明已全量扫描。
- [x] USB Host Framework 打开路径已恢复。
- [x] USB attach 接收器已全量扫描并检查代码。
- [x] `ipod` daemon 已排除为 Apple 协议组件。
- [ ] 车机运行时 `/data/app` 和 `service list` 尚待现场检查。
- [ ] iPhone `openDevice()` 连续稳定成功尚待现场验证。
- [ ] `goc_spp` socket 权限和协议帧尚待动态验证。

## 9. 复盘

本轮主要踩坑：

1. 早期 ZIP 失败来自误指向 0 字节临时文件，原 ZIP 本身可由 `unzip` 正常处理。
2. macOS 当前环境的 `/usr/bin/file` 返回 `Input/output error`，已改用 `xxd`、`strings` 和 OAT 解析工具交叉确认。
3. `XCBTService.apk` 不含 `classes.dex`，必须先从 ART ODEX 结合同固件 `boot.oat` 解优化。
4. OAT 工具会把 sidecar 写到输入文件附近，导致 `system_extracted/` 文件数增加；后续应先复制 ODEX 到独立工作目录再处理。
5. `/system/bin/ipod` 名称容易误判，字符串证明它是 MTK IPO 电源管理组件。
6. “AIDL/常量存在”不等于“服务存在”，必须同时检查 Manifest provider、ServiceManager 注册方和实际方法实现。

后续优化：

- 在探针中新增 NForetek Binder 只读检查，不再仅依赖 Android `BluetoothAdapter`；
- USB 探测按 attach 回调立即检查 permission 并打开，记录每次 deviceName 与 detach 时间；
- 将 GOC SPP socket 研究拆成独立 PoC，避免与 HFP/A2DP 验证混在一起；
- OAT 恢复统一在复制后的分析目录执行，保持提取目录可重复校验。

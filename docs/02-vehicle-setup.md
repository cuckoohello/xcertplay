# 02 · 车机连接与共同基线

所有实车工作的共同前置。执行任何 L3 手册（有线 / 无线）之前，必须先完成本文档的 §1–§3。

场景手册：

- 有线：[03-wired-carplay.md](03-wired-carplay.md)
- 无线：[04-wireless-carplay.md §7](04-wireless-carplay.md#7-实车操作手册)

## 1. 基线与范围

| 项目 | 值 |
|---|---|
| 车机 | 几何 C E01，Android 5.1 / API 22，SoC MT6735 |
| 系统版本 | `SW0GE130703H5070.00013`（GE13） |
| ADB TCP 端口 | `5555`（固定） |
| SELinux | `Disabled`（GE13） |
| 仓库分支 | `e01-wired-carplay` |
| 本地 HEAD | `f124bee1d57de01795d556f669f3b9942eeedde0` |
| 有线 APK | `com.shilapi.xcertplay.e01` v `0.2.0`，SHA-256 见 runbook §1 |
| 无线 transport | [GocSppTransport.kt](../shared/src/main/java/com/shilapi/xcertplay/transport/GocSppTransport.kt) |
| Remote MFi | 唯一跨系统依赖，HTTP 服务 |

严禁进行的操作：

- 测试制动、转向、挡位、动力和 ADAS 写控制；
- 修改 `cmode`、系统分区、GOC 服务或启动脚本；
- 替换或重签系统 APK；
- 在同一实车窗口混用有线 / 无线诊断。

## 2. 连接车机

### 2.1 车辆状态

- [ ] P 挡且稳定供电（禁止靠电池独立运行长测）；
- [ ] 已加强 VBUS 供电，Hub `0424:4940`、iPhone `05ac:12a8` 可稳定 480 Mbps 枚举
  （见 [runtime-evidence §5](evidence/e01-runtime-2026-09-23.md#5-usb-与供电)）；
- [ ] 车机启动至可交互桌面，无异常提示。

### 2.2 Wi-Fi ADB

E01 出厂默认不开 Wi-Fi ADB，首次开启需 root：

```bash
adb shell su -c 'setprop service.adb.tcp.port 5555'
adb shell su -c 'stop adbd; start adbd'
```

- [ ] 记录车机 IP，形如 `192.168.43.1:5555`；
- [ ] `adb connect <IP>:5555` 返回 `connected`；
- [ ] `adb devices -l` 状态为 `device`；
- [ ] 后续命令统一使用 `adb -s <IP>:5555`。

### 2.3 基线只读

```bash
adb -s <IP>:5555 shell 'date; getenforce; id'
adb -s <IP>:5555 shell 'getprop ro.build.fingerprint'
adb -s <IP>:5555 shell 'ip addr'
adb -s <IP>:5555 shell su -c 'pidof gocsdk; ps | grep gocsdk'
```

- [ ] fingerprint 与本文档基线一致，否则暂停并同步；
- [ ] `getenforce=Disabled`；
- [ ] `gocsdk` 只有一个稳定 PID；
- [ ] `ap0` 与 `tbox0` 均 UP。

### 2.4 Remote MFi 可达性

```bash
adb -s <IP>:5555 shell ping -c 3 <remote-mfi-host>
```

- [ ] ping 或约定的健康检查通过；
- [ ] `POST /mfi/reset`、`GET /mfi/certificate`、`POST /mfi/sign` 均可用；
- [ ] Token 短期、可撤销，日志和截图禁止记录。

Remote MFi 未就绪时只能跑各手册的“无 MFi 诊断”，不得进入正式模式。

## 3. 日志采集

每个场景独立清 logcat：

```bash
adb -s <IP>:5555 logcat -c
adb -s <IP>:5555 logcat -v threadtime \
  xcertplay-e01:I xcertplay-usb:I '*:S' \
  > logs/$(date +%Y%m%d-%H%M%S)-<scenario>.log
```

- [ ] 每场景一份日志，文件名含时间戳；
- [ ] 保留失败时的完整 logcat，不裁剪；
- [ ] 敏感字段（Token、证书、iPhone 序列号、Wi-Fi 地址、HostID、SystemBUID）不入库。

## 4. 有线 CarPlay

依赖：USB 数据口、iPhone、加强 VBUS 供电、Remote MFi（或先跑无 MFi 诊断）。

执行、阶段表、验收 checklist、失败分流全部见
[03-wired-carplay.md §6–§8](03-wired-carplay.md#6-安装与启动)。

进入前必须满足：

- [ ] §2 基线全部通过；
- [ ] `e01-debug.apk` SHA-256 与 runbook §1 一致；
- [ ] 现场无其他人在写 `cmode` 或其他系统属性。

## 5. 无线 CarPlay

依赖：iPhone 已与车机原厂 UI 完成蓝牙配对、`/dev/socket/goc_spp` 静态存在、Remote MFi 服务就绪（完整 bootstrap 才需要）。

执行、地址握手、iAP2 marker、失败分流、稳定性验收全部见
[04-wireless-carplay.md §7](04-wireless-carplay.md#7-实车操作手册)。

阶段顺序不可打乱：`7.1 只读盘点 → 7.2 地址输入 → 7.3 SPP 连接 → 7.4 iAP2 marker → 7.5 无 MFi 判别 → 7.6 完整 bootstrap → 7.7 稳定性`。

进入前必须满足：

- [ ] §2 基线全部通过；
- [ ] `gocsdk` 与 `/dev/socket/goc_spp` 只读盘点结果符合预期；
- [ ] 已从原厂 UI 记录目标 iPhone MAC（12 位 hex，无冒号，不反转字节序）。

## 6. 共同验收

- [ ] Remote MFi 请求量与阶段计数与预期一致；
- [ ] 原厂 HFP / A2DP / 方向盘按键在测试前后行为相同；
- [ ] 车机温度、Hub、线束无异常发热；
- [ ] 任何 `avc: denied`、`ANR`、`FATAL EXCEPTION` 出现即停止；
- [ ] 每个 checklist 附上日志文件名和关键行号。

## 7. 回滚

- 有线：runbook §9（`am force-stop` / `uninstall` / `pm clear`）；
- 无线：goc-spp §6（`transport.close()` → 确认 `/proc/net/unix` 中 client 消失 → `gocsdk` PID 未变 → 原厂 HFP/A2DP 恢复）。

严禁行为：替换分区文件、重签系统 APK、修改 `cmode`。

## 8. 未闭环项

必须在下一次实车窗口先解决，否则不进入依赖阶段：

- config 6 自动重枚举稳定性；
- `com.apple.carkit.service` 是否接受当前 identification；
- Remote MFi / BAA 完整 iAP2 与 AirPlay 两次鉴权；
- NCM TUN IPv6 双向；
- `/dev/socket/goc_spp` 在量产 GE13 上的持续存在与 DAC / SELinux 放行；
- GOC 无 UUID `spp_connect` 是否命中 iPhone 的 iAP2 RFCOMM service。

## 9. 复盘规则

每次实车窗口结束：

1. 汇总日志和 checklist 结果；
2. 更新 [runtime-evidence](evidence/e01-runtime-2026-09-23.md) 的“已证明”条目；
3. 新的失败模式补入对应手册（有线 → runbook §7.2；无线 → goc-spp §7.3）；
4. 基线 commit、APK SHA-256、Remote MFi API 有变，同步 §1 表格；
5. 未解决项写回 §8，避免下一轮重复踩坑。

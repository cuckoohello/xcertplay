# xcertplay 文档

面向几何 C E01 车机的 CarPlay 接收器项目，中文文档分四层：

| 层 | 文件 | 说明 |
|---|---|---|
| **L1 概览** | [01-overview.md](01-overview.md) | 项目边界、模块设计、开发任务与执行顺序。新人从这里开始。 |
| **L2 通用步骤** | [02-vehicle-setup.md](02-vehicle-setup.md) | 车机连接、Wi-Fi ADB、基线只读、日志、共同回滚与复盘。 |
| **L3 场景手册** | [03-wired-carplay.md](03-wired-carplay.md) | 有线 CarPlay 执行手册（阶段表 / 验收 / 失败分流） |
|              | [04-wireless-carplay.md](04-wireless-carplay.md) | 无线 CarPlay 执行手册（GOC SPP 协议 + 实车步骤） |
| **L4 证据** | [evidence/e01-runtime-2026-09-23.md](evidence/e01-runtime-2026-09-23.md) | 实车已证明事实快照 |
|              | [evidence/ge13-firmware-static-analysis.md](evidence/ge13-firmware-static-analysis.md) | GE13 固件底盘分析 |
|              | [evidence/hud-audio-research.md](evidence/hud-audio-research.md) | HUD / 导航声道预研（未启动） |

## 使用指引

- **规划开发任务**：读 L1。
- **首次到车旁**：L1 §5 → L2 全文 → L3 或 L4 场景。
- **实车执行**：直接进入 L3/L4，L2 是共同前置。
- **补证据 / 复盘**：改 L4，同步 L2 §复盘规则。

## 目录规则

- L1/L2/L3 文件名带序号，是执行入口，禁止拆分或加日期后缀；
- L4 是可回溯的历史/研究快照，命名允许带日期；
- 新增文档前先确认 L1–L4 是否已有承载点，避免碎片化。

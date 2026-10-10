<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>xcertplay 是面向 Android 车机的 CarPlay 接收端项目，使用纯软件本地 MFi 认证，支持 CarPlay 有线和无线连接。</p>
</div>

## Features

- 面向 Android 车机的 CarPlay 主机应用。
- 支持使用本地证书/私钥文件进行纯软件 MFi 认证。
- 支持 CarPlay 有线或无线连接。
- 支持触发 CarPlay Ultra （未测试/未完成的协议栈，但是确实可以在 iPhone 上触发 CarPlay Ultra 的提示）。
- 支持语音、导航、音乐多通道音频输出并 mapping 至 Android 的对应通道。
- 支持动态 Activity resize ，并自动重新握手至新的分辨率。
- 支持车机位置回传。
- 支持 Android 9 (API 28) 。

## 使用方法

1. 通过蓝牙将 iPhone 与车机配对。
2. CarPlay 视频流尚未启动时，点击右下角的设置按钮；也可以用三指向下滑动打开设置页面。
3. 确认所有设置均已按需配置。
   如需启用另一种手势，打开 `More gestures to Settings page`：单指从屏幕左侧 1/8 区域的上 1/4 开始，沿左侧下滑，在下 1/4 区域抬起。
4. 滑动到底部，选择 `Save & Reconnect`。
5. 选择本地 MFi 证书和私钥。
6. 等待连接完成，然后开始使用。

## 当前进度

他运转👍，已在车机/手机平台测试，如果出现部分车机不适配的情况欢迎 issue （并附上你的 log ，位于 `/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`）

## 本地 MFI 文件

在 `MFI certificate & signing` 中通过两个 `Choose` 按钮使用 Android 系统文件选择器
选择证书和私钥。当前支持 DER PKCS#7 证书（`.p7b`）及与之匹配的、未加密 DER PKCS#8
私钥（`.pk8`）。应用会在开始连接手机前校验两者是否匹配，并在 MFI 重连时重新读取文件。

建议把私钥放在受保护的位置。应用不会把证书或私钥复制到偏好设置，只会保存 Android
授予的持久读取权限和文档 URI。

## 工程结构

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 应用模块：宿主界面、设置、资源、Manifest 和打包配置。 |
| `core/` | CarPlay 协议、本地 MFi、iPhone、iAP2、NCM、VPN、AirPlay 和媒体实现。 |

## 环境要求

- 启动 Gradle 需要 JDK 17 或更高版本；daemon 通过 Gradle toolchain 解析 Java 25。
- Android SDK Platform 37。
- Android 9（API 28）或更高版本。
  在 Android 9 上不可用 Wi-Fi P2P 5 GHz 模式，应用会改用 LocalOnlyHotspot。

## 构建

在 Windows PowerShell 中：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :core:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

在 macOS 或 Linux 中：

```bash
./gradlew :core:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

构建未签名 release APK：

```powershell
.\gradlew.bat :app:assembleRelease
```

## 许可证

本项目采用 [GNU General Public License v3.0](LICENSE) 许可。

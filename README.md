<div align="center">
  <img src="https://raw.githubusercontent.com/shilapi/xcertplay/refs/heads/master/asset/xcertplay_small.png" width="180" height="180" alt="xcertplay icon" />
<h1><strong><font size="6">xcertplay</font></strong></h1>
  <a href="README.md">English</a> | <a href="README.zh-CN.md">中文</a>
  <p>An Android head-unit CarPlay receiver using local software MFi authentication, with wired and wireless CarPlay connections.</p>
</div>

## Features

- CarPlay host application for Android head units.
- Local software MFi authentication using a certificate/private-key file pair.
- Wired and wireless CarPlay connections.
- CarPlay Ultra triggering (the protocol stack is untested/incomplete, but it
  can trigger the CarPlay Ultra prompt on an iPhone).
- Voice, navigation, and music multi-channel audio output mapped to the
  corresponding Android channels.
- Dynamic Activity resizing with automatic re-handshaking to the new
  resolution.
- Vehicle head-unit location reporting.
- Android 9 (API 28) support.

## Usage

1. Pair your iPhone with the head unit via Bluetooth.
2. Before a CarPlay video stream starts, tap the Settings button in the lower-right corner. You can also swipe down with three fingers to open Settings.
3. Make sure all the settings are configured as desired.
   To enable another entry gesture, turn on `More gestures to Settings page`. Start with one finger in the upper quarter of the left eighth of the screen, slide down along that strip, and lift in the lower quarter.
4. Scroll to the bottom and select `Save & Reconnect`.
5. Select the local MFi certificate and private key.
6. Wait for the connection to complete, then enjoy.

## Current progress

It works 👍. It has been tested on car head units and phones. If you encounter
an incompatible car head unit, please open an issue and attach your log from
`/sdcard/Android/data/com.shilapi.xcertplay/files/logs/xcertplay.log`.

## Local MFI files

Use the two `Choose` buttons under `MFI certificate & signing` to select the
certificate and private key with Android's system document picker. The supported
formats are a DER PKCS#7 certificate (`.p7b`) and its matching, unencrypted DER
PKCS#8 private key (`.pk8`). The app validates that the files match before
starting the phone connection and reloads them on MFI reconnect.

Store the private key in a protected location. Neither file is copied into app
preferences; only Android's persistent read permission and document URI are
saved.

## Project structure

| Path | Purpose |
| --- | --- |
| `app/` | Android application module: host UI, settings, resources, manifests, and packaging. |
| `core/` | CarPlay protocol, local MFi, iPhone, iAP2, NCM, VPN, AirPlay, and media implementation. |

## Requirements

- JDK 17 or newer to launch Gradle. The daemon resolves Java 25 through the
  Gradle toolchain.
- Android SDK Platform 37.
- Android 9 (API 28) or newer.
  On Android 9, Wi-Fi P2P 5 GHz mode is unavailable and LocalOnlyHotspot is used instead.

## Build

On Windows PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :core:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

On macOS or Linux:

```bash
./gradlew :core:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Unsigned release APKs:

```powershell
.\gradlew.bat :app:assembleRelease
```

## License

Licensed under the [GNU General Public License v3.0](LICENSE).

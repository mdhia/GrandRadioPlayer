# Grand Radio Player

Grand Radio Player is an Android app for listening to the in-game radio stations from your favorite games, right from your phone.

## Key Features
- Play the radio stations from your games' audio files as local "stations", each one automatically detected from a sub-folder of a folder you choose.
- Cover art is picked up automatically from a `cover.png`/`cover.jpg` file in each station's folder, or a placeholder is generated for you if none is found.
- Background playback with full media controls, including a notification with playback controls and support for headset/Bluetooth buttons.
- Android Auto support, so you can browse and play your stations from your car's head unit.

## Installation

### Download Pre-built APK

1. Go to [Releases](https://github.com/mdhia/GrandRadioPlayer/releases/latest).
2. Download the latest `GrandRadioPlayer-<date>.apk`.
3. Enable "Install unknown apps" for your file manager or browser if prompted (Android Settings → Apps → Special app access).
4. Open the APK to install.

*Requires Android 9.0 (API 28) or higher.*

### Build from Source

Prerequisites: JDK 17 and the Android SDK (or Android Studio).

```powershell
git clone https://github.com/mdhia/GrandRadioPlayer.git
cd GrandRadioPlayer
./gradlew assembleDebug
```

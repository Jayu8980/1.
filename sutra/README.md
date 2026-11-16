# Sutra — background automation service (no permission prompts)

A small Android app (Kotlin + Jetpack Compose, minSdk 26, targetSdk 36) that runs a set of automations
in the background **until you stop it**, and never shows a permission dialog.

**Ready-to-install APK:** [`dist/Sutra-0.1.0.apk`](dist/Sutra-0.1.0.apk) (signed with a debug key, for sideloading).

## What it does
Press **Start service** once. From then on it keeps running when you close the app, swipe it from Recents,
or reboot the phone, until you press **Stop** (in the app, on the Quick Settings tile, or on its notification).

| When | Do |
|---|---|
| Charger connected / disconnected | Speak it |
| Battery drops to 20% (once per drop) | Vibrate + speak level |
| Battery full while charging | Speak "unplug the charger" |
| Headphones plugged / unplugged | Toast + log |
| Phone unlocked | Count unlocks today |
| Shake phone (screen on only) | Toggle flashlight |
| Airplane mode / Battery Saver changed | Speak / log (off by default) |
| Every 15 min | Log "still running" (proves it's alive) |

Each automation has an on/off switch, and everything is written to the Activity log on the home screen.

## Permissions — why there is no dialog
All declared permissions are install-time ("normal") ones, granted automatically:
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `VIBRATE`.
`POST_NOTIFICATIONS` is declared but **never requested**: on Android 13+ the status notification stays hidden
unless you turn notifications on yourself in system settings. The service runs either way; Android still lists
it under Settings → Apps → *Active apps* (where it can also be stopped).
The flashlight uses `CameraManager.setTorchMode`, which needs no camera permission.

**Honest limits:** anything that needs a runtime permission (SMS, location, Wi-Fi name, notifications from other
apps, calls, contacts) is deliberately not included. On Xiaomi/HyperOS, Oppo, Vivo, Realme and some Samsung phones
the OEM battery manager can still kill background apps — set Sutra's battery usage to *Unrestricted* / allow
autostart (the app links to the settings page; it does not request anything).

## Build
```bash
cd sutra
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk (R8-shrunk, ~1 MB)
```
Release uses `keystore.properties` (git-ignored: `storeFile`, `storePassword`, `keyAlias`, `keyPassword`)
if present, otherwise the debug key. On Windows use `gradlew.bat` from Android Studio's terminal.
CI: `.github/workflows/sutra-apk.yml` builds both APKs and uploads them as the `sutra-apks` artifact.

## Install
`adb install -r dist/Sutra-0.1.0.apk`, or copy the APK to the phone and open it (allow "Install unknown apps"
for your file manager when asked).

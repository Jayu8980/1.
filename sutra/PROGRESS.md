# Progress

Source spec: the "Sutra" master prompt (iPhone-Shortcuts-style app, milestones M1–M5).
Current request scope: a background-running APK with no permission prompts, running until the user stops it.

## Done (v0.1.0)
- Single `:app` module, Compose + Material 3 (dynamic color), English + Hindi strings.
- `SutraService`: `specialUse` foreground service, START_STICKY, runtime receivers, accelerometer shake
  (paused while screen off), TTS, vibration, torch, 15-min heartbeat. Stop via app / tile / notification.
- `BootReceiver`: restarts after reboot or app update only if the user left it running.
- `SutraTileService`: Quick Settings on/off tile.
- Lint clean (errors), debug + release APK build; CI workflow.

## Not done (from the full spec)
Shortcut editor, execution engine/ContentItem types, Room, Hilt, multi-module split, user-defined
automations, permission-based triggers (SMS, location, notifications, Wi-Fi SSID), widgets, share sheet,
import/export, AI actions, Shizuku/Accessibility. Untested on a physical device (no emulator here).

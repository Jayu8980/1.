# Building TubePilot

## Requirements

| Tool | Version |
|---|---|
| JDK | 17 or 21 |
| Android SDK | Platform 36 (Android 16), Build-Tools 36.0.0 |
| Gradle | 8.14.3 (wrapper included) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |

The APK is built for **arm64-v8a** only (`ndk.abiFilters` in `app/build.gradle.kts`): every Android 16 phone is
64-bit ARM. Remove the filter if you need x86_64 for an emulator.

## 1. Point Gradle at the SDK

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # or set ANDROID_HOME
```

## 2. Debug build

```bash
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk  (package com.tubepilot.app.debug)
```

The debug build uses the debug keystore (`~/.android/debug.keystore`). Register its SHA-1 as a separate Android
OAuth client if you want YouTube sign-in in debug builds (see GOOGLE_CLOUD_SETUP.md).

## 3. Signed release build

1. Create an upload/signing key once and keep it safe; losing it means you cannot update the app:

   ```bash
   keytool -genkeypair -v -keystore tubepilot-release.jks -storetype PKCS12 \
     -alias tubepilot -keyalg RSA -keysize 4096 -validity 9125
   ```

2. Create `keystore.properties` in the project root (it is git-ignored):

   ```properties
   storeFile=/absolute/path/to/tubepilot-release.jks
   storePassword=...
   keyAlias=tubepilot
   keyPassword=...
   ```

3. Build:

   ```bash
   ./gradlew :app:assembleRelease          # APK  → app/build/outputs/apk/release/app-release.apk
   ./gradlew :app:bundleRelease            # AAB for Google Play → app/build/outputs/bundle/release/
   ```

   Release builds run R8 (minify + resource shrinking) with `app/proguard-rules.pro`. Without
   `keystore.properties` the release APK is produced unsigned.

4. Verify the signature and read the certificate fingerprints (you need the SHA-1 for the OAuth client):

   ```bash
   $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   # or from the keystore:
   keytool -list -v -keystore tubepilot-release.jks -alias tubepilot
   ```

5. Install on a phone (USB debugging on):

   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

If you publish through Google Play with Play App Signing, Google re-signs the app: register the **App signing key**
SHA-1 from Play Console → Setup → App integrity as an additional Android OAuth client.

## 4. Tests

```bash
./gradlew testDebugUnitTest
```

60 JVM unit tests cover the LLM gateway (routing, retry, budget pause, kill switch, fallback, cost), the real
Anthropic SDK and OpenAI SSE wire formats against a mock server, the skill registry (enablement, permissions,
validation, logging, sandboxed import), all 14 built-in manifests and their rules, the agent tool loop
(deliverables, fallbacks, disabled skills, kill switch, memory injection), agent rules (conflict resolution,
scheduling, title A/B, QA, SEO limits) and the pipeline state machine.

## 5. Notes for CI and restricted networks

`settings.gradle.kts` lists Google's mirror of Maven Central (`maven-central.storage-download.googleapis.com`) before
`mavenCentral()` because Maven Central rate-limits busy CI runners (HTTP 429).

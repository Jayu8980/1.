# AutoTest Runner (Android 16 app)

An Android app that runs automated UI tests **on the phone itself**, with no PC,
cable or Appium server. It runs the same tests as the PC project in
`../android-appium-java` against Appium's free **ApiDemos** sample app.

**Ready-made APK:** [`release/AutoTestRunner-1.0.0-android16.apk`](release/AutoTestRunner-1.0.0-android16.apk)

| | |
|---|---|
| Target | Android 16 (API 36) |
| Minimum | Android 11 (API 30) |
| CPU | arm64-v8a and every other ABI (pure Java, no native code) |
| Size | about 40 KB |

## How it works

A normal UiAutomator or Espresso test APK cannot start by itself: Android only
lets a PC start it over `adb`. This app uses Android's **Accessibility Service**
instead, the same system that MacroDroid and Tasker use. It lets the app:

- read the screen of another app (buttons, text, IDs, checked state)
- tap, type, scroll and press system keys (Back, Home, Recents, notifications)
- perform real finger gestures (long press, drag and drop, swipe)
- see toast messages and take screenshots

```
Run button ─► TestRunner (background thread)
                 │  opens ApiDemos fresh before each test
                 ▼
              Device  (find / tap / type / swipe / wait / screenshot)
                 │
                 ▼
              AutoService (Accessibility Service) ─► ApiDemos app
```

## Install on the phone, step by step

1. **Copy the APK to the phone** (download it from GitHub, or send it by USB,
   email or Drive) and tap it.
2. **Allow installing unknown apps** when Android asks (for your browser or
   file manager), then tap **Install**.
3. **Install ApiDemos**, the app the tests drive: download
   `ApiDemos-debug.apk` from
   https://github.com/appium/android-apidemos/releases and install it the same way.
   (The app also has a button that opens this page.)
4. **Open AutoTest Runner** and tap **Turn on AutoTest Runner in Accessibility**.
5. In Accessibility settings, tap **AutoTest Runner** (it may be under
   *Downloaded apps* or *Installed apps*) and switch it **on**.
6. **If the switch is greyed out** ("Restricted setting"): Android 13+ blocks
   this for apps installed from files. Go to
   **Settings → Apps → AutoTest Runner → ⋮ (top right) → Allow restricted settings**,
   confirm, then repeat step 5.
7. Back in the app, both lines show **✔**. Tap **Run selected tests**.
8. Don't touch the phone while tests run. ApiDemos opens and the steps happen
   by themselves. At the end the app returns with **PASS/FAIL** for each test.

Failure screenshots are saved to
`Android/data/com.automation.autotest/files/screenshots/`.

Turn the accessibility service off again when you are not testing.

## The tests (30)

| Group | Tests |
|---|---|
| Basics | App launch · checkbox that enables a setting · typing in a dialog and saving · scrolling to an item · Back button |
| Controls | Checkbox · radio buttons · toggle button · on/off switch · dropdown (green, violet, red) · text field · date picker |
| Gestures | Drag and drop · long press menu · expand a list · swipe · slider |
| Dialogs | Accept alert · dismiss alert · list dialog · single-choice dialog · popup menu and toast |
| Device | Home and reopen · background for 3 s · notification panel · quick settings · recent apps · screenshot |

Rotation and the clipboard are in the PC project only: an accessibility service
is not allowed to do them.

## Project structure

```
android-autotest-app/
├── release/AutoTestRunner-1.0.0-android16.apk   Ready-to-install APK
├── app/build.gradle                              SDK levels, signing, shrinking
└── app/src/main/
    ├── AndroidManifest.xml
    ├── res/xml/accessibility_config.xml          What the service is allowed to do
    └── java/com/automation/autotest/
        ├── MainActivity.java     Screen: setup status, test list, results
        ├── AutoService.java      The accessibility service
        ├── Device.java           Automation API: find, tap, type, gestures, waits
        ├── By.java               Locators: text, desc, id, className
        ├── TestCase.java         Test + assertions
        ├── TestRunner.java       Runs tests, screenshots on failure
        └── ApiDemosTests.java    All 30 tests
```

## Writing a new test

Add it in `ApiDemosTests.all()`:

```java
t.add(new TestCase("My group", "Open Animation", d -> {
    d.tap(By.desc("Views"));
    d.tap(By.desc("Animation"));
    assertTrue(d.exists(By.desc("Bouncing Balls")), "Animation screen did not open");
}));
```

`Device` methods: `tap`, `type`, `getText`, `getTextInside`, `isChecked`,
`isEnabled`, `exists`, `waitFor`, `waitUntilGone`, `scrollToText`, `tapAt`,
`longPress`, `doubleTap`, `swipe`, `swipeUp`, `dragAndDrop`, `back`, `home`,
`recents`, `openNotifications`, `openQuickSettings`, `waitForToast`,
`screenshot`, `launchFresh`, `resume`.

To test a different app, change `APP` in `ApiDemosTests.java` and add its
package to `<queries>` in `AndroidManifest.xml`.

## Build it yourself

Needs JDK 17+ and the Android SDK (platform 36).

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`.

**Signing:** release builds use the key described in `keystore.properties`:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=autotest
keyPassword=...
```

Create a key with:

```bash
keytool -genkeypair -keystore release.keystore -alias autotest -keyalg RSA -keysize 4096 -validity 10000
```

Without `keystore.properties` the release build is signed with the debug key.
Neither the key nor `keystore.properties` is committed. An APK signed with a
different key can't update the installed app: uninstall it first.

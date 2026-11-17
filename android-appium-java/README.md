# Android Automation Project (Java + Appium + TestNG)

A complete, ready-to-run Android test automation project using the
**Page Object Model**. The tests run against Appium's free sample app
(**ApiDemos**), so you can try everything before using your own app.

## Project structure

```
android-appium-java/
├── pom.xml                         Maven: libraries and build settings
├── testng.xml                      Which test classes to run
├── apps/                           Put the APK file here
└── src/test/
    ├── resources/
    │   └── config.properties       Device, app and server settings
    └── java/com/automation/
        ├── utils/ConfigReader.java       Reads config.properties
        ├── drivers/DriverFactory.java    Starts / stops the Appium driver
        ├── base/BasePage.java            Common actions: tap, type, wait, scroll
        ├── base/BaseTest.java            Opens app before each test, closes after
        ├── pages/                        One class per app screen
        │   ├── HomePage.java
        │   ├── PreferencePage.java
        │   ├── PreferenceDependenciesPage.java
        │   └── ViewsPage.java
        ├── tests/                        The actual tests
        │   ├── PreferenceTest.java
        │   └── ViewsTest.java
        └── listeners/TestListener.java   Screenshot on failure
```

## Setup, step by step

### Step 1: Install Java (JDK 17 or newer)
Download from https://adoptium.net and set `JAVA_HOME`. Check:
```bash
java -version
```

### Step 2: Install Maven
Download from https://maven.apache.org and add its `bin` folder to `Path`. Check:
```bash
mvn -v
```

### Step 3: Install Android Studio
Download from https://developer.android.com/studio, then set:
```
ANDROID_HOME = C:\Users\<you>\AppData\Local\Android\Sdk
Path += %ANDROID_HOME%\platform-tools
```

### Step 4: Start an emulator or connect a phone
- **Emulator:** Android Studio → Device Manager → Create Device → Start.
- **Real phone:** Settings → About Phone → tap Build Number 7 times →
  Developer Options → turn on USB Debugging → connect by USB.

Check that the device is visible:
```bash
adb devices
```
Copy the device name (for example `emulator-5554`).

### Step 5: Install and start Appium
```bash
npm install -g appium
appium driver install uiautomator2
appium
```
Leave this terminal open. The server runs at `http://127.0.0.1:4723`.

### Step 6: Download the sample app
Download `ApiDemos-debug.apk` from
https://github.com/appium/android-apidemos/releases
and put it in the `apps/` folder.

### Step 7: Edit the settings
Open `src/test/resources/config.properties` and set `device.name`
to the name from Step 4.

### Step 8: Run the tests
In a second terminal, inside this folder:
```bash
mvn test
```
The app opens on the device and the steps run by themselves.

Run only one test class:
```bash
mvn test -Dtest=PreferenceTest
```
Use a different device without editing the file:
```bash
mvn test -Ddevice.name=R58M123ABC
```

### Step 9: See the results
- Console: `✔ PASS` / `✘ FAIL` for each test.
- Report: `target/surefire-reports/index.html`
- Failure screenshots: `screenshots/`

## What the tests do

| Test | Steps |
|---|---|
| `appLaunches` | Opens the app and checks the home screen |
| `wifiCheckboxEnablesSettings` | Preference → Preference dependencies → toggles the WiFi checkbox and checks that "WiFi settings" turns on/off |
| `saveWifiName` | Opens the WiFi dialog, types a name, taps OK, reopens it and checks the name was saved |
| `scrollToWebView` | Views → scrolls down the long list to "WebView" |
| `backReturnsHome` | Opens Views, presses Back, checks the home screen |

## Use it for your own app

1. Put your APK in `apps/` and change `app.path` in `config.properties`
   (or clear `app.path` and set `app.package` and `app.activity`).
2. Open your app in **Appium Inspector** and note the element IDs.
3. Add one class in `pages/` per screen, extending `BasePage`:
   ```java
   public class LoginPage extends BasePage {
       private final By email = By.id("com.myapp:id/email");
       private final By password = By.id("com.myapp:id/password");
       private final By loginBtn = By.id("com.myapp:id/login");

       public void login(String user, String pass) {
           type(email, user);
           type(password, pass);
           tap(loginBtn);
       }
   }
   ```
4. Add a test class in `tests/`, extending `BaseTest`:
   ```java
   public class LoginTest extends BaseTest {
       @Test
       public void validLogin() {
           new LoginPage().login("test@mail.com", "12345");
           Assert.assertTrue(new DashboardPage().isLoaded());
       }
   }
   ```
5. Add the class to `testng.xml`.

## Common problems

| Problem | Fix |
|---|---|
| `Connection refused` | Appium server is not running (Step 5) |
| `APK not found` | The APK is missing from `apps/` (Step 6) |
| `adb devices` shows nothing | Turn on USB Debugging and allow the popup on the phone |
| `Could not find a connected Android device` | `device.name` is wrong or the emulator is not started |
| Element not found | Check the locator in Appium Inspector |

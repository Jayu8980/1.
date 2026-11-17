package com.automation.autotest;

import static com.automation.autotest.TestCase.assertEquals;
import static com.automation.autotest.TestCase.assertFalse;
import static com.automation.autotest.TestCase.assertTrue;

import android.graphics.Rect;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * All automated tests, run against Appium's free ApiDemos sample app.
 * These are the same tests as the PC (Appium) project, rewritten to run
 * on the phone. Every test starts with ApiDemos opened fresh on its home screen.
 */
public final class ApiDemosTests {

    public static final String APP = "io.appium.android.apis";
    private static final String ID = APP + ":id/";

    // Home screen menu items
    private static final By HOME = By.desc("Preference");
    private static final By VIEWS = By.desc("Views");
    private static final By APP_MENU = By.desc("App");

    // Standard Android dialog parts
    private static final By OK = By.id("android:id/button1");
    private static final By CANCEL = By.id("android:id/button2");
    private static final By DIALOG_TITLE = By.id("android:id/alertTitle");
    private static final By DIALOG_MESSAGE = By.id("android:id/message");

    private ApiDemosTests() {
    }

    public static List<TestCase> all(File screenshotDir) {
        List<TestCase> t = new ArrayList<>();

        // ================================================== Basics
        t.add(new TestCase("Basics", "App opens on the home screen", d ->
                assertTrue(d.exists(HOME) || d.waitFor(HOME) != null, "Home screen did not load")));

        t.add(new TestCase("Basics", "Checkbox enables another setting", d -> {
            openPreferenceDependencies(d);
            By wifi = By.id("android:id/checkbox");
            By wifiSettings = By.text("WiFi settings");

            if (d.isChecked(wifi)) d.tap(wifi);
            assertFalse(d.isEnabled(wifiSettings), "WiFi settings should be disabled when WiFi is off");

            d.tap(wifi);
            assertTrue(d.isEnabled(wifiSettings), "WiFi settings should be enabled when WiFi is on");
        }));

        t.add(new TestCase("Basics", "Type in a dialog and save", d -> {
            openPreferenceDependencies(d);
            By wifi = By.id("android:id/checkbox");
            By field = By.id("android:id/edit");
            if (!d.isChecked(wifi)) d.tap(wifi);

            d.tap(By.text("WiFi settings"));
            assertEquals(d.getText(DIALOG_TITLE), "WiFi settings", "Wrong dialog title");
            d.type(field, "MyHomeWifi");
            d.tap(OK);

            d.tap(By.text("WiFi settings"));
            assertEquals(d.getText(field), "MyHomeWifi", "Saved WiFi name is wrong");
            d.tap(OK);
        }));

        t.add(new TestCase("Basics", "Scroll a long list to an item", d -> {
            d.tap(VIEWS);
            d.waitFor(By.desc("Animation"));
            assertTrue(d.scrollToText("WebView") != null, "WebView not found after scrolling");
        }));

        t.add(new TestCase("Basics", "Back button returns home", d -> {
            d.tap(VIEWS);
            d.waitFor(By.desc("Animation"));
            d.back();
            assertTrue(d.waitFor(HOME) != null, "Did not return to the home screen");
        }));

        // ================================================== Controls
        t.add(new TestCase("Controls", "Checkbox", d -> {
            openLightThemeControls(d);
            By box = By.id(ID + "check1");
            boolean before = d.isChecked(box);
            d.tap(box);
            assertTrue(d.isChecked(box) != before, "Checkbox did not change");
        }));

        t.add(new TestCase("Controls", "Radio buttons (only one selected)", d -> {
            openLightThemeControls(d);
            By r1 = By.id(ID + "radio1");
            By r2 = By.id(ID + "radio2");
            d.tap(r1);
            assertTrue(d.isChecked(r1), "Radio 1 not selected");
            d.tap(r2);
            assertTrue(d.isChecked(r2), "Radio 2 not selected");
            assertFalse(d.isChecked(r1), "Radio 1 should be cleared");
        }));

        t.add(new TestCase("Controls", "Toggle button", d -> {
            openLightThemeControls(d);
            By toggle = By.id(ID + "toggle1");
            boolean before = d.isChecked(toggle);
            d.tap(toggle);
            assertTrue(d.isChecked(toggle) != before, "Toggle did not change");
        }));

        t.add(new TestCase("Controls", "On/off switch", d -> {
            openViewsItem(d, "Switches");
            By sw = By.className("android.widget.Switch");
            boolean before = d.isChecked(sw);
            d.tap(sw);
            assertTrue(d.isChecked(sw) != before, "Switch did not change");
        }));

        for (String color : new String[]{"green", "violet", "red"}) {
            t.add(new TestCase("Controls", "Dropdown: pick " + color, d -> {
                openViewsItem(d, "Spinner");
                By spinner = By.id(ID + "spinner1");
                String other = color.equals("red") ? "blue" : "red";
                d.tap(spinner);                 // first pick another value, so the
                d.tap(By.text(other));          // test proves the selection changes
                d.tap(spinner);
                d.tap(By.text(color));
                assertEquals(d.getTextInside(spinner), color, "Dropdown shows the wrong value");
            }));
        }

        t.add(new TestCase("Controls", "Text field", d -> {
            openViewsItem(d, "TextFields");
            By field = By.id(ID + "edit");
            d.type(field, "Hello Appium");
            assertEquals(d.getText(field), "Hello Appium", "Text field content is wrong");
        }));

        t.add(new TestCase("Controls", "Date picker", d -> {
            openViewsItem(d, "Date Widgets");
            d.tap(By.desc("1. Dialog"));
            d.tap(By.desc("change the date"));
            d.tap(By.text("15"));
            d.tap(OK);
            String shown = d.getText(By.id(ID + "dateDisplay"));
            assertTrue(shown.contains("-15-"), "Date shown was: " + shown);
        }));

        // ================================================== Gestures
        t.add(new TestCase("Gestures", "Drag and drop", d -> {
            openViewsItem(d, "Drag and Drop");
            d.dragAndDrop(By.id(ID + "drag_dot_1"), By.id(ID + "drag_dot_2"));
            assertEquals(d.getText(By.id(ID + "drag_result_text")), "Dropped!", "Drop not detected");
        }));

        t.add(new TestCase("Gestures", "Long press opens a menu", d -> {
            openViewsItem(d, "Expandable Lists");
            d.tap(By.desc("1. Custom Adapter"));
            d.longPress(By.text("People Names"));
            assertTrue(d.waitFor(By.text("Sample menu")) != null, "Context menu did not open");
            d.back();
        }));

        t.add(new TestCase("Gestures", "Tap to expand a list", d -> {
            openViewsItem(d, "Expandable Lists");
            d.tap(By.desc("1. Custom Adapter"));
            d.tap(By.text("People Names"));
            assertTrue(d.waitFor(By.text("Arnold")) != null, "Group did not expand");
        }));

        t.add(new TestCase("Gestures", "Swipe up on a list", d -> {
            d.tap(VIEWS);
            d.waitFor(By.desc("Animation"));
            d.swipeUp();
            assertTrue(d.waitUntilGone(By.desc("Animation"), 3000), "List did not move after swipe");
        }));

        t.add(new TestCase("Gestures", "Slider (seek bar)", d -> {
            openViewsItem(d, "Seek Bar");
            By bar = By.id(ID + "seek");
            By progress = By.id(ID + "progress");
            Rect r = d.bounds(bar);

            d.tapAt(r.left + (int) (r.width() * 0.9), r.centerY());
            int high = Integer.parseInt(d.getText(progress).split(" ")[0]);
            d.tapAt(r.left + (int) (r.width() * 0.1), r.centerY());
            int low = Integer.parseInt(d.getText(progress).split(" ")[0]);

            assertTrue(high > low, "Slider did not move: high=" + high + " low=" + low);
        }));

        // ================================================== Dialogs
        t.add(new TestCase("Dialogs", "Accept an alert (OK)", d -> {
            openAlertDialogs(d);
            d.tap(By.desc("OK Cancel dialog with a message"));
            d.waitFor(OK);
            d.tap(OK);
            assertTrue(d.waitUntilGone(OK, 3000), "Dialog did not close");
        }));

        t.add(new TestCase("Dialogs", "Dismiss an alert (Cancel)", d -> {
            openAlertDialogs(d);
            d.tap(By.desc("OK Cancel dialog with a message"));
            d.tap(CANCEL);
            assertTrue(d.waitUntilGone(CANCEL, 3000), "Dialog did not close");
        }));

        t.add(new TestCase("Dialogs", "List dialog", d -> {
            openAlertDialogs(d);
            d.tap(By.desc("List dialog"));
            d.tap(By.text("Command two"));
            String msg = d.getText(DIALOG_MESSAGE);
            assertTrue(msg.contains("Command two"), "Message was: " + msg);
            d.back();
        }));

        t.add(new TestCase("Dialogs", "Single-choice dialog", d -> {
            openAlertDialogs(d);
            d.tap(By.desc("Single choice list"));
            d.tap(By.text("Satellite"));
            assertTrue(d.isChecked(By.text("Satellite")), "Satellite not selected");
            d.tap(OK);
        }));

        t.add(new TestCase("Dialogs", "Popup menu and toast message", d -> {
            openViewsItem(d, "Popup Menu");
            d.tap(By.desc("Make a Popup!"));
            long since = System.currentTimeMillis();
            d.tap(By.text("Search"));
            String toast = d.waitForToast(since, 5000);
            assertTrue(toast.contains("Search"), "Toast was: " + toast);
        }));

        // ================================================== Device
        t.add(new TestCase("Device", "Home key, then reopen the app", d -> {
            d.waitFor(HOME);
            d.home();
            assertFalse(APP.equals(d.foregroundPackage()), "App should be in the background");
            d.resume(APP);
            assertTrue(d.waitFor(HOME) != null, "App did not come back");
        }));

        t.add(new TestCase("Device", "App in background for 3 seconds", d -> {
            d.tap(VIEWS);
            d.waitFor(By.desc("Animation"));
            d.home();
            Device.sleep(3000);
            d.resume(APP);
            assertTrue(d.waitFor(By.desc("Animation")) != null, "App did not resume where it was");
        }));

        t.add(new TestCase("Device", "Notification panel", d -> {
            d.waitFor(HOME);
            d.openNotifications();
            assertEquals(d.foregroundPackage(), "com.android.systemui", "Notification panel did not open");
            d.back();
            assertTrue(d.waitFor(HOME) != null, "Did not return to the app");
        }));

        t.add(new TestCase("Device", "Quick settings panel", d -> {
            d.waitFor(HOME);
            d.openQuickSettings();
            assertEquals(d.foregroundPackage(), "com.android.systemui", "Quick settings did not open");
            d.back();
            d.back();
            assertTrue(d.waitFor(HOME) != null, "Did not return to the app");
        }));

        t.add(new TestCase("Device", "Recent apps screen", d -> {
            d.waitFor(HOME);
            d.recents();
            assertFalse(APP.equals(d.foregroundPackage()), "Recents screen did not open");
            d.back();
            d.resume(APP);
            assertTrue(d.waitFor(HOME) != null, "Did not return to the app");
        }));

        t.add(new TestCase("Device", "Take a screenshot", d -> {
            d.waitFor(HOME);
            File f = d.screenshot(screenshotDir, "home_screen");
            assertTrue(f.length() > 0, "Screenshot file is empty");
        }));

        return t;
    }

    // ------------------------------------------------ navigation helpers

    private static void openPreferenceDependencies(Device d) {
        d.tap(HOME);
        d.tap(By.desc("3. Preference dependencies"));
        d.waitFor(By.id("android:id/checkbox"));
    }

    private static void openViewsItem(Device d, String item) {
        d.tap(VIEWS);
        d.waitFor(By.desc("Animation"));
        d.scrollToTextAndTap(item);
    }

    private static void openLightThemeControls(Device d) {
        openViewsItem(d, "Controls");
        d.tap(By.desc("1. Light Theme"));
        d.waitFor(By.id(ID + "check1"));
    }

    private static void openAlertDialogs(Device d) {
        d.tap(APP_MENU);
        d.tap(By.desc("Alert Dialogs"));
        d.waitFor(By.desc("OK Cancel dialog with a message"));
    }
}

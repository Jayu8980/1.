package com.automation.autotest;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The automation API the tests use. It plays the role of Appium's driver,
 * but runs on the phone itself through the accessibility service.
 */
public class Device {

    public static final long DEFAULT_WAIT_MS = 10_000;
    private static final long POLL_MS = 200;

    private final AutoService service;

    public Device(AutoService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ apps

    /** Starts an app fresh on its first screen (clears its back stack). */
    public void launchFresh(String packageName) {
        launch(packageName, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /** Brings an app back to the front without resetting it. */
    public void resume(String packageName) {
        launch(packageName, Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private void launch(String packageName, int flags) {
        Intent intent = service.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            throw new AssertionError("App not installed: " + packageName);
        }
        intent.addFlags(flags);
        service.startActivity(intent);
        sleep(800);
    }

    public static boolean isInstalled(Context context, String packageName) {
        return context.getPackageManager().getLaunchIntentForPackage(packageName) != null;
    }

    /** Package name of the app currently in front, e.g. com.android.systemui. */
    public String foregroundPackage() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    // -------------------------------------------------------------- finding

    /** Finds the first matching element on screen (all windows, top first), or null. */
    public AccessibilityNodeInfo find(By by) {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        // Topmost windows first so dialogs and popups win over the screen behind them.
        windows.sort((a, b) -> Integer.compare(b.getLayer(), a.getLayer()));
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo found = search(window.getRoot(), by);
            if (found != null) {
                return found;
            }
        }
        return search(service.getRootInActiveWindow(), by);
    }

    private static AccessibilityNodeInfo search(AccessibilityNodeInfo root, By by) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (by.matches(node) && node.isVisibleToUser()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    public boolean exists(By by) {
        return find(by) != null;
    }

    /** Waits until the element appears, like Appium's explicit wait. */
    public AccessibilityNodeInfo waitFor(By by, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo node = find(by);
            if (node != null) {
                return node;
            }
            sleep(POLL_MS);
        }
        throw new AssertionError("Element not found after " + timeoutMs / 1000 + "s: " + by);
    }

    public AccessibilityNodeInfo waitFor(By by) {
        return waitFor(by, DEFAULT_WAIT_MS);
    }

    public boolean waitUntilGone(By by, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (find(by) == null) {
                return true;
            }
            sleep(POLL_MS);
        }
        return false;
    }

    // ---------------------------------------------------------------- reading

    public String getText(By by) {
        AccessibilityNodeInfo node = waitFor(by);
        node.refresh();
        return node.getText() == null ? "" : node.getText().toString();
    }

    /** Text of the first child element that has text, e.g. the value shown in a dropdown. */
    public String getTextInside(By by) {
        AccessibilityNodeInfo node = waitFor(by);
        node.refresh();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(node);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n != node && n.getText() != null && n.getText().length() > 0) {
                return n.getText().toString();
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return "";
    }

    public boolean isChecked(By by) {
        AccessibilityNodeInfo node = waitFor(by);
        node.refresh();
        return node.isChecked();
    }

    /** Enabled only if the element and its parent row are both enabled. */
    public boolean isEnabled(By by) {
        AccessibilityNodeInfo node = waitFor(by);
        node.refresh();
        AccessibilityNodeInfo parent = node.getParent();
        return node.isEnabled() && (parent == null || parent.isEnabled());
    }

    public Rect bounds(By by) {
        Rect r = new Rect();
        waitFor(by).getBoundsInScreen(r);
        return r;
    }

    // ---------------------------------------------------------------- actions

    /** Taps an element. Uses the accessibility click, or a real finger tap if needed. */
    public void tap(By by) {
        AccessibilityNodeInfo node = waitFor(by);
        AccessibilityNodeInfo target = node;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target == null || !target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            tapAt(r.centerX(), r.centerY());
        }
        sleep(400);
    }

    /** Types into a text field (replaces what is there). */
    public void type(By by, String text) {
        AccessibilityNodeInfo node = waitFor(by);
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            throw new AssertionError("Could not type into " + by);
        }
        sleep(300);
    }

    /** Scrolls the first scrollable list down until the text is on screen. */
    public AccessibilityNodeInfo scrollToText(String text) {
        By target = By.text(text);
        for (int i = 0; i < 20; i++) {
            AccessibilityNodeInfo node = find(target);
            if (node != null) {
                return node;
            }
            AccessibilityNodeInfo list = findScrollable(service.getRootInActiveWindow());
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                break;
            }
            sleep(500);
        }
        throw new AssertionError("Could not scroll to \"" + text + "\"");
    }

    public void scrollToTextAndTap(String text) {
        scrollToText(text);
        tap(By.text(text));
    }

    private static AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo node = queue.poll();
            if (node.isScrollable()) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return null;
    }

    // --------------------------------------------------------------- gestures

    public void tapAt(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        gesture(new GestureDescription.StrokeDescription(p, 0, 60));
        sleep(300);
    }

    public void longPress(By by) {
        Rect r = bounds(by);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        gesture(new GestureDescription.StrokeDescription(p, 0, 1500));
        sleep(500);
    }

    public void doubleTap(By by) {
        Rect r = bounds(by);
        tapAt(r.centerX(), r.centerY());
        tapAt(r.centerX(), r.centerY());
    }

    /** Swipe from one point to another over the given time. */
    public void swipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        gesture(new GestureDescription.StrokeDescription(p, 0, durationMs));
        sleep(600);
    }

    /** Swipe up across the middle of the screen (like scrolling a list down). */
    public void swipeUp() {
        int w = screenWidth(), h = screenHeight();
        swipe(w / 2, (int) (h * 0.75), w / 2, (int) (h * 0.30), 400);
    }

    /** Press and hold on one element, then move the finger onto another and release. */
    public void dragAndDrop(By from, By to) {
        Rect a = bounds(from);
        Rect b = bounds(to);

        Path hold = new Path();
        hold.moveTo(a.centerX(), a.centerY());
        hold.lineTo(a.centerX() + 1, a.centerY() + 1);
        GestureDescription.StrokeDescription press =
                new GestureDescription.StrokeDescription(hold, 0, 1200, true);
        gesture(press);

        Path move = new Path();
        move.moveTo(a.centerX() + 1, a.centerY() + 1);
        move.lineTo(b.centerX(), b.centerY());
        gesture(press.continueStroke(move, 0, 1200, false));
        sleep(800);
    }

    private void gesture(GestureDescription.StrokeDescription stroke) {
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            boolean sent = service.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok.set(true);
                    done.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    done.countDown();
                }
            }, main);
            if (!sent) {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS) || !ok.get()) {
                throw new AssertionError("Gesture was cancelled by the system");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted");
        }
    }

    // ---------------------------------------------------------- system keys

    public void back() {
        global(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    public void home() {
        global(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    public void recents() {
        global(AccessibilityService.GLOBAL_ACTION_RECENTS);
    }

    public void openNotifications() {
        global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }

    public void openQuickSettings() {
        global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
    }

    private void global(int action) {
        if (!service.performGlobalAction(action)) {
            throw new AssertionError("System action failed: " + action);
        }
        sleep(1000);
    }

    // ------------------------------------------------------------- toasts

    /** Waits for a toast that appeared after `sinceMs` and returns its text. */
    public String waitForToast(long sinceMs, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (AutoService.lastToastTime() >= sinceMs) {
                return AutoService.lastToast();
            }
            sleep(POLL_MS);
        }
        throw new AssertionError("No toast message appeared");
    }

    // ------------------------------------------------------------ screenshot

    /** Saves a PNG of the whole screen and returns the file. */
    public File screenshot(File dir, String name) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> result = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult shot) {
                        result.set(Bitmap.wrapHardwareBuffer(shot.getHardwareBuffer(), shot.getColorSpace()));
                        shot.getHardwareBuffer().close();
                        done.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        error.set("error code " + errorCode);
                        done.countDown();
                    }
                });
        try {
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Bitmap bitmap = result.get();
        if (bitmap == null) {
            throw new AssertionError("Screenshot failed: " + error.get());
        }
        dir.mkdirs();
        File file = new File(dir, name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            throw new AssertionError("Could not save screenshot: " + e.getMessage());
        }
        return file;
    }

    // ---------------------------------------------------------------- misc

    public int screenWidth() {
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        return m.widthPixels;
    }

    public int screenHeight() {
        DisplayMetrics m = service.getResources().getDisplayMetrics();
        return m.heightPixels;
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.automation.autotest;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * The accessibility service. Android binds it once the user turns it on in
 * Settings -> Accessibility. It gives the app the power to read other apps'
 * screens and to tap, type and swipe in them. All the logic lives in Device.
 */
public class AutoService extends AccessibilityService {

    private static volatile AutoService instance;

    /** Last toast message seen on screen, and when. */
    private static volatile String lastToast = "";
    private static volatile long lastToastTime = 0;

    public static AutoService get() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static String lastToast() {
        return lastToast;
    }

    public static long lastToastTime() {
        return lastToastTime;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Toasts are not part of any window tree; they only arrive as events.
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && event.getClassName() != null
                && event.getClassName().toString().contains("Toast")) {
            List<CharSequence> text = event.getText();
            lastToast = text == null || text.isEmpty() ? "" : String.valueOf(text.get(0));
            lastToastTime = System.currentTimeMillis();
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing to stop: tests run on their own thread.
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }
}

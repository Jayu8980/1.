package com.automation.autotest;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Locators, the same idea as Appium's By / AppiumBy:
 *   By.text("OK")                       visible text
 *   By.desc("Preference")               content-description (accessibility id)
 *   By.id("android:id/button1")         resource-id
 *   By.className("android.widget.Switch")
 */
public final class By {

    private enum Kind { TEXT, TEXT_CONTAINS, DESC, ID, CLASS }

    private final Kind kind;
    private final String value;

    private By(Kind kind, String value) {
        this.kind = kind;
        this.value = value;
    }

    public static By text(String text) {
        return new By(Kind.TEXT, text);
    }

    public static By textContains(String text) {
        return new By(Kind.TEXT_CONTAINS, text);
    }

    public static By desc(String accessibilityId) {
        return new By(Kind.DESC, accessibilityId);
    }

    public static By id(String resourceId) {
        return new By(Kind.ID, resourceId);
    }

    public static By className(String className) {
        return new By(Kind.CLASS, className);
    }

    boolean matches(AccessibilityNodeInfo node) {
        switch (kind) {
            case TEXT:
                return node.getText() != null && value.equals(node.getText().toString());
            case TEXT_CONTAINS:
                return node.getText() != null && node.getText().toString().contains(value);
            case DESC:
                return node.getContentDescription() != null
                        && value.equals(node.getContentDescription().toString());
            case ID:
                return value.equals(node.getViewIdResourceName());
            case CLASS:
                return node.getClassName() != null && value.equals(node.getClassName().toString());
            default:
                return false;
        }
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + "=\"" + value + "\"";
    }
}

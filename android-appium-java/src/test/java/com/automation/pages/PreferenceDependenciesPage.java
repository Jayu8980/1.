package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

/**
 * Screen with a "WiFi" checkbox. When checked, "WiFi settings" becomes
 * enabled and opens a dialog with a text field: like a small form/login flow.
 */
public class PreferenceDependenciesPage extends BasePage {

    private final By wifiCheckbox = By.id("android:id/checkbox");
    private final By wifiSettings = AppiumBy.androidUIAutomator("new UiSelector().text(\"WiFi settings\")");
    private final By dialogTitle = By.id("android:id/alertTitle");
    private final By dialogInput = By.id("android:id/edit");
    private final By okButton = By.id("android:id/button1");

    public boolean isWifiChecked() {
        WebElement box = waitForVisible(wifiCheckbox);
        return Boolean.parseBoolean(box.getAttribute("checked"));
    }

    public PreferenceDependenciesPage setWifi(boolean on) {
        if (isWifiChecked() != on) {
            tap(wifiCheckbox);
        }
        return this;
    }

    public boolean isWifiSettingsEnabled() {
        return Boolean.parseBoolean(waitForVisible(wifiSettings).getAttribute("enabled"));
    }

    public PreferenceDependenciesPage openWifiSettings() {
        tap(wifiSettings);
        return this;
    }

    public String getDialogTitle() {
        return getText(dialogTitle);
    }

    public PreferenceDependenciesPage enterWifiName(String name) {
        type(dialogInput, name);
        return this;
    }

    public PreferenceDependenciesPage tapOk() {
        tap(okButton);
        return this;
    }

    /** Reads back the saved value by reopening the dialog. */
    public String readSavedWifiName() {
        openWifiSettings();
        String value = getText(dialogInput);
        tapOk();
        return value;
    }
}

package com.automation.drivers;

import com.automation.utils.ConfigReader;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.options.UiAutomator2Options;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * Creates and stores the AndroidDriver.
 * ThreadLocal keeps one driver per thread, so tests can later run in parallel.
 */
public final class DriverFactory {

    private static final ThreadLocal<AndroidDriver> DRIVER = new ThreadLocal<>();

    private DriverFactory() {
    }

    public static void initDriver() {
        UiAutomator2Options options = new UiAutomator2Options()
                .setPlatformName(ConfigReader.get("platform.name"))
                .setAutomationName(ConfigReader.get("automation.name"))
                .setDeviceName(ConfigReader.get("device.name"))
                .setAutoGrantPermissions(true)
                .setNewCommandTimeout(Duration.ofSeconds(120));

        String appPath = ConfigReader.get("app.path");
        if (!appPath.isEmpty()) {
            File apk = new File(appPath);
            if (!apk.exists()) {
                throw new IllegalStateException("APK not found: " + apk.getAbsolutePath()
                        + " (see README: Step 6)");
            }
            options.setApp(apk.getAbsolutePath());
        } else {
            options.setAppPackage(ConfigReader.get("app.package"));
            options.setAppActivity(ConfigReader.get("app.activity"));
        }

        DRIVER.set(new AndroidDriver(serverUrl(), options));
    }

    public static AndroidDriver getDriver() {
        AndroidDriver driver = DRIVER.get();
        if (driver == null) {
            throw new IllegalStateException("Driver not started. Call initDriver() first.");
        }
        return driver;
    }

    public static boolean hasDriver() {
        return DRIVER.get() != null;
    }

    public static void quitDriver() {
        AndroidDriver driver = DRIVER.get();
        if (driver != null) {
            driver.quit();
            DRIVER.remove();
        }
    }

    private static URL serverUrl() {
        try {
            return URI.create(ConfigReader.get("appium.server.url")).toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Bad appium.server.url in config.properties", e);
        }
    }
}

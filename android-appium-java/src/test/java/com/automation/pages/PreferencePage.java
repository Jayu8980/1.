package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Preference menu screen. */
public class PreferencePage extends BasePage {

    private final By preferenceDependencies =
            AppiumBy.accessibilityId("3. Preference dependencies");

    public PreferenceDependenciesPage openPreferenceDependencies() {
        tap(preferenceDependencies);
        return new PreferenceDependenciesPage();
    }
}

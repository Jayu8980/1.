package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** First screen of ApiDemos: a list of menu items. */
public class HomePage extends BasePage {

    private final By preferenceMenu = AppiumBy.accessibilityId("Preference");
    private final By viewsMenu = AppiumBy.accessibilityId("Views");
    private final By appMenu = AppiumBy.accessibilityId("App");

    public boolean isLoaded() {
        return isDisplayed(preferenceMenu);
    }

    public PreferencePage openPreference() {
        tap(preferenceMenu);
        return new PreferencePage();
    }

    public AlertDialogsPage openAlertDialogs() {
        tap(appMenu);
        tap(AppiumBy.accessibilityId("Alert Dialogs"));
        return new AlertDialogsPage();
    }

    public ViewsPage openViews() {
        tap(viewsMenu);
        return new ViewsPage();
    }
}

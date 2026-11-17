package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Controls -> 1. Light Theme: checkbox, radio buttons, toggle. */
public class ControlsPage extends BasePage {

    private final By checkbox1 = By.id("io.appium.android.apis:id/check1");
    private final By radio1 = By.id("io.appium.android.apis:id/radio1");
    private final By radio2 = By.id("io.appium.android.apis:id/radio2");
    private final By toggle1 = By.id("io.appium.android.apis:id/toggle1");

    public ControlsPage() {
        hideKeyboardIfShown();
    }

    public ControlsPage tapCheckbox() {
        tap(checkbox1);
        return this;
    }

    public boolean isCheckboxChecked() {
        return isChecked(checkbox1);
    }

    public ControlsPage selectRadio1() {
        tap(radio1);
        return this;
    }

    public ControlsPage selectRadio2() {
        tap(radio2);
        return this;
    }

    public boolean isRadio1Selected() {
        return isChecked(radio1);
    }

    public boolean isRadio2Selected() {
        return isChecked(radio2);
    }

    public ControlsPage tapToggle() {
        tap(toggle1);
        return this;
    }

    public boolean isToggleOn() {
        return isChecked(toggle1);
    }
}

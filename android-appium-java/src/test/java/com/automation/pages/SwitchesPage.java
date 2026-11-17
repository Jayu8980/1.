package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Switches: on/off switch. */
public class SwitchesPage extends BasePage {

    private final By firstSwitch = By.className("android.widget.Switch");

    public SwitchesPage tapSwitch() {
        tap(firstSwitch);
        return this;
    }

    public boolean isSwitchOn() {
        return isChecked(firstSwitch);
    }
}

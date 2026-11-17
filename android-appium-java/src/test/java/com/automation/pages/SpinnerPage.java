package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Spinner: dropdown lists. */
public class SpinnerPage extends BasePage {

    private final By colorSpinner = By.id("io.appium.android.apis:id/spinner1");
    private final By selectedColor =
            By.xpath("//*[@resource-id='io.appium.android.apis:id/spinner1']/android.widget.TextView");

    public SpinnerPage selectColor(String color) {
        tap(colorSpinner);
        tapText(color);
        return this;
    }

    public String getSelectedColor() {
        return getText(selectedColor);
    }
}

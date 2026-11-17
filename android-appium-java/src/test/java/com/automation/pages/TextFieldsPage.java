package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> TextFields: typing and the keyboard. */
public class TextFieldsPage extends BasePage {

    private final By field = By.id("io.appium.android.apis:id/edit");

    public TextFieldsPage typeText(String text) {
        type(field, text);
        hideKeyboardIfShown();
        return this;
    }

    public String getFieldText() {
        return getText(field);
    }

    public boolean isKeyboardShown() {
        return driver.isKeyboardShown();
    }
}

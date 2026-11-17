package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;

/** Views -> Popup Menu: popup menu and toast message. */
public class PopupMenuPage extends BasePage {

    private final By makePopup = AppiumBy.accessibilityId("Make a Popup!");
    private final By toast = By.xpath("//android.widget.Toast");

    public PopupMenuPage chooseMenuItem(String item) {
        tap(makePopup);
        tapText(item);
        return this;
    }

    /** A toast is the small message that appears for 2 seconds and vanishes. */
    public String getToastText() {
        return wait.until(ExpectedConditions.presenceOfElementLocated(toast)).getAttribute("name");
    }
}

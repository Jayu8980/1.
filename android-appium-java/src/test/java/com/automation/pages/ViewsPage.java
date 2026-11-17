package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Long scrollable list of view examples. Used to show scrolling. */
public class ViewsPage extends BasePage {

    private final By firstItem = AppiumBy.accessibilityId("Animation");

    public boolean isLoaded() {
        return isDisplayed(firstItem);
    }

    /** Scrolls down to an item by its text and returns whether it is visible. */
    public boolean scrollToItem(String itemText) {
        return scrollToText(itemText).isDisplayed();
    }

    public void openItem(String itemText) {
        scrollToText(itemText).click();
    }
}

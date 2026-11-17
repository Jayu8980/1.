package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Expandable Lists -> 1. Custom Adapter: long press menu. */
public class ExpandableListPage extends BasePage {

    private final By peopleNames =
            AppiumBy.androidUIAutomator("new UiSelector().text(\"People Names\")");
    private final By sampleMenu =
            AppiumBy.androidUIAutomator("new UiSelector().text(\"Sample menu\")");
    private final By firstChild =
            AppiumBy.androidUIAutomator("new UiSelector().text(\"Arnold\")");

    public ExpandableListPage longPressPeopleNames() {
        longPress(peopleNames);
        return this;
    }

    public boolean isContextMenuShown() {
        return isDisplayed(sampleMenu);
    }

    public ExpandableListPage expandPeopleNames() {
        tap(peopleNames);
        return this;
    }

    public boolean isFirstChildShown() {
        return isDisplayed(firstChild);
    }
}

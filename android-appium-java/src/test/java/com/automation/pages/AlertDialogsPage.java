package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** App -> Alert Dialogs: popups with buttons and lists. */
public class AlertDialogsPage extends BasePage {

    private final By okCancelDialog = AppiumBy.accessibilityId("OK Cancel dialog with a message");
    private final By listDialog = AppiumBy.accessibilityId("List dialog");
    private final By singleChoiceDialog = AppiumBy.accessibilityId("Single choice list");
    private final By title = By.id("android:id/alertTitle");
    private final By message = By.id("android:id/message");
    private final By okButton = By.id("android:id/button1");
    private final By cancelButton = By.id("android:id/button2");

    public AlertDialogsPage openOkCancelDialog() {
        tap(okCancelDialog);
        return this;
    }

    public AlertDialogsPage openListDialog() {
        tap(listDialog);
        return this;
    }

    public AlertDialogsPage openSingleChoiceDialog() {
        tap(singleChoiceDialog);
        return this;
    }

    public AlertDialogsPage chooseListItem(String item) {
        tapText(item);
        return this;
    }

    public boolean isOptionChecked(String item) {
        return isChecked(AppiumBy.androidUIAutomator("new UiSelector().text(\"" + item + "\")"));
    }

    public boolean isDialogOpen() {
        return !driver.findElements(okButton).isEmpty();
    }

    public String getTitle() {
        return getText(title);
    }

    public String getMessage() {
        return getText(message);
    }

    public AlertDialogsPage tapOk() {
        tap(okButton);
        return this;
    }

    public AlertDialogsPage tapCancel() {
        tap(cancelButton);
        return this;
    }

    public boolean isLoaded() {
        return isDisplayed(okCancelDialog);
    }
}

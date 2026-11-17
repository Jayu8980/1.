package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Date Widgets -> 1. Dialog: date picker. */
public class DateWidgetsPage extends BasePage {

    private final By changeDate = AppiumBy.accessibilityId("change the date");
    private final By dateDisplay = By.id("io.appium.android.apis:id/dateDisplay");
    private final By okButton = By.id("android:id/button1");

    /** Opens the calendar, taps a day of the current month and confirms. */
    public DateWidgetsPage pickDay(int day) {
        tap(changeDate);
        tapText(String.valueOf(day));
        tap(okButton);
        return this;
    }

    /** Example: "11-15-2026 09:30" (month-day-year). */
    public String getDisplayedDate() {
        return getText(dateDisplay);
    }
}

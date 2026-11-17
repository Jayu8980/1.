package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;
import org.openqa.selenium.Rectangle;

/** Views -> Seek Bar: slider. */
public class SeekBarPage extends BasePage {

    private final By seekBar = By.id("io.appium.android.apis:id/seek");
    private final By progress = By.id("io.appium.android.apis:id/progress");

    /** Taps the bar at the given fraction of its width (0.0 = left, 1.0 = right). */
    public SeekBarPage setTo(double fraction) {
        Rectangle bar = waitForVisible(seekBar).getRect();
        int x = bar.getX() + (int) (bar.getWidth() * fraction);
        int y = bar.getY() + bar.getHeight() / 2;
        tapAt(x, y);
        return this;
    }

    /** Example: "80 from touch=true". */
    public String getProgressText() {
        return getText(progress);
    }

    public int getProgressValue() {
        return Integer.parseInt(getProgressText().split(" ")[0]);
    }
}

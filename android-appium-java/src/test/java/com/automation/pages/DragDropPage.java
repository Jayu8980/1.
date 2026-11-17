package com.automation.pages;

import com.automation.base.BasePage;
import io.appium.java_client.AppiumBy;
import org.openqa.selenium.By;

/** Views -> Drag and Drop. */
public class DragDropPage extends BasePage {

    private final By dot1 = By.id("io.appium.android.apis:id/drag_dot_1");
    private final By dot2 = By.id("io.appium.android.apis:id/drag_dot_2");
    private final By result = By.id("io.appium.android.apis:id/drag_result_text");

    public DragDropPage dragFirstDotOntoSecond() {
        dragAndDrop(dot1, dot2);
        return this;
    }

    public String getResultText() {
        return getText(result);
    }
}

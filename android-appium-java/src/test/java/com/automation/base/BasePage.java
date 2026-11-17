package com.automation.base;

import com.automation.drivers.DriverFactory;
import com.automation.utils.ConfigReader;
import io.appium.java_client.AppiumBy;
import io.appium.java_client.android.AndroidDriver;
import org.openqa.selenium.By;
import org.openqa.selenium.Point;
import org.openqa.selenium.Rectangle;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.RemoteWebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Map;

/**
 * Parent class for every page. Holds the common actions
 * (wait, tap, type, read text, scroll) so pages stay short.
 */
public abstract class BasePage {

    protected final AndroidDriver driver;
    protected final WebDriverWait wait;

    protected BasePage() {
        this.driver = DriverFactory.getDriver();
        this.wait = new WebDriverWait(driver,
                Duration.ofSeconds(ConfigReader.getInt("explicit.wait")));
    }

    protected WebElement waitForVisible(By locator) {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
    }

    protected void tap(By locator) {
        wait.until(ExpectedConditions.elementToBeClickable(locator)).click();
    }

    protected void type(By locator, String text) {
        WebElement field = waitForVisible(locator);
        field.clear();
        field.sendKeys(text);
    }

    protected String getText(By locator) {
        return waitForVisible(locator).getText();
    }

    protected boolean isDisplayed(By locator) {
        try {
            return waitForVisible(locator).isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }

    /** Scrolls the first scrollable list until an element with this exact text is visible. */
    protected WebElement scrollToText(String text) {
        return driver.findElement(AppiumBy.androidUIAutomator(
                "new UiScrollable(new UiSelector().scrollable(true))"
                        + ".scrollIntoView(new UiSelector().text(\"" + text + "\"))"));
    }

    protected void tapText(String text) {
        tap(AppiumBy.androidUIAutomator("new UiSelector().text(\"" + text + "\")"));
    }

    protected boolean isChecked(By locator) {
        return Boolean.parseBoolean(waitForVisible(locator).getAttribute("checked"));
    }

    protected void hideKeyboardIfShown() {
        try {
            if (driver.isKeyboardShown()) {
                driver.hideKeyboard();
            }
        } catch (RuntimeException ignored) {
            // no keyboard on screen
        }
    }

    // ---------- Gestures (UiAutomator2 "mobile:" commands) ----------

    private static String idOf(WebElement element) {
        return ((RemoteWebElement) element).getId();
    }

    protected void longPress(By locator) {
        driver.executeScript("mobile: longClickGesture",
                Map.of("elementId", idOf(waitForVisible(locator)), "duration", 1500));
    }

    protected void doubleTap(By locator) {
        driver.executeScript("mobile: doubleClickGesture",
                Map.of("elementId", idOf(waitForVisible(locator))));
    }

    /** Taps a raw screen coordinate. */
    protected void tapAt(int x, int y) {
        driver.executeScript("mobile: clickGesture", Map.of("x", x, "y", y));
    }

    /** Drags one element and drops it on the centre of another. */
    protected void dragAndDrop(By from, By to) {
        Rectangle target = waitForVisible(to).getRect();
        Point centre = new Point(target.getX() + target.getWidth() / 2,
                target.getY() + target.getHeight() / 2);
        driver.executeScript("mobile: dragGesture", Map.of(
                "elementId", idOf(waitForVisible(from)),
                "endX", centre.getX(),
                "endY", centre.getY()));
    }

    /** Swipes inside an element. direction: up, down, left or right. */
    protected void swipe(By locator, String direction) {
        driver.executeScript("mobile: swipeGesture", Map.of(
                "elementId", idOf(waitForVisible(locator)),
                "direction", direction,
                "percent", 0.75));
    }

    protected void pinchOpen(By locator) {
        driver.executeScript("mobile: pinchOpenGesture",
                Map.of("elementId", idOf(waitForVisible(locator)), "percent", 0.75));
    }

    protected void pinchClose(By locator) {
        driver.executeScript("mobile: pinchCloseGesture",
                Map.of("elementId", idOf(waitForVisible(locator)), "percent", 0.75));
    }

    public void goBack() {
        driver.navigate().back();
    }
}

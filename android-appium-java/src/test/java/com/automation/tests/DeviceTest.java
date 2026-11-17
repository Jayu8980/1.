package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.drivers.DriverFactory;
import com.automation.pages.HomePage;
import com.automation.utils.ConfigReader;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.android.nativekey.AndroidKey;
import io.appium.java_client.android.nativekey.KeyEvent;
import io.appium.java_client.appmanagement.ApplicationState;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.ScreenOrientation;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DeviceTest extends BaseTest {

    private static final String APP_PACKAGE = ConfigReader.get("app.package");

    private AndroidDriver driver() {
        return DriverFactory.getDriver();
    }

    @Test(description = "Rotate the screen to landscape and back")
    public void rotateScreen() {
        HomePage home = new HomePage();
        try {
            driver().rotate(ScreenOrientation.LANDSCAPE);
            Assert.assertEquals(driver().getOrientation(), ScreenOrientation.LANDSCAPE);
            Assert.assertTrue(home.isLoaded(), "Home screen missing after rotation");
        } finally {
            driver().rotate(ScreenOrientation.PORTRAIT);
        }
    }

    @Test(description = "Send the app to the background and bring it back")
    public void backgroundAndResume() {
        HomePage home = new HomePage();
        driver().runAppInBackground(Duration.ofSeconds(3));
        Assert.assertTrue(home.isLoaded(), "App did not come back");
    }

    @Test(description = "Press Home, then reopen the app")
    public void homeKeyAndReopen() {
        HomePage home = new HomePage();
        Assert.assertTrue(home.isLoaded());

        driver().pressKey(new KeyEvent(AndroidKey.HOME));
        Assert.assertNotEquals(driver().queryAppState(APP_PACKAGE),
                ApplicationState.RUNNING_IN_FOREGROUND, "App should be in the background");

        driver().activateApp(APP_PACKAGE);
        Assert.assertEquals(driver().queryAppState(APP_PACKAGE),
                ApplicationState.RUNNING_IN_FOREGROUND);
    }

    @Test(description = "Close the app completely and start it again")
    public void terminateAndRelaunch() {
        driver().terminateApp(APP_PACKAGE);
        Assert.assertEquals(driver().queryAppState(APP_PACKAGE), ApplicationState.NOT_RUNNING);

        driver().activateApp(APP_PACKAGE);
        Assert.assertTrue(new HomePage().isLoaded(), "App did not restart");
    }

    @Test(description = "Open the notification panel and close it")
    public void notificationPanel() {
        HomePage home = new HomePage();
        driver().openNotifications();
        driver().navigate().back();
        Assert.assertTrue(home.isLoaded(), "Did not return to the app");
    }

    @Test(description = "Copy text to the clipboard and read it back")
    public void clipboard() {
        driver().setClipboardText("copied by Appium");
        Assert.assertEquals(driver().getClipboardText(), "copied by Appium");
    }

    @Test(description = "Take a screenshot and save it to a file")
    public void screenshot() throws IOException {
        File shot = driver().getScreenshotAs(OutputType.FILE);
        Path target = Path.of("screenshots", "home_screen.png");
        Files.createDirectories(target.getParent());
        Files.copy(shot.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        Assert.assertTrue(Files.size(target) > 0, "Screenshot file is empty");
    }

    @Test(description = "Read device details")
    public void deviceInfo() {
        Assert.assertTrue(driver().getContextHandles().contains("NATIVE_APP"));
        Assert.assertEquals(driver().getCurrentPackage(), APP_PACKAGE);
        Assert.assertTrue(driver().manage().window().getSize().getWidth() > 0);
    }
}

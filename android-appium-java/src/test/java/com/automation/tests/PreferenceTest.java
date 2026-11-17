package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.pages.HomePage;
import com.automation.pages.PreferenceDependenciesPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class PreferenceTest extends BaseTest {

    @Test(description = "App opens on the home screen")
    public void appLaunches() {
        Assert.assertTrue(new HomePage().isLoaded(), "Home screen did not load");
    }

    @Test(description = "Turning WiFi on enables WiFi settings")
    public void wifiCheckboxEnablesSettings() {
        PreferenceDependenciesPage page = new HomePage()
                .openPreference()
                .openPreferenceDependencies();

        page.setWifi(false);
        Assert.assertFalse(page.isWifiSettingsEnabled(), "Settings should be disabled when WiFi is off");

        page.setWifi(true);
        Assert.assertTrue(page.isWifiSettingsEnabled(), "Settings should be enabled when WiFi is on");
    }

    @Test(description = "Type a WiFi name in the dialog and save it")
    public void saveWifiName() {
        String wifiName = "MyHomeWifi";

        PreferenceDependenciesPage page = new HomePage()
                .openPreference()
                .openPreferenceDependencies()
                .setWifi(true)
                .openWifiSettings();

        Assert.assertEquals(page.getDialogTitle(), "WiFi settings");

        page.enterWifiName(wifiName).tapOk();

        Assert.assertEquals(page.readSavedWifiName(), wifiName, "Saved WiFi name is wrong");
    }
}

package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.pages.HomePage;
import com.automation.pages.ViewsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ViewsTest extends BaseTest {

    @Test(description = "Scroll down a long list to find an item")
    public void scrollToWebView() {
        ViewsPage views = new HomePage().openViews();
        Assert.assertTrue(views.isLoaded(), "Views screen did not load");
        Assert.assertTrue(views.scrollToItem("WebView"), "WebView item not found after scrolling");
    }

    @Test(description = "Back button returns to the home screen")
    public void backReturnsHome() {
        HomePage home = new HomePage();
        ViewsPage views = home.openViews();
        Assert.assertTrue(views.isLoaded());

        views.goBack();
        Assert.assertTrue(home.isLoaded(), "Did not return to home screen");
    }
}

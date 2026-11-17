package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.pages.AlertDialogsPage;
import com.automation.pages.HomePage;
import com.automation.pages.PopupMenuPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class DialogTest extends BaseTest {

    @Test(description = "Accept an alert with OK")
    public void acceptAlert() {
        AlertDialogsPage alerts = new HomePage().openAlertDialogs().openOkCancelDialog();
        Assert.assertTrue(alerts.isDialogOpen(), "Dialog did not open");

        alerts.tapOk();
        Assert.assertTrue(alerts.isLoaded(), "Dialog did not close");
    }

    @Test(description = "Dismiss an alert with Cancel")
    public void dismissAlert() {
        AlertDialogsPage alerts = new HomePage().openAlertDialogs().openOkCancelDialog();
        alerts.tapCancel();
        Assert.assertTrue(alerts.isLoaded(), "Dialog did not close");
    }

    @Test(description = "Choose an item from a list dialog")
    public void listDialog() {
        AlertDialogsPage alerts = new HomePage().openAlertDialogs()
                .openListDialog()
                .chooseListItem("Command two");

        Assert.assertTrue(alerts.getMessage().contains("Command two"),
                "Message was: " + alerts.getMessage());
    }

    @Test(description = "Choose a radio option in a dialog")
    public void singleChoiceDialog() {
        AlertDialogsPage alerts = new HomePage().openAlertDialogs()
                .openSingleChoiceDialog()
                .chooseListItem("Satellite");

        Assert.assertTrue(alerts.isOptionChecked("Satellite"));
        alerts.tapOk();
    }

    @Test(description = "Read a toast message after choosing a popup menu item")
    public void popupMenuAndToast() {
        new HomePage().openViews().openItem("Popup Menu");
        PopupMenuPage popup = new PopupMenuPage().chooseMenuItem("Search");

        Assert.assertTrue(popup.getToastText().contains("Search"),
                "Toast was: " + popup.getToastText());
    }
}

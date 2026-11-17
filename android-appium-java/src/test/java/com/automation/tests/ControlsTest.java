package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.pages.ControlsPage;
import com.automation.pages.DateWidgetsPage;
import com.automation.pages.HomePage;
import com.automation.pages.SpinnerPage;
import com.automation.pages.SwitchesPage;
import com.automation.pages.TextFieldsPage;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ControlsTest extends BaseTest {

    @Test(description = "Tick and untick a checkbox")
    public void checkbox() {
        new HomePage().openViews().openItem("Controls").openSubItem("1. Light Theme");
        ControlsPage controls = new ControlsPage();

        boolean before = controls.isCheckboxChecked();
        controls.tapCheckbox();
        Assert.assertNotEquals(controls.isCheckboxChecked(), before, "Checkbox did not change");
    }

    @Test(description = "Only one radio button can be selected")
    public void radioButtons() {
        new HomePage().openViews().openItem("Controls").openSubItem("1. Light Theme");
        ControlsPage controls = new ControlsPage();

        controls.selectRadio1();
        Assert.assertTrue(controls.isRadio1Selected());

        controls.selectRadio2();
        Assert.assertTrue(controls.isRadio2Selected());
        Assert.assertFalse(controls.isRadio1Selected(), "Radio 1 should be cleared");
    }

    @Test(description = "Toggle button changes state")
    public void toggleButton() {
        new HomePage().openViews().openItem("Controls").openSubItem("1. Light Theme");
        ControlsPage controls = new ControlsPage();

        boolean before = controls.isToggleOn();
        controls.tapToggle();
        Assert.assertNotEquals(controls.isToggleOn(), before, "Toggle did not change");
    }

    @Test(description = "Switch turns on and off")
    public void onOffSwitch() {
        new HomePage().openViews().openItem("Switches");
        SwitchesPage switches = new SwitchesPage();

        boolean before = switches.isSwitchOn();
        switches.tapSwitch();
        Assert.assertNotEquals(switches.isSwitchOn(), before, "Switch did not change");
    }

    @DataProvider(name = "colors")
    public Object[][] colors() {
        return new Object[][]{{"red"}, {"green"}, {"violet"}};
    }

    @Test(dataProvider = "colors", description = "Pick values from a dropdown (data-driven)")
    public void dropdown(String color) {
        new HomePage().openViews().openItem("Spinner");
        SpinnerPage spinner = new SpinnerPage().selectColor(color);
        Assert.assertEquals(spinner.getSelectedColor(), color);
    }

    @Test(description = "Type into a text field and hide the keyboard")
    public void textField() {
        new HomePage().openViews().openItem("TextFields");
        TextFieldsPage fields = new TextFieldsPage().typeText("Hello Appium");

        Assert.assertEquals(fields.getFieldText(), "Hello Appium");
        Assert.assertFalse(fields.isKeyboardShown(), "Keyboard should be hidden");
    }

    @Test(description = "Pick a day in the date picker")
    public void datePicker() {
        new HomePage().openViews().openItem("Date Widgets").openSubItem("1. Dialog");
        DateWidgetsPage dates = new DateWidgetsPage().pickDay(15);

        Assert.assertTrue(dates.getDisplayedDate().contains("-15-"),
                "Date shown was: " + dates.getDisplayedDate());
    }
}

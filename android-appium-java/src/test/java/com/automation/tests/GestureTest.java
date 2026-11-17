package com.automation.tests;

import com.automation.base.BaseTest;
import com.automation.pages.DragDropPage;
import com.automation.pages.ExpandableListPage;
import com.automation.pages.HomePage;
import com.automation.pages.SeekBarPage;
import com.automation.pages.ViewsPage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GestureTest extends BaseTest {

    @Test(description = "Drag one dot and drop it on another")
    public void dragAndDrop() {
        new HomePage().openViews().openItem("Drag and Drop");
        DragDropPage page = new DragDropPage().dragFirstDotOntoSecond();
        Assert.assertEquals(page.getResultText(), "Dropped!");
    }

    @Test(description = "Long press opens a context menu")
    public void longPress() {
        new HomePage().openViews().openItem("Expandable Lists").openSubItem("1. Custom Adapter");
        ExpandableListPage list = new ExpandableListPage().longPressPeopleNames();
        Assert.assertTrue(list.isContextMenuShown(), "Context menu did not open");
    }

    @Test(description = "Tap a group to expand it")
    public void expandList() {
        new HomePage().openViews().openItem("Expandable Lists").openSubItem("1. Custom Adapter");
        ExpandableListPage list = new ExpandableListPage().expandPeopleNames();
        Assert.assertTrue(list.isFirstChildShown(), "Group did not expand");
    }

    @Test(description = "Swipe up on a list")
    public void swipe() {
        ViewsPage views = new HomePage().openViews();
        Assert.assertTrue(views.isLoaded());
        Assert.assertTrue(views.swipeUpHidesFirstItem(), "List did not move after swipe");
    }

    @Test(description = "Move a slider (seek bar)")
    public void slider() {
        new HomePage().openViews().openItem("Seek Bar");
        SeekBarPage seekBar = new SeekBarPage();

        seekBar.setTo(0.9);
        int high = seekBar.getProgressValue();
        seekBar.setTo(0.1);
        int low = seekBar.getProgressValue();

        Assert.assertTrue(high > low, "Slider did not move: high=" + high + " low=" + low);
        Assert.assertTrue(seekBar.getProgressText().contains("from touch=true"));
    }
}

package com.automation.listeners;

import com.automation.drivers.DriverFactory;
import org.openqa.selenium.OutputType;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Prints test status and saves a screenshot to /screenshots when a test fails. */
public class TestListener implements ITestListener {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Override
    public void onTestStart(ITestResult result) {
        System.out.println("▶ START: " + result.getMethod().getMethodName());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        System.out.println("✔ PASS : " + result.getMethod().getMethodName());
    }

    @Override
    public void onTestFailure(ITestResult result) {
        String name = result.getMethod().getMethodName();
        System.out.println("✘ FAIL : " + name + " -> " + result.getThrowable());

        if (!DriverFactory.hasDriver()) {
            return;
        }
        try {
            File src = DriverFactory.getDriver().getScreenshotAs(OutputType.FILE);
            Path dir = Path.of("screenshots");
            Files.createDirectories(dir);
            Path target = dir.resolve(name + "_" + LocalDateTime.now().format(STAMP) + ".png");
            Files.copy(src.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("  Screenshot: " + target.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            System.out.println("  Could not save screenshot: " + e.getMessage());
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        System.out.println("➜ SKIP : " + result.getMethod().getMethodName());
    }
}

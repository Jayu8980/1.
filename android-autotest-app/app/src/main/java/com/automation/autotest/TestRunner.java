package com.automation.autotest;

import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.util.List;

/**
 * Runs the selected tests one after another on a background thread.
 * Before each test ApiDemos is opened fresh; after a failure a screenshot is saved.
 */
public class TestRunner {

    public interface Listener {
        void onTestStarted(int index, TestCase test);

        void onTestFinished(int index, TestCase test, boolean passed, String error, long ms);

        void onAllFinished(int passed, int failed, long totalMs);
    }

    private static volatile boolean running = false;
    private static volatile boolean stopRequested = false;

    public static boolean isRunning() {
        return running;
    }

    public static void requestStop() {
        stopRequested = true;
    }

    public static File screenshotDir(Context context) {
        return new File(context.getExternalFilesDir(null), "screenshots");
    }

    public static void runAsync(Context context, List<TestCase> tests, Listener listener) {
        if (running) {
            return;
        }
        running = true;
        stopRequested = false;
        Context app = context.getApplicationContext();

        new Thread(() -> {
            int passed = 0, failed = 0;
            long start = System.currentTimeMillis();
            try {
                for (int i = 0; i < tests.size() && !stopRequested; i++) {
                    TestCase test = tests.get(i);
                    AutoService service = AutoService.get();
                    if (service == null) {
                        listener.onTestFinished(i, test, false, "Accessibility service was turned off", 0);
                        failed++;
                        break;
                    }
                    Device d = new Device(service);
                    listener.onTestStarted(i, test);
                    long t0 = System.currentTimeMillis();
                    String error = null;
                    try {
                        d.launchFresh(ApiDemosTests.APP);
                        test.body.run(d);
                    } catch (Throwable e) {
                        error = e.getMessage() == null ? e.toString() : e.getMessage();
                        try {
                            d.screenshot(screenshotDir(app), "FAIL_" + (i + 1));
                        } catch (Throwable ignored) {
                            // screenshot is best effort
                        }
                    }
                    long ms = System.currentTimeMillis() - t0;
                    if (error == null) passed++;
                    else failed++;
                    listener.onTestFinished(i, test, error == null, error, ms);
                }
            } finally {
                running = false;
                listener.onAllFinished(passed, failed, System.currentTimeMillis() - start);
                bringToFront(app);
            }
        }, "test-runner").start();
    }

    private static void bringToFront(Context context) {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        AutoService service = AutoService.get();
        // Started from the accessibility service, which Android allows from the background.
        (service != null ? service : context).startActivity(intent);
    }
}

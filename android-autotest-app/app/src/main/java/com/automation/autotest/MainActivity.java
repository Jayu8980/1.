package com.automation.autotest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The app's only screen: setup status, test list, run button and results. */
public class MainActivity extends Activity implements TestRunner.Listener {

    private static final String APIDEMOS_URL = "https://github.com/appium/android-apidemos/releases";
    private static final int GREEN = Color.rgb(46, 160, 67);
    private static final int RED = Color.rgb(207, 34, 46);
    private static final int GREY = Color.rgb(120, 120, 120);

    // Kept static so results survive the screen being recreated (rotation, return from tests).
    private static List<TestCase> tests;
    private static final List<String> resultText = new ArrayList<>();
    private static final List<Integer> resultColor = new ArrayList<>();
    private static String summary = "";
    private static int summaryColor = Color.GRAY;
    /** The screen currently shown, so results reach it even if it was recreated mid-run. */
    private static volatile MainActivity current;

    private final List<CheckBox> boxes = new ArrayList<>();
    private final List<TextView> results = new ArrayList<>();
    private TextView serviceStatus;
    private TextView appStatus;
    private TextView summaryView;
    private Button runButton;
    private Button installButton;
    private Button serviceButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (tests == null) {
            tests = ApiDemosTests.all(TestRunner.screenshotDir(this));
            for (int i = 0; i < tests.size(); i++) {
                resultText.add("");
                resultColor.add(GREY);
            }
        }
        setContentView(buildUi());
        current = this;
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ------------------------------------------------------------- UI

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text("AutoTest Runner", 24, true);
        col.addView(title);
        col.addView(text("Runs automated UI tests on the ApiDemos app, right on this phone.", 14, false));

        col.addView(header("Setup"));
        appStatus = text("", 14, false);
        col.addView(appStatus);
        installButton = button("1. Download ApiDemos app", v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(APIDEMOS_URL))));
        col.addView(installButton);

        serviceStatus = text("", 14, false);
        col.addView(serviceStatus);
        serviceButton = button("2. Turn on AutoTest Runner in Accessibility", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(serviceButton);

        col.addView(header("Run"));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        runButton = button("Run selected tests", v -> runSelected());
        row.addView(runButton, new LinearLayout.LayoutParams(0, -2, 2));
        row.addView(button("Stop", v -> TestRunner.requestStop()), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(row);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(button("Select all", v -> setAll(true)), new LinearLayout.LayoutParams(0, -2, 1));
        row2.addView(button("Select none", v -> setAll(false)), new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(row2);

        summaryView = text(summary, 16, true);
        summaryView.setTextColor(summaryColor);
        summaryView.setPadding(0, dp(8), 0, 0);
        col.addView(summaryView);

        String group = null;
        for (int i = 0; i < tests.size(); i++) {
            TestCase t = tests.get(i);
            if (!t.group.equals(group)) {
                group = t.group;
                col.addView(header(group));
            }
            CheckBox box = new CheckBox(this);
            box.setText((i + 1) + ". " + t.name);
            box.setChecked(true);
            boxes.add(box);
            col.addView(box);

            TextView result = text(resultText.get(i), 13, false);
            result.setTextColor(resultColor.get(i));
            result.setPadding(dp(32), 0, 0, dp(4));
            result.setVisibility(resultText.get(i).isEmpty() ? View.GONE : View.VISIBLE);
            results.add(result);
            col.addView(result);
        }

        col.addView(text("\nFailure screenshots are saved in:\n"
                + TestRunner.screenshotDir(this).getAbsolutePath(), 12, false));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        // Android 15+ draws apps edge to edge: keep content clear of the status and nav bars.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        return scroll;
    }

    private void refreshStatus() {
        boolean appOk = Device.isInstalled(this, ApiDemosTests.APP);
        boolean serviceOk = AutoService.isRunning();

        appStatus.setText(appOk ? "✔ ApiDemos app is installed" : "✘ ApiDemos app is not installed");
        appStatus.setTextColor(appOk ? GREEN : RED);
        installButton.setVisibility(appOk ? View.GONE : View.VISIBLE);

        serviceStatus.setText(serviceOk ? "✔ Accessibility service is on" : "✘ Accessibility service is off");
        serviceStatus.setTextColor(serviceOk ? GREEN : RED);
        serviceButton.setVisibility(serviceOk ? View.GONE : View.VISIBLE);

        runButton.setEnabled(appOk && serviceOk && !TestRunner.isRunning());
    }

    private void setAll(boolean checked) {
        for (CheckBox b : boxes) b.setChecked(checked);
    }

    private void runSelected() {
        List<TestCase> selected = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            if (boxes.get(i).isChecked()) {
                selected.add(tests.get(i));
            }
            resultText.set(i, "");
            resultColor.set(i, GREY);
            results.get(i).setVisibility(View.GONE);
        }
        if (selected.isEmpty()) {
            summaryView.setText("Select at least one test.");
            return;
        }
        summary = "Running " + selected.size() + " tests…";
        summaryColor = GREY;
        summaryView.setText(summary);
        summaryView.setTextColor(summaryColor);
        runButton.setEnabled(false);
        TestRunner.runAsync(this, selected, this);
    }

    // ------------------------------------------- runner callbacks (background thread)

    @Override
    public void onTestStarted(int index, TestCase test) {
        int i = tests.indexOf(test);
        setResult(i, "▶ running…", GREY);
    }

    @Override
    public void onTestFinished(int index, TestCase test, boolean passed, String error, long ms) {
        int i = tests.indexOf(test);
        String time = String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
        setResult(i, passed ? "✔ PASS  " + time : "✘ FAIL  " + time + "\n" + error, passed ? GREEN : RED);
    }

    @Override
    public void onAllFinished(int passed, int failed, long totalMs) {
        summary = String.format(java.util.Locale.US, "Done: %d passed, %d failed (%.0fs)",
                passed, failed, totalMs / 1000.0);
        summaryColor = failed == 0 ? GREEN : RED;
        MainActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            a.summaryView.setText(summary);
            a.summaryView.setTextColor(summaryColor);
            a.refreshStatus();
        });
    }

    private void setResult(int i, String text, int color) {
        if (i < 0) return;
        resultText.set(i, text);
        resultColor.set(i, color);
        MainActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            TextView v = a.results.get(i);
            v.setText(text);
            v.setTextColor(color);
            v.setVisibility(View.VISIBLE);
        });
    }

    // ------------------------------------------------------------ helpers

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView header(String s) {
        TextView t = text(s, 18, true);
        t.setPadding(0, dp(16), 0, dp(4));
        return t;
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(click);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}

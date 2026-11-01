package com.aitrading.desk;

import android.content.pm.PackageInfo;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Methods the web UI calls as window.NativeApp.*. HTTP goes through here so the app can read
 * market data and talk to the desk server without browser CORS rules; results come back through
 * window.__nativeHttp(id, status, body, error).
 */
public class NativeBridge {
    private static final int MAX_BYTES = 25 * 1024 * 1024;
    private final MainActivity activity;
    private final WebView web;
    private final ExecutorService pool = Executors.newFixedThreadPool(6);

    NativeBridge(MainActivity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    void shutdown() {
        pool.shutdownNow();
    }

    @JavascriptInterface
    public void http(final String id, final String requestJson) {
        pool.execute(() -> {
            int status = 0;
            String body = "", error = "";
            HttpURLConnection conn = null;
            try {
                JSONObject req = new JSONObject(requestJson);
                URL url = new URL(req.getString("url"));
                String protocol = url.getProtocol();
                if (!"https".equals(protocol) && !"http".equals(protocol)) throw new IllegalArgumentException("only http(s) URLs are allowed");
                int timeout = req.optInt("timeout", 30000);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(Math.min(timeout, 15000));
                conn.setReadTimeout(timeout);
                conn.setInstanceFollowRedirects(true);
                String method = req.optString("method", "GET").toUpperCase();
                conn.setRequestMethod(method);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AITradingDesk/" + versionName());
                JSONObject headers = req.optJSONObject("headers");
                if (headers != null) {
                    for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
                        String k = it.next();
                        conn.setRequestProperty(k, headers.getString(k));
                    }
                }
                if (!req.isNull("body") && req.has("body")) {
                    byte[] out = req.getString("body").getBytes(StandardCharsets.UTF_8);
                    conn.setDoOutput(true);
                    conn.setFixedLengthStreamingMode(out.length);
                    try (OutputStream os = conn.getOutputStream()) { os.write(out); }
                }
                status = conn.getResponseCode();
                InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (in != null) {
                    try (InputStream src = in) {
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] chunk = new byte[16384];
                        int n;
                        while ((n = src.read(chunk)) > 0) {
                            if (buf.size() + n > MAX_BYTES) throw new IllegalStateException("response too large");
                            buf.write(chunk, 0, n);
                        }
                        body = buf.toString("UTF-8");
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String js = "window.__nativeHttp(" + JSONObject.quote(id) + "," + status + "," + JSONObject.quote(body) + ","
                    + (error.isEmpty() ? "null" : JSONObject.quote(error)) + ")";
            activity.runOnUiThread(() -> web.evaluateJavascript(js, null));
        });
    }

    @JavascriptInterface
    public void setTheme(String theme) {
        activity.setDarkTheme(!"light".equals(theme));
    }

    @JavascriptInterface
    public void openUrl(String url) {
        activity.runOnUiThread(() -> activity.openExternal(url));
    }

    @JavascriptInterface
    public String insets() {
        return activity.insetsJson();
    }

    @JavascriptInterface
    public String version() {
        return versionName();
    }

    private String versionName() {
        try {
            PackageInfo p = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}

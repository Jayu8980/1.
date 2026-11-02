package com.aitrading.desk;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

/** A full-screen WebView that hosts the trading desk UI bundled in assets/www. */
public class MainActivity extends ComponentActivity {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/assets/www/index.html";

    private WebView web;
    private NativeBridge bridge;
    private float insetTop = 0, insetBottom = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#070B14"));
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                openExternal(uri.toString());  // never navigate the app's WebView away from the app
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pushInsets();
            }
        });

        bridge = new NativeBridge(this, web);
        web.addJavascriptInterface(bridge, "NativeApp");

        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            float density = getResources().getDisplayMetrics().density;
            // the keyboard shrinks the WebView; the system bars are padded inside the page by CSS
            v.setPadding(0, 0, 0, ime.bottom);
            insetTop = bars.top / density;
            insetBottom = (ime.bottom > 0 ? 0 : bars.bottom) / density;
            pushInsets();
            return WindowInsetsCompat.CONSUMED;
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("window.appBack ? window.appBack() : false", value -> {
                    if (!"true".equals(value)) {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    void pushInsets() {
        web.evaluateJavascript("window.__setInsets && window.__setInsets(" + insetTop + "," + insetBottom + ")", null);
    }

    String insetsJson() {
        return "{\"top\":" + insetTop + ",\"bottom\":" + insetBottom + "}";
    }

    void setDarkTheme(boolean dark) {
        runOnUiThread(() -> {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(!dark);
            c.setAppearanceLightNavigationBars(!dark);
            int bg = Color.parseColor(dark ? "#070B14" : "#F3F5FA");
            getWindow().getDecorView().setBackgroundColor(bg);
            web.setBackgroundColor(bg);
        });
    }

    void openExternal(String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            // no browser installed
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        bridge.shutdown();
        web.destroy();
        super.onDestroy();
    }
}

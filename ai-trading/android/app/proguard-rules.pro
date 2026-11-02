# Methods called from JavaScript through addJavascriptInterface
-keepclassmembers class com.aitrading.desk.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}

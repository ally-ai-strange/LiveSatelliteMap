# Keep the JavaScript bridge methods called from map.html
-keepclassmembers class com.livesatellite.map.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

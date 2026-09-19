# Add project specific ProGuard rules here.

# Keep the JavaScript bridge methods: they are called from injected JS by name,
# so R8 must not rename or strip them.
-keepclassmembers class org.geminiassist.app.MainActivity {
    public *;
}
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

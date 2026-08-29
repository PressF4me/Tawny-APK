# Tawny — R8 / ProGuard rules for the release build.

# --- WebView JS bridge ------------------------------------------------------
# Bridge.post() / Bridge.saveImage() are called from public/app.js by name.
# Without this R8 renames or strips them and the native bridge goes dead.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Java-WebSocket + SLF4J ----------------------------------------------
# The embedded LAN signaling server. slf4j-nop has no real backend.
-dontwarn org.slf4j.**
-keep class org.java_websocket.** { *; }

# --- ZXing ---------------------------------------------------------------
-dontwarn com.google.zxing.**

# --- Kotlin / coroutines noise -----------------------------------------
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**

# Keep source file + line numbers in stack traces (Play pre-launch reports).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

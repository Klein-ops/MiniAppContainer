# JS Bridge 通过 @JavascriptInterface 暴露，类名/方法名不可混淆。
-keepclassmembers class com.miniapp.container.bridge.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.miniapp.container.bridge.** { *; }

# WASM JNI 原生方法。
-keepclasseswithmembernames class com.miniapp.container.wasm.** {
    native <methods>;
}

# 数据模型反射。
-keep class com.miniapp.container.core.** { *; }

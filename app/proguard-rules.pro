# 桥接方法必须保留，否则 @JavascriptInterface 会被混淆掉
-keepclassmembers class com.zsonit.carcare.CarBridge {
    public *;
}
-keep class com.zsonit.carcare.CarBridge { *; }

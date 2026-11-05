# WebView JavaScript entry points (if introduced); AndroidX/Media3/serialization supply their own consumer rules.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

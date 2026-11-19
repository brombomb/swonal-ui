# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.model.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Mozilla GeckoView JNI bridges and native callbacks
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.geckoview.**
-dontwarn java.beans.**

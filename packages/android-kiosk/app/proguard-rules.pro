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
-keep,includedescriptorclasses class com.swonal.kiosk.sync.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.sync.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.sync.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.db.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.db.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.db.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.engine.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.engine.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.engine.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.music.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.music.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.social.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.social.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.social.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.integrations.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.integrations.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.integrations.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.swonal.kiosk.server.**$$serializer { *; }
-keepclassmembers class com.swonal.kiosk.server.** {
    *** Companion;
}
-keepclasseswithmembers class com.swonal.kiosk.server.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep Bundled SQLite JNI bindings
-keep class androidx.sqlite.** { *; }
-keep class androidx.sqlite.driver.bundled.** { *; }

# Keep NanoHTTPD / NanoWSD
-keep class fi.iki.elonen.** { *; }
-dontwarn fi.iki.elonen.**

# Keep Mozilla GeckoView JNI bridges and native callbacks
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-dontwarn org.mozilla.gecko.**
-dontwarn org.mozilla.geckoview.**
-dontwarn java.beans.**

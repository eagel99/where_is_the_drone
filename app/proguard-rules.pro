# SkyPoint ProGuard / R8 rules

# Keep our data classes' fields so Gson serialization survives obfuscation.
-keep class com.example.where_is_the_drone.CapturedTarget { *; }
-keep class com.example.where_is_the_drone.TargetType { *; }

# Gson uses reflection on TypeToken.
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking interface com.google.gson.reflect.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken

# CameraX Camera2 interop accessed reflectively.
-keep class androidx.camera.camera2.** { *; }

# Tink (used by androidx.security.crypto for keyset serialization).
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.proto.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
# Tink's optional remote-key-download path references google-api-client and joda-time.
# We don't use that feature; suppress R8 missing-class errors.
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# AndroidX Security MasterKey reflection.
-keep class androidx.security.crypto.** { *; }

# Strip log calls in release builds to avoid leaking diagnostic data.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

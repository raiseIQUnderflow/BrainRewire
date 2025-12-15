# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# BrainRewire specific rules - keep all app components
-keep class com.example.brainrewire.BrainRewireDeviceAdminReceiver {
    void <init>();
    *;
}
-keep class com.example.brainrewire.services.** {
    void <init>();
    *;
}
-keep class com.example.brainrewire.receivers.** {
    void <init>();
    *;
}
-keep class com.example.brainrewire.data.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { void <init>(); }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { void <init>(); }

# DataStore
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Keep Compose classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Don't warn about missing classes
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**


# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep annotation used by Room
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions

# Keep Entity classes
-keep class com.snapbudget.ocr.data.model.** { *; }

# Biometric
-keep class androidx.biometric.** { *; }
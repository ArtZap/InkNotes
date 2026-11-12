# The "minified" build type only: device tests call into the app and its libraries
# (Compose, test runner glue), which R8 must not remove from under them.
-keep class androidx.compose.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.core.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class org.junit.** { *; }
-dontwarn org.junit.**
-dontwarn androidx.test.**
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.concurrent.** { *; }
-keep class com.google.common.util.concurrent.** { *; }
-keep class androidx.tracing.Trace { *; }

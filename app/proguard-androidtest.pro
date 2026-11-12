# The device-test APK of the "minified" build: R8 runs on it too (with the app's names).
# Its runner and tests are kept as they are; annotation-only libraries are not shipped.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-keep class dev.inknotes.** { *; }
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }

# Keep the entire io.waylandie.display package — the bridge protocol relies
# on reflective access to MainActivity methods (native callbacks, JNI).
-keep class io.waylandie.display.** { *; }

# Keep all classes referenced from JNI.
-keepclasseswithmembernames class * {
    native <methods>;
}

# AndroidX — don't strip entry points.
-dontwarn androidx.**
-keep class androidx.core.** { *; }

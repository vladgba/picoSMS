# picoSMS — R8 configuration.
# All entry points (activities, receivers, service, widget) are referenced from the
# manifest, so aapt2 generates keep rules for them automatically.

-repackageclasses
-allowaccessmodification
-overloadaggressively

# Strip Kotlin null-check intrinsics (the compiler already omits most via -Xno-*-assertions).
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
}

# Drop any stray logging.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

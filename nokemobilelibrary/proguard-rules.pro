# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ==================== ION-2 Phone Key Rules ====================
# Keep model classes used with JSON parsing and reflection
-keep class com.noke.nokemobilelibrary.phonekey.models.** { *; }
-keep class com.noke.nokemobilelibrary.enums.** { *; }

# Keep SecurityService callback interfaces
-keep interface com.noke.nokemobilelibrary.phonekey.internal.SecurityService$* { *; }

# Keep exception constructors for error handling
-keepclassmembers class * extends java.lang.Exception {
    <init>(...);
}

# Keep NokeMobileLibraryError sealed class hierarchy
-keep class com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError { *; }
-keep class com.noke.nokemobilelibrary.phonekey.NokeMobileLibraryError$* { *; }

# Keep public API facades (PhoneKeyAccessService, PhoneKeyStateMonitor)
-keep public class com.noke.nokemobilelibrary.phonekey.PhoneKeyAccessService {
    public protected *;
}
-keep public class com.noke.nokemobilelibrary.phonekey.PhoneKeyStateMonitor {
    public protected *;
}

# Keep Flow extension functions
-keep class com.noke.nokemobilelibrary.phonekey.PhoneKeyFlowExtensionsKt { *; }

# Preserve annotations for runtime introspection
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


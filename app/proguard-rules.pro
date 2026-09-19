# ProGuard Rules for Captain Auto-Accept / Smart Text Analyzer
# Ensures release builds compile without syntax errors or junk tokens.

# Keep application data classes and state models used across coroutines and flows
-keep class com.example.ParsedRideOffer { *; }
-keep class com.example.RideEvaluation { *; }
-keep class com.example.AnalysisResult { *; }
-keep class com.example.SettingsState { *; }
-keep class com.example.ActivityLogEntry { *; }
-keep class com.example.LogSeverity { *; }
-keep class com.example.AppPermissionItem { *; }
-keep class com.example.PermissionCategory { *; }

# Keep Accessibility Services so Android system can instantiate them via reflection
-keep class com.example.MyAccessibilityService { *; }
-keep class com.example.SmartTextService { *; }

# Preserve LineNumberTable and SourceFile for actionable production stack traces
-keepattributes SourceFile,LineNumberTable

# Kotlin Coroutines & Flow reflection rules
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Android Architecture Components / Jetpack Compose
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

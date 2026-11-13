# ProGuard / R8 Rules for Order Master (Zero-Trust Thin Client)

# Preserve LineNumberTable and SourceFile for production crash diagnostics
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep application Entry Points, Activities, Services, and Receivers
-keep class com.example.OrderMasterApplication { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.CaptainStoreActivity { *; }
-keep class com.example.PaymentActivity { *; }
-keep class com.example.MyAccessibilityService { *; }
-keep class com.example.SmartTextService { *; }
-keep class com.example.RapidoAccessibilityService { *; }
-keep class com.example.FloatingOverlayService { *; }
-keep class com.example.DownloadCompleteReceiver { *; }

# Keep application data classes, Room entities, and state models
-keep class com.example.ParsedRideOffer { *; }
-keep class com.example.RideEvaluation { *; }
-keep class com.example.AnalysisResult { *; }
-keep class com.example.SettingsState { *; }
-keep class com.example.ActivityLogEntry { *; }
-keep class com.example.LogSeverity { *; }
-keep class com.example.AppPermissionItem { *; }
-keep class com.example.PermissionCategory { *; }
-keep class com.example.data.OrderEntity { *; }
-keep class com.example.data.UserProfile { *; }
-keep class com.example.data.ReceivedPayment { *; }

# Kotlin Coroutines, Metadata & Flow rules
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Firebase / Firestore / Auth / AppCheck / Play Integrity
-keepattributes Signature
-keepclassmembers class * {
    @com.google.firebase.firestore.PropertyName <fields>;
    @com.google.firebase.firestore.PropertyName <methods>;
}
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Credential Manager & Google Identity
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**

# Google Mobile Ads (AdMob)
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# ViewModel & Android Architecture Components
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}


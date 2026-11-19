# ProGuard / R8 Rules for Order Master (Zero-Trust Thin Client)

# Preserve LineNumberTable and SourceFile for production crash diagnostics
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep application Entry Points, Activities, Services, and Receivers
-keep class com.example.OrderMasterApplication { *; }
-keep class com.example.MainActivity { *; }
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
-keep class com.example.data.repository.AppConfig { *; }
-keep class com.example.data.repository.** { *; }

# Keep licensing, entitlements, admin managers, and device security
-keep class com.example.LicenseManager { *; }
-keep class com.example.LicenseManager$** { *; }
-keep class com.example.FeatureIds { *; }
-keep class com.example.FeatureIds$** { *; }
-keep class com.example.FeatureUnlockPricing { *; }
-keep class com.example.PassTier { *; }
-keep class com.example.PassTier$** { *; }
-keep class com.example.AccessStatus { *; }
-keep class com.example.AccessStatus$** { *; }
-keep class com.example.ActivePassInfo { *; }
-keep class com.example.TrialInfo { *; }
-keep class com.example.GlobalAdminManager { *; }
-keep class com.example.UserBanState { *; }
-keep class com.example.UserBanState$** { *; }
-keep class com.example.AppSettings { *; }
-keep class com.example.DeviceUtils { *; }
-keep class com.example.RewardedAdManager { *; }
-keep class com.example.RewardedAdManager$** { *; }
-keep class com.example.AdConfig { *; }
-keep class com.example.ui.components.BannerAdViewKt { *; }

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


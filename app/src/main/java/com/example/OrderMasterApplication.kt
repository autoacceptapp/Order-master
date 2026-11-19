package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.initialization.InitializationStatus

class OrderMasterApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 1. Initialize FirebaseApp strictly from authentic configuration or fallback options
        DeviceUtils.ensureFirebaseInitialized(this)

        // 2. Initialize Google Mobile Ads SDK explicitly immediately after Firebase
        try {
            if (!DeviceUtils.isUnitTestEnvironment()) {
                if (BuildConfig.DEBUG) {
                    val requestConfig = com.google.android.gms.ads.RequestConfiguration.Builder()
                        .setTestDeviceIds(listOf(com.google.android.gms.ads.AdRequest.DEVICE_ID_EMULATOR))
                        .build()
                    MobileAds.setRequestConfiguration(requestConfig)
                }
                MobileAds.initialize(this) { initializationStatus: InitializationStatus ->
                    Log.i("OrderMasterApp", "AdMob SDK Initialized: $initializationStatus")
                }
            } else {
                Log.d("OrderMasterApp", "Skipping MobileAds initialization in unit test environment.")
            }
        } catch (e: Throwable) {
            Log.w("OrderMasterApp", "Failed to initialize Google Mobile Ads: ${e.message}")
        }

        // 3. Initialize App Check in strict sequence
        initFirebaseAppCheck()

        // 4. Configure Firestore global offline persistence settings
        initFirestoreSettings()

        // 5. Initialize core managers at Application level for background services
        initCoreEngines()

        // 6. Pre-register all notification channels for Android 8.0+ / Android 13+
        initNotificationChannels()
    }

    /**
     * Pre-registers all notification channels at application startup for Android 8.0+ (API 26+)
     * to prevent BadNotificationException and ensure foreground services & system alerts function smoothly.
     */
    private fun initNotificationChannels() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(android.app.NotificationManager::class.java) ?: return

                val overlayChannel = android.app.NotificationChannel(
                    FloatingOverlayService.CHANNEL_ID,
                    "Order Master Overlay",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the Order Master floating overlay button active over driver apps"
                    setShowBadge(false)
                }

                val updateChannel = android.app.NotificationChannel(
                    UpdateNotificationManager.CHANNEL_ID,
                    "App Updates",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications for new Order Master app versions and downloads"
                    enableLights(true)
                    enableVibration(true)
                }

                nm.createNotificationChannel(overlayChannel)
                nm.createNotificationChannel(updateChannel)
                Log.d(TAG, "Notification channels pre-registered successfully.")
            } catch (e: Exception) {
                Log.w(TAG, "Notification channel pre-registration notice: ${e.message}")
            }
        }
    }

    /**
     * Initializes Firebase App Check in the strict required sequence:
     * Firebase initialization -> App Check provider installation -> Token auto-refresh -> Service usage.
     *
     * Debug builds: uses DebugAppCheckProviderFactory.
     * Release builds: uses PlayIntegrityAppCheckProviderFactory.
     */
    private fun initFirebaseAppCheck() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                Log.w(TAG, "Skipping App Check: FirebaseApp could not be initialized.")
                return
            }

            // Install App Check provider factory strictly based on build variant
            val firebaseAppCheck = FirebaseAppCheck.getInstance()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Configuring Firebase App Check with DebugAppCheckProviderFactory (DEBUG build)")
                val debugFactory = try {
                    Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                        .getMethod("getInstance")
                        .invoke(null) as AppCheckProviderFactory
                } catch (t: Throwable) {
                    Log.w(TAG, "DebugAppCheckProviderFactory unavailable (${t.message}); falling back to Play Integrity")
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                }
                firebaseAppCheck.installAppCheckProviderFactory(debugFactory)
            } else {
                // In release builds (both Play Store and sideloaded GitHub Releases APKs),
                // Google Play Integrity API works as long as Google Play Services is available
                // and the release signing key SHA-256 is registered in Firebase Console.
                try {
                    Log.d(TAG, "Configuring Firebase App Check with PlayIntegrityAppCheckProviderFactory (RELEASE build)")
                    firebaseAppCheck.installAppCheckProviderFactory(
                        PlayIntegrityAppCheckProviderFactory.getInstance()
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "PlayIntegrityAppCheckProviderFactory initialization error or Play Services missing: ${t.message}; degrading gracefully.")
                }
            }

            // Enable automatic App Check token refresh for continuous background monitoring
            firebaseAppCheck.setTokenAutoRefreshEnabled(true)
            Log.d(TAG, "Firebase App Check token auto-refresh enabled.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase App Check: ${e.message}", e)
        }
    }

    /**
     * Configures Firestore offline persistent cache for zero-crash offline resilience.
     */
    private fun initFirestoreSettings() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                Log.w(TAG, "Skipping Firestore settings: FirebaseApp is not initialized.")
                return
            }
            val firestore = FirebaseFirestore.getInstance()
            val newSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
                .build()
            firestore.firestoreSettings = newSettings
            Log.d(TAG, "Firestore persistent cache initialized successfully.")
        } catch (e: Exception) {
            // Firestore settings may already be set; continue safely
            Log.w(TAG, "Firestore settings initialization notice: ${e.message}")
        }
    }

    /**
     * Initializes AppSettings and LicenseManager on Application startup so that
     * background Accessibility and Floating Overlay services have immediate access to state.
     * Runs asynchronously on Dispatchers.IO to prevent ANRs on slow network or heavy I/O.
     */
    private fun initCoreEngines() {
        if (DeviceUtils.isUnitTestEnvironment()) {
            try {
                AppSettings.init(this)
                LicenseManager.init(this)
                GlobalAdminManager.init(this)
                ReferralManager.init(this)
                com.example.data.repository.AppConfigRepository.init(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize core engines synchronously in test: ${e.message}", e)
            }
            return
        }

        appScope.launch {
            try {
                AppSettings.init(this@OrderMasterApplication)
                LicenseManager.init(this@OrderMasterApplication)
                GlobalAdminManager.init(this@OrderMasterApplication)
                ReferralManager.init(this@OrderMasterApplication)
                com.example.data.repository.AppConfigRepository.init(this@OrderMasterApplication)
                com.example.data.OrderRepository.getInstance(this@OrderMasterApplication).pruneExpiredOrdersIfNeeded()
                Log.d(TAG, "Core engines initialized successfully on background thread.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize core engines in Application.onCreate: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "OrderMasterApplication"
    }
}

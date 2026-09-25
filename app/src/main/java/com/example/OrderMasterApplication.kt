package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

class OrderMasterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 1. Initialize Firebase & App Check in strict order
        initFirebaseAppCheck()

        // 2. Configure Firestore global offline persistence settings
        initFirestoreSettings()

        // 3. Initialize core managers at Application level for background services
        initCoreEngines()
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
            // 1. Ensure FirebaseApp is initialized first
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }

            // 2. Install App Check provider factory strictly based on build variant
            val firebaseAppCheck = FirebaseAppCheck.getInstance()

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Configuring Firebase App Check with DebugAppCheckProviderFactory (DEBUG build)")
                firebaseAppCheck.installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
            } else {
                Log.d(TAG, "Configuring Firebase App Check with PlayIntegrityAppCheckProviderFactory (RELEASE build)")
                firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
            }

            // 3. Enable automatic App Check token refresh for continuous background monitoring
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
     */
    private fun initCoreEngines() {
        try {
            AppSettings.init(this)
            LicenseManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize core engines in Application.onCreate: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "OrderMasterApplication"
    }
}

package com.example

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class OrderMasterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        initFirebaseAppCheck()
    }

    /**
     * Initializes Firebase App Check in the strict required sequence:
     * Firebase initialization -> App Check provider installation -> Firebase Authentication & Firestore usage.
     *
     * Debug builds: uses DebugAppCheckProviderFactory.
     *   The debug token will be printed in Logcat by the Firebase SDK:
     *   "Enter this debug secret into the allow list in the Firebase Console..."
     * Release builds: uses PlayIntegrityAppCheckProviderFactory.
     *   Never uses the debug provider in release/production builds.
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Firebase App Check: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "OrderMasterApplication"
    }
}

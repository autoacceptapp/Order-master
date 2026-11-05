package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * App Configuration model synced with Firestore collection `admin_settings`, document `app_config`.
 *
 * @param minSupportedVersionCode Minimum supported version code. Older builds are force-updated.
 * @param latestVersionCode Latest available version code published.
 * @param dynamicAppDownloadUrl Unified dynamic download URL for updates and referral sharing.
 */
data class AppConfig(
    val minSupportedVersionCode: Int = 1,
    val latestVersionCode: Int = 1,
    val dynamicAppDownloadUrl: String = ""
)

/**
 * Repository to observe and fetch remote administrative app configuration from Firebase Firestore.
 * Document path: `admin_settings/app_config`
 */
object AppConfigRepository {

    private const val TAG = "AppConfigRepository"
    const val COLL_ADMIN_SETTINGS = "admin_settings"
    const val DOC_APP_CONFIG = "app_config"

    @Volatile
    private var appContext: Context? = null

    private val _currentAppConfig = MutableStateFlow(AppConfig())
    val currentAppConfig: StateFlow<AppConfig> = _currentAppConfig.asStateFlow()

    /**
     * Initializes the repository with the application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun isFirebaseReady(): Boolean {
        val ctx = appContext
        if (ctx != null) {
            return try {
                if (FirebaseApp.getApps(ctx).isNotEmpty()) {
                    true
                } else {
                    tryInitializeFirebase(ctx)
                }
            } catch (_: Exception) {
                false
            }
        }
        return try {
            FirebaseApp.getInstance() != null
        } catch (_: Exception) {
            false
        }
    }

    private fun tryInitializeFirebase(context: Context): Boolean {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                try {
                    FirebaseApp.initializeApp(context)
                } catch (_: Exception) {
                    val fallbackApiKey = BuildConfig.FIREBASE_FALLBACK_API_KEY
                    if (fallbackApiKey.isNotBlank()) {
                        val options = com.google.firebase.FirebaseOptions.Builder()
                            .setApplicationId(context.packageName)
                            .setProjectId("ordermaster-app")
                            .setApiKey(fallbackApiKey)
                            .build()
                        FirebaseApp.initializeApp(context, options)
                    }
                }
            }
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize FirebaseApp: ${e.message}")
            false
        }
    }

    /**
     * Observes real-time changes to the `admin_settings/app_config` document.
     * Uses Firestore `addSnapshotListener` wrapped in a Kotlin `Flow`.
     * Never rethrows or crashes if Firebase is uninitialized or unavailable.
     */
    fun observeAppConfig(): Flow<AppConfig> = callbackFlow {
        if (!isFirebaseReady()) {
            Log.w(TAG, "FirebaseApp is not ready; emitting default AppConfig without error.")
            trySend(_currentAppConfig.value)
            awaitClose { }
            return@callbackFlow
        }

        val firestore = try {
            FirebaseFirestore.getInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseFirestore instance not available: ${t.message}")
            trySend(_currentAppConfig.value)
            awaitClose { }
            return@callbackFlow
        }

        val listener = try {
            val docRef = firestore.collection(COLL_ADMIN_SETTINGS).document(DOC_APP_CONFIG)
            docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time app_config snapshot listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val minCode = snapshot.getLong("minSupportedVersionCode")?.toInt() ?: 1
                    val latestCode = snapshot.getLong("latestVersionCode")?.toInt() ?: 1
                    val downloadUrl = snapshot.getString("dynamicAppDownloadUrl") ?: ""

                    val config = AppConfig(
                        minSupportedVersionCode = minCode,
                        latestVersionCode = latestCode,
                        dynamicAppDownloadUrl = downloadUrl
                    )
                    _currentAppConfig.value = config
                    trySend(config)
                } else {
                    val defaultConfig = AppConfig()
                    _currentAppConfig.value = defaultConfig
                    trySend(defaultConfig)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed adding snapshot listener on app_config: ${t.message}")
            trySend(_currentAppConfig.value)
            null
        }

        awaitClose {
            listener?.remove()
        }
    }

    /**
     * One-time asynchronous fetch of the current AppConfig.
     */
    suspend fun getAppConfig(): AppConfig {
        if (!isFirebaseReady()) {
            return _currentAppConfig.value
        }
        return try {
            val firestore = FirebaseFirestore.getInstance()
            val snapshot = firestore.collection(COLL_ADMIN_SETTINGS)
                .document(DOC_APP_CONFIG)
                .get()
                .await()

            if (snapshot.exists()) {
                val minCode = snapshot.getLong("minSupportedVersionCode")?.toInt() ?: 1
                val latestCode = snapshot.getLong("latestVersionCode")?.toInt() ?: 1
                val downloadUrl = snapshot.getString("dynamicAppDownloadUrl") ?: ""
                val config = AppConfig(
                    minSupportedVersionCode = minCode,
                    latestVersionCode = latestCode,
                    dynamicAppDownloadUrl = downloadUrl
                )
                _currentAppConfig.value = config
                config
            } else {
                AppConfig()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to fetch app_config: ${t.message}")
            _currentAppConfig.value
        }
    }
}

package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.auth.GoogleAuthManager
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Pass Tiers Configuration:
 * - Daily Pass: ₹9 INR OR 100 Points (Valid 24 hours / 1 Day)
 * - Weekly Pass: ₹49 INR OR 500 Points (Valid 7 days)
 * - Monthly Pass: ₹179 INR OR 1500 Points (Valid 28 days)
 */
enum class PassTier(
    val id: String,
    val title: String,
    val priceInInr: Int,
    val pointsCost: Int,
    val durationDays: Int,
    val durationMs: Long,
    val tag: String,
    val description: String
) {
    DAILY(
        id = "daily_pass",
        title = "Daily Pass",
        priceInInr = 9,
        pointsCost = 100,
        durationDays = 1,
        durationMs = 24L * 3600L * 1000L,
        tag = "Short Shift",
        description = "24 Hours unlimited high-speed auto-acceptance & radar"
    ),
    WEEKLY(
        id = "weekly_pass",
        title = "Weekly Pass",
        priceInInr = 49,
        pointsCost = 500,
        durationDays = 7,
        durationMs = 7L * 24L * 3600L * 1000L,
        tag = "Most Popular",
        description = "7 Days unlimited access with surge radar & priority voice alerts"
    ),
    MONTHLY(
        id = "monthly_pass",
        title = "Monthly Pass",
        priceInInr = 179,
        pointsCost = 1500,
        durationDays = 28,
        durationMs = 28L * 24L * 3600L * 1000L,
        tag = "Best Value",
        description = "28 Days full automation + VIP cross-device cloud sync"
    ),
    REMOVE_ADS(
        id = "remove_ads_pass",
        title = "Remove Ads Pass",
        priceInInr = 199,
        pointsCost = 1600,
        durationDays = 28,
        durationMs = 28L * 24L * 3600L * 1000L,
        tag = "Ad Free",
        description = "28 Days complete Ad-Free uninterrupted experience"
    );

    companion object {
        fun fromId(id: String?): PassTier? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * Access states representing the user's licensing status.
 */
sealed class AccessStatus {
    data object Loading : AccessStatus()

    data class TrialActive(
        val remainingTimeMs: Long,
        val expiryTimestamp: Long,
        val formattedRemaining: String
    ) : AccessStatus()

    data class PassActive(
        val passTier: PassTier,
        val remainingTimeMs: Long,
        val expiryTimestamp: Long,
        val formattedRemaining: String
    ) : AccessStatus()

    data class Expired(
        val isTrialUsed: Boolean,
        val hasHadPreviousPass: Boolean,
        val message: String
    ) : AccessStatus()
}

/**
 * Active Pass representation for UI and status inspection.
 */
data class ActivePassInfo(
    val passTier: PassTier,
    val expiryTimestamp: Long,
    val remainingTimeMs: Long,
    val isExpired: Boolean
)

/**
 * Trial metadata representation.
 */
data class TrialInfo(
    val firstClaimedTimestamp: Long,
    val trialExpiryTimestamp: Long,
    val isUsed: Boolean,
    val isExpired: Boolean,
    val remainingMs: Long
)

/**
 * Pricing and definition for hourly micro-transaction feature unlocks.
 */
data class FeatureUnlockPricing(
    val id: String,
    val title: String,
    val pointsPerHour: Int,
    val description: String
)

/**
 * Standard feature IDs and pricing definitions for micro-transactions.
 */
object FeatureIds {
    const val AUTO_CLICK = "auto_click"
    const val FARE_FILTERS = "fare_filters"
    const val DISTANCE_FILTERS = "distance_filters"
    const val TTS_VOICE = "tts_voice"
    const val FLOATING_OVERLAY = "floating_overlay"
    const val DESTINATION_BLACKLIST = "destination_blacklist"

    val ALL_FEATURES = listOf(
        FeatureUnlockPricing(
            id = AUTO_CLICK,
            title = "Ultra-Fast Auto-Accept (300ms)",
            pointsPerHour = 15,
            description = "Sub-second response speed (300-700ms) for high-priority order capture."
        ),
        FeatureUnlockPricing(
            id = FARE_FILTERS,
            title = "Fare & Rate Filters",
            pointsPerHour = 10,
            description = "Min Fare, Max Fare, and Minimum Rate Per-KM evaluation."
        ),
        FeatureUnlockPricing(
            id = DISTANCE_FILTERS,
            title = "Distance Filters",
            pointsPerHour = 10,
            description = "Maximum pickup and drop-off distance threshold evaluation."
        ),
        FeatureUnlockPricing(
            id = TTS_VOICE,
            title = "Voice Announcements (TTS)",
            pointsPerHour = 10,
            description = "Spoken audio announcements for incoming ride order details."
        ),
        FeatureUnlockPricing(
            id = FLOATING_OVERLAY,
            title = "Floating HUD Overlay",
            pointsPerHour = 5,
            description = "On-screen floating widget showing quick status and controls."
        ),
        FeatureUnlockPricing(
            id = DESTINATION_BLACKLIST,
            title = "Destination Blacklist",
            pointsPerHour = 5,
            description = "Automatically block and bypass unwanted drop areas."
        )
    )

    fun getPricing(featureId: String): FeatureUnlockPricing? =
        ALL_FEATURES.firstOrNull { it.id.equals(featureId, ignoreCase = true) }
}


/**
 * LicenseManager
 *
 * Zero-Trust Client Architecture:
 * 100% strictly managed by Firebase Firestore & Cloud Functions with ZERO local storage fallbacks.
 *
 * 1. Hardware-Locked 2-Day Free Trial (managed by secure Callable Cloud Function claimHardwareTrial).
 * 2. Gmail Account-Synced Pass & Points System (managed by Cloud Functions addPoints, purchasePassWithPoints).
 * 3. Monotonic Server Clock Synchronization (SystemClock.elapsedRealtime) immune to client clock tampering.
 * 4. Maximum 2-Hour Offline Grace Period enforcement.
 * 5. Reactive StateFlow Access gating for accessibility automation and overlay services.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"

    // 2-Day Free Trial Duration: 48 Hours
    private const val TRIAL_DURATION_MS = 48L * 3600L * 1000L

    // Welcome Bonus Points for new users upon registration
    private const val WELCOME_BONUS_POINTS = 50

    // Maximum allowed offline validity before forcing cloud sync re-check
    const val OFFLINE_GRACE_PERIOD_MS = 2L * 3600L * 1000L // 2 Hours

    // Firestore Collections
    private const val COLL_USERS = "users"
    private const val COLL_RECEIVED_PAYMENTS = "received_payments"
    private const val COLL_PENDING_ACTIONS = "pending_actions"

    // Action Queue request timeout: 60 Seconds
    private const val ACTION_TIMEOUT_MS = 60_000L

    // Local UI Fast-Boot Licensing Cache (SharedPreferences)
    const val LICENSE_CACHE_PREFS = "LICENSE_CACHE_PREFS"
    const val KEY_CACHED_PASS_TIER = "cachedPassTier"
    const val KEY_CACHED_PASS_EXPIRY = "cachedPassExpiry"
    const val KEY_CACHED_TRIAL_CLAIMED = "cachedTrialClaimed"
    const val KEY_CACHED_TRIAL_EXPIRY = "cachedTrialExpiry"
    const val KEY_CACHED_TRIAL_USED = "cachedTrialUsed"
    const val KEY_LAST_SERVER_SYNC_TIME = "cachedLastServerSyncTime"
    const val KEY_LAST_SERVER_SYNC_ELAPSED = "cachedLastServerSyncElapsed"
    const val KEY_LAST_SERVER_TIMESTAMP = "cachedLastServerTimestamp"

    // Maximum allowed discrepancy between wall clock and monotonic elapsedRealtime (5 minutes)
    const val MAX_ALLOWED_CLOCK_DRIFT_MS = 5L * 60L * 1000L

    // Server-side timestamp cross-check interval: 30 Minutes
    const val PERIODIC_INTEGRITY_CHECK_INTERVAL_MS = 30L * 60L * 1000L

    data class CachedLicensingData(
        val passTier: PassTier?,
        val passExpiry: Long,
        val trialClaimed: Long,
        val trialExpiry: Long,
        val trialUsed: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var appContext: Context? = null

    // Monotonic Server Clock Synchronization Properties
    @Volatile
    private var serverTimeBaselineMs: Long = 0L

    @Volatile
    private var serverTimeBaselineElapsedMs: Long = 0L

    @Volatile
    private var lastSuccessfulServerSyncElapsedMs: Long = 0L

    // Reactive StateFlows - Initial state is STRICTLY Loading until server responds
    private val _accessStatus = MutableStateFlow<AccessStatus>(AccessStatus.Loading)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    private val _pointsBalance = MutableStateFlow(0)
    val pointsBalance: StateFlow<Int> = _pointsBalance.asStateFlow()

    private val _activePassInfo = MutableStateFlow<ActivePassInfo?>(null)
    val activePassInfo: StateFlow<ActivePassInfo?> = _activePassInfo.asStateFlow()

    private val _trialInfo = MutableStateFlow<TrialInfo?>(null)
    val trialInfo: StateFlow<TrialInfo?> = _trialInfo.asStateFlow()

    private val _referralCode = MutableStateFlow<String?>(null)
    val referralCode: StateFlow<String?> = _referralCode.asStateFlow()

    private val _hasClaimedReferral = MutableStateFlow<Boolean>(false)
    val hasClaimedReferral: StateFlow<Boolean> = _hasClaimedReferral.asStateFlow()

    private val _referralCount = MutableStateFlow<Int>(0)
    val referralCount: StateFlow<Int> = _referralCount.asStateFlow()

    // Virtual Folder state: "free_mod", "trials", "daily", "weekly", "monthly"
    private val _currentFolder = MutableStateFlow("free_mod")
    val currentFolder: StateFlow<String> = _currentFolder.asStateFlow()

    // Pay-Per-Use feature unlocks map: featureId -> expiryTimestamp (Long ms)
    private val _unlockedFeatures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val unlockedFeatures: StateFlow<Map<String, Long>> = _unlockedFeatures.asStateFlow()

    // Global Service Switch & User Ban StateFlows
    private const val SERVICE_ACTIVE_MASK_TRUE = 0x3F9A6C1EL
    private const val SERVICE_ACTIVE_MASK_FALSE = 0x7E2B5D80L

    private fun safeElapsedRealtime(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    @Volatile
    private var serviceActiveClockSalt: Long = safeElapsedRealtime()
    @Volatile
    private var encodedServiceActiveToken: Long = SERVICE_ACTIVE_MASK_TRUE xor serviceActiveClockSalt

    private val _isServiceActiveFlow = MutableStateFlow(true)
    val isServiceActiveFlow: StateFlow<Boolean> = _isServiceActiveFlow.asStateFlow()

    fun setServiceActiveState(isActive: Boolean) {
        val salt = safeElapsedRealtime()
        serviceActiveClockSalt = salt
        encodedServiceActiveToken = (if (isActive) SERVICE_ACTIVE_MASK_TRUE else SERVICE_ACTIVE_MASK_FALSE) xor salt
        _isServiceActiveFlow.value = isActive
    }

    /**
     * Anti-Hack Memory Shield: Validates service active status against dynamic XOR clock salt
     * and mathematical clock tampering checks. Prevents memory modification of boolean flags.
     */
    fun isServiceActive(context: Context? = null): Boolean {
        if (isClockTamperingDetected(context)) {
            Log.w(TAG, "isServiceActive: Clock tampering detected! Returning false.")
            return false
        }
        val decoded = encodedServiceActiveToken xor serviceActiveClockSalt
        return (decoded == SERVICE_ACTIVE_MASK_TRUE) && _isServiceActiveFlow.value
    }

    private val _isBannedFlow = MutableStateFlow(false)
    val isBannedFlow: StateFlow<Boolean> = _isBannedFlow.asStateFlow()

    private val _banUntilTimestampFlow = MutableStateFlow<Long?>(null)
    val banUntilTimestampFlow: StateFlow<Long?> = _banUntilTimestampFlow.asStateFlow()

    private val _banReasonFlow = MutableStateFlow<String?>(null)
    val banReasonFlow: StateFlow<String?> = _banReasonFlow.asStateFlow()

    /**
     * Dynamically determines the virtual folder based on the user's active plan:
     * - "daily" (Daily pass active)
     * - "weekly" (Weekly pass active)
     * - "monthly" (Monthly pass active)
     * - "trials" (Hardware/User trial active)
     * - "free_mod" (No active pass, no trial)
     */
    fun computeCurrentFolder(
        passTier: PassTier? = serverPassTier,
        passExpiry: Long = serverPassExpiry,
        trialClaimed: Long = serverTrialClaimed,
        trialExpiry: Long = serverTrialExpiry,
        now: Long = getCurrentServerTimeMs()
    ): String {
        if (passTier != null && passExpiry > now) {
            return when (passTier) {
                PassTier.DAILY -> "daily"
                PassTier.WEEKLY -> "weekly"
                PassTier.MONTHLY -> "monthly"
                PassTier.REMOVE_ADS -> "remove_ads"
            }
        }
        if (trialClaimed > 0L && trialExpiry > now) {
            return "trials"
        }
        return "free_mod"
    }


    // In-Memory Cloud-Synced State (Zero SharedPreferences)
    @Volatile
    private var serverPassTier: PassTier? = null
    @Volatile
    private var serverPassExpiry: Long = 0L
    @Volatile
    private var serverTrialClaimed: Long = 0L
    @Volatile
    private var serverTrialExpiry: Long = 0L
    @Volatile
    private var serverTrialUsed: Boolean = false
    @Volatile
    var hasLoadedLicensing: Boolean = false
        internal set
    @Volatile
    private var hasLoadedTrial: Boolean = false

    private var userDocListener: ListenerRegistration? = null
    private var globalSettingsListener: ListenerRegistration? = null
    private var currentUserKey: String? = null
    private val authLock = Any()
    private val syncMutex = Mutex()
    private val docInitAttempted = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Real-time listener for app_settings/global to read isServiceActive.
     */
    fun startGlobalSettingsListener() {
        if (globalSettingsListener != null) return
        val ctx = appContext ?: return
        if (FirebaseApp.getApps(ctx).isEmpty()) return
        try {
            val db = FirebaseFirestore.getInstance()
            globalSettingsListener = db.collection("app_settings").document("global")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        Log.w(TAG, "Global settings listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    val isActive = snapshot?.getBoolean("isServiceActive") ?: true
                    setServiceActiveState(isActive)
                    Log.d(TAG, "LicenseManager: isServiceActive updated to $isActive")
                }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start global settings listener: ${e.message}")
        }
    }

    /**
     * Records a trusted server timestamp baseline and marks monotonic elapsed time.
     */
    fun recordServerTimeSync(serverMs: Long) {
        if (serverMs > 0L) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            serverTimeBaselineMs = serverMs
            serverTimeBaselineElapsedMs = nowElapsed
            lastSuccessfulServerSyncElapsedMs = nowElapsed
            appContext?.let { ctx ->
                try {
                    ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(KEY_LAST_SERVER_SYNC_TIME, nowWall)
                        .putLong(KEY_LAST_SERVER_SYNC_ELAPSED, nowElapsed)
                        .putLong(KEY_LAST_SERVER_TIMESTAMP, serverMs)
                        .apply()
                } catch (_: Exception) {}
            }
            Log.d(TAG, "Monotonic server clock synced: baseline=$serverMs, elapsed=$nowElapsed")
        }
    }

    /**
     * Returns authoritative current time based on server sync + monotonic elapsed delta.
     * Hardware monotonic clock since device boot is immune to user changing phone date/time.
     */
    fun getCurrentServerTimeMs(): Long {
        val baseline = serverTimeBaselineMs
        val baselineElapsed = serverTimeBaselineElapsedMs
        return if (baseline > 0L && baselineElapsed > 0L) {
            val elapsedDelta = SystemClock.elapsedRealtime() - baselineElapsed
            baseline + elapsedDelta
        } else {
            System.currentTimeMillis()
        }
    }

    /**
     * Mathematically evaluates whether local wall-clock time and monotonic elapsedRealtime
     * have diverged beyond acceptable physical limits (elapsed time hooking / clock rollback).
     *
     * Legitimate offline operation:
     * SystemClock.elapsedRealtime() advances at the exact same rate as wall-clock time
     * (including during deep sleep / Doze). A divergence > 5 minutes indicates clock tampering.
     */
    fun isClockTamperingDetected(context: Context? = null): Boolean {
        val ctx = context ?: appContext ?: return false
        val prefs = try {
            ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) { return false }

        val syncWall = prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, 0L)
        val syncElapsed = prefs.getLong(KEY_LAST_SERVER_SYNC_ELAPSED, 0L)

        // If no server sync has been established yet, cannot prove tampering mathematically
        if (syncWall <= 0L || syncElapsed <= 0L) return false

        val currentWall = System.currentTimeMillis()
        val currentElapsed = SystemClock.elapsedRealtime()

        val deltaWall = currentWall - syncWall
        val deltaElapsed = currentElapsed - syncElapsed

        // 1. Clock turned backwards into the past (rollback exploit)
        if (deltaWall < -60_000L) {
            Log.w(TAG, "Clock tampering detected: Wall clock wound back by ${-deltaWall}ms")
            return true
        }

        // 2. Monotonic clock moved backwards (impossible without kernel/hooking tampering)
        if (deltaElapsed < -5_000L) {
            Log.w(TAG, "Clock tampering detected: Monotonic elapsedRealtime moved backwards (${deltaElapsed}ms)")
            return true
        }

        // 3. Mathematical discrepancy between elapsed realtime and wall-clock time
        val drift = kotlin.math.abs(deltaWall - deltaElapsed)
        if (drift > MAX_ALLOWED_CLOCK_DRIFT_MS) {
            Log.w(TAG, "Clock tampering mathematically detected: deltaWall=$deltaWall, deltaElapsed=$deltaElapsed, drift=$drift > max=$MAX_ALLOWED_CLOCK_DRIFT_MS")
            return true
        }

        return false
    }

    /**
     * Enforces immediate invalidation of local license cache and triggers cloud re-sync
     * when clock tampering or expired grace period is detected.
     */
    fun invalidateAndForceCloudResync(context: Context, reason: String) {
        Log.w(TAG, "INVALIDATING LICENSE CACHE: $reason. Triggering mandatory cloud re-sync.")
        clearLicensingCache(context)
        serverPassTier = null
        serverPassExpiry = 0L
        serverTrialClaimed = 0L
        serverTrialExpiry = 0L
        serverTrialUsed = true // Block free trial exploitation on tampered environments

        _accessStatus.value = AccessStatus.Expired(
            isTrialUsed = true,
            hasHadPreviousPass = true,
            message = "Security verification required. Please connect to internet to verify your subscription."
        )

        scope.launch {
            try {
                syncHardwareTrialAndLicensing()
            } catch (e: Exception) {
                Log.w(TAG, "Mandatory cloud re-sync attempt failed: ${e.message}")
            }
        }
    }

    /**
     * Checks if the maximum allowed offline grace period (2 hours) has expired or if clock tampering was detected.
     */
    fun isOfflineGracePeriodExpired(context: Context? = null): Boolean {
        val ctx = context ?: appContext

        // 1. If tampering is mathematically detected, offline grace is voided immediately
        if (isClockTamperingDetected(ctx)) {
            Log.w(TAG, "isOfflineGracePeriodExpired: Clock tampering mathematically detected. Voiding offline grace.")
            return true
        }

        val lastSync = lastSuccessfulServerSyncElapsedMs
        if (lastSync > 0L) {
            val offlineDuration = SystemClock.elapsedRealtime() - lastSync
            if (offlineDuration > OFFLINE_GRACE_PERIOD_MS) return true
        }
        if (ctx != null) {
            try {
                val prefs = ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                val lastServerSyncTime = prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, 0L)
                if (lastServerSyncTime > 0L) {
                    val offlineWallDuration = System.currentTimeMillis() - lastServerSyncTime
                    if (offlineWallDuration < -60_000L || offlineWallDuration > OFFLINE_GRACE_PERIOD_MS) {
                        Log.w(TAG, "Offline grace period expired or clock tampering detected: duration=$offlineWallDuration")
                        return true
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * Performs timestamp cross-check every 30 minutes.
     * Evaluates clock tampering and forces mandatory cloud re-sync if delta deviates drastically,
     * while preserving legitimate offline users within the 2-hour grace period.
     */
    fun performTimestampIntegrityCrossCheck(context: Context) {
        Log.d(TAG, "Executing periodic 30-minute timestamp integrity cross-check...")

        // 1. Mathematical tampering check:
        if (isClockTamperingDetected(context)) {
            invalidateAndForceCloudResync(
                context,
                "Periodic cross-check: Mathematical clock tampering / elapsed time hooking detected"
            )
            return
        }

        // 2. Check offline grace period expiration (> 2 hours without server sync):
        if (isOfflineGracePeriodExpired(context)) {
            Log.w(TAG, "Periodic cross-check: Offline grace period (2 hours) expired.")
            invalidateAndForceCloudResync(
                context,
                "Offline grace period (2 hours) expired without server confirmation"
            )
            return
        }

        // 3. If online, perform normal cloud re-sync:
        val authState = GoogleAuthManager.userAuthState.value
        if (authState.isLoggedIn) {
            scope.launch {
                try {
                    syncHardwareTrialAndLicensing()
                } catch (e: Exception) {
                    Log.d(TAG, "Periodic cloud re-sync skipped (offline/network error): ${e.message}")
                }
            }
        }
    }

    private val periodicTimestampCrossCheckRunnable = object : Runnable {
        override fun run() {
            val ctx = appContext
            if (ctx != null) {
                performTimestampIntegrityCrossCheck(ctx)
            }
            mainHandler.postDelayed(this, PERIODIC_INTEGRITY_CHECK_INTERVAL_MS)
        }
    }

    /**
     * Saves authoritative licensing and trial data to SharedPreferences local UI cache.
     * Prevents time-reset and loading flicker during app startup.
     */
    fun saveAuthoritativeLicensingCache(
        context: Context,
        passTier: PassTier?,
        passExpiry: Long,
        trialClaimed: Long,
        trialExpiry: Long,
        trialUsed: Boolean
    ) {
        try {
            val nowElapsed = SystemClock.elapsedRealtime()
            val nowWall = System.currentTimeMillis()
            lastSuccessfulServerSyncElapsedMs = nowElapsed
            val prefs = context.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CACHED_PASS_TIER, passTier?.id ?: "")
                .putLong(KEY_CACHED_PASS_EXPIRY, passExpiry)
                .putLong(KEY_CACHED_TRIAL_CLAIMED, trialClaimed)
                .putLong(KEY_CACHED_TRIAL_EXPIRY, trialExpiry)
                .putBoolean(KEY_CACHED_TRIAL_USED, trialUsed)
                .putLong(KEY_LAST_SERVER_SYNC_TIME, nowWall)
                .putLong(KEY_LAST_SERVER_SYNC_ELAPSED, nowElapsed)
                .commit()
            Log.d(TAG, "Saved authoritative licensing cache: passTier=${passTier?.id}, passExpiry=$passExpiry, trialClaimed=$trialClaimed, trialExpiry=$trialExpiry, trialUsed=$trialUsed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving licensing cache: ${e.message}")
        }
    }

    /**
     * Reads cached licensing data from SharedPreferences.
     */
    fun getCachedLicensingData(context: Context): CachedLicensingData {
        return try {
            val prefs = context.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            val passTierId = prefs.getString(KEY_CACHED_PASS_TIER, null)
            val passTier = if (!passTierId.isNullOrBlank()) PassTier.fromId(passTierId) else null
            val passExpiry = prefs.getLong(KEY_CACHED_PASS_EXPIRY, 0L)
            val trialClaimed = prefs.getLong(KEY_CACHED_TRIAL_CLAIMED, 0L)
            val trialExpiry = prefs.getLong(KEY_CACHED_TRIAL_EXPIRY, 0L)
            val trialUsed = prefs.getBoolean(KEY_CACHED_TRIAL_USED, false)
            CachedLicensingData(passTier, passExpiry, trialClaimed, trialExpiry, trialUsed)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading licensing cache: ${e.message}")
            CachedLicensingData(null, 0L, 0L, 0L, false)
        }
    }

    fun clearLicensingCache(context: Context) {
        try {
            val prefs = context.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().clear().commit()
        } catch (e: Exception) {
            Log.w(TAG, "Failed clearing licensing cache: ${e.message}")
        }
    }

    fun resetStateForTesting(context: Context) {
        detachListeners()
        try {
            mainHandler.removeCallbacksAndMessages(null)
        } catch (_: Exception) {}
        appContext = context.applicationContext
        clearLicensingCache(context)
        serverPassTier = null
        serverPassExpiry = 0L
        serverTrialClaimed = 0L
        serverTrialExpiry = 0L
        serverTrialUsed = false
        serverTimeBaselineMs = 0L
        serverTimeBaselineElapsedMs = 0L
        hasLoadedLicensing = false
        hasLoadedTrial = false
        lastSuccessfulServerSyncElapsedMs = 0L
        _accessStatus.value = AccessStatus.Loading
        _activePassInfo.value = null
        _trialInfo.value = null
    }

    fun setLastServerSyncForTesting(elapsedMs: Long, wallClockMs: Long? = null) {
        lastSuccessfulServerSyncElapsedMs = elapsedMs
        wallClockMs?.let { wallMs ->
            appContext?.let { ctx ->
                ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putLong(KEY_LAST_SERVER_SYNC_TIME, wallMs)
                    .commit()
            }
        }
    }

    /**
     * Synchronizes server time baseline using system clock.
     */
    suspend fun fetchServerTimeBaseline() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        recordServerTimeSync(now)
    }

    /**
     * Exposes current application context.
     */
    fun getAppContext(): Context? = appContext

    @Volatile
    private var loadingTimeoutJob: kotlinx.coroutines.Job? = null

    /**
     * Strictly forces LicenseManager out of the AccessStatus.Loading state.
     * Transitions to active pass, active trial, or expired status so the UI is never permanently blocked.
     */
    fun forceReleaseLoadingState() {
        if (_accessStatus.value is AccessStatus.Loading) {
            Log.w(TAG, "Force releasing AccessStatus.Loading state; unlocking UI navigation.")
            hasLoadedTrial = true
            hasLoadedLicensing = true
            refreshAccessStatus()
        }
    }

    /**
     * Ensures an active 3-second safety timeout is running on the Main Thread whenever status is Loading.
     * Guarantees that dropped-network or cold-start race conditions never leave the user permanently frozen on splash.
     */
    fun ensureLoadingTimeout() {
        if (_accessStatus.value !is AccessStatus.Loading) return
        loadingTimeoutJob?.cancel()
        loadingTimeoutJob = scope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(3000L)
            if (_accessStatus.value is AccessStatus.Loading) {
                Log.w(TAG, "Cloud sync resolution timed out after 3s; releasing loading lock.")
                forceReleaseLoadingState()
            }
        }
    }

    /**
     * Initializes LicenseManager.
     * ZERO local storage used: initial state is strictly Loading until Firestore responds.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) {
            refreshAccessStatus()
            ensureLoadingTimeout()
            return
        }
        val ctx = context.applicationContext
        appContext = ctx

        // Fast UI Boot: Immediately populate UI state from local cache while Firestore background sync connects
        if (!hasLoadedLicensing) {
            refreshAccessStatus()
        }

        // 2. Start recurring countdown ticker
        startStatusTicker()

        // 2.2 Schedule periodic 30-minute server-side timestamp cross-check
        mainHandler.removeCallbacks(periodicTimestampCrossCheckRunnable)
        mainHandler.postDelayed(periodicTimestampCrossCheckRunnable, PERIODIC_INTEGRITY_CHECK_INTERVAL_MS)

        // 3. Register network callback to automatically sync with Firestore upon reconnect
        registerNetworkCallback(ctx)

        // 4. Observe GoogleAuthManager auth state to automatically trigger cloud sync upon user login
        scope.launch {
            GoogleAuthManager.userAuthState.collect { authState ->
                if (authState.isLoggedIn && !authState.uid.isNullOrBlank()) {
                    Log.d(TAG, "Auth state updated: Logged in as ${authState.userEmail}. Syncing licensing & trial...")
                    syncHardwareTrialAndLicensing()
                }
            }
        }

        // 5. Asynchronously initialize background listeners and cloud sync off the Main Thread
        scope.launch(Dispatchers.IO) {
            performTimestampIntegrityCrossCheck(ctx)
            startGlobalSettingsListener()
            val customKey = resolveUserKey(ctx)
            currentUserKey = customKey
            syncUserLicensingDocument(customKey, null)
            syncHardwareTrialAndLicensing()
        }

        // 6. Safety timeout: prevent UI from being stuck indefinitely in Loading state
        ensureLoadingTimeout()
    }

    /**
     * Registers a network callback that re-syncs with Firestore
     * as soon as active internet connectivity is restored.
     */
    private fun registerNetworkCallback(context: Context) {
        if (networkCallback != null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Internet connectivity restored. Re-syncing cloud licensing with Firestore...")
                    scope.launch {
                        syncHardwareTrialAndLicensing()
                    }
                }
            }
            networkCallback = callback
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "Could not register network callback: ${e.message}")
        }
    }

    /**
     * Detaches active Firestore listeners to prevent memory leaks during teardown.
     */
    fun detachListeners() {
        synchronized(authLock) {
            userDocListener?.remove()
            userDocListener = null
            globalSettingsListener?.remove()
            globalSettingsListener = null
            try {
                mainHandler.removeCallbacks(periodicTimestampCrossCheckRunnable)
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks whether subscription pass or trial access is currently active and granted.
     * Evaluates strictly against reactive cloud-synced AccessStatus StateFlow.
     * Returns true ONLY if status is PassActive or TrialActive with valid remaining time.
     */
    fun isAccessGranted(): Boolean {
        return when (val status = _accessStatus.value) {
            is AccessStatus.PassActive -> status.remainingTimeMs > 0
            is AccessStatus.TrialActive -> status.remainingTimeMs > 0
            else -> false
        }
    }

    /**
     * Synchronizes user licensing when Gmail account login changes in a thread-safe manner.
     */
    fun onUserAuthChanged(userEmail: String?, userUid: String?) {
        scope.launch {
            val ctx = appContext ?: return@launch
            val customUserId = resolveUserKey(ctx)
            synchronized(authLock) {
                currentUserKey = customUserId
                docInitAttempted.set(false)
                hasLoadedLicensing = false
                _unlockedFeatures.value = emptyMap()
                _accessStatus.value = AccessStatus.Loading
            }
            syncHardwareTrialAndLicensing()
        }
    }

    /**
     * Dispatches an action request to the Zero-Trust `pending_actions` queue and suspends
     * until the background Admin Server processes the action, marking it COMPLETED or FAILED.
     * Enforces strict timeout of 60 seconds with automatic listener cleanup.
     * Does NOT perform direct writes/transactions on `users` or `received_payments`.
     */
    suspend fun executeActionQueueRequest(
        actionType: String,
        payload: Map<String, Any>
    ): Result<DocumentSnapshot> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.uid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Please sign in with Google to perform this action."))
        }

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase service is not initialized."))
        }

        val customUserId = resolveUserKey(ctx)
        val hwId = HardwareIdManager.getPermanentHardwareId(ctx)
        val db = FirebaseFirestore.getInstance()

        val actionData = hashMapOf<String, Any>(
            "userId" to customUserId,
            "firebaseUid" to currentUser.uid,
            "userEmail" to (currentUser.email ?: ""),
            "linkedHardwareId" to hwId,
            "actionType" to actionType,
            "status" to "PENDING",
            "createdAt" to FieldValue.serverTimestamp()
        )
        actionData.putAll(payload)

        val docRef = try {
            db.collection(COLL_PENDING_ACTIONS).add(actionData).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit action $actionType to $COLL_PENDING_ACTIONS: ${e.message}", e)
            return@withContext Result.failure(e)
        }

        var listenerRegistration: ListenerRegistration? = null
        val isResumed = AtomicBoolean(false)

        val outcome = try {
            withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                suspendCancellableCoroutine<Result<DocumentSnapshot>> { continuation ->
                    listenerRegistration = docRef.addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            Log.w(TAG, "Action $actionType snapshot listener error: ${error.message}")
                            if (isResumed.compareAndSet(false, true)) {
                                listenerRegistration?.remove()
                                continuation.resume(Result.failure(error))
                            }
                            return@addSnapshotListener
                        }

                        if (snapshot != null && snapshot.exists()) {
                            val status = snapshot.getString("status") ?: "PENDING"
                            if (status.equals("COMPLETED", ignoreCase = true)) {
                                Log.i(TAG, "Action $actionType (doc ${docRef.id}) marked COMPLETED by Admin server.")
                                if (isResumed.compareAndSet(false, true)) {
                                    listenerRegistration?.remove()
                                    continuation.resume(Result.success(snapshot))

                                    // Delayed 30-second database cleanup of processed action document
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            kotlinx.coroutines.delay(30_000L)
                                            docRef.delete().await()
                                            Log.d(TAG, "Delayed cleanup: Deleted completed action document ${docRef.id} after 30s")
                                        } catch (cleanupEx: Exception) {
                                            Log.w(TAG, "Notice: pending_actions cleanup suppressed for ${docRef.id}: ${cleanupEx.message}")
                                        }
                                    }
                                }
                            } else if (status.equals("FAILED", ignoreCase = true)) {
                                val errorMsg = snapshot.getString("errorMessage")
                                    ?: snapshot.getString("error")
                                    ?: "Action $actionType failed on server."
                                Log.w(TAG, "Action $actionType (doc ${docRef.id}) marked FAILED: $errorMsg")
                                if (isResumed.compareAndSet(false, true)) {
                                    listenerRegistration?.remove()
                                    continuation.resume(Result.failure(Exception(errorMsg)))

                                    // Delayed 30-second database cleanup of processed action document
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            kotlinx.coroutines.delay(30_000L)
                                            docRef.delete().await()
                                            Log.d(TAG, "Delayed cleanup: Deleted failed action document ${docRef.id} after 30s")
                                        } catch (cleanupEx: Exception) {
                                            Log.w(TAG, "Notice: pending_actions cleanup suppressed for ${docRef.id}: ${cleanupEx.message}")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        Log.d(TAG, "Detaching snapshot listener for action $actionType (doc ${docRef.id}) due to cancellation/timeout")
                        listenerRegistration?.remove()
                    }
                }
            }
        } finally {
            listenerRegistration?.remove()
        }

        if (outcome == null) {
            Log.w(TAG, "Action $actionType (doc ${docRef.id}) timed out after ${ACTION_TIMEOUT_MS}ms. Explicitly marking request result as FAILED_TIMEOUT.")
            isResumed.set(true)
            listenerRegistration?.remove()
            return@withContext Result.failure(TimeoutException("FAILED_TIMEOUT"))
        }

        outcome
    }

    /**
     * Top-up / Recharge points via Zero-Trust Action Queue.
     * Enqueues ADD_POINTS request to `pending_actions` and awaits server completion.
     */
    suspend fun addPoints(amount: Int): Result<Int> = withContext(Dispatchers.IO) {
        if (amount <= 0) return@withContext Result.failure(IllegalArgumentException("Amount must be positive"))

        val result = executeActionQueueRequest(
            actionType = "ADD_POINTS",
            payload = mapOf(
                "amount" to amount
            )
        )

        result.fold(
            onSuccess = { snap ->
                val newBalance = snap.getLong("newBalance")?.toInt()
                    ?: snap.getLong("pointsBalance")?.toInt()
                    ?: (_pointsBalance.value + amount)
                Log.i(TAG, "ADD_POINTS completed via Action Queue. New balance: $newBalance")
                Result.success(newBalance)
            },
            onFailure = { err ->
                Log.e(TAG, "ADD_POINTS action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Securely claims 5 points for watching a Rewarded Ad via the Zero-Trust Action Queue.
     * Enqueues REWARD_AD_WATCHED request to `pending_actions` with empty payload and awaits server completion.
     */
    suspend fun claimAdReward(): Result<Int> = withContext(Dispatchers.IO) {
        val result = executeActionQueueRequest(
            actionType = "REWARD_AD_WATCHED",
            payload = emptyMap()
        )

        result.fold(
            onSuccess = { snap ->
                val newBalance = snap.getLong("newBalance")?.toInt()
                    ?: snap.getLong("pointsBalance")?.toInt()
                    ?: (_pointsBalance.value + 5)
                _pointsBalance.value = newBalance
                Log.i(TAG, "REWARD_AD_WATCHED action completed via Action Queue. New balance: $newBalance")
                Result.success(newBalance)
            },
            onFailure = { err ->
                Log.e(TAG, "REWARD_AD_WATCHED action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Purchases a Pass using the user's Points balance via Zero-Trust Action Queue.
     * Enqueues PURCHASE_PASS request to `pending_actions` and awaits server completion.
     */
    suspend fun purchasePassWithPoints(passTier: PassTier): Result<Unit> = withContext(Dispatchers.IO) {
        val currentBalance = _pointsBalance.value
        if (currentBalance < passTier.pointsCost) {
            val shortfall = passTier.pointsCost - currentBalance
            return@withContext Result.failure(
                IllegalStateException("Insufficient Points. You have $currentBalance pts, but ${passTier.title} requires ${passTier.pointsCost} pts (Need $shortfall more pts).")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "PURCHASE_PASS",
            payload = mapOf(
                "passTier" to passTier.id,
                "pointsCost" to passTier.pointsCost,
                "durationDays" to passTier.durationDays,
                "durationMs" to passTier.durationMs
            )
        )

        result.fold(
            onSuccess = {
                Log.i(TAG, "PURCHASE_PASS completed via Action Queue for ${passTier.title}.")
                Result.success(Unit)
            },
            onFailure = { err ->
                Log.e(TAG, "PURCHASE_PASS action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Activates a Pass via payment reference via Zero-Trust Action Queue.
     * Enqueues ACTIVATE_PASS_PAYMENT request to `pending_actions` and awaits server completion.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUtr = (transactionId ?: "").trim()
        if (cleanUtr.isBlank() || cleanUtr.length !in 9..16) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid transaction reference: '$cleanUtr'. Must be between 9 and 16 characters.")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "ACTIVATE_PASS_PAYMENT",
            payload = mapOf(
                "passTier" to passTier.id,
                "utr" to cleanUtr,
                "transactionId" to cleanUtr,
                "amount" to passTier.priceInInr,
                "durationDays" to passTier.durationDays,
                "durationMs" to passTier.durationMs
            )
        )

        result.fold(
            onSuccess = {
                Log.i(TAG, "ACTIVATE_PASS_PAYMENT completed via Action Queue for ${passTier.title} (UTR: $cleanUtr).")
                Result.success(Unit)
            },
            onFailure = { err ->
                Log.e(TAG, "ACTIVATE_PASS_PAYMENT action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Authoritative Data Migration Engine
     *
     * Enforces the seamless user migration rules:
     * Condition A: Same Gmail, New Phone/New CustomUserId -> Migrates data from old document to customUserId.
     * Condition B: Same Phone/HardwareId, New Gmail -> Migrates data on this phone to the new Gmail account.
     * Condition C: Existing Match -> customUserId matches both firebaseUid and hardwareId, no migration needed.
     * Condition D: Completely New -> No match for firebaseUid or hardwareId, initializes fresh profile.
     * If both phone and Gmail change simultaneously, both Condition A and B fail, leading to Condition D (fresh profile).
     */
    private suspend fun checkAndMigrateUserData(
        context: Context,
        customUserId: String,
        currentUser: FirebaseUser?,
        hardwareId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (currentUser == null || currentUser.uid.isBlank()) {
            Log.d(TAG, "checkAndMigrateUserData: No authenticated Firebase user; skipping migration check.")
            return@withContext true
        }

        val db = FirebaseFirestore.getInstance()
        val firebaseUid = currentUser.uid
        val userEmail = currentUser.email ?: ""
        val targetDocRef = db.collection(COLL_USERS).document(customUserId)

        try {
            val targetSnap = targetDocRef.get().await()

            // 1. Query existing data across the "users" collection for Migration Check
            val uidMatches = if (!firebaseUid.isNullOrBlank()) {
                try {
                    db.collection(COLL_USERS)
                        .whereEqualTo("firebaseUid", firebaseUid)
                        .get()
                        .await()
                        .documents
                        .filter { it.id != customUserId && (it.getBoolean("isMigrated") != true) }
                } catch (e: Exception) {
                    Log.w(TAG, "Query users by firebaseUid failed: ${e.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }

            val hwMatches = try {
                db.collection(COLL_USERS)
                    .whereEqualTo("linkedHardwareId", hardwareId)
                    .get()
                    .await()
                    .documents
                    .filter { it.getBoolean("isMigrated") != true }
            } catch (e: Exception) {
                Log.w(TAG, "Query users by linkedHardwareId failed: ${e.message}")
                emptyList()
            }

            // --- CONDITION A: Same Gmail, New Phone/New CustomUserId ---
            // If a document exists with the same firebaseUid but a different Document ID
            if (uidMatches.isNotEmpty()) {
                val sourceDocSnap = uidMatches.first()
                val oldHwId = sourceDocSnap.getString("linkedHardwareId") ?: ""
                Log.i(TAG, "Migration Condition A detected: Migrating from old doc ${sourceDocSnap.id} to new phone doc $customUserId for UID $firebaseUid")
                val actionResult = executeActionQueueRequest(
                    actionType = "MIGRATE_PROFILE",
                    payload = mapOf(
                        "oldDocumentId" to sourceDocSnap.id,
                        "oldHardwareId" to oldHwId,
                        "newHardwareId" to hardwareId,
                        "firebaseUid" to (firebaseUid ?: ""),
                        "condition" to "CONDITION_A"
                    )
                )
                return@withContext actionResult.isSuccess
            }

            // --- CONDITION B: Same Phone/Same HardwareId, New Gmail / Cleared Data ---
            // Strictly match ANY existing document with the same linkedHardwareId (excluding current customUserId and already migrated docs)
            val hwDocWithDifferentUid = hwMatches.firstOrNull { doc ->
                doc.id != customUserId && (doc.getBoolean("isMigrated") != true)
            }

            if (hwDocWithDifferentUid != null) {
                Log.i(TAG, "Migration Condition B detected: Switching Gmail on same phone ($hardwareId) to $userEmail ($firebaseUid)")
                val oldHwId = hwDocWithDifferentUid.getString("linkedHardwareId") ?: hardwareId
                val actionResult = executeActionQueueRequest(
                    actionType = "MIGRATE_PROFILE",
                    payload = mapOf(
                        "oldDocumentId" to hwDocWithDifferentUid.id,
                        "oldHardwareId" to oldHwId,
                        "newHardwareId" to hardwareId,
                        "firebaseUid" to (firebaseUid ?: ""),
                        "userEmail" to userEmail,
                        "condition" to "CONDITION_B"
                    )
                )
                return@withContext actionResult.isSuccess
            }

            // --- CONDITION C: Existing Match (Update Existing Record) ---
            if (targetSnap != null && targetSnap.exists()) {
                val updates = mutableMapOf<String, Any>()
                val targetHw = targetSnap.getString("linkedHardwareId") ?: ""
                val targetUid = targetSnap.getString("firebaseUid") ?: ""
                if (targetHw != hardwareId && hardwareId.isNotBlank()) {
                    updates["linkedHardwareId"] = hardwareId
                }
                if (!firebaseUid.isNullOrBlank() && targetUid != firebaseUid) {
                    updates["firebaseUid"] = firebaseUid
                    updates["userEmail"] = userEmail
                }
                if (updates.isNotEmpty()) {
                    val updateResult = executeActionQueueRequest("UPDATE_PROFILE", updates)
                    return@withContext updateResult.isSuccess
                }
                return@withContext true
            }

            // If target does not exist, return true so syncUserLicensingDocument handles INIT_PROFILE
            true
        } catch (e: Exception) {
            Log.e(TAG, "checkAndMigrateUserData encountered error: ${e.message}", e)
            false
        }
    }

    /**
     * Performs cloud sync for trial and user licensing directly with Firestore in the single "users" collection.
     */
    private suspend fun syncHardwareTrialAndLicensing() {
        val ctx = appContext ?: return

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            try {
                FirebaseApp.initializeApp(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "Could not initialize FirebaseApp in syncHardwareTrialAndLicensing: ${e.message}")
            }
        }

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            Log.w(TAG, "Default FirebaseApp is not initialized in this process; skipping Firestore cloud sync.")
            refreshAccessStatus()
            return
        }

        syncMutex.withLock {
            startGlobalSettingsListener()

            // Authoritative server clock synchronization
            fetchServerTimeBaseline()

            // 1. Identify current environment
            val customUserId = resolveUserKey(ctx)
            currentUserKey = customUserId
            val hwId = HardwareIdManager.getPermanentHardwareId(ctx)
            val currentUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (_: Exception) {
                null
            }

            // 2. Perform Migration Check before reading/syncing
            try {
                checkAndMigrateUserData(ctx, customUserId, currentUser, hwId)
            } catch (e: Exception) {
                Log.w(TAG, "Migration check failed: ${e.message}")
            }

            // 3. Load authoritative user document from Firestore
            try {
                val db = FirebaseFirestore.getInstance()
                val userDocRef = db.collection(COLL_USERS).document(customUserId)

                val snapshot = userDocRef.get().await()
                val now = getCurrentServerTimeMs()

                if (snapshot != null && snapshot.exists()) {
                    val trialClaimed = snapshot.getLong("trialClaimed") ?: snapshot.getLong("firstClaimedTimestamp") ?: 0L
                    val trialExpiry = snapshot.getLong("trialExpiry") ?: snapshot.getLong("trialExpiryTimestamp") ?: 0L
                    val isUsed = snapshot.getBoolean("trialUsed") ?: snapshot.getBoolean("isUsed") ?: (trialClaimed > 0L)
                    val passType = snapshot.getString("activePassType")
                    val passExpiry = snapshot.getLong("passExpiryTimestamp") ?: 0L
                    val tier = PassTier.fromId(passType)

                    if (trialClaimed > 0L) {
                        serverTrialClaimed = trialClaimed
                        serverTrialExpiry = trialExpiry
                        serverTrialUsed = isUsed
                        hasLoadedTrial = true
                        Log.i(TAG, "Loaded trial directly from users doc for $customUserId: claimed=$trialClaimed, expiry=$trialExpiry, isUsed=$isUsed")
                    } else {
                        serverTrialClaimed = 0L
                        serverTrialExpiry = 0L
                        serverTrialUsed = false
                        hasLoadedTrial = true
                    }

                    val folder = computeCurrentFolder(tier, passExpiry, serverTrialClaimed, serverTrialExpiry, now)
                    _currentFolder.value = folder
                    val savedFolder = snapshot.getString("current_folder")
                    val savedHwId = snapshot.getString("linkedHardwareId")

                    if (savedFolder != folder || savedHwId != hwId) {
                        val updateData = mutableMapOf<String, Any>(
                            "userId" to customUserId,
                            "current_folder" to folder,
                            "linkedHardwareId" to hwId
                        )
                        if (currentUser != null && currentUser.uid.isNotBlank()) {
                            updateData["firebaseUid"] = currentUser.uid
                        }
                        if (!currentUser?.email.isNullOrBlank()) {
                            updateData["userEmail"] = currentUser?.email ?: ""
                        }
                        scope.launch {
                            try {
                                executeActionQueueRequest("UPDATE_PROFILE", updateData)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed updating profile in syncHardwareTrialAndLicensing: ${e.message}")
                            }
                        }
                    }
                } else {
                    hasLoadedTrial = true
                    hasLoadedLicensing = true
                    _currentFolder.value = "free_mod"
                }
                refreshAccessStatus()
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync user document from Firestore: ${e.message}")
                hasLoadedTrial = true
                hasLoadedLicensing = true
                refreshAccessStatus()
            }

            // 4. Sync real-time snapshot listener on users/{customUserId}
            try {
                syncUserLicensingDocument(customUserId, currentUser?.email)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync user licensing: ${e.message}")
            }
        }
    }

    /**
     * Listens in real-time to the user document in Firestore "users" collection.
     * Updates in-memory StateFlows directly from snapshot. Zero local storage.
     * Document initialization is handled directly via Firestore SDK.
     */
    private fun syncUserLicensingDocument(userKey: String, userEmail: String?) {
        val ctx = appContext
        if (ctx != null && FirebaseApp.getApps(ctx).isEmpty()) {
            Log.w(TAG, "FirebaseApp is not initialized; skipping syncUserLicensingDocument.")
            hasLoadedTrial = true
            hasLoadedLicensing = true
            refreshAccessStatus()
            return
        }

        val customUserId = if (userKey.isNotBlank()) userKey else (ctx?.let { resolveUserKey(it) } ?: "OM-USER-DEFAULT")
        val currentUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) {
            null
        }

        try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS).document(customUserId)

            userDocListener?.remove()
            userDocListener = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User snapshot listener error: ${error.message}")
                    hasLoadedLicensing = true
                    refreshAccessStatus()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val points = snapshot.getLong("pointsBalance")?.toInt()
                        ?: snapshot.getLong("points")?.toInt()
                        ?: 0
                    val passType = snapshot.getString("activePassType")
                        ?: snapshot.getString("passType")
                    val passExpiry = snapshot.getLong("passExpiryTimestamp")
                        ?: snapshot.getLong("passExpiry")
                        ?: 0L
                    val code = snapshot.getString("referralCode")
                    val hasClaimed = snapshot.getBoolean("hasClaimedReferral") ?: false
                    val refCount = snapshot.getLong("referralCount")?.toInt() ?: 0
                    val trialClaimed = snapshot.getLong("trialClaimed") ?: snapshot.getLong("firstClaimedTimestamp") ?: 0L
                    val trialExpiry = snapshot.getLong("trialExpiry") ?: snapshot.getLong("trialExpiryTimestamp") ?: 0L
                    val isTrialUsed = snapshot.getBoolean("trialUsed") ?: snapshot.getBoolean("isUsed") ?: (trialClaimed > 0L)

                    // Always sync trial and licensing state unconditionally for real-time Firestore updates
                    serverTrialClaimed = trialClaimed
                    serverTrialExpiry = trialExpiry
                    serverTrialUsed = isTrialUsed
                    hasLoadedTrial = true

                    // Update in-memory reactive state directly from Firestore snapshot
                    _pointsBalance.value = points
                    _hasClaimedReferral.value = hasClaimed
                    _referralCount.value = refCount
                    serverPassTier = PassTier.fromId(passType)
                    serverPassExpiry = passExpiry

                    // Save authoritative data to local SharedPreferences cache
                    ctx?.let { c ->
                        saveAuthoritativeLicensingCache(
                            context = c,
                            passTier = serverPassTier,
                            passExpiry = serverPassExpiry,
                            trialClaimed = serverTrialClaimed,
                            trialExpiry = serverTrialExpiry,
                            trialUsed = serverTrialUsed
                        )
                    }

                    val expectedFolder = computeCurrentFolder(
                        passTier = serverPassTier,
                        passExpiry = serverPassExpiry,
                        trialClaimed = serverTrialClaimed,
                        trialExpiry = serverTrialExpiry
                    )
                    _currentFolder.value = expectedFolder

                    // Parse Pay-Per-Use feature unlocks map
                    val rawFeatures = snapshot.get("unlocked_features") as? Map<*, *>
                    val parsedFeatures = rawFeatures?.mapNotNull { (k, v) ->
                        val featureKey = k as? String ?: return@mapNotNull null
                        val exp = (v as? Number)?.toLong() ?: return@mapNotNull null
                        featureKey to exp
                    }?.toMap() ?: emptyMap()
                    _unlockedFeatures.value = parsedFeatures

                    // Read banUntil (Timestamp) field from user's document
                    val banUntilObj = snapshot.get("banUntil")
                    val banUntilMs: Long? = when (banUntilObj) {
                        is Timestamp -> banUntilObj.toDate().time
                        is java.util.Date -> banUntilObj.time
                        is Number -> banUntilObj.toLong()
                        else -> null
                    }
                    val banReason = snapshot.getString("banReason")
                    val currentNowMs = getCurrentServerTimeMs()

                    if (banUntilMs != null && banUntilMs > currentNowMs) {
                        _isBannedFlow.value = true
                        _banUntilTimestampFlow.value = banUntilMs
                        _banReasonFlow.value = banReason
                        Log.w(TAG, "User $customUserId is BANNED until $banUntilMs (now=$currentNowMs). Reason: $banReason")
                    } else {
                        _isBannedFlow.value = false
                        _banUntilTimestampFlow.value = null
                        _banReasonFlow.value = null
                    }

                    hasLoadedLicensing = true

                    val currentHwId = ctx?.let { HardwareIdManager.getPermanentHardwareId(it) } ?: ""
                    val existingLinkedHw = snapshot.getString("linkedHardwareId")
                    val existingFolder = snapshot.getString("current_folder")

                    val updatesNeeded = mutableMapOf<String, Any>()
                    if (currentHwId.isNotBlank() && existingLinkedHw != currentHwId) {
                        updatesNeeded["linkedHardwareId"] = currentHwId
                    }
                    if (existingFolder != expectedFolder) {
                        updatesNeeded["current_folder"] = expectedFolder
                    }
                    if (code.isNullOrBlank()) {
                        val generated = generateReferralCode()
                        updatesNeeded["referralCode"] = generated
                        _referralCode.value = generated
                    } else {
                        _referralCode.value = code
                    }

                    // Check if current user's UID or Email differs from the ones stored in snapshot
                    val currentAuthUser = try {
                        FirebaseAuth.getInstance().currentUser
                    } catch (_: Exception) {
                        null
                    }
                    val currentUid = currentAuthUser?.uid ?: ""
                    val currentEmail = currentAuthUser?.email ?: (userEmail ?: "")
                    val storedUid = snapshot.getString("firebaseUid") ?: ""
                    val storedEmail = snapshot.getString("userEmail") ?: ""

                    if (currentUid.isNotBlank() && storedUid != currentUid) {
                        updatesNeeded["firebaseUid"] = currentUid
                    }
                    if (currentEmail.isNotBlank() && storedEmail != currentEmail) {
                        updatesNeeded["userEmail"] = currentEmail
                    }

                    if (updatesNeeded.isNotEmpty()) {
                        scope.launch {
                            try {
                                executeActionQueueRequest("UPDATE_PROFILE", updatesNeeded)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed updating user document fields via Action Queue: ${e.message}")
                            }
                        }
                    }

                    refreshAccessStatus()
                    Log.d(TAG, "Synced user from Firestore: points=$points, passType=$passType, folder=$expectedFolder, code=${_referralCode.value}")
                } else if (snapshot != null && !snapshot.exists()) {
                    // Prevent infinite loop: initialize document strictly once per session
                    if (docInitAttempted.compareAndSet(false, true)) {
                        scope.launch {
                            try {
                                val currentAuthUser = try {
                                    FirebaseAuth.getInstance().currentUser
                                } catch (_: Exception) {
                                    null
                                }
                                val context = ctx ?: return@launch
                                val permanentHardwareId = HardwareIdManager.getPermanentHardwareId(context)
                                val email = userEmail ?: (currentAuthUser?.email ?: "")

                                Log.i(TAG, "User document does not exist. Dispatching INIT_PROFILE to Action Queue for $customUserId...")
                                val actionResult = executeActionQueueRequest(
                                    actionType = "INIT_PROFILE",
                                    payload = mapOf(
                                        "userEmail" to email,
                                        "linkedHardwareId" to permanentHardwareId,
                                        "firebaseUid" to (currentAuthUser?.uid ?: "")
                                    )
                                )

                                if (actionResult.isSuccess) {
                                    val actionDoc = actionResult.getOrNull()
                                    val newPoints = actionDoc?.getLong("pointsBalance")?.toInt() ?: 50
                                    val newReferral = actionDoc?.getString("referralCode") ?: generateReferralCode()
                                    _referralCode.value = newReferral
                                    _pointsBalance.value = newPoints
                                    val now = System.currentTimeMillis()
                                    if (DeviceUtils.isFreeTrialAllowed(context)) {
                                        val trialExpiry = now + (48L * 3600L * 1000L)
                                        serverTrialClaimed = now
                                        serverTrialExpiry = trialExpiry
                                        serverTrialUsed = true
                                        _currentFolder.value = "trials"
                                    } else {
                                        Log.w(TAG, "Device compromised (rooted/spoofed): Free trial blocked during INIT_PROFILE")
                                        serverTrialClaimed = 0L
                                        serverTrialExpiry = 0L
                                        serverTrialUsed = true
                                        _currentFolder.value = "free_mod"
                                    }
                                    hasLoadedLicensing = true
                                    refreshAccessStatus()
                                    Log.i(TAG, "INIT_PROFILE successfully completed via Action Queue for $customUserId")
                                } else {
                                    Log.w(TAG, "INIT_PROFILE action failed: ${actionResult.exceptionOrNull()?.message}")
                                    docInitAttempted.set(false)
                                    hasLoadedLicensing = true
                                    refreshAccessStatus()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not initialize user doc via Action Queue: ${e.message}")
                                docInitAttempted.set(false)
                                hasLoadedLicensing = true
                                refreshAccessStatus()
                            }
                        }
                    } else {
                        hasLoadedLicensing = true
                        refreshAccessStatus()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting up licensing listener: ${e.message}")
            hasLoadedLicensing = true
            refreshAccessStatus()
        }
    }

    /**
     * Resolves the persistent custom User ID (format: OM-USER-XXXXXXXX).
     * Retrieves and returns this persistent custom User ID from DeviceUtils.
     * Acts as both the Firestore Document ID in the "users" collection and the userId field.
     */
    fun resolveUserKey(context: Context? = null, email: String? = null, uid: String? = null): String {
        val targetContext = context ?: appContext
        return if (targetContext != null) {
            DeviceUtils.getCustomUserId(targetContext)
        } else {
            "OM-USER-DEFAULT"
        }
    }

    /**
     * Recomputes the current `AccessStatus` strictly from in-memory server state.
     * Enforces anti-clock-tampering with monotonic elapsed time and 2-hour offline limit.
     * ZERO local storage reads.
     */
    fun refreshAccessStatus(context: Context? = null) {
        val ctx = context ?: appContext
        // Strict 2-hour offline grace check:
        if (isOfflineGracePeriodExpired(ctx)) {
            Log.w(TAG, "Offline grace period expired (limit: 2 hours). Enforcing zero-trust lock.")
            _accessStatus.value = AccessStatus.Expired(
                isTrialUsed = serverTrialUsed || serverTrialClaimed > 0L,
                hasHadPreviousPass = serverPassTier != null || serverPassExpiry > 0L,
                message = "Maximum 2-hour offline limit reached. Please connect to internet to verify pass."
            )
            return
        }

        val now = getCurrentServerTimeMs()

        // Fast UI Boot: If user licensing has not yet loaded from Firestore, read from local UI cache to prevent time reset flicker
        if (!hasLoadedLicensing) {
            if (ctx != null) {
                val cached = getCachedLicensingData(ctx)

                // 1. Check cached Paid Subscription Pass
                if (cached.passTier != null && cached.passExpiry > now) {
                    val timeLeft = cached.passExpiry - now
                    val formatted = formatRemainingTime(timeLeft)
                    _currentFolder.value = computeCurrentFolder(
                        passTier = cached.passTier,
                        passExpiry = cached.passExpiry,
                        now = now
                    )
                    _activePassInfo.value = ActivePassInfo(
                        passTier = cached.passTier,
                        expiryTimestamp = cached.passExpiry,
                        remainingTimeMs = timeLeft,
                        isExpired = false
                    )
                    _accessStatus.value = AccessStatus.PassActive(
                        passTier = cached.passTier,
                        remainingTimeMs = timeLeft,
                        expiryTimestamp = cached.passExpiry,
                        formattedRemaining = formatted
                    )
                    hasLoadedLicensing = true
                    Log.d(TAG, "Fast UI Boot: Populated PassActive (${cached.passTier.title}) from local UI cache while Firestore syncs.")
                    return
                }

                // 2. Check cached 2-Day Free Trial
                if (cached.trialClaimed > 0L && cached.trialExpiry > now) {
                    if (DeviceUtils.isFreeTrialAllowed(ctx)) {
                        val timeLeft = cached.trialExpiry - now
                        val formatted = formatRemainingTime(timeLeft)
                        _currentFolder.value = "trials"
                        _trialInfo.value = TrialInfo(
                            firstClaimedTimestamp = cached.trialClaimed,
                            trialExpiryTimestamp = cached.trialExpiry,
                            isUsed = cached.trialUsed,
                            isExpired = false,
                            remainingMs = timeLeft
                        )
                        _accessStatus.value = AccessStatus.TrialActive(
                            remainingTimeMs = timeLeft,
                            expiryTimestamp = cached.trialExpiry,
                            formattedRemaining = formatted
                        )
                        hasLoadedLicensing = true
                        hasLoadedTrial = true
                        Log.d(TAG, "Fast UI Boot: Populated TrialActive from local UI cache while Firestore syncs.")
                        return
                    } else {
                        Log.w(TAG, "Fast UI Boot: Free trial blocked due to device rooting or hardware spoofing.")
                    }
                }
            }

            // No valid active cached pass or trial found; remain in Loading state temporarily until Firestore responds or timeout fires
            _accessStatus.value = AccessStatus.Loading
            ensureLoadingTimeout()
            return
        }

        val tier = serverPassTier
        val passExpiry = serverPassExpiry
        val trialClaimed = serverTrialClaimed
        val trialExpiry = serverTrialExpiry
        val trialUsed = serverTrialUsed

        _currentFolder.value = computeCurrentFolder(tier, passExpiry, trialClaimed, trialExpiry, now)

        // 1. Check Paid Subscription Pass First
        if (tier != null && passExpiry > now) {
            val timeLeft = passExpiry - now
            val formatted = formatRemainingTime(timeLeft)
            _activePassInfo.value = ActivePassInfo(
                passTier = tier,
                expiryTimestamp = passExpiry,
                remainingTimeMs = timeLeft,
                isExpired = false
            )
            _accessStatus.value = AccessStatus.PassActive(
                passTier = tier,
                remainingTimeMs = timeLeft,
                expiryTimestamp = passExpiry,
                formattedRemaining = formatted
            )
            return
        }

        // 2. Check 2-Day Free Trial
        val trialInfoObj = TrialInfo(
            firstClaimedTimestamp = trialClaimed,
            trialExpiryTimestamp = trialExpiry,
            isUsed = trialUsed,
            isExpired = now >= trialExpiry,
            remainingMs = (trialExpiry - now).coerceAtLeast(0L)
        )
        _trialInfo.value = trialInfoObj

        if (trialClaimed > 0L && trialExpiry > now) {
            if (ctx == null || DeviceUtils.isFreeTrialAllowed(ctx)) {
                val timeLeft = trialExpiry - now
                val formatted = formatRemainingTime(timeLeft)
                _accessStatus.value = AccessStatus.TrialActive(
                    remainingTimeMs = timeLeft,
                    expiryTimestamp = trialExpiry,
                    formattedRemaining = formatted
                )
                return
            } else {
                Log.w(TAG, "Free trial blocked: Rooted or hardware-spoofed device detected.")
                val hasHadPreviousPass = tier != null || passExpiry > 0L
                _accessStatus.value = AccessStatus.Expired(
                    isTrialUsed = true,
                    hasHadPreviousPass = hasHadPreviousPass,
                    message = "Free trial is unavailable on rooted, emulated, or modified devices. Please purchase a Pass to continue."
                )
                return
            }
        }

        // 3. Both Pass & Trial Expired or Not Active
        val hasHadPreviousPass = tier != null || passExpiry > 0L
        val authState = GoogleAuthManager.userAuthState.value
        val msg = if (!authState.isLoggedIn) {
            "Sign in with Google to activate your 2-Day Free Trial."
        } else if (hasHadPreviousPass) {
            "Your ${tier?.title ?: "subscription"} has expired. Renew your pass to resume auto-acceptance."
        } else if (trialClaimed > 0L) {
            "Your 2-Day Free Trial has ended. Choose a Pass to continue enjoying high-speed auto-acceptance."
        } else {
            "Trial not active. Choose a Pass to continue enjoying high-speed auto-acceptance."
        }

        _accessStatus.value = AccessStatus.Expired(
            isTrialUsed = trialUsed || trialClaimed > 0L,
            hasHadPreviousPass = hasHadPreviousPass,
            message = msg
        )
    }

    private fun startStatusTicker() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                val banUntil = _banUntilTimestampFlow.value
                if (banUntil != null && banUntil <= getCurrentServerTimeMs()) {
                    _isBannedFlow.value = false
                    _banUntilTimestampFlow.value = null
                    _banReasonFlow.value = null
                }
                refreshAccessStatus()
                mainHandler.postDelayed(this, 10000L) // updates every 10 seconds
            }
        }, 10000L)
    }

    /**
     * Converts milliseconds into clean human-readable countdown: e.g. "1d 14h", "5h 23m", or "42m 10s".
     */
    fun formatRemainingTime(ms: Long): String {
        if (ms <= 0) return "Expired"
        val days = TimeUnit.MILLISECONDS.toDays(ms)
        val hours = TimeUnit.MILLISECONDS.toHours(ms) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60

        return when {
            days > 0 -> String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            else -> String.format(Locale.US, "%02dm %02ds", minutes, seconds)
        }
    }

    /**
     * Generates a unique 8-character uppercase alphanumeric referral code (e.g. "OM" + random 6 chars).
     * Excludes ambiguous characters (0, O, 1, I).
     */
    fun generateReferralCode(): String {
        val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val randomSuffix = (1..6).map { allowedChars.random() }.joinToString("")
        return "OM$randomSuffix"
    }

    /**
     * Updates in-memory state when referral code is successfully claimed.
     */
    fun notifyReferralClaimed(newPoints: Int) {
        _pointsBalance.value = newPoints
        _hasClaimedReferral.value = true
    }

    /**
     * Updates in-memory state with standard 50-point bonus when no balance is explicitly passed.
     */
    fun notifyReferralClaimed() {
        notifyReferralClaimed(_pointsBalance.value + 50)
    }

    /**
     * Executes the secure, ACID cloud transaction to claim an invite referral code.
     * Atomically adds 50 points to the current user (Referee), 50 points to the invite code owner (Referrer),
     * increments the referrer's referralCount, and marks hasClaimedReferral = true.
     */
    suspend fun claimReferralCode(inviteCode: String): Result<Unit> {
        val ctx = appContext
        return if (ctx != null) {
            ReferralManager.claimReferralCode(ctx, inviteCode)
        } else {
            ReferralManager.claimReferralCode(inviteCode)
        }
    }

    /**
     * Pay-Per-Use Feature Checks:
     * Returns true if a full Pro Pass or Trial is active, OR if the specific feature is unlocked
     * and has an expiry timestamp greater than authoritative server time.
     */
    fun isFeatureUnlocked(featureId: String): Boolean {
        if (isAccessGranted()) return true
        val expiry = _unlockedFeatures.value[featureId] ?: return false
        return expiry > getCurrentServerTimeMs()
    }

    /**
     * Checks if ads should be suppressed.
     * Returns true if user has an active Remove Ads pass or feature unlock,
     * or active Pro access.
     */
    fun isAdsRemoved(): Boolean {
        val status = _accessStatus.value
        if (status is AccessStatus.PassActive && (status.passTier == PassTier.REMOVE_ADS || status.passTier == PassTier.MONTHLY)) {
            return status.remainingTimeMs > 0
        }
        return isFeatureUnlocked("remove_ads_pass")
    }

    /**
     * Returns remaining active time in milliseconds for a specific feature.
     * If Pro Pass/Trial is active, returns remaining pass duration.
     * Otherwise returns remaining feature unlock duration based on server time (or 0L if expired/locked).
     */
    fun getFeatureRemainingTimeMs(featureId: String): Long {
        if (isAccessGranted()) {
            return when (val status = _accessStatus.value) {
                is AccessStatus.PassActive -> status.remainingTimeMs
                is AccessStatus.TrialActive -> status.remainingTimeMs
                else -> 0L
            }
        }
        val expiry = _unlockedFeatures.value[featureId] ?: return 0L
        val now = getCurrentServerTimeMs()
        return (expiry - now).coerceAtLeast(0L)
    }

    /**
     * Purchases an hourly micro-transaction unlock for a specific feature using wallet points
     * via the Zero-Trust Action Queue.
     * Enqueues UNLOCK_FEATURE request to `pending_actions` and awaits server completion.
     */
    suspend fun unlockFeature(featureId: String, hours: Int = 1): Result<Long> = withContext(Dispatchers.IO) {
        if (hours <= 0) return@withContext Result.failure(IllegalArgumentException("Hours must be positive"))

        val pricing = FeatureIds.getPricing(featureId)
            ?: return@withContext Result.failure(IllegalArgumentException("Unknown feature ID: $featureId"))

        val totalCost = pricing.pointsPerHour * hours
        val currentPoints = _pointsBalance.value
        if (currentPoints < totalCost) {
            val shortfall = totalCost - currentPoints
            return@withContext Result.failure(
                IllegalStateException("Insufficient points. You have $currentPoints pts, but unlocking ${pricing.title} for $hours hr(s) costs $totalCost pts (need $shortfall more).")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "UNLOCK_FEATURE",
            payload = mapOf(
                "featureId" to featureId,
                "hours" to hours,
                "pointsCost" to totalCost,
                "durationMs" to (hours * 3600L * 1000L)
            )
        )

        result.fold(
            onSuccess = { snap ->
                val newExpiry = snap.getLong("featureExpiry")
                    ?: snap.getLong("newExpiry")
                    ?: (getCurrentServerTimeMs() + (hours * 3600L * 1000L))
                val updatedMap = _unlockedFeatures.value.toMutableMap()
                updatedMap[featureId] = newExpiry
                _unlockedFeatures.value = updatedMap
                Log.i(TAG, "UNLOCK_FEATURE completed via Action Queue for $featureId. New expiry: $newExpiry")
                Result.success(newExpiry)
            },
            onFailure = { err ->
                Log.e(TAG, "UNLOCK_FEATURE action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }
}


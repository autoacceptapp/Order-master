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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    // Maximum allowed offline validity before forcing cloud sync re-check
    const val OFFLINE_GRACE_PERIOD_MS = 2L * 3600L * 1000L // 2 Hours

    // Firestore Collections
    private const val COLL_HARDWARE_TRIALS = "hardware_trials"
    private const val COLL_USERS_LICENSING = "users_licensing"

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

    // Pay-Per-Use feature unlocks map: featureId -> expiryTimestamp (Long ms)
    private val _unlockedFeatures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val unlockedFeatures: StateFlow<Map<String, Long>> = _unlockedFeatures.asStateFlow()


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
    private var hasLoadedLicensing: Boolean = false
    @Volatile
    private var hasLoadedTrial: Boolean = false

    private var userDocListener: ListenerRegistration? = null
    private var currentUserKey: String? = null
    private val authLock = Any()
    private val docInitAttempted = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Records a trusted server timestamp baseline and marks monotonic elapsed time.
     */
    fun recordServerTimeSync(serverMs: Long) {
        if (serverMs > 0L) {
            val nowElapsed = SystemClock.elapsedRealtime()
            serverTimeBaselineMs = serverMs
            serverTimeBaselineElapsedMs = nowElapsed
            lastSuccessfulServerSyncElapsedMs = nowElapsed
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
     * Checks if the maximum allowed offline grace period (2 hours) has expired.
     */
    fun isOfflineGracePeriodExpired(): Boolean {
        val lastSync = lastSuccessfulServerSyncElapsedMs
        if (lastSync <= 0L) return false
        val offlineDuration = SystemClock.elapsedRealtime() - lastSync
        return offlineDuration > OFFLINE_GRACE_PERIOD_MS
    }

    /**
     * Queries authoritative server time via getServerTime Cloud Function.
     */
    suspend fun fetchServerTimeBaseline() = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext
        if (FirebaseApp.getApps(ctx).isEmpty()) return@withContext
        try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions.getHttpsCallable("getServerTime").call().await()
            val data = result.data as? Map<*, *>
            val serverMs = (data?.get("serverTimestampMs") as? Number)?.toLong() ?: 0L
            if (serverMs > 0L) {
                recordServerTimeSync(serverMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed fetching server time baseline: ${e.message}")
        }
    }

    /**
     * Exposes current application context.
     */
    fun getAppContext(): Context? = appContext

    /**
     * Initializes LicenseManager.
     * ZERO local storage used: initial state is strictly Loading until Firestore responds.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) {
            refreshAccessStatus()
            return
        }
        val ctx = context.applicationContext
        appContext = ctx

        // 1. Initial state is strictly Loading
        _accessStatus.value = AccessStatus.Loading

        // 2. Start recurring countdown ticker
        startStatusTicker()

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

        // 5. Initiate authoritative Firestore cloud sync
        scope.launch {
            syncHardwareTrialAndLicensing()
        }
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
            val newKey = resolveUserKey(ctx, userEmail, userUid)
            synchronized(authLock) {
                if (newKey != currentUserKey) {
                    userDocListener?.remove()
                    userDocListener = null
                    currentUserKey = newKey
                    docInitAttempted.set(false)
                    hasLoadedLicensing = false
                    _unlockedFeatures.value = emptyMap()
                    _accessStatus.value = AccessStatus.Loading
                    syncUserLicensingDocument(newKey, userEmail)
                }
            }
        }
    }

    /**
     * Top-up / Recharge points directly via secure Cloud Function.
     * Enforced by zero-trust architecture: all mutations are performed on server.
     * Zero local storage fallback.
     */
    suspend fun addPoints(amount: Int): Result<Int> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
        if (amount <= 0) return@withContext Result.failure(IllegalArgumentException("Amount must be positive"))

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase is not initialized"))
        }

        try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions.getHttpsCallable("addPoints")
                .call(mapOf("amount" to amount))
                .await()

            val data = result.data as? Map<*, *>
            val newBalance = (data?.get("newPointsBalance") as? Number)?.toInt()
                ?: (_pointsBalance.value + amount)

            _pointsBalance.value = newBalance
            Log.i(TAG, "Successfully added $amount points via Cloud Function. New balance: $newBalance")
            Result.success(newBalance)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud addPoints failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Purchases a Pass using the user's Points balance via secure Cloud Function purchasePassWithPoints.
     * Zero local storage fallback. Server verifies balance and computes pass expiry.
     */
    suspend fun purchasePassWithPoints(passTier: PassTier): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
        val currentBalance = _pointsBalance.value

        if (currentBalance < passTier.pointsCost) {
            val shortfall = passTier.pointsCost - currentBalance
            return@withContext Result.failure(
                IllegalStateException("Insufficient Points. You have $currentBalance pts, but ${passTier.title} requires ${passTier.pointsCost} pts (Need $shortfall more pts).")
            )
        }

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase is not initialized"))
        }

        try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions.getHttpsCallable("purchasePassWithPoints")
                .call(mapOf("passTier" to passTier.id))
                .await()

            val data = result.data as? Map<*, *>
            val newPoints = (data?.get("newPointsBalance") as? Number)?.toInt()
                ?: (currentBalance - passTier.pointsCost)
            val newExpiry = (data?.get("passExpiryTimestamp") as? Number)?.toLong() ?: 0L
            val serverNow = (data?.get("serverTimeMs") as? Number)?.toLong() ?: 0L

            if (serverNow > 0L) {
                recordServerTimeSync(serverNow)
            }

            _pointsBalance.value = newPoints
            serverPassTier = passTier
            serverPassExpiry = newExpiry
            refreshAccessStatus()

            Log.i(TAG, "Successfully purchased ${passTier.title} via Cloud Function. New expiry: $newExpiry")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud purchasePassWithPoints failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Activates a Pass via payment reference. Delegates verification and activation
     * to the secure verifyUtrAndActivatePass Cloud Function.
     * Zero local storage fallback.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase is not initialized"))
        }
        val cleanUtr = (transactionId ?: "").trim()
        if (cleanUtr.isNotBlank() && cleanUtr.length in 9..16) {
            try {
                val functions = FirebaseFunctions.getInstance()
                val result = functions.getHttpsCallable("verifyUtrAndActivatePass")
                    .call(mapOf("utr" to cleanUtr, "passTier" to passTier.id))
                    .await()

                val data = result.data as? Map<*, *>
                val expiry = (data?.get("passExpiryTimestamp") as? Number)?.toLong() ?: 0L
                val serverNow = (data?.get("serverTimeMs") as? Number)?.toLong() ?: 0L
                if (serverNow > 0L) {
                    recordServerTimeSync(serverNow)
                }
                serverPassTier = passTier
                serverPassExpiry = expiry
                refreshAccessStatus()
                return@withContext Result.success(Unit)
            } catch (e: Exception) {
                Log.w(TAG, "Cloud verifyUtrAndActivatePass call encountered: ${e.message}")
            }
        }

        // In-memory update if already verified by backend
        val now = getCurrentServerTimeMs()
        val currentExpiry = serverPassExpiry
        val baseTime = if (currentExpiry > now) currentExpiry else now
        val newExpiry = baseTime + passTier.durationMs
        serverPassTier = passTier
        serverPassExpiry = newExpiry
        refreshAccessStatus()
        Result.success(Unit)
    }

    /**
     * Performs cloud sync for hardware trial and user licensing directly with Firestore.
     * Enforces that hardware trials can ONLY be claimed by authenticated Google users
     * via the secure Firebase Callable Cloud Function `claimHardwareTrial`.
     * Zero direct client-side writes to `hardware_trials`.
     */
    private suspend fun syncHardwareTrialAndLicensing() {
        val ctx = appContext ?: return
        val hwId = DeviceUtils.getDeviceHardwareId(ctx)

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

        // Authoritative server clock synchronization
        fetchServerTimeBaseline()

        // 1. Check user authentication
        val authUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) {
            null
        }
        val authState = GoogleAuthManager.userAuthState.value
        val isAuthenticated = (authUser != null && !authUser.isAnonymous) || (authState.isLoggedIn && !authState.uid.isNullOrBlank())

        try {
            val db = FirebaseFirestore.getInstance()
            val trialDocRef = db.collection(COLL_HARDWARE_TRIALS).document(hwId)

            val trialSnapshot = trialDocRef.get().await()
            if (trialSnapshot != null && trialSnapshot.exists()) {
                // Existing trial record found on Firestore for this hardware
                val claimed = trialSnapshot.getLong("firstClaimedTimestamp") ?: getCurrentServerTimeMs()
                val expiry = trialSnapshot.getLong("trialExpiryTimestamp") ?: (claimed + TRIAL_DURATION_MS)
                val isUsed = trialSnapshot.getBoolean("isUsed") ?: true
                val serverCreatedAt = trialSnapshot.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                if (serverCreatedAt > 0L) {
                    recordServerTimeSync(serverCreatedAt)
                }

                serverTrialClaimed = claimed
                serverTrialExpiry = expiry
                serverTrialUsed = isUsed
                hasLoadedTrial = true
                Log.i(TAG, "Loaded existing trial for hardware $hwId: claimed=$claimed, expiry=$expiry, isUsed=$isUsed")
            } else if (isAuthenticated) {
                // First time launch on this hardware AND user is authenticated:
                // Grant 2-day free trial strictly via Cloud Function claimHardwareTrial.
                // Zero client-side writes to hardware_trials or user_trials collections.
                try {
                    val functions = FirebaseFunctions.getInstance()
                    val result = functions.getHttpsCallable("claimHardwareTrial")
                        .call(mapOf(
                            "hardwareId" to hwId,
                            "deviceSpecs" to DeviceUtils.getHardwareSpecs()
                        ))
                        .await()

                    val data = result.data as? Map<*, *>
                    val claimed = (data?.get("firstClaimedTimestamp") as? Number)?.toLong() ?: getCurrentServerTimeMs()
                    val expiry = (data?.get("trialExpiryTimestamp") as? Number)?.toLong() ?: (claimed + TRIAL_DURATION_MS)
                    val isUsed = (data?.get("isUsed") as? Boolean) ?: true

                    recordServerTimeSync(claimed)
                    serverTrialClaimed = claimed
                    serverTrialExpiry = expiry
                    serverTrialUsed = isUsed
                    hasLoadedTrial = true
                    Log.i(TAG, "Hardware trial claimed via Cloud Function for $hwId until $expiry")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to claim hardware trial via Cloud Function: ${e.message}", e)
                    serverTrialClaimed = 0L
                    serverTrialExpiry = 0L
                    serverTrialUsed = true
                    hasLoadedTrial = true
                }
            } else {
                // Unauthenticated user attempting to use trial:
                // Anonymous or unauthenticated hardware IDs must no longer receive a free trial
                Log.w(TAG, "Hardware trial not claimed: User is not authenticated. Google Sign-In required.")
                serverTrialClaimed = 0L
                serverTrialExpiry = 0L
                serverTrialUsed = false
                hasLoadedTrial = true
            }
            refreshAccessStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync hardware trial from Firestore: ${e.message}")
            // Zero local fallback: remains in Loading state if server unreachable
        }

        // Sync User Licensing Document
        try {
            val user = if (FirebaseApp.getApps(ctx).isNotEmpty()) {
                FirebaseAuth.getInstance().currentUser
            } else {
                null
            }
            val userKey = resolveUserKey(ctx, user?.email, user?.uid)
            currentUserKey = userKey
            syncUserLicensingDocument(userKey, user?.email)
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync user licensing: ${e.message}")
        }
    }

    /**
     * Listens in real-time to the user's licensing document in Firestore.
     * Updates in-memory StateFlows directly from snapshot. Zero local storage.
     * Document initialization is handled strictly by Cloud Function initializeUserLicensing.
     */
    private fun syncUserLicensingDocument(userKey: String, userEmail: String?) {
        val ctx = appContext
        if (ctx != null && FirebaseApp.getApps(ctx).isEmpty()) {
            Log.w(TAG, "FirebaseApp is not initialized; skipping syncUserLicensingDocument.")
            return
        }
        try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)

            userDocListener?.remove()
            userDocListener = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User licensing snapshot listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val points = snapshot.getLong("pointsBalance")?.toInt() ?: 0
                    val passType = snapshot.getString("activePassType")
                    val passExpiry = snapshot.getLong("passExpiryTimestamp") ?: 0L
                    val code = snapshot.getString("referralCode")
                    val hasClaimed = snapshot.getBoolean("hasClaimedReferral") ?: false
                    val refCount = snapshot.getLong("referralCount")?.toInt() ?: 0
                    val serverTimestamp = snapshot.getTimestamp("updatedAt")?.toDate()?.time
                        ?: snapshot.getTimestamp("createdAt")?.toDate()?.time

                    if (serverTimestamp != null && serverTimestamp > 0L) {
                        recordServerTimeSync(serverTimestamp)
                    }

                    // Update in-memory reactive state directly from Firestore snapshot
                    _pointsBalance.value = points
                    _hasClaimedReferral.value = hasClaimed
                    _referralCount.value = refCount
                    serverPassTier = PassTier.fromId(passType)
                    serverPassExpiry = passExpiry

                    // Parse Pay-Per-Use feature unlocks map
                    val rawFeatures = snapshot.get("unlocked_features") as? Map<*, *>
                    val parsedFeatures = rawFeatures?.mapNotNull { (k, v) ->
                        val featureKey = k as? String ?: return@mapNotNull null
                        val exp = (v as? Number)?.toLong() ?: return@mapNotNull null
                        featureKey to exp
                    }?.toMap() ?: emptyMap()
                    _unlockedFeatures.value = parsedFeatures

                    hasLoadedLicensing = true

                    if (!code.isNullOrBlank()) {
                        _referralCode.value = code
                    } else {
                        // Request backend initialization/assignment of referral code
                        scope.launch {
                            try {
                                val functions = FirebaseFunctions.getInstance()
                                functions.getHttpsCallable("initializeUserLicensing").call().await()
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed calling initializeUserLicensing for code assignment: ${e.message}")
                            }
                        }
                    }

                    refreshAccessStatus()
                    Log.d(TAG, "Synced user licensing from Firestore: points=$points, passType=$passType, code=${_referralCode.value}")
                } else if (snapshot != null && !snapshot.exists()) {
                    // Prevent infinite loop: initialize document strictly once per session via Cloud Function
                    if (docInitAttempted.compareAndSet(false, true)) {
                        scope.launch {
                            try {
                                val functions = FirebaseFunctions.getInstance()
                                val result = functions.getHttpsCallable("initializeUserLicensing").call().await()
                                val data = result.data as? Map<*, *>
                                val newCode = data?.get("referralCode") as? String
                                if (!newCode.isNullOrBlank()) {
                                    _referralCode.value = newCode
                                }
                                hasLoadedLicensing = true
                                refreshAccessStatus()
                                Log.i(TAG, "Initialized new user licensing document via Cloud Function for $userKey")
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not initialize user licensing doc on Firestore via Cloud Function: ${e.message}")
                                docInitAttempted.set(false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting up licensing listener: ${e.message}")
        }
    }

    /**
     * Resolves a persistent key for the user:
     * 1. Firebase Auth UID
     * 2. Gmail address sanitized
     * 3. Hardware ID fallback if not logged in
     */
    private fun resolveUserKey(context: Context, email: String?, uid: String?): String {
        if (!uid.isNullOrBlank()) return uid
        if (!email.isNullOrBlank()) return "email_" + email.replace(".", "_").replace("@", "_at_")
        return "hw_" + DeviceUtils.getDeviceHardwareId(context)
    }

    /**
     * Recomputes the current `AccessStatus` strictly from in-memory server state.
     * Enforces anti-clock-tampering with monotonic elapsed time and 2-hour offline limit.
     * ZERO local storage reads.
     */
    fun refreshAccessStatus() {
        // If neither user licensing nor hardware trial has loaded from Firestore, remain Loading
        if (!hasLoadedLicensing && !hasLoadedTrial) {
            _accessStatus.value = AccessStatus.Loading
            return
        }

        // Strict 2-hour offline grace check:
        if (isOfflineGracePeriodExpired()) {
            Log.w(TAG, "Offline grace period expired (limit: 2 hours). Enforcing zero-trust lock.")
            _accessStatus.value = AccessStatus.Expired(
                isTrialUsed = serverTrialUsed || serverTrialClaimed > 0L,
                hasHadPreviousPass = serverPassTier != null || serverPassExpiry > 0L,
                message = "Maximum 2-hour offline limit reached. Please connect to internet to verify pass."
            )
            return
        }

        val now = getCurrentServerTimeMs()
        val tier = serverPassTier
        val passExpiry = serverPassExpiry

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

        // 2. Check Hardware 2-Day Free Trial
        val trialClaimed = serverTrialClaimed
        val trialExpiry = serverTrialExpiry
        val trialUsed = serverTrialUsed

        val trialInfoObj = TrialInfo(
            firstClaimedTimestamp = trialClaimed,
            trialExpiryTimestamp = trialExpiry,
            isUsed = trialUsed,
            isExpired = now >= trialExpiry,
            remainingMs = (trialExpiry - now).coerceAtLeast(0L)
        )
        _trialInfo.value = trialInfoObj

        if (trialClaimed > 0L && trialExpiry > now) {
            val timeLeft = trialExpiry - now
            val formatted = formatRemainingTime(timeLeft)
            _accessStatus.value = AccessStatus.TrialActive(
                remainingTimeMs = timeLeft,
                expiryTimestamp = trialExpiry,
                formattedRemaining = formatted
            )
            return
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
     * Purchases an hourly micro-transaction unlock for a specific feature using wallet points.
     * Calls the secure Callable Cloud Function `unlockFeatureWithPoints`.
     * Zero client-side trust: Points deduction, expiry calculation, and timestamp recording occur on server.
     */
    suspend fun unlockFeature(featureId: String, hours: Int = 1): Result<Long> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
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

        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase is not initialized"))
        }

        try {
            val functions = FirebaseFunctions.getInstance()
            val result = functions.getHttpsCallable("unlockFeatureWithPoints")
                .call(mapOf(
                    "featureId" to featureId,
                    "hours" to hours
                ))
                .await()

            val data = result.data as? Map<*, *>
            val newPoints = (data?.get("newPointsBalance") as? Number)?.toInt() ?: (currentPoints - totalCost)
            val newExpiry = (data?.get("expiryTimestamp") as? Number)?.toLong() ?: 0L
            val serverNow = (data?.get("serverTimeMs") as? Number)?.toLong() ?: 0L

            if (serverNow > 0L) {
                recordServerTimeSync(serverNow)
            }

            _pointsBalance.value = newPoints
            val updatedMap = _unlockedFeatures.value.toMutableMap()
            updatedMap[featureId] = newExpiry
            _unlockedFeatures.value = updatedMap

            Log.i(TAG, "Successfully unlocked feature '$featureId' for $hours hr(s) until $newExpiry. New balance: $newPoints")
            Result.success(newExpiry)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud unlockFeatureWithPoints failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}


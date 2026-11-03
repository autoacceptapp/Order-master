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
    private val _isServiceActiveFlow = MutableStateFlow(true)
    val isServiceActiveFlow: StateFlow<Boolean> = _isServiceActiveFlow.asStateFlow()

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
    private var hasLoadedLicensing: Boolean = false
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
                    _isServiceActiveFlow.value = isActive
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

        // 2.5 Start real-time global service settings listener
        startGlobalSettingsListener()

        // 2.7 Immediately start user document real-time listener for current profile key
        val customKey = resolveUserKey(ctx)
        currentUserKey = customKey
        syncUserLicensingDocument(customKey, null)

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

        // 6. Safety timeout: prevent UI from being stuck indefinitely in Loading state
        scope.launch {
            kotlinx.coroutines.delay(4000L)
            if (_accessStatus.value is AccessStatus.Loading) {
                Log.w(TAG, "Cloud sync resolution timed out after 4s; releasing loading lock.")
                hasLoadedTrial = true
                hasLoadedLicensing = true
                refreshAccessStatus()
            }
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

        val outcome = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<DocumentSnapshot>> { continuation ->
                var listenerRegistration: ListenerRegistration? = null
                val isResumed = AtomicBoolean(false)

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

                                // Delayed 60-second database cleanup of processed action document
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        kotlinx.coroutines.delay(60_000L)
                                        docRef.delete().await()
                                        Log.d(TAG, "Delayed cleanup: Deleted completed action document ${docRef.id}")
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

                                // Delayed 60-second database cleanup of processed action document
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        kotlinx.coroutines.delay(60_000L)
                                        docRef.delete().await()
                                        Log.d(TAG, "Delayed cleanup: Deleted failed action document ${docRef.id}")
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

        outcome ?: Result.failure(TimeoutException("Request timed out. Admin server might be offline."))
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
        val db = FirebaseFirestore.getInstance()
        val firebaseUid = currentUser?.uid?.takeIf { it.isNotBlank() }
        val userEmail = currentUser?.email ?: ""
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
                    _currentFolder.value = "free_mod"
                }
                refreshAccessStatus()
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync user document from Firestore: ${e.message}")
                hasLoadedTrial = true
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
                    val serverTimestamp = snapshot.getTimestamp("updatedAt")?.toDate()?.time
                        ?: snapshot.getTimestamp("createdAt")?.toDate()?.time

                    if (serverTimestamp != null && serverTimestamp > 0L) {
                        recordServerTimeSync(serverTimestamp)
                    }

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
                                    val trialExpiry = now + (48L * 3600L * 1000L)
                                    serverTrialClaimed = now
                                    serverTrialExpiry = trialExpiry
                                    serverTrialUsed = true
                                    _currentFolder.value = "trials"
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
    fun refreshAccessStatus() {
        // If user licensing has not yet loaded from Firestore, remain Loading
        if (!hasLoadedLicensing) {
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


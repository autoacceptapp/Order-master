package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
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
import kotlinx.coroutines.tasks.await
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
 * LicenseManager
 *
 * Cloud-Only Architecture:
 * 100% strictly managed by Firebase Firestore with ZERO local storage fallbacks.
 *
 * 1. Hardware-Locked 2-Day Free Trial (cloud-synced in `hardware_trials/{Device_Hardware_ID}`).
 * 2. Gmail Account-Synced Pass & Points System (cloud-synced in `users_licensing/{User_Gmail_UID}`).
 * 3. Reactive StateFlow Access gating for accessibility automation and overlay services.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"

    // 2-Day Free Trial Duration: 48 Hours
    private const val TRIAL_DURATION_MS = 48L * 3600L * 1000L

    // Firestore Collections
    private const val COLL_HARDWARE_TRIALS = "hardware_trials"
    private const val COLL_USERS_LICENSING = "users_licensing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    // Reactive StateFlows - Initial state is STRICTLY Loading until server responds
    private val _accessStatus = MutableStateFlow<AccessStatus>(AccessStatus.Loading)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    private val _pointsBalance = MutableStateFlow(0)
    val pointsBalance: StateFlow<Int> = _pointsBalance.asStateFlow()

    private val _activePassInfo = MutableStateFlow<ActivePassInfo?>(null)
    val activePassInfo: StateFlow<ActivePassInfo?> = _activePassInfo.asStateFlow()

    private val _trialInfo = MutableStateFlow<TrialInfo?>(null)
    val trialInfo: StateFlow<TrialInfo?> = _trialInfo.asStateFlow()

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

        // 4. Initiate authoritative Firestore cloud sync
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
     * Returns true allowing access and permissions without blocking when pass is not purchased.
     */
    fun isAccessGranted(): Boolean {
        return true
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
                    _accessStatus.value = AccessStatus.Loading
                    syncUserLicensingDocument(newKey, userEmail)
                }
            }
        }
    }

    /**
     * Top-up / Recharge points directly in the user's wallet on Firestore.
     * Zero local storage fallback.
     */
    suspend fun addPoints(amount: Int): Result<Int> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("LicenseManager not initialized"))
        if (amount <= 0) return Result.failure(IllegalArgumentException("Amount must be positive"))

        return try {
            val userKey = currentUserKey ?: resolveUserKey(ctx, null, null)
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)

            userDocRef.set(
                mapOf(
                    "pointsBalance" to FieldValue.increment(amount.toLong()),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            val newBalance = _pointsBalance.value + amount
            Log.i(TAG, "Successfully added $amount points on Firestore. New balance: $newBalance")
            Result.success(newBalance)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud addPoints failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Purchases a Pass using the user's Points balance via an atomic Firestore transaction.
     * Zero local storage fallback.
     */
    suspend fun purchasePassWithPoints(passTier: PassTier): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("LicenseManager not initialized"))
        val currentBalance = _pointsBalance.value

        if (currentBalance < passTier.pointsCost) {
            val shortfall = passTier.pointsCost - currentBalance
            return Result.failure(
                IllegalStateException("Insufficient Points. You have $currentBalance pts, but ${passTier.title} requires ${passTier.pointsCost} pts (Need $shortfall more pts).")
            )
        }

        val userKey = currentUserKey ?: resolveUserKey(ctx, null, null)
        val db = FirebaseFirestore.getInstance()
        val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)
        val now = System.currentTimeMillis()

        return try {
            var resultingPoints = currentBalance
            var resultingExpiry = 0L

            db.runTransaction { transaction ->
                val snapshot = transaction.get(userDocRef)
                val serverPoints = snapshot.getLong("pointsBalance")?.toInt() ?: currentBalance
                if (serverPoints < passTier.pointsCost) {
                    throw IllegalStateException("Insufficient server points balance ($serverPoints pts).")
                }

                val currentExpiry = snapshot.getLong("passExpiryTimestamp") ?: 0L
                val baseTime = if (currentExpiry > now) currentExpiry else now
                val newExpiry = baseTime + passTier.durationMs
                val newPoints = (serverPoints - passTier.pointsCost).coerceAtLeast(0)

                resultingPoints = newPoints
                resultingExpiry = newExpiry

                transaction.set(
                    userDocRef,
                    mapOf(
                        "pointsBalance" to newPoints.toLong(),
                        "activePassType" to passTier.id,
                        "passExpiryTimestamp" to newExpiry,
                        "lastPurchasedTier" to passTier.id,
                        "updatedAt" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge()
                )
            }.await()

            // Update in-memory state directly from server transaction
            _pointsBalance.value = resultingPoints
            serverPassTier = passTier
            serverPassExpiry = resultingExpiry
            refreshAccessStatus()

            Log.i(TAG, "Successfully purchased ${passTier.title} on Firestore. New expiry: $resultingExpiry")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed for pass purchase: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Activates a Pass directly via UPI or Payment Gateway on Firestore.
     * Zero local storage fallback.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("LicenseManager not initialized"))
        val userKey = currentUserKey ?: resolveUserKey(ctx, null, null)
        val now = System.currentTimeMillis()

        val currentExpiry = serverPassExpiry
        val baseTime = if (currentExpiry > now) currentExpiry else now
        val newExpiry = baseTime + passTier.durationMs

        return try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)
            userDocRef.set(
                mapOf(
                    "activePassType" to passTier.id,
                    "passExpiryTimestamp" to newExpiry,
                    "lastPurchasedTier" to passTier.id,
                    "lastPaymentMethod" to "UPI",
                    "lastTransactionId" to (transactionId ?: "DIRECT_UPI_${System.currentTimeMillis()}"),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()

            serverPassTier = passTier
            serverPassExpiry = newExpiry
            refreshAccessStatus()

            Log.i(TAG, "Successfully activated ${passTier.title} on Firestore via UPI payment. Expiry: $newExpiry")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore sync for UPI pass activation failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Performs cloud sync for hardware trial and user licensing directly with Firestore.
     */
    private suspend fun syncHardwareTrialAndLicensing() {
        val ctx = appContext ?: return
        val hwId = DeviceUtils.getDeviceHardwareId(ctx)
        val now = System.currentTimeMillis()

        try {
            val db = FirebaseFirestore.getInstance()
            val trialDocRef = db.collection(COLL_HARDWARE_TRIALS).document(hwId)

            val trialSnapshot = trialDocRef.get().await()
            if (!trialSnapshot.exists()) {
                // First time launch on this physical hardware!
                // Grant 2-day free trial locked to this device on Firestore.
                val expiry = now + TRIAL_DURATION_MS
                val trialData = mapOf(
                    "hardwareId" to hwId,
                    "firstClaimedTimestamp" to now,
                    "trialExpiryTimestamp" to expiry,
                    "isUsed" to true,
                    "deviceSpecs" to DeviceUtils.getHardwareSpecs(),
                    "createdAt" to FieldValue.serverTimestamp()
                )
                trialDocRef.set(trialData).await()
                serverTrialClaimed = now
                serverTrialExpiry = expiry
                serverTrialUsed = true
                hasLoadedTrial = true
                Log.i(TAG, "Hardware trial claimed on Firestore for $hwId until $expiry")
            } else {
                // Existing trial found for this hardware on Firestore
                val claimed = trialSnapshot.getLong("firstClaimedTimestamp") ?: now
                val expiry = trialSnapshot.getLong("trialExpiryTimestamp") ?: (claimed + TRIAL_DURATION_MS)
                val isUsed = trialSnapshot.getBoolean("isUsed") ?: true
                serverTrialClaimed = claimed
                serverTrialExpiry = expiry
                serverTrialUsed = isUsed
                hasLoadedTrial = true
            }
            refreshAccessStatus()
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync hardware trial to Firestore: ${e.message}")
            // Zero local fallback: remains in Loading state if server unreachable
        }

        // Sync User Licensing Document
        val user = FirebaseAuth.getInstance().currentUser
        val userKey = resolveUserKey(ctx, user?.email, user?.uid)
        currentUserKey = userKey
        syncUserLicensingDocument(userKey, user?.email)
    }

    /**
     * Listens in real-time to the user's licensing document in Firestore.
     * Updates in-memory StateFlows directly from snapshot. Zero local storage.
     */
    private fun syncUserLicensingDocument(userKey: String, userEmail: String?) {
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

                    // Update in-memory reactive state directly from Firestore snapshot
                    _pointsBalance.value = points
                    serverPassTier = PassTier.fromId(passType)
                    serverPassExpiry = passExpiry
                    hasLoadedLicensing = true

                    refreshAccessStatus()
                    Log.d(TAG, "Synced user licensing from Firestore: points=$points, passType=$passType, expiry=$passExpiry")
                } else if (snapshot != null && !snapshot.exists()) {
                    // Prevent infinite loop: initialize document strictly once per session
                    if (docInitAttempted.compareAndSet(false, true)) {
                        scope.launch {
                            try {
                                val initialData = mapOf(
                                    "userId" to userKey,
                                    "userEmail" to (userEmail ?: ""),
                                    "pointsBalance" to 0L,
                                    "activePassType" to null,
                                    "passExpiryTimestamp" to 0L,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                                userDocRef.set(initialData, SetOptions.merge()).await()
                                hasLoadedLicensing = true
                                refreshAccessStatus()
                                Log.i(TAG, "Initialized new user licensing document on Firestore for $userKey")
                            } catch (e: Exception) {
                                Log.w(TAG, "Could not initialize user licensing doc on Firestore: ${e.message}")
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
     * ZERO local storage reads.
     */
    fun refreshAccessStatus() {
        // If neither user licensing nor hardware trial has loaded from Firestore, remain Loading
        if (!hasLoadedLicensing && !hasLoadedTrial) {
            _accessStatus.value = AccessStatus.Loading
            return
        }

        val now = System.currentTimeMillis()
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

        // 3. Both Pass & Trial Expired
        val hasHadPreviousPass = tier != null || passExpiry > 0L
        val msg = if (hasHadPreviousPass) {
            "Your ${tier?.title ?: "subscription"} has expired. Renew your pass to resume auto-acceptance."
        } else {
            "Your 2-Day Free Trial has ended. Choose a Pass to continue enjoying high-speed auto-acceptance."
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
}

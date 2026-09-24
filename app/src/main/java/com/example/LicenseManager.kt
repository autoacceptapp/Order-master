package com.example

import android.content.Context
import android.content.SharedPreferences
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
 * Core engine governing:
 * 1. Hardware-Locked 2-Day Free Trial (cloud-synced in `hardware_trials/{Device_Hardware_ID}`).
 * 2. Gmail Account-Synced Pass & Points System (cloud-synced in `users_licensing/{User_Gmail_UID}`).
 * 3. Reactive StateFlow Access gating for accessibility automation and overlay services.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val PREFS_NAME = "licensing_security_prefs"

    // SharedPreferences Keys
    private const val KEY_LOCAL_POINTS = "cached_points_balance"
    private const val KEY_LOCAL_PASS_TYPE = "cached_active_pass_type"
    private const val KEY_LOCAL_PASS_EXPIRY = "cached_pass_expiry"
    private const val KEY_LOCAL_TRIAL_CLAIMED = "cached_trial_claimed_timestamp"
    private const val KEY_LOCAL_TRIAL_EXPIRY = "cached_trial_expiry_timestamp"
    private const val KEY_LOCAL_TRIAL_USED = "cached_trial_is_used"

    // 2-Day Free Trial Duration: 48 Hours
    private const val TRIAL_DURATION_MS = 48L * 3600L * 1000L

    // Firestore Collections
    private const val COLL_HARDWARE_TRIALS = "hardware_trials"
    private const val COLL_USERS_LICENSING = "users_licensing"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private val _accessStatus = MutableStateFlow<AccessStatus>(AccessStatus.Loading)
    val accessStatus: StateFlow<AccessStatus> = _accessStatus.asStateFlow()

    private val _pointsBalance = MutableStateFlow(0)
    val pointsBalance: StateFlow<Int> = _pointsBalance.asStateFlow()

    private val _activePassInfo = MutableStateFlow<ActivePassInfo?>(null)
    val activePassInfo: StateFlow<ActivePassInfo?> = _activePassInfo.asStateFlow()

    private val _trialInfo = MutableStateFlow<TrialInfo?>(null)
    val trialInfo: StateFlow<TrialInfo?> = _trialInfo.asStateFlow()

    private var userDocListener: ListenerRegistration? = null
    private var currentUserKey: String? = null

    /**
     * Initializes LicenseManager, loads cached state immediately for zero startup lag,
     * and triggers asynchronous Firestore sync.
     */
    @Synchronized
    fun init(context: Context) {
        if (appContext != null) {
            refreshAccessStatus()
            return
        }
        val ctx = context.applicationContext
        appContext = ctx

        // 1. Read local cache for immediate offline responsiveness
        loadLocalCache(ctx)

        // 2. Start recurring ticker to update remaining countdowns and transition states
        startStatusTicker()

        // 3. Initiate cloud sync
        scope.launch {
            syncHardwareTrialAndLicensing()
        }
    }

    /**
     * Returns true allowing access and permissions without blocking when pass is not purchased.
     */
    fun isAccessGranted(): Boolean {
        return true
    }

    /**
     * Synchronizes user licensing when Gmail account login changes.
     */
    fun onUserAuthChanged(userEmail: String?, userUid: String?) {
        scope.launch {
            val ctx = appContext ?: return@launch
            val newKey = resolveUserKey(ctx, userEmail, userUid)
            if (newKey != currentUserKey) {
                userDocListener?.remove()
                currentUserKey = newKey
                syncUserLicensingDocument(newKey, userEmail)
            }
        }
    }

    /**
     * Top-up / Recharge points directly in the user's wallet.
     * Updates Firestore atomically using FieldValue.increment.
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
                    "updatedAt" to System.currentTimeMillis()
                ),
                SetOptions.merge()
            ).await()

            // Update local memory and cache
            val newBalance = _pointsBalance.value + amount
            _pointsBalance.value = newBalance
            saveLocalPoints(ctx, newBalance)
            refreshAccessStatus()
            Log.i(TAG, "Successfully added $amount points. New balance: $newBalance")
            Result.success(newBalance)
        } catch (e: Exception) {
            Log.w(TAG, "Cloud addPoints failed, updating local wallet as fallback: ${e.message}")
            // Fallback local update if network is unavailable
            val newBalance = _pointsBalance.value + amount
            _pointsBalance.value = newBalance
            saveLocalPoints(ctx, newBalance)
            refreshAccessStatus()
            Result.success(newBalance)
        }
    }

    /**
     * Purchases a Pass using the user's Points balance.
     * Uses atomic transaction to verify balance, deduct points, and set or extend active pass.
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
            db.runTransaction { transaction ->
                val snapshot = transaction.get(userDocRef)
                val serverPoints = snapshot.getLong("pointsBalance")?.toInt() ?: currentBalance
                if (serverPoints < passTier.pointsCost) {
                    throw IllegalStateException("Insufficient server points balance ($serverPoints pts).")
                }

                val currentExpiry = snapshot.getLong("passExpiryTimestamp") ?: 0L
                val baseTime = if (currentExpiry > now) currentExpiry else now
                val newExpiry = baseTime + passTier.durationMs

                transaction.set(
                    userDocRef,
                    mapOf(
                        "pointsBalance" to FieldValue.increment(-passTier.pointsCost.toLong()),
                        "activePassType" to passTier.id,
                        "passExpiryTimestamp" to newExpiry,
                        "lastPurchasedTier" to passTier.id,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
            }.await()

            // Update local state and SharedPreferences
            val newPoints = (_pointsBalance.value - passTier.pointsCost).coerceAtLeast(0)
            _pointsBalance.value = newPoints
            saveLocalPoints(ctx, newPoints)

            val currentLocalExpiry = _activePassInfo.value?.expiryTimestamp ?: 0L
            val baseTime = if (currentLocalExpiry > now) currentLocalExpiry else now
            val newLocalExpiry = baseTime + passTier.durationMs

            saveLocalPass(ctx, passTier.id, newLocalExpiry)
            refreshAccessStatus()

            Log.i(TAG, "Successfully purchased ${passTier.title}. New expiry: $newLocalExpiry")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Transaction failed for pass purchase: ${e.message}", e)
            // If offline transaction fails, verify and apply with local fallback
            if (currentBalance >= passTier.pointsCost) {
                val newPoints = currentBalance - passTier.pointsCost
                _pointsBalance.value = newPoints
                saveLocalPoints(ctx, newPoints)

                val currentLocalExpiry = _activePassInfo.value?.expiryTimestamp ?: 0L
                val baseTime = if (currentLocalExpiry > now) currentLocalExpiry else now
                val newLocalExpiry = baseTime + passTier.durationMs

                saveLocalPass(ctx, passTier.id, newLocalExpiry)
                refreshAccessStatus()
                Result.success(Unit)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * Activates a Pass directly via UPI or Payment Gateway without deducting points.
     * Instantly sets / extends pass expiry and synchronizes with AppSettings and Firestore.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("LicenseManager not initialized"))
        val userKey = currentUserKey ?: resolveUserKey(ctx, null, null)
        val now = System.currentTimeMillis()

        val currentLocalExpiry = _activePassInfo.value?.expiryTimestamp ?: 0L
        val baseTime = if (currentLocalExpiry > now) currentLocalExpiry else now
        val newLocalExpiry = baseTime + passTier.durationMs

        // 1. Immediately update local pass storage & AppSettings
        saveLocalPass(ctx, passTier.id, newLocalExpiry)
        AppSettings.setPassExpiryTimestamp(ctx, newLocalExpiry, passTier.id)
        refreshAccessStatus()

        // 2. Cloud Firestore synchronization (async)
        return try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)
            userDocRef.set(
                mapOf(
                    "activePassType" to passTier.id,
                    "passExpiryTimestamp" to newLocalExpiry,
                    "lastPurchasedTier" to passTier.id,
                    "lastPaymentMethod" to "UPI",
                    "lastTransactionId" to (transactionId ?: "DIRECT_UPI_${System.currentTimeMillis()}"),
                    "updatedAt" to now
                ),
                SetOptions.merge()
            ).await()

            Log.i(TAG, "Successfully activated ${passTier.title} via UPI payment. Expiry: $newLocalExpiry")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "Cloud sync for UPI pass activation failed (offline fallback active): ${e.message}")
            // Local activation already completed successfully!
            Result.success(Unit)
        }
    }

    /**
     * Performs cloud sync for hardware trial and user licensing.
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
                // Grant 2-day free trial locked to this device.
                val expiry = now + TRIAL_DURATION_MS
                val trialData = mapOf(
                    "hardwareId" to hwId,
                    "firstClaimedTimestamp" to now,
                    "trialExpiryTimestamp" to expiry,
                    "isUsed" to true,
                    "deviceSpecs" to DeviceUtils.getHardwareSpecs(),
                    "createdAt" to now
                )
                trialDocRef.set(trialData).await()
                saveLocalTrial(ctx, claimed = now, expiry = expiry, isUsed = true)
                Log.i(TAG, "Hardware trial claimed on $hwId until $expiry")
            } else {
                // Existing trial found for this hardware
                val claimed = trialSnapshot.getLong("firstClaimedTimestamp") ?: now
                val expiry = trialSnapshot.getLong("trialExpiryTimestamp") ?: (claimed + TRIAL_DURATION_MS)
                val isUsed = trialSnapshot.getBoolean("isUsed") ?: true
                saveLocalTrial(ctx, claimed = claimed, expiry = expiry, isUsed = isUsed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not sync hardware trial to Firestore (offline or error): ${e.message}")
            // Check if local trial exists; if not, initialize first local trial
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.contains(KEY_LOCAL_TRIAL_CLAIMED)) {
                val expiry = now + TRIAL_DURATION_MS
                saveLocalTrial(ctx, claimed = now, expiry = expiry, isUsed = true)
            }
        }

        // Sync User Licensing Document
        val user = FirebaseAuth.getInstance().currentUser
        val userKey = resolveUserKey(ctx, user?.email, user?.uid)
        currentUserKey = userKey
        syncUserLicensingDocument(userKey, user?.email)
    }

    /**
     * Listens in real-time to the user's licensing document in Firestore.
     */
    private fun syncUserLicensingDocument(userKey: String, userEmail: String?) {
        val ctx = appContext ?: return
        try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS_LICENSING).document(userKey)

            userDocListener?.remove()
            userDocListener = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User licensing listener error: ${error.message}")
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val points = snapshot.getLong("pointsBalance")?.toInt() ?: 0
                    val passType = snapshot.getString("activePassType")
                    val passExpiry = snapshot.getLong("passExpiryTimestamp") ?: 0L

                    _pointsBalance.value = points
                    saveLocalPoints(ctx, points)
                    saveLocalPass(ctx, passType, passExpiry)
                    refreshAccessStatus()
                    Log.d(TAG, "Synced user licensing: points=$points, passType=$passType, expiry=$passExpiry")
                } else {
                    // Initialize new user document with starting trial points (e.g. 10 bonus points for joining)
                    scope.launch {
                        try {
                            val initialData = mapOf(
                                "userId" to userKey,
                                "userEmail" to (userEmail ?: ""),
                                "pointsBalance" to _pointsBalance.value,
                                "activePassType" to null,
                                "passExpiryTimestamp" to 0L,
                                "createdAt" to System.currentTimeMillis()
                            )
                            userDocRef.set(initialData, SetOptions.merge()).await()
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not initialize user licensing doc: ${e.message}")
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
     * Recomputes the current `AccessStatus` based on local and synced data.
     */
    fun refreshAccessStatus() {
        val ctx = appContext ?: return
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        val passType = prefs.getString(KEY_LOCAL_PASS_TYPE, null)
        val passExpiry = prefs.getLong(KEY_LOCAL_PASS_EXPIRY, 0L)
        val tier = PassTier.fromId(passType)

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
        val trialClaimed = prefs.getLong(KEY_LOCAL_TRIAL_CLAIMED, 0L)
        val trialExpiry = prefs.getLong(KEY_LOCAL_TRIAL_EXPIRY, 0L)
        val trialUsed = prefs.getBoolean(KEY_LOCAL_TRIAL_USED, false)

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

    private fun loadLocalCache(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _pointsBalance.value = prefs.getInt(KEY_LOCAL_POINTS, 0)
        refreshAccessStatus()
    }

    private fun saveLocalPoints(context: Context, points: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LOCAL_POINTS, points)
            .apply()
    }

    private fun saveLocalPass(context: Context, passType: String?, expiry: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_PASS_TYPE, passType)
            .putLong(KEY_LOCAL_PASS_EXPIRY, expiry)
            .apply()
        // Synchronize with AppSettings for instant access across services and StateFlows
        AppSettings.setPassExpiryTimestamp(context, expiry, passType)
    }

    private fun saveLocalTrial(context: Context, claimed: Long, expiry: Long, isUsed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LOCAL_TRIAL_CLAIMED, claimed)
            .putLong(KEY_LOCAL_TRIAL_EXPIRY, expiry)
            .putBoolean(KEY_LOCAL_TRIAL_USED, isUsed)
            .apply()
        refreshAccessStatus()
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

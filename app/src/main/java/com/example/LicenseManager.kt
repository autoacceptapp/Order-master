package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.auth.GoogleAuthManager
import com.example.data.repository.AppConfigRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
 * Single canonical source of truth for pass pricing (INR), points costs, and amount-to-tier mapping.
 * Defaults are hardcoded for instant cold boot; remote overrides from `admin_settings/payment_config`
 * are applied on top at runtime when available.
 */
object PassPricingConfig {
    const val DAILY_PRICE_INR = 9
    const val WEEKLY_PRICE_INR = 49
    const val MONTHLY_PRICE_INR = 179
    const val REMOVE_ADS_PRICE_INR = 199

    const val DAILY_POINTS = 100
    const val WEEKLY_POINTS = 500
    const val MONTHLY_POINTS = 1500
    const val REMOVE_ADS_POINTS = 1600

    @Volatile
    var effectiveDailyPriceInr: Int = DAILY_PRICE_INR
        private set

    @Volatile
    var effectiveWeeklyPriceInr: Int = WEEKLY_PRICE_INR
        private set

    @Volatile
    var effectiveMonthlyPriceInr: Int = MONTHLY_PRICE_INR
        private set

    @Volatile
    var effectiveRemoveAdsPriceInr: Int = REMOVE_ADS_PRICE_INR
        private set

    @Volatile
    var effectiveDailyPoints: Int = DAILY_POINTS
        private set

    @Volatile
    var effectiveWeeklyPoints: Int = WEEKLY_POINTS
        private set

    @Volatile
    var effectiveMonthlyPoints: Int = MONTHLY_POINTS
        private set

    @Volatile
    var effectiveRemoveAdsPoints: Int = REMOVE_ADS_POINTS
        private set

    @Volatile
    var effectiveValidPassAmountsInr: List<Int> = listOf(
        DAILY_PRICE_INR,
        WEEKLY_PRICE_INR,
        MONTHLY_PRICE_INR,
        REMOVE_ADS_PRICE_INR
    )
        private set

    fun applyOverrides(
        dailyPriceInr: Int? = null,
        weeklyPriceInr: Int? = null,
        monthlyPriceInr: Int? = null,
        removeAdsPriceInr: Int? = null,
        dailyPoints: Int? = null,
        weeklyPoints: Int? = null,
        monthlyPoints: Int? = null,
        removeAdsPoints: Int? = null,
        validPassAmountsInr: List<Int>? = null
    ) {
        effectiveDailyPriceInr = dailyPriceInr?.takeIf { it > 0 } ?: DAILY_PRICE_INR
        effectiveWeeklyPriceInr = weeklyPriceInr?.takeIf { it > 0 } ?: WEEKLY_PRICE_INR
        effectiveMonthlyPriceInr = monthlyPriceInr?.takeIf { it > 0 } ?: MONTHLY_PRICE_INR
        effectiveRemoveAdsPriceInr = removeAdsPriceInr?.takeIf { it > 0 } ?: REMOVE_ADS_PRICE_INR

        effectiveDailyPoints = dailyPoints?.takeIf { it > 0 } ?: DAILY_POINTS
        effectiveWeeklyPoints = weeklyPoints?.takeIf { it > 0 } ?: WEEKLY_POINTS
        effectiveMonthlyPoints = monthlyPoints?.takeIf { it > 0 } ?: MONTHLY_POINTS
        effectiveRemoveAdsPoints = removeAdsPoints?.takeIf { it > 0 } ?: REMOVE_ADS_POINTS

        val sanitizedValidAmounts = validPassAmountsInr
            ?.filter { it > 0 }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        effectiveValidPassAmountsInr = sanitizedValidAmounts ?: listOf(
            effectiveDailyPriceInr,
            effectiveWeeklyPriceInr,
            effectiveMonthlyPriceInr,
            effectiveRemoveAdsPriceInr
        ).distinct()
    }

    fun resetDefaultsForTesting() {
        applyOverrides()
    }

    fun effectivePriceFor(tier: PassTier): Int = when (tier) {
        PassTier.DAILY -> effectiveDailyPriceInr
        PassTier.WEEKLY -> effectiveWeeklyPriceInr
        PassTier.MONTHLY -> effectiveMonthlyPriceInr
        PassTier.REMOVE_ADS -> effectiveRemoveAdsPriceInr
    }

    fun effectivePointsFor(tier: PassTier): Int = when (tier) {
        PassTier.DAILY -> effectiveDailyPoints
        PassTier.WEEKLY -> effectiveWeeklyPoints
        PassTier.MONTHLY -> effectiveMonthlyPoints
        PassTier.REMOVE_ADS -> effectiveRemoveAdsPoints
    }

    fun mapAmountToPassTier(amount: Long): PassTier? {
        return when (amount) {
            effectiveDailyPriceInr.toLong() -> PassTier.DAILY
            effectiveWeeklyPriceInr.toLong() -> PassTier.WEEKLY
            effectiveMonthlyPriceInr.toLong() -> PassTier.MONTHLY
            effectiveRemoveAdsPriceInr.toLong() -> PassTier.REMOVE_ADS
            else -> null
        }
    }

    fun isExactPassAmount(amount: Long): Boolean =
        amount > 0L && (mapAmountToPassTier(amount) != null || effectiveValidPassAmountsInr.any { it.toLong() == amount })
}

/**
 * Pass Tiers Configuration (defaults, overridable via `admin_settings/payment_config`):
 * - Daily Pass: ₹9 INR OR 100 Points (Valid 24 hours / 1 Day)
 * - Weekly Pass: ₹49 INR OR 500 Points (Valid 7 days)
 * - Monthly Pass: ₹179 INR OR 1500 Points (Valid 28 days)
 * - Remove Ads Pass: ₹199 INR OR 1600 Points (Valid 28 days, ads-only)
 */
enum class PassTier(
    val id: String,
    val canonicalId: String,
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
        canonicalId = "daily",
        title = "Daily Pass",
        priceInInr = PassPricingConfig.DAILY_PRICE_INR,
        pointsCost = PassPricingConfig.DAILY_POINTS,
        durationDays = 1,
        durationMs = 24L * 3600L * 1000L,
        tag = "Short Shift",
        description = "24 Hours unlimited high-speed auto-acceptance & radar"
    ),
    WEEKLY(
        id = "weekly_pass",
        canonicalId = "weekly",
        title = "Weekly Pass",
        priceInInr = PassPricingConfig.WEEKLY_PRICE_INR,
        pointsCost = PassPricingConfig.WEEKLY_POINTS,
        durationDays = 7,
        durationMs = 7L * 24L * 3600L * 1000L,
        tag = "Most Popular",
        description = "7 Days unlimited access with surge radar & priority voice alerts"
    ),
    MONTHLY(
        id = "monthly_pass",
        canonicalId = "monthly",
        title = "Monthly Pass",
        priceInInr = PassPricingConfig.MONTHLY_PRICE_INR,
        pointsCost = PassPricingConfig.MONTHLY_POINTS,
        durationDays = 28,
        durationMs = 28L * 24L * 3600L * 1000L,
        tag = "Best Value",
        description = "28 Days full automation + VIP cross-device cloud sync"
    ),
    REMOVE_ADS(
        id = "remove_ads_pass",
        canonicalId = "remove_ads",
        title = "Remove Ads Pass",
        priceInInr = PassPricingConfig.REMOVE_ADS_PRICE_INR,
        pointsCost = PassPricingConfig.REMOVE_ADS_POINTS,
        durationDays = 28,
        durationMs = 28L * 24L * 3600L * 1000L,
        tag = "Ad Free",
        description = "28 Days Ad-Free experience only (Pro features require Daily/Weekly/Monthly Pass or Trial)"
    );

    val isProPass: Boolean
        get() = this != REMOVE_ADS

    fun effectivePriceInInr(): Int = PassPricingConfig.effectivePriceFor(this)

    fun effectivePointsCost(): Int = PassPricingConfig.effectivePointsFor(this)

    companion object {
        val VALID_CANONICAL_PASS_TIERS: Set<String> = setOf("daily", "weekly", "monthly", "remove_ads")

        fun fromId(id: String?): PassTier? {
            val normalized = id?.trim()?.lowercase(Locale.ROOT)
            if (normalized.isNullOrEmpty() ||
                normalized == "none" ||
                normalized == "null" ||
                normalized == "free"
            ) {
                return null
            }
            return entries.firstOrNull {
                it.canonicalId.equals(normalized, ignoreCase = true) ||
                    it.id.equals(normalized, ignoreCase = true)
            }
        }

        fun canonicalPassTierOrNull(id: String?): String? = fromId(id)?.canonicalId

        fun isPaidPassActive(
            activePassType: String?,
            passExpiryTimestamp: Long,
            now: Long = System.currentTimeMillis()
        ): Boolean {
            val parsed = fromId(activePassType) ?: return false
            return parsed.isProPass && passExpiryTimestamp > now
        }

        fun fromAmountInInr(amount: Long): PassTier? = PassPricingConfig.mapAmountToPassTier(amount)
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
    const val MIN_FARE = "min_fare"
    const val MAX_FARE = "max_fare"
    const val MAX_PICKUP_DISTANCE = "max_pickup_distance"
    const val MAX_DROP_DISTANCE = "max_drop_distance"
    const val MIN_PER_KM_RATE = "min_per_km_rate"
    const val TTS_VOICE = "tts_voice"
    const val FLOATING_OVERLAY = "floating_overlay"
    const val DESTINATION_BLACKLIST = "destination_blacklist"

    val FILTER_FEATURE_IDS = listOf(
        MIN_FARE,
        MAX_FARE,
        MAX_PICKUP_DISTANCE,
        MAX_DROP_DISTANCE,
        MIN_PER_KM_RATE,
        DESTINATION_BLACKLIST
    )

    val ALL_FEATURES = listOf(
        FeatureUnlockPricing(
            id = MIN_FARE,
            title = "Minimum Fare Filter",
            pointsPerHour = 10,
            description = "Set your own minimum ride fare threshold (\u20B9)."
        ),
        FeatureUnlockPricing(
            id = MAX_FARE,
            title = "Maximum Fare Filter",
            pointsPerHour = 10,
            description = "Set your own maximum fare ceiling (\u20B9)."
        ),
        FeatureUnlockPricing(
            id = MAX_PICKUP_DISTANCE,
            title = "Maximum Pickup Distance",
            pointsPerHour = 10,
            description = "Filter rides beyond your chosen pickup km limit."
        ),
        FeatureUnlockPricing(
            id = MAX_DROP_DISTANCE,
            title = "Maximum Drop Distance",
            pointsPerHour = 10,
            description = "Filter long trips beyond your drop km limit."
        ),
        FeatureUnlockPricing(
            id = MIN_PER_KM_RATE,
            title = "Minimum Rate Per-KM",
            pointsPerHour = 15,
            description = "Reject rides paying below your target \u20B9/km rate."
        ),
        FeatureUnlockPricing(
            id = DESTINATION_BLACKLIST,
            title = "Destination Blacklist",
            pointsPerHour = 5,
            description = "Automatically block and bypass unwanted drop areas."
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
            pointsPerHour = 10,
            description = "On-screen floating widget showing quick status and controls."
        ),
        FeatureUnlockPricing(
            id = AUTO_CLICK,
            title = "Ultra-Fast Auto-Accept (300ms)",
            pointsPerHour = 15,
            description = "Sub-second response speed (300-700ms) for high-priority order capture."
        )
    )

    private val BUNDLE_PRICING = mapOf(
        FARE_FILTERS to FeatureUnlockPricing(
            id = FARE_FILTERS,
            title = "All Fare & Rate Filters",
            pointsPerHour = 35,
            description = "Min Fare, Max Fare, and Minimum Rate Per-KM evaluation."
        ),
        DISTANCE_FILTERS to FeatureUnlockPricing(
            id = DISTANCE_FILTERS,
            title = "All Distance Filters",
            pointsPerHour = 20,
            description = "Maximum pickup and drop-off distance threshold evaluation."
        )
    )

    @Volatile
    private var dynamicHourlyRates: Map<String, Int> = emptyMap()

    fun applyRateOverrides(rates: Map<String, Int>) {
        dynamicHourlyRates = rates.entries
            .mapNotNull { (k, v) ->
                val cleanKey = k.trim().lowercase()
                if (cleanKey.isNotEmpty() && v > 0) cleanKey to v else null
            }
            .toMap()
    }

    fun resetRatesForTesting() {
        dynamicHourlyRates = emptyMap()
    }

    fun parentGroupFor(featureId: String): String? = when (featureId.lowercase()) {
        MIN_FARE, MAX_FARE, MIN_PER_KM_RATE -> FARE_FILTERS
        MAX_PICKUP_DISTANCE, MAX_DROP_DISTANCE -> DISTANCE_FILTERS
        else -> null
    }

    fun getPricing(featureId: String): FeatureUnlockPricing? {
        val base = ALL_FEATURES.firstOrNull { it.id.equals(featureId, ignoreCase = true) }
            ?: BUNDLE_PRICING[featureId.lowercase()]
            ?: return null
        val override = dynamicHourlyRates[base.id.lowercase()]
        return if (override != null && override > 0) base.copy(pointsPerHour = override) else base
    }

    fun getEffectivePointsPerHour(featureId: String): Int =
        getPricing(featureId)?.pointsPerHour ?: 10
}


/**
 * LicenseManager
 *
 * Zero-Trust Thin-Client Architecture:
 * All authoritative licensing state (`users/{uid}`) is server-managed by the Order Master Admin App
 * via Firestore `pending_actions` and `pending_verifications` queues, with zero direct client writes.
 *
 * 1. Hardware-Locked 2-Day Free Trial (initialized and verified by Admin App via `INIT_PROFILE`).
 * 2. Account-Synced Pass & Points System (processed by Admin App via `pending_actions`).
 * 3. Monotonic Server Clock Synchronization (`SystemClock.elapsedRealtime`) immune to client clock tampering.
 * 4. Maximum 2-Hour Offline Grace Period enforcement.
 * 5. Reactive `StateFlow` Access gating for accessibility automation and overlay services.
 */
object LicenseManager {

    private const val TAG = "LicenseManager"

    // 2-Day Free Trial Duration: 48 Hours
    private const val TRIAL_DURATION_MS = 48L * 3600L * 1000L

    // Maximum allowed offline validity before forcing cloud sync re-check
    const val OFFLINE_GRACE_PERIOD_MS = 2L * 3600L * 1000L // 2 Hours

    // Firestore Collections
    private const val COLL_USERS = "users"
    private const val COLL_PENDING_ACTIONS = "pending_actions"

    // Action Queue request timeout: 60 Seconds
    private const val ACTION_TIMEOUT_MS = 60_000L

    // Action Queue Firestore rate-limit cooldown: 13.5 Seconds (12s Firestore rule + 1.5s network/clock buffer)
    internal const val ACTION_RATE_LIMIT_COOLDOWN_MS = 13_500L
    internal const val ACTION_COOLDOWN_FRIENDLY_MESSAGE = "Please wait a few seconds before trying again."

    // Local UI Fast-Boot Licensing Cache (SharedPreferences)
    const val LICENSE_CACHE_PREFS = "LICENSE_CACHE_PREFS"
    const val KEY_CACHED_PASS_TIER = "cachedPassTier"
    const val KEY_CACHED_PASS_EXPIRY = "cachedPassExpiry"
    const val KEY_CACHED_REMOVE_ADS_EXPIRY = "cachedRemoveAdsExpiry"
    const val KEY_CACHED_TRIAL_CLAIMED = "cachedTrialClaimed"
    const val KEY_CACHED_TRIAL_EXPIRY = "cachedTrialExpiry"
    const val KEY_CACHED_TRIAL_USED = "cachedTrialUsed"
    const val KEY_LAST_SERVER_SYNC_TIME = "cachedLastServerSyncTime"
    const val KEY_LAST_SERVER_SYNC_ELAPSED = "cachedLastServerSyncElapsed"
    const val KEY_LAST_SERVER_TIMESTAMP = "cachedLastServerTimestamp"
    const val KEY_CACHE_HMAC = "cachedLicenseHmac"

    // Maximum allowed discrepancy between wall clock and monotonic elapsedRealtime (5 minutes)
    const val MAX_ALLOWED_CLOCK_DRIFT_MS = 5L * 60L * 1000L

    // Server-side timestamp cross-check interval: 30 Minutes
    const val PERIODIC_INTEGRITY_CHECK_INTERVAL_MS = 30L * 60L * 1000L

    data class CachedLicensingData(
        val passTier: PassTier?,
        val passExpiry: Long,
        val trialClaimed: Long,
        val trialExpiry: Long,
        val trialUsed: Boolean,
        val removeAdsExpiry: Long = 0L
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

    // Pay-Per-Use & Admin feature unlocks map: featureId -> expiryTimestamp (Long ms; -1L = Admin permanent unlock)
    private val _unlockedFeatures = MutableStateFlow<Map<String, Long>>(emptyMap())
    val unlockedFeatures: StateFlow<Map<String, Long>> = _unlockedFeatures.asStateFlow()

    // Explicit Admin Lock set: any feature ID listed here stays strictly LOCKED regardless of pass/points
    private val _lockedFeatures = MutableStateFlow<Set<String>>(emptySet())
    val lockedFeatures: StateFlow<Set<String>> = _lockedFeatures.asStateFlow()

    // Independent Remove Ads Pass expiry timestamp (Long ms; 0L = ads enabled)
    private val _removeAdsExpiryMs = MutableStateFlow(0L)
    val removeAdsExpiryMs: StateFlow<Long> = _removeAdsExpiryMs.asStateFlow()

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
        if (passTier != null && passTier.isProPass && passExpiry > now) {
            return when (passTier) {
                PassTier.DAILY -> "daily"
                PassTier.WEEKLY -> "weekly"
                PassTier.MONTHLY -> "monthly"
                PassTier.REMOVE_ADS -> "free_mod"
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
    private var serverRemoveAdsExpiry: Long = 0L
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
    private var currentUserKey: String? = null
    private var activeUserDocUid: String? = null
    private val authLock = Any()
    private val syncMutex = Mutex()
    private val actionDispatchMutex = Mutex()
    private val docInitAttempted = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    internal const val SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE =
        "Background profile sync deferred while a user action has priority."

    internal val USER_TRIGGERED_ACTION_TYPES: Set<String> = setOf(
        "PURCHASE_PASS",
        "ACTIVATE_PASS_PAYMENT",
        "REWARD_AD_WATCHED",
        "UNLOCK_FEATURE",
        "UNLOCK_MULTIPLE_FEATURES",
        "CLAIM_REFERRAL"
    )

    @Volatile
    private var lastActionCompletedAtMs: Long = 0L
    private val actionInFlight = AtomicBoolean(false)
    private val backgroundProfileUpdateInFlight = AtomicBoolean(false)

    @Volatile
    private var deferredProfileUpdateJob: Job? = null

    @VisibleForTesting
    internal fun isSilentBackgroundAction(actionType: String): Boolean {
        return actionType.trim().equals("UPDATE_PROFILE", ignoreCase = true)
    }

    @VisibleForTesting
    internal fun isUserTriggeredAction(actionType: String): Boolean {
        return USER_TRIGGERED_ACTION_TYPES.contains(actionType.trim().uppercase(Locale.ROOT))
    }

    @VisibleForTesting
    internal fun getLastActionDispatchElapsedForTesting(): Long = lastActionCompletedAtMs

    @VisibleForTesting
    internal fun getLastActionCompletedAtMsForTesting(): Long = lastActionCompletedAtMs

    @VisibleForTesting
    internal fun isActionInFlightForTesting(): Boolean = actionInFlight.get()

    @VisibleForTesting
    internal fun setActionInFlightForTesting(inFlight: Boolean) {
        actionInFlight.set(inFlight)
    }

    @VisibleForTesting
    internal fun isBackgroundProfileUpdateInFlightForTesting(): Boolean =
        backgroundProfileUpdateInFlight.get()

    @VisibleForTesting
    internal fun setBackgroundProfileUpdateInFlightForTesting(inFlight: Boolean) {
        backgroundProfileUpdateInFlight.set(inFlight)
    }

    /**
     * Anchors the client-side 13.5s rate-limit cooldown to a foreground/user action's terminal
     * completion (`COMPLETED`, `REJECTED`, or `FAILED`), matching Admin App Step 7A & Step 11A
     * where silent `UPDATE_PROFILE` does NOT overwrite `users/{uid}.lastActionTime`.
     */
    @VisibleForTesting
    internal fun recordActionTerminalCompletion(
        nowElapsed: Long = safeElapsedRealtime(),
        actionType: String? = null
    ) {
        if (actionType != null && isSilentBackgroundAction(actionType)) {
            return
        }
        val marker = if (nowElapsed == 0L) -1L else nowElapsed
        if (lastActionCompletedAtMs == 0L || marker > lastActionCompletedAtMs) {
            lastActionCompletedAtMs = marker
        }
    }

    /**
     * Synchronizes the client-side action queue cooldown from the server `lastActionTime`
     * (or `processedAt`) field so rapid back-to-back actions respect Firestore's 12s rule
     * plus 1.5s clock/network buffer (`13_500L` ms).
     */
    @VisibleForTesting
    internal fun updateLastActionCooldownFromServerTimestamp(
        lastActionServerMs: Long?,
        nowServerMs: Long = getCurrentServerTimeMs(),
        nowElapsed: Long = safeElapsedRealtime()
    ) {
        if (lastActionServerMs == null || lastActionServerMs <= 0L) return
        val ageMs = (nowServerMs - lastActionServerMs).coerceAtLeast(0L)
        if (ageMs < ACTION_RATE_LIMIT_COOLDOWN_MS) {
            val rawEstimatedElapsed = nowElapsed - ageMs
            val estimatedElapsed = if (rawEstimatedElapsed == 0L) -1L else rawEstimatedElapsed
            if (lastActionCompletedAtMs == 0L || estimatedElapsed > lastActionCompletedAtMs) {
                lastActionCompletedAtMs = estimatedElapsed
            }
        }
    }

    /**
     * Returns remaining milliseconds in the local 13.5s cooldown window anchored to
     * `lastActionCompletedAtMs`, or `0L` if a new action may be submitted immediately.
     */
    @VisibleForTesting
    internal fun computeActionCooldownRemainingMs(nowElapsed: Long = safeElapsedRealtime()): Long {
        val anchor = lastActionCompletedAtMs
        if (anchor == 0L) return 0L
        val elapsed = nowElapsed - anchor
        return if (elapsed in 0L until ACTION_RATE_LIMIT_COOLDOWN_MS) {
            ACTION_RATE_LIMIT_COOLDOWN_MS - elapsed
        } else {
            0L
        }
    }

    /**
     * Returns `true` only when no explicit user-triggered action is currently in flight,
     * no other background profile sync is in flight, and the 13.5s user-action cooldown window
     * is completely clear. Otherwise silent `UPDATE_PROFILE` must defer to give user actions priority.
     */
    @VisibleForTesting
    internal fun canDispatchSilentProfileUpdate(nowElapsed: Long = safeElapsedRealtime()): Boolean {
        return !actionInFlight.get() &&
            !backgroundProfileUpdateInFlight.get() &&
            computeActionCooldownRemainingMs(nowElapsed) == 0L
    }

    /**
     * Checks whether a new action submission is permitted right now.
     * - Silent `UPDATE_PROFILE` defers whenever any user action is in-flight or in cooldown.
     * - Explicit user actions check only `actionInFlight` and the 13.5s user cooldown (never
     *   blocked by a silent background `UPDATE_PROFILE`).
     */
    @VisibleForTesting
    internal fun checkActionSubmissionAllowed(
        nowElapsed: Long = safeElapsedRealtime(),
        actionType: String? = null
    ): Result<Unit> {
        if (actionType != null && isSilentBackgroundAction(actionType)) {
            return if (canDispatchSilentProfileUpdate(nowElapsed)) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE))
            }
        }
        if (actionInFlight.get() || computeActionCooldownRemainingMs(nowElapsed) > 0L) {
            return Result.failure(IllegalStateException(ACTION_COOLDOWN_FRIENDLY_MESSAGE))
        }
        return Result.success(Unit)
    }

    @Volatile
    internal var onEntitlementsChanged: (() -> Unit)? = null

    @Volatile
    private var profileUpdateDispatchedHash: String? = null
    private val profileUpdateLock = Any()

    @VisibleForTesting
    internal fun computeNeededProfileUpdates(
        snapshotFirebaseUid: String?,
        snapshotHardwareId: String?,
        snapshotEmail: String?,
        expectedFirebaseUid: String,
        currentHardwareId: String,
        authEmail: String,
        snapshotAutoClickEnabled: Boolean? = null,
        desiredAutoClickEnabled: Boolean? = null
    ): Map<String, Any> {
        val updatesNeeded = mutableMapOf<String, Any>()
        if (expectedFirebaseUid.isNotBlank() && snapshotFirebaseUid.orEmpty() != expectedFirebaseUid) {
            updatesNeeded["uid"] = expectedFirebaseUid
        }
        if (currentHardwareId.isNotBlank() && snapshotHardwareId.orEmpty() != currentHardwareId) {
            updatesNeeded["linkedHardwareId"] = currentHardwareId
        }
        if (authEmail.isNotBlank() && snapshotEmail.orEmpty() != authEmail) {
            updatesNeeded["userEmail"] = authEmail
        }
        if (desiredAutoClickEnabled != null && snapshotAutoClickEnabled != desiredAutoClickEnabled) {
            updatesNeeded["isAutoClickEnabled"] = desiredAutoClickEnabled
        }
        if (updatesNeeded.isNotEmpty() && expectedFirebaseUid.isNotBlank()) {
            updatesNeeded["uid"] = expectedFirebaseUid
        }
        return updatesNeeded
    }

    @VisibleForTesting
    internal fun buildInitProfilePayload(
        firebaseUid: String,
        userEmail: String,
        hardwareId: String,
        @Suppress("UNUSED_PARAMETER") legacyCustomId: String? = null
    ): Map<String, Any> {
        return mapOf(
            "uid" to firebaseUid,
            "userEmail" to userEmail,
            "linkedHardwareId" to hardwareId
        )
    }

    private fun computeProfileUpdateHash(userKey: String, fields: Map<String, Any>): String? {
        if (fields.isEmpty()) return null
        val normalized = fields.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }
        if (normalized.isEmpty()) return null
        return "$userKey::$normalized"
    }

    @VisibleForTesting
    internal fun shouldDispatchProfileUpdate(userKey: String, fields: Map<String, Any>): Boolean {
        val newHash = computeProfileUpdateHash(userKey, fields) ?: return false
        synchronized(profileUpdateLock) {
            if (profileUpdateDispatchedHash == newHash) {
                return false
            }
            profileUpdateDispatchedHash = newHash
            return true
        }
    }

    private fun resetProfileUpdateHashIfMatches(userKey: String, fields: Map<String, Any>) {
        val targetHash = computeProfileUpdateHash(userKey, fields) ?: return
        synchronized(profileUpdateLock) {
            if (profileUpdateDispatchedHash == targetHash) {
                profileUpdateDispatchedHash = null
            }
        }
    }

    private fun cancelDeferredProfileUpdateJob() {
        synchronized(profileUpdateLock) {
            deferredProfileUpdateJob?.cancel()
            deferredProfileUpdateJob = null
        }
    }

    private fun scheduleDeferredSilentProfileUpdate(
        userKey: String,
        updatesNeeded: Map<String, Any>,
        attempt: Int
    ) {
        if (attempt >= 4 || updatesNeeded.isEmpty()) return
        val delayMs = (computeActionCooldownRemainingMs() + 500L).coerceAtLeast(1_500L)
        synchronized(profileUpdateLock) {
            deferredProfileUpdateJob?.cancel()
            deferredProfileUpdateJob = scope.launch {
                delay(delayMs)
                dispatchSilentProfileUpdateWithPriority(userKey, updatesNeeded, attempt)
            }
        }
    }

    private fun dispatchSilentProfileUpdateWithPriority(
        userKey: String,
        updatesNeeded: Map<String, Any>,
        attempt: Int = 0
    ) {
        if (updatesNeeded.isEmpty()) return
        if (!canDispatchSilentProfileUpdate()) {
            Log.d(
                TAG,
                "Deferring silent UPDATE_PROFILE (attempt=$attempt) to preserve priority for active/recent user action."
            )
            scheduleDeferredSilentProfileUpdate(userKey, updatesNeeded, attempt + 1)
            return
        }
        if (!shouldDispatchProfileUpdate(userKey, updatesNeeded)) return

        scope.launch {
            try {
                val result = executeActionQueueRequest("UPDATE_PROFILE", updatesNeeded)
                if (result.isFailure) {
                    resetProfileUpdateHashIfMatches(userKey, updatesNeeded)
                    if (result.exceptionOrNull()?.message == SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE) {
                        scheduleDeferredSilentProfileUpdate(userKey, updatesNeeded, attempt + 1)
                    }
                }
            } catch (e: Exception) {
                resetProfileUpdateHashIfMatches(userKey, updatesNeeded)
                Log.w(TAG, "Failed updating user document fields via Action Queue: ${e.message}")
            }
        }
    }

    /**
     * Delegates real-time listener for app_settings/global to GlobalAdminManager
     * so only a single Firestore snapshot listener is attached to app_settings/global.
     */
    fun startGlobalSettingsListener() {
        GlobalAdminManager.startGlobalServiceListener()
    }

    /**
     * Records a trusted server timestamp baseline and marks monotonic elapsed time.
     */
    fun recordServerTimeSync(serverMs: Long) {
        if (serverMs > 0L) {
            val nowElapsed = safeElapsedRealtime()
            val nowWall = System.currentTimeMillis()
            serverTimeBaselineMs = serverMs
            serverTimeBaselineElapsedMs = nowElapsed
            lastSuccessfulServerSyncElapsedMs = nowElapsed
            appContext?.let { ctx ->
                try {
                    val prefs = ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                    val passTierId = prefs.getString(KEY_CACHED_PASS_TIER, "") ?: ""
                    val passExpiry = prefs.getLong(KEY_CACHED_PASS_EXPIRY, 0L)
                    val removeAdsExpiry = prefs.getLong(KEY_CACHED_REMOVE_ADS_EXPIRY, 0L)
                    val trialClaimed = prefs.getLong(KEY_CACHED_TRIAL_CLAIMED, 0L)
                    val trialExpiry = prefs.getLong(KEY_CACHED_TRIAL_EXPIRY, 0L)
                    val trialUsed = prefs.getBoolean(KEY_CACHED_TRIAL_USED, false)
                    val hmac = computeLicenseCacheHmac(
                        ctx, passTierId, passExpiry, trialClaimed, trialExpiry, trialUsed,
                        nowWall, nowElapsed, serverMs, removeAdsExpiry
                    )
                    prefs.edit()
                        .putLong(KEY_LAST_SERVER_SYNC_TIME, nowWall)
                        .putLong(KEY_LAST_SERVER_SYNC_ELAPSED, nowElapsed)
                        .putLong(KEY_LAST_SERVER_TIMESTAMP, serverMs)
                        .putString(KEY_CACHE_HMAC, hmac)
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
        if (baseline > 0L && baselineElapsed > 0L) {
            val elapsedDelta = safeElapsedRealtime() - baselineElapsed
            return baseline + elapsedDelta
        }
        val wallNow = System.currentTimeMillis()
        val lastServerTs = try {
            appContext?.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                ?.getLong(KEY_LAST_SERVER_TIMESTAMP, 0L) ?: 0L
        } catch (_: Exception) {
            0L
        }
        return if (lastServerTs > 0L) maxOf(wallNow, lastServerTs) else wallNow
    }

    @VisibleForTesting
    internal enum class ClockTamperStatus {
        NONE,
        FORWARD_DRIFT,
        HARD_TAMPER
    }

    /**
     * Classifies clock discrepancies into NONE, FORWARD_DRIFT (wall clock moved ahead of monotonic elapsed),
     * or HARD_TAMPER (clock rollback, reboot rollback, or offline cold-start skew).
     */
    @VisibleForTesting
    internal fun classifyClockTampering(context: Context? = null): ClockTamperStatus {
        val ctx = context ?: appContext ?: return ClockTamperStatus.NONE
        val prefs = try {
            ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        } catch (_: Exception) { return ClockTamperStatus.NONE }

        val syncWall = prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, 0L)
        val syncElapsed = prefs.getLong(KEY_LAST_SERVER_SYNC_ELAPSED, 0L)
        val lastServerTs = prefs.getLong(KEY_LAST_SERVER_TIMESTAMP, 0L)

        val currentWall = System.currentTimeMillis()
        val currentElapsed = safeElapsedRealtime()

        if (serverTimeBaselineMs > 0L && serverTimeBaselineElapsedMs > 0L) {
            val expectedUtcNow = serverTimeBaselineMs + (currentElapsed - serverTimeBaselineElapsedMs)
            if (currentWall < expectedUtcNow - MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Pre-launch/runtime clock rollback detected: wall=$currentWall < expectedServerUtc=$expectedUtcNow")
                return ClockTamperStatus.HARD_TAMPER
            }
            if (currentWall > expectedUtcNow + MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Forward clock drift vs server baseline detected: wall=$currentWall > expectedServerUtc=$expectedUtcNow")
                return ClockTamperStatus.FORWARD_DRIFT
            }
        } else if (lastServerTs > 0L && currentWall < lastServerTs - MAX_ALLOWED_CLOCK_DRIFT_MS) {
            Log.w(TAG, "Offline cold-start clock rollback detected: wall=$currentWall < lastServerTs=$lastServerTs")
            return ClockTamperStatus.HARD_TAMPER
        }

        // If no persisted server sync exists yet, stop here
        if (syncWall <= 0L || syncElapsed <= 0L) return ClockTamperStatus.NONE

        val deltaWall = currentWall - syncWall
        val deltaElapsed = currentElapsed - syncElapsed

        // 1. Clock turned backwards into the past (rollback exploit)
        if (deltaWall < -60_000L) {
            Log.w(TAG, "Clock tampering detected: Wall clock wound back by ${-deltaWall}ms")
            return ClockTamperStatus.HARD_TAMPER
        }

        val effectiveGraceMs = getEffectiveOfflineGracePeriodMs(ctx, syncWall, currentWall)

        // 2. Device reboot detection: elapsedRealtime resets near 0 after a device reboot.
        val hasRebooted = deltaElapsed < -5_000L
        if (hasRebooted) {
            if (currentWall < syncWall - MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Clock tampering detected after reboot: wall=$currentWall < syncWall=$syncWall")
                return ClockTamperStatus.HARD_TAMPER
            }
            if (lastServerTs > 0L && currentWall < lastServerTs - MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Clock tampering detected after reboot: wall=$currentWall < lastServerTs=$lastServerTs")
                return ClockTamperStatus.HARD_TAMPER
            }
            if (deltaWall > effectiveGraceMs) {
                Log.w(TAG, "Post-reboot offline wall delta exceeds grace period: ${deltaWall}ms > ${effectiveGraceMs}ms")
                return ClockTamperStatus.HARD_TAMPER
            }
            // Wall clock stayed consistent and within offline grace across reboot; skip elapsed delta drift check
            return ClockTamperStatus.NONE
        }

        // 3. Cross-check against persisted authoritative UTC server timestamp across restarts
        if (lastServerTs > 0L && deltaElapsed >= 0L && deltaElapsed < effectiveGraceMs) {
            val expectedWallFromServer = lastServerTs + deltaElapsed
            if (currentWall < expectedWallFromServer - MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Clock rollback vs server UTC detected: wall=$currentWall < expected=$expectedWallFromServer")
                return ClockTamperStatus.HARD_TAMPER
            }
            if (currentWall > expectedWallFromServer + MAX_ALLOWED_CLOCK_DRIFT_MS) {
                Log.w(TAG, "Forward clock drift vs server UTC detected: wall=$currentWall > expected=$expectedWallFromServer")
                return ClockTamperStatus.FORWARD_DRIFT
            }
        }

        // 4. Mathematical discrepancy between elapsed realtime and wall-clock time
        val signedDrift = deltaWall - deltaElapsed
        if (signedDrift < -MAX_ALLOWED_CLOCK_DRIFT_MS) {
            Log.w(TAG, "Clock tampering (negative drift) detected: deltaWall=$deltaWall, deltaElapsed=$deltaElapsed")
            return ClockTamperStatus.HARD_TAMPER
        }
        if (signedDrift > MAX_ALLOWED_CLOCK_DRIFT_MS) {
            Log.w(TAG, "Forward clock drift detected: deltaWall=$deltaWall, deltaElapsed=$deltaElapsed, drift=$signedDrift > max=$MAX_ALLOWED_CLOCK_DRIFT_MS")
            return ClockTamperStatus.FORWARD_DRIFT
        }

        return ClockTamperStatus.NONE
    }

    /**
     * Mathematically evaluates whether local wall-clock time, authoritative server UTC time,
     * and monotonic elapsedRealtime have diverged beyond acceptable physical limits.
     */
    fun isClockTamperingDetected(context: Context? = null): Boolean {
        return classifyClockTampering(context) != ClockTamperStatus.NONE
    }

    private fun clearUserSessionStateLocked(preserveTrialUsed: Boolean = false) {
        serverPassTier = null
        serverPassExpiry = 0L
        serverRemoveAdsExpiry = 0L
        serverTrialClaimed = 0L
        serverTrialExpiry = 0L
        if (!preserveTrialUsed) {
            serverTrialUsed = false
        }
        docInitAttempted.set(false)
        lastActionCompletedAtMs = 0L
        actionInFlight.set(false)
        backgroundProfileUpdateInFlight.set(false)
        cancelDeferredProfileUpdateJob()
        _pointsBalance.value = 0
        _hasClaimedReferral.value = false
        _referralCount.value = 0
        _referralCode.value = null
        _removeAdsExpiryMs.value = 0L
        _unlockedFeatures.value = emptyMap()
        _lockedFeatures.value = emptySet()
        _activePassInfo.value = null
        _trialInfo.value = null
        _currentFolder.value = "free_mod"
        _isBannedFlow.value = false
        _banUntilTimestampFlow.value = null
        _banReasonFlow.value = null
        GlobalAdminManager.syncBanStateFromLicenseManager(null, null)
    }

    /**
     * Enforces immediate invalidation of local license cache and triggers cloud re-sync
     * when clock tampering or expired grace period is detected.
     */
    fun invalidateAndForceCloudResync(context: Context, reason: String) {
        Log.w(TAG, "INVALIDATING LICENSE CACHE: $reason. Triggering mandatory cloud re-sync.")
        clearLicensingCache(context)
        clearUserSessionStateLocked(preserveTrialUsed = true)
        serverTrialUsed = true // Block free trial exploitation on tampered environments

        _accessStatus.value = AccessStatus.Expired(
            isTrialUsed = true,
            hasHadPreviousPass = true,
            message = "Security verification required. Please connect to internet to verify your subscription."
        )
        onEntitlementsChanged?.invoke()

        scope.launch {
            try {
                syncHardwareTrialAndLicensing()
            } catch (e: Exception) {
                Log.w(TAG, "Mandatory cloud re-sync attempt failed: ${e.message}")
            }
        }
    }

    /**
     * Returns remote-configured paid pass offline grace hours clamped to `[2, 72]` (default 24h).
     */
    fun getEffectivePaidPassOfflineGraceHours(): Int {
        return AppConfigRepository.effectivePaidPassOfflineGraceHours()
    }

    /**
     * Resolves an active paid Pro Pass expiry timestamp (in ms) from authoritative memory or
     * HMAC-verified local cache. Returns `0L` for free/trial/remove-ads/expired states so only
     * active paid pass drivers receive the extended offline grace window.
     */
    @VisibleForTesting
    internal fun resolveActivePaidPassExpiryMs(
        context: Context? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        if (serverPassTier?.isProPass == true && serverPassExpiry > nowMs) {
            return serverPassExpiry
        }
        val ctx = context ?: appContext ?: return 0L
        val cached = getCachedLicensingData(ctx)
        if (cached.passTier?.isProPass == true && cached.passExpiry > nowMs) {
            return cached.passExpiry
        }
        return 0L
    }

    /**
     * Pure deterministic offline grace deadline calculation:
     * - Active paid pass (`activePaidPassExpiryMs > nowMs` and `> lastCloudSyncAtMs`):
     *   `min(activePaidPassExpiryMs, lastCloudSyncAtMs + clampedHours * 3_600_000L)`
     * - Free / Trial / Unpaid / Expired states:
     *   `lastCloudSyncAtMs + OFFLINE_GRACE_PERIOD_MS` (strict 2 hours).
     */
    @VisibleForTesting
    internal fun computeOfflineGraceDeadlineMs(
        lastCloudSyncAtMs: Long,
        activePaidPassExpiryMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        paidPassOfflineGraceHours: Int = getEffectivePaidPassOfflineGraceHours()
    ): Long {
        if (lastCloudSyncAtMs <= 0L) return 0L
        if (activePaidPassExpiryMs > nowMs && activePaidPassExpiryMs > lastCloudSyncAtMs) {
            val clampedHours = paidPassOfflineGraceHours.coerceIn(
                AppConfigRepository.MIN_PAID_PASS_OFFLINE_GRACE_HOURS,
                AppConfigRepository.MAX_PAID_PASS_OFFLINE_GRACE_HOURS
            )
            return minOf(activePaidPassExpiryMs, lastCloudSyncAtMs + clampedHours * 3_600_000L)
        }
        return lastCloudSyncAtMs + OFFLINE_GRACE_PERIOD_MS
    }

    /**
     * Computes the effective offline grace duration in milliseconds from [lastSyncWallMs].
     */
    @VisibleForTesting
    internal fun getEffectiveOfflineGracePeriodMs(
        context: Context? = null,
        lastSyncWallMs: Long = 0L,
        nowWallMs: Long = System.currentTimeMillis()
    ): Long {
        val activePaidExpiry = resolveActivePaidPassExpiryMs(context, nowWallMs)
        if (activePaidExpiry <= nowWallMs) {
            return OFFLINE_GRACE_PERIOD_MS
        }
        val clampedHours = getEffectivePaidPassOfflineGraceHours()
        val maxPaidGraceMs = clampedHours * 3_600_000L
        if (lastSyncWallMs > 0L) {
            val deadline = computeOfflineGraceDeadlineMs(
                lastCloudSyncAtMs = lastSyncWallMs,
                activePaidPassExpiryMs = activePaidExpiry,
                nowMs = nowWallMs,
                paidPassOfflineGraceHours = clampedHours
            )
            return (deadline - lastSyncWallMs).coerceAtLeast(0L)
        }
        return minOf(maxPaidGraceMs, (activePaidExpiry - nowWallMs).coerceAtLeast(0L))
    }

    /**
     * Returns the human-readable offline grace limit in hours currently applicable for this user
     * (`paidPassOfflineGraceHours` for active paid pass drivers, `2` for free/trial/unpaid users).
     */
    fun getEffectiveOfflineGraceHours(context: Context? = null): Int {
        val nowWall = System.currentTimeMillis()
        val activePaidExpiry = resolveActivePaidPassExpiryMs(context, nowWall)
        return if (activePaidExpiry > nowWall) {
            getEffectivePaidPassOfflineGraceHours()
        } else {
            (OFFLINE_GRACE_PERIOD_MS / 3_600_000L).toInt()
        }
    }

    /**
     * Checks if the effective offline grace period has expired or if clock tampering was detected.
     * - Active paid pass drivers: `min(passExpiryTimestamp, lastCloudSyncAt + paidPassOfflineGraceHours * 3_600_000L)`.
     * - Free / Trial / Unpaid users: strict 2-hour `OFFLINE_GRACE_PERIOD_MS`.
     */
    fun isOfflineGracePeriodExpired(context: Context? = null): Boolean {
        val ctx = context ?: appContext

        // 1. If tampering is mathematically detected, offline grace is voided immediately
        if (isClockTamperingDetected(ctx)) {
            Log.w(TAG, "isOfflineGracePeriodExpired: Clock tampering mathematically detected. Voiding offline grace.")
            return true
        }

        val nowWall = System.currentTimeMillis()
        var lastServerSyncTime = 0L
        if (ctx != null) {
            try {
                val prefs = ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
                lastServerSyncTime = prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, 0L)
            } catch (_: Exception) {}
        }

        val effectiveGraceMs = getEffectiveOfflineGracePeriodMs(ctx, lastServerSyncTime, nowWall)

        val lastSync = lastSuccessfulServerSyncElapsedMs
        if (lastSync > 0L) {
            val offlineDuration = SystemClock.elapsedRealtime() - lastSync
            if (offlineDuration > effectiveGraceMs) return true
        }
        if (lastServerSyncTime > 0L) {
            val offlineWallDuration = nowWall - lastServerSyncTime
            val deadlineMs = computeOfflineGraceDeadlineMs(
                lastCloudSyncAtMs = lastServerSyncTime,
                activePaidPassExpiryMs = resolveActivePaidPassExpiryMs(ctx, nowWall),
                nowMs = nowWall
            )
            if (offlineWallDuration < -60_000L || nowWall > deadlineMs) {
                Log.w(
                    TAG,
                    "Offline grace period expired or clock tampering detected: duration=${offlineWallDuration}ms, limit=${effectiveGraceMs}ms"
                )
                return true
            }
        }
        return false
    }

    /**
     * Performs timestamp cross-check every 30 minutes.
     * - Immediate invalidation on hard clock rollback / offline cold-start skew.
     * - For forward (deltaWall - deltaElapsed) drift while online, triggers fetchServerTimeBaseline()
     *   to re-calibrate the authoritative server clock before invalidating the user's license cache.
     */
    suspend fun performTimestampIntegrityCrossCheckSuspend(context: Context) {
        Log.d(TAG, "Executing periodic timestamp integrity cross-check...")

        when (classifyClockTampering(context)) {
            ClockTamperStatus.HARD_TAMPER -> {
                invalidateAndForceCloudResync(
                    context,
                    "Periodic cross-check: Mathematical clock rollback / elapsed time hooking detected"
                )
                return
            }
            ClockTamperStatus.FORWARD_DRIFT -> {
                if (DeviceUtils.isNetworkAvailable(context)) {
                    Log.i(TAG, "Forward clock drift detected while online; re-calibrating server clock baseline first.")
                    try {
                        fetchServerTimeBaseline()
                    } catch (e: Exception) {
                        Log.w(TAG, "Forward-drift server baseline re-calibration failed: ${e.message}")
                    }
                    if (isClockTamperingDetected(context)) {
                        invalidateAndForceCloudResync(
                            context,
                            "Periodic cross-check: Clock tampering confirmed after server time re-calibration"
                        )
                        return
                    }
                    Log.i(TAG, "Forward clock drift resolved via server time baseline re-calibration.")
                } else {
                    invalidateAndForceCloudResync(
                        context,
                        "Periodic cross-check: Forward clock drift detected offline"
                    )
                    return
                }
            }
            ClockTamperStatus.NONE -> Unit
        }

        // 2. Check offline grace period expiration (24h default for active paid pass, 2h for free/trial):
        if (isOfflineGracePeriodExpired(context)) {
            val graceHours = getEffectiveOfflineGraceHours(context)
            Log.w(TAG, "Periodic cross-check: Offline grace period ($graceHours hours) expired.")
            invalidateAndForceCloudResync(
                context,
                "Offline grace period ($graceHours hours) expired without server confirmation"
            )
            return
        }

        // 3. If online, perform normal cloud re-sync:
        val authState = GoogleAuthManager.userAuthState.value
        if (authState.isLoggedIn) {
            try {
                syncHardwareTrialAndLicensing()
            } catch (e: Exception) {
                Log.d(TAG, "Periodic cloud re-sync skipped (offline/network error): ${e.message}")
            }
        }
    }

    fun performTimestampIntegrityCrossCheck(context: Context) {
        when (classifyClockTampering(context)) {
            ClockTamperStatus.HARD_TAMPER -> {
                invalidateAndForceCloudResync(
                    context,
                    "Periodic cross-check: Mathematical clock rollback / elapsed time hooking detected"
                )
                return
            }
            ClockTamperStatus.FORWARD_DRIFT -> {
                if (DeviceUtils.isNetworkAvailable(context)) {
                    scope.launch { performTimestampIntegrityCrossCheckSuspend(context) }
                } else {
                    invalidateAndForceCloudResync(
                        context,
                        "Periodic cross-check: Forward clock drift detected offline"
                    )
                }
                return
            }
            ClockTamperStatus.NONE -> Unit
        }

        if (isOfflineGracePeriodExpired(context)) {
            val graceHours = getEffectiveOfflineGraceHours(context)
            Log.w(TAG, "Periodic cross-check: Offline grace period ($graceHours hours) expired.")
            invalidateAndForceCloudResync(
                context,
                "Offline grace period ($graceHours hours) expired without server confirmation"
            )
            return
        }

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

    private fun isUnitTestEnvironment(): Boolean = DeviceUtils.isUnitTestEnvironment()

    private const val KEYSTORE_HMAC_ALIAS = "order_master_license_cache_hmac_key"
    private val hmacKeyLock = Any()

    @Volatile
    private var unitTestFallbackHmacKey: SecretKey? = null

    private fun getOrCreateLicenseCacheHmacKey(): SecretKey? {
        synchronized(hmacKeyLock) {
            try {
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val existing = keyStore.getKey(KEYSTORE_HMAC_ALIAS, null) as? SecretKey
                if (existing != null) return existing

                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                    "AndroidKeyStore"
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEYSTORE_HMAC_ALIAS,
                    KeyProperties.PURPOSE_SIGN
                )
                    .setKeySize(256)
                    .build()
                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            } catch (e: Exception) {
                if (isUnitTestEnvironment()) {
                    return unitTestFallbackHmacKey ?: ByteArray(32).also {
                        SecureRandom().nextBytes(it)
                    }.let { randomBytes ->
                        SecretKeySpec(randomBytes, "HmacSHA256").also {
                            unitTestFallbackHmacKey = it
                        }
                    }
                }
                Log.w(TAG, "AndroidKeyStore HMAC key unavailable: ${e.message}")
                return null
            }
        }
    }

    /**
     * Computes AndroidKeyStore hardware/package-bound HMAC-SHA256 signature over cached licensing fields so
     * root users editing LICENSE_CACHE_PREFS.xml cannot forge active passes or timestamps.
     */
    private fun computeLicenseCacheHmac(
        context: Context,
        passTierId: String,
        passExpiry: Long,
        trialClaimed: Long,
        trialExpiry: Long,
        trialUsed: Boolean,
        syncWall: Long,
        syncElapsed: Long,
        serverTimestamp: Long,
        removeAdsExpiry: Long = 0L
    ): String {
        return try {
            val hmacKey = getOrCreateLicenseCacheHmacKey() ?: return ""
            val hwId = DeviceUtils.getDeviceHardwareId(context)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(hmacKey)
            val payload = "$hwId|${context.packageName}|$passTierId|$passExpiry|$trialClaimed|$trialExpiry|$trialUsed|$syncWall|$syncElapsed|$serverTimestamp|$removeAdsExpiry"
            mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed computing cache HMAC: ${e.message}")
            ""
        }
    }

    /**
     * Saves authoritative licensing and trial data to SharedPreferences local UI cache
     * protected by an HMAC-SHA256 tag bound to device hardware identity.
     */
    fun saveAuthoritativeLicensingCache(
        context: Context,
        passTier: PassTier?,
        passExpiry: Long,
        trialClaimed: Long,
        trialExpiry: Long,
        trialUsed: Boolean,
        removeAdsExpiry: Long = 0L
    ) {
        try {
            val nowElapsed = safeElapsedRealtime()
            val nowWall = System.currentTimeMillis()
            val serverTs = serverTimeBaselineMs.takeIf { it > 0L } ?: nowWall
            val effectivePassTier = passTier?.takeIf { it.isProPass }
            val effectivePassExpiry = if (effectivePassTier != null) passExpiry else 0L
            val finalRemoveAdsExpiry = maxOf(
                removeAdsExpiry,
                if (passTier == PassTier.REMOVE_ADS) passExpiry else 0L
            )
            val passTierId = effectivePassTier?.id ?: ""
            lastSuccessfulServerSyncElapsedMs = nowElapsed
            val hmac = computeLicenseCacheHmac(
                context, passTierId, effectivePassExpiry, trialClaimed, trialExpiry, trialUsed,
                nowWall, nowElapsed, serverTs, finalRemoveAdsExpiry
            )
            val prefs = context.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CACHED_PASS_TIER, passTierId)
                .putLong(KEY_CACHED_PASS_EXPIRY, effectivePassExpiry)
                .putLong(KEY_CACHED_REMOVE_ADS_EXPIRY, finalRemoveAdsExpiry)
                .putLong(KEY_CACHED_TRIAL_CLAIMED, trialClaimed)
                .putLong(KEY_CACHED_TRIAL_EXPIRY, trialExpiry)
                .putBoolean(KEY_CACHED_TRIAL_USED, trialUsed)
                .putLong(KEY_LAST_SERVER_SYNC_TIME, nowWall)
                .putLong(KEY_LAST_SERVER_SYNC_ELAPSED, nowElapsed)
                .putLong(KEY_LAST_SERVER_TIMESTAMP, serverTs)
                .putString(KEY_CACHE_HMAC, hmac)
                .commit()
            Log.d(TAG, "Saved HMAC-signed authoritative licensing cache: passTier=${effectivePassTier?.id}, passExpiry=$effectivePassExpiry, removeAdsExpiry=$finalRemoveAdsExpiry, trialClaimed=$trialClaimed, trialExpiry=$trialExpiry, trialUsed=$trialUsed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving licensing cache: ${e.message}")
        }
    }

    /**
     * Reads and cryptographically verifies cached licensing data from SharedPreferences.
     * Any tampering, missing sync metadata, or HMAC mismatch wipes the cache immediately.
     */
    fun getCachedLicensingData(context: Context): CachedLicensingData {
        return try {
            val prefs = context.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            val passTierId = prefs.getString(KEY_CACHED_PASS_TIER, "") ?: ""
            val passExpiry = prefs.getLong(KEY_CACHED_PASS_EXPIRY, 0L)
            val removeAdsExpiry = prefs.getLong(KEY_CACHED_REMOVE_ADS_EXPIRY, 0L)
            val trialClaimed = prefs.getLong(KEY_CACHED_TRIAL_CLAIMED, 0L)
            val trialExpiry = prefs.getLong(KEY_CACHED_TRIAL_EXPIRY, 0L)
            val trialUsed = prefs.getBoolean(KEY_CACHED_TRIAL_USED, false)
            val syncWall = prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, 0L)
            val syncElapsed = prefs.getLong(KEY_LAST_SERVER_SYNC_ELAPSED, 0L)
            val serverTs = prefs.getLong(KEY_LAST_SERVER_TIMESTAMP, 0L)
            val storedHmac = prefs.getString(KEY_CACHE_HMAC, "") ?: ""

            if (syncWall <= 0L || syncElapsed <= 0L || storedHmac.isBlank()) {
                if (passExpiry > 0L || trialExpiry > 0L || removeAdsExpiry > 0L) {
                    Log.w(TAG, "Licensing cache missing integrity metadata; wiping cache.")
                    clearLicensingCache(context)
                }
                return CachedLicensingData(null, 0L, 0L, 0L, false, 0L)
            }

            val expectedHmac = computeLicenseCacheHmac(
                context, passTierId, passExpiry, trialClaimed, trialExpiry, trialUsed,
                syncWall, syncElapsed, serverTs, removeAdsExpiry
            )
            if (expectedHmac.isBlank() ||
                !MessageDigest.isEqual(storedHmac.toByteArray(Charsets.UTF_8), expectedHmac.toByteArray(Charsets.UTF_8))
            ) {
                Log.w(TAG, "Licensing cache HMAC mismatch! Tampering detected; wiping cache.")
                clearLicensingCache(context)
                return CachedLicensingData(null, 0L, 0L, 0L, false, 0L)
            }

            val passTier = if (passTierId.isNotBlank()) PassTier.fromId(passTierId) else null
            CachedLicensingData(passTier, passExpiry, trialClaimed, trialExpiry, trialUsed, removeAdsExpiry)
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading licensing cache: ${e.message}")
            CachedLicensingData(null, 0L, 0L, 0L, false, 0L)
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
        serverRemoveAdsExpiry = 0L
        _removeAdsExpiryMs.value = 0L
        serverTrialClaimed = 0L
        serverTrialExpiry = 0L
        serverTrialUsed = false
        serverTimeBaselineMs = 0L
        serverTimeBaselineElapsedMs = 0L
        hasLoadedLicensing = false
        hasLoadedTrial = false
        lastSuccessfulServerSyncElapsedMs = 0L
        lastActionCompletedAtMs = 0L
        actionInFlight.set(false)
        backgroundProfileUpdateInFlight.set(false)
        cancelDeferredProfileUpdateJob()
        profileUpdateDispatchedHash = null
        activeUserDocUid = null
        _accessStatus.value = AccessStatus.Loading
        _activePassInfo.value = null
        _trialInfo.value = null
        _unlockedFeatures.value = emptyMap()
        _lockedFeatures.value = emptySet()
        PassPricingConfig.resetDefaultsForTesting()
        FeatureIds.resetRatesForTesting()
    }

    fun setLastServerSyncForTesting(elapsedMs: Long, wallClockMs: Long? = null) {
        lastSuccessfulServerSyncElapsedMs = elapsedMs
        appContext?.let { ctx ->
            val prefs = ctx.getSharedPreferences(LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
            val passTierId = prefs.getString(KEY_CACHED_PASS_TIER, "") ?: ""
            val passExpiry = prefs.getLong(KEY_CACHED_PASS_EXPIRY, 0L)
            val removeAdsExpiry = prefs.getLong(KEY_CACHED_REMOVE_ADS_EXPIRY, 0L)
            val trialClaimed = prefs.getLong(KEY_CACHED_TRIAL_CLAIMED, 0L)
            val trialExpiry = prefs.getLong(KEY_CACHED_TRIAL_EXPIRY, 0L)
            val trialUsed = prefs.getBoolean(KEY_CACHED_TRIAL_USED, false)
            val wallMs = wallClockMs ?: prefs.getLong(KEY_LAST_SERVER_SYNC_TIME, System.currentTimeMillis())
            val hmac = computeLicenseCacheHmac(
                ctx, passTierId, passExpiry, trialClaimed, trialExpiry, trialUsed,
                wallMs, elapsedMs, wallMs, removeAdsExpiry
            )
            prefs.edit()
                .putLong(KEY_LAST_SERVER_SYNC_TIME, wallMs)
                .putLong(KEY_LAST_SERVER_SYNC_ELAPSED, elapsedMs)
                .putLong(KEY_LAST_SERVER_TIMESTAMP, wallMs)
                .putString(KEY_CACHE_HMAC, hmac)
                .commit()
        }
    }

    /**
     * Synchronizes authoritative UTC server time baseline via TLS HTTPS Date header
     * (immune to phone date/time tampering). Never trusts System.currentTimeMillis()
     * in production builds.
     */
    suspend fun fetchServerTimeBaseline() = withContext(Dispatchers.IO) {
        var serverDateMs = 0L
        var conn: HttpsURLConnection? = null
        try {
            val url = URL("https://clients3.google.com/generate_204")
            conn = (url.openConnection() as? HttpsURLConnection)?.apply {
                requestMethod = "HEAD"
                connectTimeout = 3500
                readTimeout = 3500
                useCaches = false
                connect()
            }
            val headerDate = conn?.date ?: 0L
            if (headerDate > 1_700_000_000_000L) {
                serverDateMs = headerDate
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTPS authoritative clock fetch skipped/failed: ${e.message}")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }

        if (serverDateMs > 0L) {
            recordServerTimeSync(serverDateMs)
        } else if (isUnitTestEnvironment()) {
            recordServerTimeSync(System.currentTimeMillis())
        }
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
            performTimestampIntegrityCrossCheckSuspend(ctx)
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
            activeUserDocUid = null
            GlobalAdminManager.detachGlobalServiceListener()
            try {
                mainHandler.removeCallbacks(periodicTimestampCrossCheckRunnable)
            } catch (_: Exception) {}
            cancelDeferredProfileUpdateJob()
        }
    }

    /**
     * Checks whether subscription pass or trial access is currently active and granted.
     * Evaluates strictly against reactive cloud-synced AccessStatus StateFlow.
     * Returns true ONLY if status is PassActive or TrialActive with valid remaining time.
     */
    val isPassActive: Boolean
        get() = when (val s = _accessStatus.value) {
            is AccessStatus.PassActive -> s.passTier.isProPass && s.remainingTimeMs > 0
            else -> false
        }

    val isTrialActive: Boolean
        get() = when (val s = _accessStatus.value) {
            is AccessStatus.TrialActive -> s.remainingTimeMs > 0
            else -> false
        }

    fun isProMode(): Boolean = isPassActive || isTrialActive

    fun isAccessGranted(): Boolean {
        if (DeviceUtils.isRuntimeHookOrDebuggerDetected(appContext)) {
            Log.w(TAG, "isAccessGranted blocked: Runtime hooking or debugger tamper detected.")
            return false
        }
        if (isClockTamperingDetected(appContext)) {
            Log.w(TAG, "isAccessGranted blocked: Clock tampering detected.")
            return false
        }
        return isProMode()
    }

    /**
     * Synchronizes user licensing when Gmail account login changes in a thread-safe manner.
     */
    fun onUserAuthChanged(userEmail: String?, userUid: String?) {
        scope.launch {
            val ctx = appContext ?: return@launch
            val customUserId = resolveUserKey(ctx)
            val isSignedOut = userUid.isNullOrBlank()
            val alreadyAttached = synchronized(authLock) {
                if (!isSignedOut && activeUserDocUid == customUserId && userDocListener != null && currentUserKey == customUserId) {
                    true
                } else {
                    if (isSignedOut) {
                        userDocListener?.remove()
                        userDocListener = null
                    }
                    currentUserKey = customUserId
                    activeUserDocUid = null
                    hasLoadedLicensing = false
                    clearUserSessionStateLocked()
                    _accessStatus.value = AccessStatus.Loading
                    false
                }
            }
            if (isSignedOut) {
                clearLicensingCache(ctx)
                hasLoadedTrial = false
            }
            onEntitlementsChanged?.invoke()
            if (!alreadyAttached) {
                profileUpdateDispatchedHash = null
            }
            syncHardwareTrialAndLicensing()
        }
    }

    @VisibleForTesting
    internal val PENDING_ACTION_ALLOWED_TYPES: Set<String> = setOf(
        "INIT_PROFILE",
        "UPDATE_PROFILE",
        "MIGRATE_PROFILE",
        "CLAIM_REFERRAL",
        "PURCHASE_PASS",
        "ACTIVATE_PASS_PAYMENT",
        "UNLOCK_FEATURE",
        "UNLOCK_MULTIPLE_FEATURES",
        "CLAIM_TRIAL",
        "REWARD_AD_WATCHED"
    )

    @VisibleForTesting
    internal val PENDING_ACTION_ALLOWED_KEYS: Set<String> = setOf(
        "uid",
        "userEmail", "displayName", "photoUrl",
        "actionType", "status",
        "createdAt",
        "appVersion", "versionCode", "appSignatureSha256", "deviceIntegrityOk",
        "linkedHardwareId",
        "referralCode",
        "passTier",
        "utr",
        "amount",
        "featureId", "featureIds", "hours",
        "isFloatingOverlayEnabled", "isVoiceAnnouncerEnabled", "isAutoClickEnabled",
        "isMinFareEnabled", "minFare", "isMaxFareEnabled", "maxFare",
        "isMaxPickupDistanceEnabled", "maxPickupDistance",
        "isMaxDropDistanceEnabled", "maxDropDistance",
        "isMinPerKmRateEnabled", "minPerKmRate", "clickDelayMs",
        "isDestinationBlacklistEnabled", "blacklistedDestinations",
        "currentFolder"
    )

    @VisibleForTesting
    internal val FORBIDDEN_LEGACY_PAYLOAD_KEYS = setOf(
        "firebaseUid",
        "legacyCustomUserId",
        "email",
        "hardwareId",
        "deviceId",
        "blockedLocations",
        "points",
        "userId",
        "payload",
        "data",
        "isAutoAcceptEnabled",
        "autoAcceptEnabled"
    )

    @VisibleForTesting
    internal fun buildActionQueuePayload(
        firebaseUid: String,
        userEmail: String,
        hardwareId: String,
        deviceIntegrityOk: Boolean,
        appSignatureSha256: String,
        actionType: String,
        payload: Map<String, Any>,
        createdAtValue: Any = FieldValue.serverTimestamp()
    ): Map<String, Any> {
        val actionData = hashMapOf<String, Any>()
        val isPassTierAction = actionType.equals("PURCHASE_PASS", ignoreCase = true) ||
            actionType.equals("ACTIVATE_PASS_PAYMENT", ignoreCase = true)
        for ((k, v) in payload) {
            if (k in FORBIDDEN_LEGACY_PAYLOAD_KEYS) continue
            if (k !in PENDING_ACTION_ALLOWED_KEYS) continue
            if (actionType == "REWARD_AD_WATCHED" && k == "amount") continue
            if (isPassTierAction && k == "passTier" && v is String) {
                actionData[k] = PassTier.fromId(v)?.canonicalId ?: v
                continue
            }
            actionData[k] = v
        }
        actionData["uid"] = firebaseUid
        actionData["userEmail"] = userEmail
        actionData["linkedHardwareId"] = hardwareId
        actionData["deviceIntegrityOk"] = deviceIntegrityOk
        actionData["appSignatureSha256"] = DeviceUtils.normalizeSha256(appSignatureSha256)
        actionData["actionType"] = actionType
        actionData["status"] = "PENDING"
        actionData["createdAt"] = createdAtValue
        return actionData
    }

    private fun extractServerTimestampMs(obj: Any?): Long? = when (obj) {
        is Timestamp -> obj.toDate().time
        is java.util.Date -> obj.time
        is Number -> obj.toLong()
        else -> null
    }

    @VisibleForTesting
    internal sealed class ActionQueueOutcome {
        data object Pending : ActionQueueOutcome()
        data object Completed : ActionQueueOutcome()
        data class Failed(val errorMessage: String) : ActionQueueOutcome()
    }

    @VisibleForTesting
    internal fun formatActionQueueErrorMessage(
        actionType: String,
        rawError: String?,
        errorCode: String? = null
    ): String {
        val combined = listOfNotNull(rawError?.trim(), errorCode?.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (combined.contains("UNTRUSTED_APP_SIGNATURE", ignoreCase = true)) {
            return "App signature verification failed. Please install the official Order Master APK release."
        }
        if (combined.contains("SECURITY_CONFIG_MISSING_APP_SHA256", ignoreCase = true)) {
            return "Server app signature verification is not configured yet. Please contact the Admin to complete security setup."
        }
        if (combined.contains("PAYMENT_NOT_RECEIVED_YET", ignoreCase = true)) {
            return "UTR submitted. Waiting for bank SMS confirmation — your pass will activate automatically once received."
        }
        if (combined.contains("UTR_ALREADY_USED", ignoreCase = true)) {
            return "UTR already claimed by another user."
        }
        if (combined.contains("AMOUNT_MISMATCH", ignoreCase = true)) {
            return "Payment amount does not match the selected Pass plan. Please select the exact plan you paid for and try again."
        }
        if (combined.contains("INVALID_PASS_TIER", ignoreCase = true)) {
            return "Invalid or missing pass plan selected. Please select Daily, Weekly, Monthly, or Remove Ads and try again."
        }
        if (!rawError.isNullOrBlank()) return rawError.trim()
        if (!errorCode.isNullOrBlank()) return "Action rejected by server ($errorCode)."
        return "Action $actionType failed on server."
    }

    @VisibleForTesting
    internal fun evaluateActionQueueSnapshot(
        actionType: String,
        status: String?,
        errorMessage: String? = null,
        error: String? = null,
        reason: String? = null,
        errorCode: String? = null,
        nowElapsed: Long = safeElapsedRealtime(),
        recordCooldownOnTerminal: Boolean = true
    ): ActionQueueOutcome {
        val cleanStatus = status?.trim()?.uppercase(Locale.ROOT) ?: "PENDING"
        val shouldRecordCooldown = recordCooldownOnTerminal && !isSilentBackgroundAction(actionType)
        if (cleanStatus == "COMPLETED") {
            if (shouldRecordCooldown) {
                recordActionTerminalCompletion(nowElapsed, actionType)
            }
            return ActionQueueOutcome.Completed
        }
        if (cleanStatus == "FAILED" || cleanStatus == "REJECTED") {
            if (shouldRecordCooldown) {
                recordActionTerminalCompletion(nowElapsed, actionType)
            }
            val rawError = errorMessage?.takeIf { it.isNotBlank() }
                ?: error?.takeIf { it.isNotBlank() }
                ?: reason?.takeIf { it.isNotBlank() }
            return ActionQueueOutcome.Failed(
                formatActionQueueErrorMessage(actionType, rawError, errorCode)
            )
        }
        return ActionQueueOutcome.Pending
    }

    @VisibleForTesting
    internal fun selectResolvedUserDocumentPath(
        uid: String,
        canonicalExists: Boolean,
        fallbackDocIds: List<String>
    ): String? {
        if (canonicalExists && uid.isNotBlank()) return uid
        return fallbackDocIds.firstOrNull { it.isNotBlank() }
    }

    /**
     * Resolves a user document by canonical `users/{uid}` first, falling back to a single
     * `whereEqualTo("firebaseUid", uid)` query only if the canonical document does not exist.
     */
    internal suspend fun resolveUserDocumentSnapshot(
        db: FirebaseFirestore,
        uid: String
    ): DocumentSnapshot? {
        if (uid.isBlank()) return null
        val canonicalSnap = try {
            db.collection(COLL_USERS).document(uid).get().await()
        } catch (_: Exception) {
            null
        }
        if (canonicalSnap != null && canonicalSnap.exists()) {
            return canonicalSnap
        }
        val fallbackSnap = try {
            db.collection(COLL_USERS)
                .whereEqualTo("uid", uid)
                .limit(1)
                .get()
                .await()
                ?.documents
                ?.firstOrNull()
        } catch (_: Exception) {
            null
        }
        return fallbackSnap?.takeIf { it.exists() } ?: canonicalSnap
    }

    /**
     * Dispatches an action request to the Zero-Trust `pending_actions` queue and suspends
     * until the background Admin Server processes the action, marking it COMPLETED, FAILED, or REJECTED.
     * Enforces strict timeout of 60 seconds with automatic listener cleanup.
     * Does NOT perform direct writes/transactions on `users` or `received_payments`.
     */
    suspend fun executeActionQueueRequest(
        actionType: String,
        payload: Map<String, Any>
    ): Result<DocumentSnapshot> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext Result.failure(IllegalStateException("LicenseManager not initialized"))
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Firebase service is not initialized."))
        }

        val currentUser = FirebaseAuth.getInstance().currentUser
        if (currentUser == null || currentUser.uid.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Please sign in with Google to perform this action."))
        }

        if (actionType !in PENDING_ACTION_ALLOWED_TYPES) {
            return@withContext Result.failure(IllegalArgumentException("Unsupported actionType: $actionType"))
        }

        val effectivePayload = if (actionType.equals("PURCHASE_PASS", ignoreCase = true) ||
            actionType.equals("ACTIVATE_PASS_PAYMENT", ignoreCase = true)
        ) {
            val rawTier = payload["passTier"] as? String
            val resolvedTier = PassTier.fromId(rawTier)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Please select a valid pass plan (Daily, Weekly, Monthly, or Remove Ads).")
                )
            val canonicalTier = resolvedTier.canonicalId
            if (canonicalTier !in PassTier.VALID_CANONICAL_PASS_TIERS) {
                return@withContext Result.failure(
                    IllegalArgumentException("Please select a valid pass plan (Daily, Weekly, Monthly, or Remove Ads).")
                )
            }
            payload.toMutableMap().apply { put("passTier", canonicalTier) }
        } else {
            payload
        }

        val hwId = HardwareIdManager.getPermanentHardwareId(ctx)
        val db = FirebaseFirestore.getInstance()

        val actionData = buildActionQueuePayload(
            firebaseUid = currentUser.uid,
            userEmail = currentUser.email ?: "",
            hardwareId = hwId,
            deviceIntegrityOk = DeviceUtils.isFreeTrialAllowed(ctx),
            appSignatureSha256 = DeviceUtils.getApkSignatureSha256(ctx),
            actionType = actionType,
            payload = effectivePayload
        )

        val isSilentProfileSync = isSilentBackgroundAction(actionType)
        val canSubmit = actionDispatchMutex.withLock {
            if (isSilentProfileSync) {
                val gate = checkActionSubmissionAllowed(actionType = actionType)
                if (gate.isFailure) {
                    gate
                } else {
                    backgroundProfileUpdateInFlight.set(true)
                    Result.success(Unit)
                }
            } else {
                val gate = checkActionSubmissionAllowed(actionType = actionType)
                if (gate.isFailure) {
                    gate
                } else {
                    actionInFlight.set(true)
                    Result.success(Unit)
                }
            }
        }
        if (canSubmit.isFailure) {
            return@withContext Result.failure(
                canSubmit.exceptionOrNull() ?: IllegalStateException(
                    if (isSilentProfileSync) SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE
                    else ACTION_COOLDOWN_FRIENDLY_MESSAGE
                )
            )
        }

        try {
            val docRef = try {
                db.collection(COLL_PENDING_ACTIONS).add(actionData).await()
            } catch (e: FirebaseFirestoreException) {
                if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    if (isSilentProfileSync) {
                        Log.w(TAG, "Silent UPDATE_PROFILE hit PERMISSION_DENIED on add; leaving user action cooldown untouched.")
                        return@withContext Result.failure(
                            IllegalStateException(SILENT_PROFILE_UPDATE_DEFERRED_MESSAGE, e)
                        )
                    }
                    Log.w(TAG, "Action $actionType hit PERMISSION_DENIED on add; syncing lastActionTime and enforcing local cooldown.")
                    try {
                        val userSnap = resolveUserDocumentSnapshot(db, currentUser.uid)
                        if (userSnap != null && userSnap.exists()) {
                            val lastActionMs = extractServerTimestampMs(userSnap.get("lastActionTime"))
                            updateLastActionCooldownFromServerTimestamp(lastActionMs)
                        }
                    } catch (syncErr: Exception) {
                        Log.w(TAG, "Failed syncing lastActionTime after PERMISSION_DENIED: ${syncErr.message}")
                    }
                    if (computeActionCooldownRemainingMs() == 0L) {
                        recordActionTerminalCompletion(actionType = actionType)
                    }
                    return@withContext Result.failure(
                        IllegalStateException(ACTION_COOLDOWN_FRIENDLY_MESSAGE, e)
                    )
                } else {
                    Log.e(TAG, "Failed to submit action $actionType to $COLL_PENDING_ACTIONS: ${e.message}", e)
                    return@withContext Result.failure(e)
                }
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
                                snapshot.getTimestamp("createdAt")?.toDate()?.time?.let { serverActionTs ->
                                    if (serverTimeBaselineMs == 0L) {
                                        recordServerTimeSync(serverActionTs)
                                    }
                                }
                                when (val snapOutcome = evaluateActionQueueSnapshot(
                                    actionType = actionType,
                                    status = snapshot.getString("status"),
                                    errorMessage = snapshot.getString("errorMessage"),
                                    errorCode = snapshot.getString("errorCode")
                                )) {
                                    ActionQueueOutcome.Completed -> {
                                        if (!isSilentProfileSync) {
                                            updateLastActionCooldownFromServerTimestamp(
                                                extractServerTimestampMs(snapshot.get("processedAt"))
                                            )
                                        }
                                        Log.i(TAG, "Action $actionType (doc ${docRef.id}) marked COMPLETED by Admin server.")
                                        if (isResumed.compareAndSet(false, true)) {
                                            listenerRegistration?.remove()
                                            continuation.resume(Result.success(snapshot))
                                        }
                                    }
                                    is ActionQueueOutcome.Failed -> {
                                        if (!isSilentProfileSync) {
                                            updateLastActionCooldownFromServerTimestamp(
                                                extractServerTimestampMs(snapshot.get("processedAt"))
                                            )
                                        }
                                        Log.w(TAG, "Action $actionType (doc ${docRef.id}) marked FAILED/REJECTED: ${snapOutcome.errorMessage}")
                                        if (isResumed.compareAndSet(false, true)) {
                                            listenerRegistration?.remove()
                                            continuation.resume(Result.failure(IllegalStateException(snapOutcome.errorMessage)))
                                        }
                                    }
                                    ActionQueueOutcome.Pending -> Unit
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
                Log.w(TAG, "Action $actionType (doc ${docRef.id}) timed out after ${ACTION_TIMEOUT_MS}ms. Checking final doc status before fallback.")
                isResumed.set(true)
                listenerRegistration?.remove()

                val finalSnap = try {
                    docRef.get().await()
                } catch (e: Exception) {
                    Log.w(TAG, "Post-timeout final doc check failed for ${docRef.id}: ${e.message}")
                    null
                }
                if (finalSnap != null && finalSnap.exists()) {
                    when (val finalOutcome = evaluateActionQueueSnapshot(
                        actionType = actionType,
                        status = finalSnap.getString("status"),
                        errorMessage = finalSnap.getString("errorMessage"),
                        errorCode = finalSnap.getString("errorCode")
                    )) {
                        ActionQueueOutcome.Completed -> {
                            if (!isSilentProfileSync) {
                                updateLastActionCooldownFromServerTimestamp(
                                    extractServerTimestampMs(finalSnap.get("processedAt"))
                                )
                            }
                            Log.i(TAG, "Action $actionType (doc ${docRef.id}) confirmed COMPLETED on post-timeout check.")
                            return@withContext Result.success(finalSnap)
                        }
                        is ActionQueueOutcome.Failed -> {
                            if (!isSilentProfileSync) {
                                updateLastActionCooldownFromServerTimestamp(
                                    extractServerTimestampMs(finalSnap.get("processedAt"))
                                )
                            }
                            return@withContext Result.failure(IllegalStateException(finalOutcome.errorMessage))
                        }
                        ActionQueueOutcome.Pending -> Unit
                    }
                }

                try {
                    refreshUserPassStatusFromServer(ctx)
                } catch (e: Exception) {
                    Log.w(TAG, "Timeout recovery refresh failed: ${e.message}")
                }
                return@withContext Result.failure(
                    TimeoutException("Server is taking longer than usual. Your status will sync automatically in a moment.")
                )
            }

            outcome
        } finally {
            if (isSilentProfileSync) {
                backgroundProfileUpdateInFlight.set(false)
            } else {
                actionInFlight.set(false)
            }
        }
    }

    /**
     * Claims a referral code via Zero-Trust Action Queue (`CLAIM_REFERRAL`).
     * Admin Server validates eligibility, prevents self/duplicate referrals, and awards
     * points atomically on both referee and referrer documents.
     */
    suspend fun claimReferralViaActionQueue(referralCode: String): Result<Int> = withContext(Dispatchers.IO) {
        val cleanCode = referralCode.trim().uppercase(Locale.ROOT)
        if (!cleanCode.matches(Regex("^[A-Z0-9]{8}$"))) {
            return@withContext Result.failure(
                IllegalArgumentException("Referral code must be exactly 8 alphanumeric characters.")
            )
        }
        val ownCode = _referralCode.value?.trim()?.uppercase(Locale.ROOT)
        if (!ownCode.isNullOrBlank() && cleanCode == ownCode) {
            return@withContext Result.failure(
                IllegalArgumentException("You cannot claim your own referral code.")
            )
        }
        val result = executeActionQueueRequest(
            actionType = "CLAIM_REFERRAL",
            payload = mapOf("referralCode" to cleanCode)
        )
        result.fold(
            onSuccess = { snap ->
                var newBalance = (snap.get("newPointsBalance") as? Number)?.toInt()
                    ?: (snap.get("pointsBalance") as? Number)?.toInt()
                if (newBalance == null) {
                    appContext?.let { refreshUserPassStatusFromServer(it) }
                    newBalance = _pointsBalance.value
                }
                notifyReferralClaimed(newBalance)
                Result.success(newBalance)
            },
            onFailure = { err ->
                Result.failure(err)
            }
        )
    }

    /**
     * Securely claims points for watching a Rewarded Ad via the Zero-Trust Action Queue.
     * Enqueues REWARD_AD_WATCHED request to `pending_actions` with empty payload and awaits server completion.
     */
    suspend fun claimAdReward(): Result<Int> = withContext(Dispatchers.IO) {
        val result = executeActionQueueRequest(
            actionType = "REWARD_AD_WATCHED",
            payload = emptyMap()
        )

        result.fold(
            onSuccess = { snap ->
                var newBalance = (snap.get("newPointsBalance") as? Number)?.toInt()
                    ?: (snap.get("pointsBalance") as? Number)?.toInt()
                if (newBalance == null) {
                    appContext?.let { refreshUserPassStatusFromServer(it) }
                    newBalance = _pointsBalance.value
                } else {
                    _pointsBalance.value = newBalance
                }
                Log.i(TAG, "REWARD_AD_WATCHED action completed via Action Queue. New balance: $newBalance")
                Result.success(newBalance)
            },
            onFailure = { err ->
                Log.e(TAG, "REWARD_AD_WATCHED action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    @VisibleForTesting
    internal fun buildPurchasePassPayload(passTier: PassTier): Map<String, Any> {
        val canonical = passTier.canonicalId.trim().lowercase(Locale.ROOT)
        require(canonical in PassTier.VALID_CANONICAL_PASS_TIERS && canonical != "none") {
            "Invalid canonical passTier '$canonical' for PURCHASE_PASS"
        }
        return mapOf("passTier" to canonical)
    }

    @VisibleForTesting
    internal fun buildActivatePassPaymentPayload(passTier: PassTier, cleanUtr: String): Map<String, Any> {
        val canonical = passTier.canonicalId.trim().lowercase(Locale.ROOT)
        require(canonical in PassTier.VALID_CANONICAL_PASS_TIERS && canonical != "none") {
            "Invalid canonical passTier '$canonical' for ACTIVATE_PASS_PAYMENT"
        }
        return mapOf(
            "passTier" to canonical,
            "utr" to cleanUtr
        )
    }

    /**
     * Purchases a Pass using the user's Points balance via Zero-Trust Action Queue.
     * Enqueues PURCHASE_PASS request to `pending_actions` with only `passTier` and awaits server completion.
     */
    suspend fun purchasePassWithPoints(passTier: PassTier): Result<Unit> = withContext(Dispatchers.IO) {
        val currentBalance = _pointsBalance.value
        val requiredPoints = passTier.effectivePointsCost()
        if (currentBalance < requiredPoints) {
            val shortfall = requiredPoints - currentBalance
            return@withContext Result.failure(
                IllegalStateException("Insufficient Points. You have $currentBalance pts, but ${passTier.title} requires $requiredPoints pts (Need $shortfall more pts).")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "PURCHASE_PASS",
            payload = buildPurchasePassPayload(passTier)
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
     * Enqueues ACTIVATE_PASS_PAYMENT request to `pending_actions` with only `passTier` and `utr` and awaits server completion.
     */
    suspend fun activatePassViaPayment(
        passTier: PassTier,
        transactionId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val cleanUtr = (transactionId ?: "").trim()
        if (!cleanUtr.matches(Regex("^\\d{12}$"))) {
            return@withContext Result.failure(
                IllegalArgumentException("Invalid UTR: '$cleanUtr'. Must be a valid 12-digit numeric reference.")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "ACTIVATE_PASS_PAYMENT",
            payload = buildActivatePassPaymentPayload(passTier, cleanUtr)
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
     * Synchronizes device binding fields (`linkedHardwareId`, `firebaseUid`, `userEmail`) on the
     * canonical `users/{uid}` document via `UPDATE_PROFILE` when needed.
     * Cross-user/legacy migration (`legacyCustomUserId` / `linkedHardwareId` matching) is executed
     * strictly server-side inside `INIT_PROFILE` so clients never query other user documents.
     */
    /**
     * Performs cloud sync for trial and user licensing directly with Firestore in the single "users" collection.
     * Relies on the single real-time snapshot listener in syncUserLicensingDocument to avoid duplicate .get() reads.
     */
    private suspend fun syncHardwareTrialAndLicensing() {
        val ctx = appContext ?: return

        if (!DeviceUtils.ensureFirebaseInitialized(ctx)) {
            Log.w(TAG, "Default FirebaseApp is not initialized in this process; skipping Firestore cloud sync.")
            refreshAccessStatus()
            return
        }

        syncMutex.withLock {
            startGlobalSettingsListener()

            // Authoritative server clock synchronization
            fetchServerTimeBaseline()

            // 1. Identify current authenticated user
            val currentUser = try {
                FirebaseAuth.getInstance().currentUser
            } catch (_: Exception) {
                null
            }
            if (currentUser == null || currentUser.uid.isBlank()) {
                Log.d(TAG, "No signed-in FirebaseAuth user; skipping users/{uid} cloud sync until login.")
                clearUserSessionStateLocked()
                hasLoadedTrial = true
                hasLoadedLicensing = true
                refreshAccessStatus()
                return
            }

            val customUserId = currentUser.uid
            currentUserKey = customUserId

            // 2. Attach/ensure real-time snapshot listener on users/{customUserId} (single authoritative read stream)
            try {
                syncUserLicensingDocument(customUserId, currentUser.email)
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync user licensing: ${e.message}")
                hasLoadedTrial = true
                hasLoadedLicensing = true
                refreshAccessStatus()
            }
        }
    }

    /**
     * Listens in real-time to the user document in Firestore "users" collection.
     * Updates in-memory StateFlows directly from snapshot. Zero local storage.
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

        val currentUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) {
            null
        }
        if (currentUser == null || currentUser.uid.isBlank()) {
            synchronized(authLock) {
                userDocListener?.remove()
                userDocListener = null
                activeUserDocUid = null
            }
            clearUserSessionStateLocked()
            hasLoadedTrial = true
            hasLoadedLicensing = true
            refreshAccessStatus()
            return
        }

        val customUserId = currentUser.uid

        synchronized(authLock) {
            if (userDocListener != null && activeUserDocUid == customUserId) {
                return
            }
            userDocListener?.remove()
            userDocListener = null
            activeUserDocUid = customUserId
        }

        try {
            val db = FirebaseFirestore.getInstance()
            val userDocRef = db.collection(COLL_USERS).document(customUserId)
            val fallbackQueryAttempted = AtomicBoolean(false)

            fun handleExistingUserSnapshot(snapshot: DocumentSnapshot) {
                val parsed = parseUserProfileSnapshotMap(snapshot.data)
                updateLastActionCooldownFromServerTimestamp(parsed.lastActionTimeMs)

                val points = parsed.points
                val passType = parsed.activePassType
                val passExpiry = parsed.passExpiryTimestamp
                val removeAdsExpiry = parsed.removeAdsExpiryTimestamp
                val code = parsed.referralCode
                val hasClaimed = parsed.hasClaimedReferral ?: false
                val refCount = parsed.referralCount ?: 0
                val trialClaimed = parsed.trialClaimed ?: 0L
                val trialExpiry = parsed.trialExpiry ?: 0L
                val isTrialUsed = parsed.trialUsed ?: (trialClaimed > 0L)
                val serverFolder = parsed.currentFolder

                // Always sync trial and licensing state unconditionally for real-time Firestore updates
                serverTrialClaimed = trialClaimed
                serverTrialExpiry = trialExpiry
                serverTrialUsed = isTrialUsed
                hasLoadedTrial = true

                // Update in-memory reactive state directly from Firestore snapshot
                _pointsBalance.value = points
                _hasClaimedReferral.value = hasClaimed
                _referralCount.value = refCount
                val parsedTier = PassTier.fromId(passType)
                serverPassTier = parsedTier?.takeIf { it.isProPass }
                serverPassExpiry = if (parsedTier?.isProPass == true) passExpiry else 0L
                serverRemoveAdsExpiry = maxOf(
                    removeAdsExpiry,
                    if (parsedTier == PassTier.REMOVE_ADS) passExpiry else 0L
                )
                val currentTimeNow = getCurrentServerTimeMs()
                _removeAdsExpiryMs.value = if (serverRemoveAdsExpiry > currentTimeNow) serverRemoveAdsExpiry else 0L

                // Save authoritative data to local SharedPreferences cache
                ctx?.let { c ->
                    saveAuthoritativeLicensingCache(
                        context = c,
                        passTier = serverPassTier,
                        passExpiry = serverPassExpiry,
                        trialClaimed = serverTrialClaimed,
                        trialExpiry = serverTrialExpiry,
                        trialUsed = serverTrialUsed,
                        removeAdsExpiry = serverRemoveAdsExpiry
                    )
                }

                val expectedFolder = computeCurrentFolder(
                    passTier = serverPassTier,
                    passExpiry = serverPassExpiry,
                    trialClaimed = serverTrialClaimed,
                    trialExpiry = serverTrialExpiry
                ).ifBlank { serverFolder ?: "free_mod" }
                _currentFolder.value = expectedFolder

                applyParsedUnlockedAndLockedFeatures(
                    parsed = parsed,
                    now = currentTimeNow,
                    passActive = parsedTier?.isProPass == true && passExpiry > currentTimeNow,
                    trialActive = trialClaimed > 0L && trialExpiry > currentTimeNow,
                    trialUsed = isTrialUsed,
                    trialExpiry = trialExpiry,
                    passExpiry = if (parsedTier?.isProPass == true) passExpiry else 0L
                )

                // Read canonical `isBanned` + `banUntil` (with legacy `banUntilTimestamp` fallback) and sync to GlobalAdminManager
                val currentNowMs = getCurrentServerTimeMs()
                val effectiveBanUntilMs = resolveEffectiveBanUntilMs(parsed, currentNowMs)
                val banReason = parsed.banReason

                if (effectiveBanUntilMs != null && effectiveBanUntilMs > currentNowMs) {
                    _isBannedFlow.value = true
                    _banUntilTimestampFlow.value = effectiveBanUntilMs
                    _banReasonFlow.value = banReason
                    Log.w(TAG, "User $customUserId is BANNED until $effectiveBanUntilMs (now=$currentNowMs). Reason: $banReason")
                } else {
                    _isBannedFlow.value = false
                    _banUntilTimestampFlow.value = null
                    _banReasonFlow.value = null
                }
                GlobalAdminManager.syncBanStateFromLicenseManager(effectiveBanUntilMs, banReason)

                hasLoadedLicensing = true

                val currentHwId = ctx?.let { HardwareIdManager.getPermanentHardwareId(it) } ?: ""
                // Check canonical Schema v2 keys strictly
                val existingUid = snapshot.getString("uid")
                val existingLinkedHw = snapshot.getString("linkedHardwareId")
                val existingEmail = snapshot.getString("userEmail")
                val existingAutoClick = snapshot.getBoolean("isAutoClickEnabled")
                val authEmail = try {
                    FirebaseAuth.getInstance().currentUser?.email.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                val updatesNeeded = computeNeededProfileUpdates(
                    snapshotFirebaseUid = existingUid,
                    snapshotHardwareId = existingLinkedHw,
                    snapshotEmail = existingEmail,
                    expectedFirebaseUid = customUserId,
                    currentHardwareId = currentHwId,
                    authEmail = authEmail,
                    snapshotAutoClickEnabled = existingAutoClick,
                    desiredAutoClickEnabled = null
                )
                _referralCode.value = code?.takeIf { it.isNotBlank() }

                // Only dispatch silent UPDATE_PROFILE when canonical profile fields differ from server doc,
                // deferring automatically whenever an explicit user action is in flight or inside its 13.5s cooldown.
                if (updatesNeeded.isNotEmpty()) {
                    dispatchSilentProfileUpdateWithPriority(customUserId, updatesNeeded)
                }

                refreshAccessStatus()
                Log.d(TAG, "Synced user from Firestore (${snapshot.id}, v${parsed.schemaVersion}): points=$points, passType=$passType, folder=$expectedFolder, code=${_referralCode.value}")
            }

            fun dispatchInitProfileOnce() {
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
                            val legacyCustomId = DeviceUtils.getSavedCustomUserIdOrNull(context)
                            val email = userEmail ?: (currentAuthUser?.email ?: "")

                            Log.i(TAG, "User document does not exist. Dispatching INIT_PROFILE to Action Queue for $customUserId...")
                            val initPayload = buildInitProfilePayload(
                                firebaseUid = customUserId,
                                userEmail = email,
                                hardwareId = permanentHardwareId,
                                legacyCustomId = legacyCustomId
                            )
                            val actionResult = executeActionQueueRequest(
                                actionType = "INIT_PROFILE",
                                payload = initPayload
                            )

                            if (actionResult.isSuccess) {
                                val actionDoc = actionResult.getOrNull()
                                val hasPointsInActionDoc = actionDoc?.contains("pointsBalance") == true
                                val parsedPoints = if (hasPointsInActionDoc) {
                                    (actionDoc?.get("pointsBalance") as? Number)?.toInt()
                                } else {
                                    null
                                }
                                val parsedReferral = actionDoc?.getString("referralCode")?.takeIf { it.isNotBlank() }
                                val hasTrialFieldsInActionDoc = actionDoc?.contains("trialClaimed") == true ||
                                    actionDoc?.contains("trialExpiry") == true
                                val serverTrialClaimedMs = extractServerTimestampMs(actionDoc?.get("trialClaimed")) ?: 0L
                                val serverTrialExpiryMs = extractServerTimestampMs(actionDoc?.get("trialExpiry")) ?: 0L
                                val trialUsedInActionDoc = actionDoc?.get("trialUsed") as? Boolean

                                applyInitProfileResolvedFields(
                                    context = context,
                                    hasPointsInActionDoc = hasPointsInActionDoc,
                                    parsedPoints = parsedPoints,
                                    parsedReferral = parsedReferral,
                                    hasTrialFieldsInActionDoc = hasTrialFieldsInActionDoc,
                                    serverTrialClaimedMs = serverTrialClaimedMs,
                                    serverTrialExpiryMs = serverTrialExpiryMs,
                                    trialUsedInActionDoc = trialUsedInActionDoc
                                )
                                if (!legacyCustomId.isNullOrBlank()) {
                                    DeviceUtils.clearSavedCustomUserId(context)
                                }
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

            val registration = userDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "User snapshot listener error: ${error.message}")
                    hasLoadedLicensing = true
                    refreshAccessStatus()
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    handleExistingUserSnapshot(snapshot)
                } else if (snapshot != null && !snapshot.exists()) {
                    if (fallbackQueryAttempted.compareAndSet(false, true)) {
                        scope.launch {
                            val fallbackDoc = try {
                                db.collection(COLL_USERS)
                                    .whereEqualTo("uid", customUserId)
                                    .limit(1)
                                    .get()
                                    .await()
                                    ?.documents
                                    ?.firstOrNull()
                            } catch (e: Exception) {
                                Log.w(TAG, "Fallback firebaseUid query skipped: ${e.message}")
                                null
                            }

                            if (fallbackDoc != null && fallbackDoc.exists() && fallbackDoc.id != customUserId) {
                                Log.i(TAG, "Resolved legacy user document ${fallbackDoc.id} via firebaseUid fallback for $customUserId")
                                val fallbackReg = fallbackDoc.reference.addSnapshotListener { fbSnap, fbErr ->
                                    if (fbErr != null) {
                                        Log.w(TAG, "Fallback user snapshot listener error: ${fbErr.message}")
                                        hasLoadedLicensing = true
                                        refreshAccessStatus()
                                        return@addSnapshotListener
                                    }
                                    if (fbSnap != null && fbSnap.exists()) {
                                        handleExistingUserSnapshot(fbSnap)
                                    } else {
                                        dispatchInitProfileOnce()
                                    }
                                }
                                synchronized(authLock) {
                                    if (activeUserDocUid == customUserId) {
                                        userDocListener?.remove()
                                        userDocListener = fallbackReg
                                    } else {
                                        fallbackReg.remove()
                                    }
                                }
                            } else {
                                dispatchInitProfileOnce()
                            }
                        }
                    } else {
                        dispatchInitProfileOnce()
                    }
                }
            }
            synchronized(authLock) {
                if (activeUserDocUid == customUserId) {
                    userDocListener = registration
                } else {
                    registration.remove()
                }
            }
        } catch (e: Exception) {
            synchronized(authLock) {
                if (activeUserDocUid == customUserId) {
                    activeUserDocUid = null
                }
            }
            Log.w(TAG, "Failed setting up licensing listener: ${e.message}")
            hasLoadedLicensing = true
            refreshAccessStatus()
        }
    }

    /**
     * Resolves the canonical Firestore Document ID for the active user:
     * Strictly uses FirebaseAuth `uid` whenever signed in to match `users/{uid}` security rules.
     * Falls back to `linkedHardwareId` (`HardwareIdManager.getPermanentHardwareId`) before sign-in
     * and never generates legacy `OM-USER-*` IDs.
     */
    fun resolveUserKey(context: Context? = null, email: String? = null, uid: String? = null): String {
        if (!uid.isNullOrBlank()) return uid
        val authUid = try {
            val targetCtx = context ?: appContext
            if (targetCtx == null || FirebaseApp.getApps(targetCtx).isNotEmpty()) {
                FirebaseAuth.getInstance().currentUser?.uid
            } else null
        } catch (_: Exception) {
            null
        }
        if (!authUid.isNullOrBlank()) return authUid

        val targetContext = context ?: appContext
        return if (targetContext != null) {
            HardwareIdManager.getPermanentHardwareId(targetContext)
        } else {
            ""
        }
    }

    /**
     * Recomputes the current `AccessStatus` strictly from in-memory server state.
     * Enforces anti-clock-tampering with monotonic elapsed time and 2-hour offline limit.
     * ZERO local storage reads.
     */
    fun refreshAccessStatus(context: Context? = null) {
        recomputeAccessStatus(context)
        onEntitlementsChanged?.invoke()
    }

    /**
     * Resolves canonical user points balance strictly from `pointsBalance` (defaulting to `0L`).
     */
    @VisibleForTesting
    internal fun resolvePointsBalanceFromMap(data: Map<String, Any?>?): Long {
        if (data == null) return 0L
        val raw = data["pointsBalance"] as? Number
        return raw?.toLong() ?: 0L
    }

    @VisibleForTesting
    internal data class ParsedUserProfileSnapshot(
        val schemaVersion: Int = 1,
        val firebaseUid: String? = null,
        val userEmail: String? = null,
        val linkedHardwareId: String? = null,
        val pointsBalance: Long = 0L,
        val points: Int = pointsBalance.toInt(),
        val hasPointsField: Boolean = false,
        val activePassType: String? = null,
        val passExpiryTimestamp: Long = 0L,
        val removeAdsExpiryTimestamp: Long = 0L,
        val unlockedFeatures: Map<String, Long> = emptyMap(),
        val lockedFeatures: Set<String> = emptySet(),
        val blacklistedDestinations: List<String>? = null,
        val isBanned: Boolean = false,
        val banUntilTimestamp: Long? = null,
        val banReason: String? = null,
        val referralCode: String? = null,
        val hasClaimedReferral: Boolean? = null,
        val referralCount: Int? = null,
        val trialClaimed: Long? = null,
        val trialExpiry: Long? = null,
        val trialUsed: Boolean? = null,
        val currentFolder: String? = null,
        val lastActionTimeMs: Long? = null
    )

    /**
     * Canonical Schema v2 `users/{uid}` parser aligned strictly with Admin App (`schemaVersion = 2`).
     * Reads strictly canonical fields with zero legacy alias fallbacks.
     */
    @VisibleForTesting
    internal fun parseUserProfileSnapshotMap(data: Map<String, Any?>?): ParsedUserProfileSnapshot {
        if (data == null) return ParsedUserProfileSnapshot()

        val schemaVersion = (data["schemaVersion"] as? Number)?.toInt() ?: 2
        val firebaseUid = (data["uid"] as? String)?.takeIf { it.isNotBlank() }
        val userEmail = (data["userEmail"] as? String)?.takeIf { it.isNotBlank() }
        val linkedHardwareId = (data["linkedHardwareId"] as? String)?.takeIf { it.isNotBlank() }

        val rawPoints = data["pointsBalance"] as? Number
        val pointsBalance = resolvePointsBalanceFromMap(data)
        val points = pointsBalance.toInt()

        val activePassType = data["activePassType"] as? String
        val passExpiryTimestamp = extractServerTimestampMs(data["passExpiryTimestamp"]) ?: 0L
        val removeAdsExpiryTimestamp = extractServerTimestampMs(data["removeAdsExpiryTimestamp"]) ?: 0L

        val rawUnlocked = data["unlockedFeatures"] as? Map<*, *>
        val unlockedFeatures = rawUnlocked?.mapNotNull { (k, v) ->
            val featureKey = k as? String ?: return@mapNotNull null
            val exp = when (v) {
                is Number -> v.toLong()
                is Timestamp -> v.toDate().time
                is java.util.Date -> v.time
                true -> -1L
                else -> return@mapNotNull null
            }
            featureKey to exp
        }?.toMap() ?: emptyMap()

        val rawLocked = data["lockedFeatures"]
        val lockedFeatures: Set<String> = when (rawLocked) {
            is List<*> -> rawLocked.mapNotNull { (it as? String)?.trim()?.lowercase(Locale.ROOT) }
                .filter { it.isNotEmpty() }.toSet()
            is Map<*, *> -> rawLocked.mapNotNull { (k, v) ->
                val key = (k as? String)?.trim()?.lowercase(Locale.ROOT) ?: return@mapNotNull null
                if (v == true) key else null
            }.toSet()
            else -> emptySet()
        }

        val blacklistedDestinations = if (data.containsKey("blacklistedDestinations")) {
            (data["blacklistedDestinations"] as? List<*>)
                ?.mapNotNull { (it as? String)?.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinctBy { it.lowercase(Locale.ROOT) }
                ?.sorted()
                ?: emptyList()
        } else {
            null
        }

        val isBanned = data["isBanned"] as? Boolean ?: false
        val banUntilTimestamp = extractServerTimestampMs(data["banUntil"])
        val banReason = (data["banReason"] as? String)?.takeIf { it.isNotBlank() }

        val referralCode = (data["referralCode"] as? String)?.takeIf { it.isNotBlank() }
        val hasClaimedReferral = data["hasClaimedReferral"] as? Boolean
        val referralCount = (data["referralCount"] as? Number)?.toInt()
        val trialClaimed = extractServerTimestampMs(data["trialClaimed"])
        val trialExpiry = extractServerTimestampMs(data["trialExpiry"])
        val trialUsed = data["trialUsed"] as? Boolean
        val currentFolder = (data["currentFolder"] as? String)?.takeIf { it.isNotBlank() }
        val lastActionTimeMs = extractServerTimestampMs(data["lastActionTime"])

        return ParsedUserProfileSnapshot(
            schemaVersion = schemaVersion,
            firebaseUid = firebaseUid,
            userEmail = userEmail,
            linkedHardwareId = linkedHardwareId,
            pointsBalance = pointsBalance,
            points = points,
            hasPointsField = rawPoints != null,
            activePassType = activePassType,
            passExpiryTimestamp = passExpiryTimestamp,
            removeAdsExpiryTimestamp = removeAdsExpiryTimestamp,
            unlockedFeatures = unlockedFeatures,
            lockedFeatures = lockedFeatures,
            blacklistedDestinations = blacklistedDestinations,
            isBanned = isBanned,
            banUntilTimestamp = banUntilTimestamp,
            banReason = banReason,
            referralCode = referralCode,
            hasClaimedReferral = hasClaimedReferral,
            referralCount = referralCount,
            trialClaimed = trialClaimed,
            trialExpiry = trialExpiry,
            trialUsed = trialUsed,
            currentFolder = currentFolder,
            lastActionTimeMs = lastActionTimeMs
        )
    }

    @VisibleForTesting
    internal fun resolveEffectiveBanUntilMs(
        parsed: ParsedUserProfileSnapshot,
        nowMs: Long
    ): Long? {
        val explicitBanUntil = parsed.banUntilTimestamp
        if (explicitBanUntil != null && explicitBanUntil > nowMs) {
            return explicitBanUntil
        }
        if (parsed.isBanned) {
            return Long.MAX_VALUE
        }
        return explicitBanUntil
    }

    private fun applyParsedUnlockedAndLockedFeatures(
        parsed: ParsedUserProfileSnapshot,
        now: Long,
        passActive: Boolean,
        trialActive: Boolean,
        trialUsed: Boolean,
        trialExpiry: Long,
        passExpiry: Long
    ) {
        _unlockedFeatures.value = filterAuthoritativeUnlockedFeatures(
            rawMap = parsed.unlockedFeatures,
            now = now,
            passActive = passActive,
            trialActive = trialActive,
            trialUsed = trialUsed,
            trialExpiry = trialExpiry,
            passExpiry = passExpiry
        )
        _lockedFeatures.value = parsed.lockedFeatures
    }

    /**
     * One-shot authoritative read of `users/{uid}` after server-side payment approval or action
     * completion so in-memory licensing, points, remove-ads expiry, and unlocked features reflect
     * server state immediately even if the real-time listener hasn't delivered its update yet.
     */
    suspend fun refreshUserPassStatusFromServer(context: Context): Pair<PassTier?, Long> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: context.applicationContext
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            withContext(Dispatchers.Main) { refreshAccessStatus(ctx) }
            return@withContext null to 0L
        }
        val uid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: return@withContext null to 0L

        return@withContext try {
            val snapshot = resolveUserDocumentSnapshot(FirebaseFirestore.getInstance(), uid)
            val now = getCurrentServerTimeMs()
            if (snapshot != null && snapshot.exists()) {
                val parsed = parseUserProfileSnapshotMap(snapshot.data)
                updateLastActionCooldownFromServerTimestamp(parsed.lastActionTimeMs, nowServerMs = now)
                if (parsed.hasPointsField) _pointsBalance.value = parsed.points
                parsed.hasClaimedReferral?.let { _hasClaimedReferral.value = it }
                parsed.referralCount?.let { _referralCount.value = it }
                parsed.referralCode?.let { _referralCode.value = it }

                val passType = parsed.activePassType
                val passExpiry = parsed.passExpiryTimestamp
                val removeAdsExpiry = parsed.removeAdsExpiryTimestamp
                val trialClaimed = parsed.trialClaimed ?: serverTrialClaimed
                val trialExpiry = parsed.trialExpiry ?: serverTrialExpiry
                val isTrialUsed = parsed.trialUsed ?: serverTrialUsed
                val tier = PassTier.fromId(passType)
                val proTier = tier?.takeIf { it.isProPass }
                val proExpiry = if (proTier != null) passExpiry else 0L
                val effectiveRemoveAds = maxOf(
                    removeAdsExpiry,
                    if (tier == PassTier.REMOVE_ADS) passExpiry else 0L
                )

                serverPassTier = proTier
                serverPassExpiry = proExpiry
                serverTrialClaimed = trialClaimed
                serverTrialExpiry = trialExpiry
                serverTrialUsed = isTrialUsed
                serverRemoveAdsExpiry = effectiveRemoveAds
                _removeAdsExpiryMs.value = if (effectiveRemoveAds > now) effectiveRemoveAds else 0L

                val effectiveBanUntilMs = resolveEffectiveBanUntilMs(parsed, now)
                if (effectiveBanUntilMs != null && effectiveBanUntilMs > now) {
                    _isBannedFlow.value = true
                    _banUntilTimestampFlow.value = effectiveBanUntilMs
                    _banReasonFlow.value = parsed.banReason
                } else {
                    _isBannedFlow.value = false
                    _banUntilTimestampFlow.value = null
                    _banReasonFlow.value = null
                }
                GlobalAdminManager.syncBanStateFromLicenseManager(effectiveBanUntilMs, parsed.banReason)

                applyParsedUnlockedAndLockedFeatures(
                    parsed = parsed,
                    now = now,
                    passActive = proTier != null && proExpiry > now,
                    trialActive = trialClaimed > 0L && trialExpiry > now,
                    trialUsed = isTrialUsed,
                    trialExpiry = trialExpiry,
                    passExpiry = proExpiry
                )
                hasLoadedTrial = true
                hasLoadedLicensing = true

                saveAuthoritativeLicensingCache(
                    context = ctx,
                    passTier = serverPassTier,
                    passExpiry = serverPassExpiry,
                    trialClaimed = serverTrialClaimed,
                    trialExpiry = serverTrialExpiry,
                    trialUsed = serverTrialUsed,
                    removeAdsExpiry = serverRemoveAdsExpiry
                )

                withContext(Dispatchers.Main) { refreshAccessStatus(ctx) }
                val resolvedExpiry = if (tier == PassTier.REMOVE_ADS) effectiveRemoveAds else proExpiry
                tier to resolvedExpiry
            } else {
                withContext(Dispatchers.Main) { refreshAccessStatus(ctx) }
                null to 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "refreshUserPassStatusFromServer notice: ${e.message}")
            withContext(Dispatchers.Main) { refreshAccessStatus(ctx) }
            null to 0L
        }
    }

    private fun recomputeAccessStatus(context: Context? = null) {
        val ctx = context ?: appContext
        if (DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) {
            Log.w(TAG, "Runtime hooking or release debugger detected; locking access.")
            _accessStatus.value = AccessStatus.Expired(
                isTrialUsed = true,
                hasHadPreviousPass = true,
                message = "Security policy violation detected. Access disabled."
            )
            return
        }

        // Effective offline grace check (24h default for active paid pass, strict 2h for free/trial):
        if (isOfflineGracePeriodExpired(ctx)) {
            val graceHours = getEffectiveOfflineGraceHours(ctx)
            Log.w(TAG, "Offline grace period expired (limit: $graceHours hours). Enforcing zero-trust lock.")
            _accessStatus.value = AccessStatus.Expired(
                isTrialUsed = serverTrialUsed || serverTrialClaimed > 0L,
                hasHadPreviousPass = serverPassTier != null || serverPassExpiry > 0L,
                message = "Maximum ${graceHours}-hour offline limit reached. Please connect to internet to verify pass."
            )
            return
        }

        val now = getCurrentServerTimeMs()

        // Fast UI Boot: Show HMAC-verified cached state without promoting it to authoritative hasLoadedLicensing
        if (!hasLoadedLicensing) {
            if (ctx != null) {
                val cached = getCachedLicensingData(ctx)
                val effectiveRemoveAds = maxOf(
                    cached.removeAdsExpiry,
                    if (cached.passTier == PassTier.REMOVE_ADS) cached.passExpiry else 0L
                )
                _removeAdsExpiryMs.value = if (effectiveRemoveAds > now) effectiveRemoveAds else 0L

                // 1. Check cached Paid Pro Pass (Daily/Weekly/Monthly ONLY; Remove Ads Pass never unlocks Pro)
                if (cached.passTier != null && cached.passTier.isProPass && cached.passExpiry > now) {
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
                    Log.d(TAG, "Fast UI Boot: Populated PassActive (${cached.passTier.title}) from HMAC-verified cache while awaiting Firestore.")
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
                        hasLoadedTrial = true
                        Log.d(TAG, "Fast UI Boot: Populated TrialActive from HMAC-verified cache while awaiting Firestore.")
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

        val rawTier = serverPassTier
        val tier = rawTier?.takeIf { it.isProPass }
        val passExpiry = if (tier != null) serverPassExpiry else 0L
        val effectiveRemoveAds = maxOf(
            serverRemoveAdsExpiry,
            if (rawTier == PassTier.REMOVE_ADS) serverPassExpiry else 0L
        )
        _removeAdsExpiryMs.value = if (effectiveRemoveAds > now) effectiveRemoveAds else 0L
        val trialClaimed = serverTrialClaimed
        val trialExpiry = serverTrialExpiry
        val trialUsed = serverTrialUsed

        _currentFolder.value = computeCurrentFolder(tier, passExpiry, trialClaimed, trialExpiry, now)

        val trialInfoObj = TrialInfo(
            firstClaimedTimestamp = trialClaimed,
            trialExpiryTimestamp = trialExpiry,
            isUsed = trialUsed,
            isExpired = now >= trialExpiry,
            remainingMs = (trialExpiry - now).coerceAtLeast(0L)
        )
        _trialInfo.value = trialInfoObj

        // Prune expired point-unlocked entries (e.g. expired remove_ads_pass or hourly unlocks)
        // even while Pro Pass / Trial is active so UI flows stay live and accurate.
        val livePrunedUnlocked = _unlockedFeatures.value.filterValues { exp ->
            exp == -1L || exp == Long.MAX_VALUE || exp > now
        }
        if (_unlockedFeatures.value != livePrunedUnlocked) {
            _unlockedFeatures.value = livePrunedUnlocked
        }

        // 1. Check Paid Pro Pass First (Daily/Weekly/Monthly ONLY)
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

        _activePassInfo.value = null

        // 2. Check 2-Day Free Trial

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
        val filtered = filterAuthoritativeUnlockedFeatures(
            rawMap = _unlockedFeatures.value,
            now = now,
            passActive = false,
            trialActive = false,
            trialUsed = trialUsed || trialClaimed > 0L,
            trialExpiry = trialExpiry,
            passExpiry = passExpiry
        )
        if (_unlockedFeatures.value != filtered) {
            _unlockedFeatures.value = filtered
        }

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
                    GlobalAdminManager.syncBanStateFromLicenseManager(null, null)
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
     * Returns true if the Admin app explicitly locked this feature (or its parent group) for the user.
     * Explicit Admin locks override Pro Passes, Trials, and Point unlocks.
     */
    fun isFeatureLockedByAdmin(featureId: String): Boolean {
        val locked = _lockedFeatures.value
        if (locked.isEmpty()) return false
        val normalized = featureId.lowercase()
        if (locked.contains(normalized)) return true
        val parent = FeatureIds.parentGroupFor(normalized)
        return parent != null && locked.contains(parent)
    }

    /**
     * Authoritative Feature Unlock Filter:
     * - Keeps Admin permanent unlocks (`-1L` or `Long.MAX_VALUE`).
     * - Strictly excludes any expired timestamps (`exp <= now`).
     * - Strictly excludes entries bounded by an expired/dead trial or expired pass.
     */
    fun filterAuthoritativeUnlockedFeatures(
        rawMap: Map<String, Long>,
        now: Long = getCurrentServerTimeMs(),
        passActive: Boolean = isPassActive,
        trialActive: Boolean = isTrialActive,
        trialUsed: Boolean = serverTrialUsed,
        trialExpiry: Long = serverTrialExpiry,
        passExpiry: Long = serverPassExpiry
    ): Map<String, Long> {
        if (rawMap.isEmpty()) return emptyMap()

        val isTrialDead = (!trialActive && !passActive) && (trialUsed || (trialExpiry in 1L..now))
        val isPassDead = !passActive && (passExpiry in 1L..now)

        return rawMap.filter { (key, exp) ->
            if (key.isBlank()) return@filter false
            // 1. Keep Admin permanent unlocks (-1L or Long.MAX_VALUE)
            if (exp == -1L || exp == Long.MAX_VALUE) {
                return@filter true
            }
            // 2. Ignore/filter out any expired timestamps
            if (exp <= now) {
                return@filter false
            }
            // 3. Strictly exclude entries bounded by a dead/expired trial
            if (isTrialDead && trialExpiry > 0L && exp <= trialExpiry) {
                return@filter false
            }
            // 4. Strictly exclude entries bounded by an expired pass
            if (isPassDead && passExpiry > 0L && exp <= passExpiry) {
                return@filter false
            }
            true
        }
    }

    private fun findFeatureUnlockExpiry(featureId: String): Long? {
        val map = _unlockedFeatures.value
        if (map.isEmpty()) return null
        val target = featureId.lowercase()
        val exp = map.entries.firstOrNull { it.key.equals(target, ignoreCase = true) }?.value
            ?: FeatureIds.parentGroupFor(target)?.let { parent ->
                map.entries.firstOrNull { it.key.equals(parent, ignoreCase = true) }?.value
            }
            ?: return null

        val now = getCurrentServerTimeMs()
        if (exp != -1L && exp != Long.MAX_VALUE) {
            if (exp <= now) return null
            if (!isPassActive && !isTrialActive) {
                val isTrialDead = serverTrialUsed || (serverTrialExpiry in 1L..now)
                if (isTrialDead && serverTrialExpiry > 0L && exp <= serverTrialExpiry) {
                    return null
                }
                if (serverPassExpiry in 1L..now && exp <= serverPassExpiry) {
                    return null
                }
            }
        }
        return exp
    }

    /**
     * Evaluates ban state live against authoritative server clock rather than relying solely
     * on the 10-second ticker Handler (which Android Doze mode may delay).
     */
    fun isCurrentlyBanned(): Boolean {
        val banUntil = _banUntilTimestampFlow.value
        if (banUntil != null) {
            if (getCurrentServerTimeMs() < banUntil) {
                if (!_isBannedFlow.value) {
                    _isBannedFlow.value = true
                }
                return true
            } else {
                _isBannedFlow.value = false
                _banUntilTimestampFlow.value = null
                _banReasonFlow.value = null
                return false
            }
        }
        return _isBannedFlow.value
    }

    /**
     * Pay-Per-Use & Admin Lock/Unlock Feature Check:
     * 1. Denies immediately if security violation / ban / explicit Admin lock applies.
     * 2. Returns true if full Pro Pass or Trial is active (unless explicitly locked by Admin).
     * 3. In Free Mode, returns true if the specific feature (or its group) was unlocked by Points
     *    or Admin (-1L / permanent or future server expiry timestamp).
     */
    fun isFeatureUnlocked(featureId: String): Boolean {
        if (isClockTamperingDetected() || isCurrentlyBanned()) return false
        val ctx = appContext
        if (ctx != null && DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) return false
        if (isFeatureLockedByAdmin(featureId)) return false
        if (isAccessGranted()) return true

        val now = getCurrentServerTimeMs()
        val directExpiry = findFeatureUnlockExpiry(featureId)
        if (directExpiry != null && (directExpiry == -1L || directExpiry > now)) {
            return true
        }

        // Legacy bundle checks: if querying FARE_FILTERS / DISTANCE_FILTERS, return true if any child filter is unlocked
        return when (featureId.lowercase()) {
            FeatureIds.FARE_FILTERS -> listOf(
                FeatureIds.MIN_FARE,
                FeatureIds.MAX_FARE,
                FeatureIds.MIN_PER_KM_RATE
            ).any { child ->
                !isFeatureLockedByAdmin(child) && (findFeatureUnlockExpiry(child)?.let { it == -1L || it > now } == true)
            }
            FeatureIds.DISTANCE_FILTERS -> listOf(
                FeatureIds.MAX_PICKUP_DISTANCE,
                FeatureIds.MAX_DROP_DISTANCE
            ).any { child ->
                !isFeatureLockedByAdmin(child) && (findFeatureUnlockExpiry(child)?.let { it == -1L || it > now } == true)
            }
            else -> false
        }
    }

    /**
     * Returns true when at least one evaluation filter is unlocked (either via active Pro Pass/Trial
     * or via an active Points/Admin unlock in Free Mode).
     */
    fun hasAnyUnlockedFilter(): Boolean {
        if (!isServiceActive() || isCurrentlyBanned() || isClockTamperingDetected()) return false
        val ctx = appContext
        if (ctx != null && DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) return false
        if (isAccessGranted()) return true

        return listOf(
            FeatureIds.MIN_FARE,
            FeatureIds.MAX_FARE,
            FeatureIds.MAX_PICKUP_DISTANCE,
            FeatureIds.MAX_DROP_DISTANCE,
            FeatureIds.MIN_PER_KM_RATE,
            FeatureIds.DESTINATION_BLACKLIST,
            FeatureIds.FARE_FILTERS,
            FeatureIds.DISTANCE_FILTERS
        ).any { isFeatureUnlocked(it) }
    }

    /**
     * Returns true when automation clicking can run for the user:
     * - Service active, unbanned, clean clock/hooks, licensing loaded (not Loading),
     *   and AUTO_CLICK not explicitly locked by Admin.
     * - Allowed in BOTH Pro Mode (300–700ms boosted speed + all filters unlocked)
     *   AND Free Mode (1–2s fast auto-accept, bypassing locked filters and enforcing only
     *   actively Point-unlocked filters).
     */
    fun hasAnyAutoAcceptCapability(): Boolean {
        if (!isServiceActive() || isCurrentlyBanned() || isClockTamperingDetected()) return false
        val ctx = appContext
        if (ctx != null && DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) return false
        if (isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK)) return false
        if (_accessStatus.value is AccessStatus.Loading && !hasAnyUnlockedFilter()) return false
        return true
    }

    /**
     * Checks if ads should be suppressed due to an active Remove Ads Pass.
     * Returns true STRICTLY when user holds an active Remove Ads Pass (removeAdsExpiryMs or
     * unlocked_features["remove_ads_pass"] / unlocked_features["remove_ads"]).
     */
    fun isRemoveAdsActive(): Boolean {
        if (isClockTamperingDetected() || isCurrentlyBanned()) return false
        val ctx = appContext
        if (ctx != null && DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) return false

        val now = getCurrentServerTimeMs()
        if (_removeAdsExpiryMs.value > now) {
            return true
        }

        val status = _accessStatus.value
        if (status is AccessStatus.PassActive && status.passTier == PassTier.REMOVE_ADS && status.remainingTimeMs > 0) {
            return true
        }

        val directExpiry = _unlockedFeatures.value.entries.firstOrNull {
            it.key.equals(PassTier.REMOVE_ADS.id, ignoreCase = true) ||
                it.key.equals("remove_ads", ignoreCase = true)
        }?.value
        if (directExpiry != null && (directExpiry == -1L || directExpiry == Long.MAX_VALUE || directExpiry > now)) {
            return true
        }

        return false
    }

    fun isAdsRemoved(): Boolean = isRemoveAdsActive()

    /**
     * Controls whether Ads (banners / interstitials / ad placements) should be shown.
     * Ads remain enabled in BOTH Free Mode AND Pro Mode (Free Trial, Daily/Weekly/Monthly Pass),
     * and are hidden STRICTLY when the user holds an active `remove_ads` pass (`isRemoveAdsActive() == true`).
     */
    fun shouldShowAds(): Boolean = !isRemoveAdsActive()

    /**
     * Controls whether Banner Ads should be shown.
     * Strictly requires an active Remove Ads Pass to hide banners; Pro Passes and Free Trial do NOT hide ads.
     */
    fun shouldShowBannerAds(): Boolean = shouldShowAds()

    /**
     * Returns remaining active time in milliseconds for a specific feature.
     * Returns Long.MAX_VALUE when permanently unlocked by Admin (`-1L` or `Long.MAX_VALUE`).
     * Returns 0L when locked or expired.
     */
    fun getFeatureRemainingTimeMs(featureId: String): Long {
        if (isClockTamperingDetected() || isCurrentlyBanned()) return 0L
        val ctx = appContext
        if (ctx != null && DeviceUtils.isRuntimeHookOrDebuggerDetected(ctx)) return 0L
        if (isFeatureLockedByAdmin(featureId)) return 0L
        if (!isFeatureUnlocked(featureId)) return 0L
        if (isAccessGranted()) {
            return when (val status = _accessStatus.value) {
                is AccessStatus.PassActive -> status.remainingTimeMs.coerceAtLeast(0L)
                is AccessStatus.TrialActive -> status.remainingTimeMs.coerceAtLeast(0L)
                else -> 0L
            }
        }
        val expiry = findFeatureUnlockExpiry(featureId) ?: return 0L
        if (expiry == -1L || expiry == Long.MAX_VALUE) return Long.MAX_VALUE
        val now = getCurrentServerTimeMs()
        return (expiry - now).coerceAtLeast(0L)
    }

    @VisibleForTesting
    internal fun buildUnlockFeaturePayload(featureId: String, hours: Int): Map<String, Any> {
        return mapOf(
            "featureId" to featureId,
            "hours" to hours
        )
    }

    @VisibleForTesting
    internal fun buildUnlockMultipleFeaturesPayload(featureIds: List<String>, hours: Int): Map<String, Any> {
        return mapOf(
            "featureIds" to featureIds,
            "hours" to hours
        )
    }

    /**
     * Purchases an hourly micro-transaction unlock for a specific feature using wallet points
     * via the Zero-Trust Action Queue.
     */
    suspend fun unlockFeature(featureId: String, hours: Int = 1): Result<Long> = withContext(Dispatchers.IO) {
        if (hours <= 0) return@withContext Result.failure(IllegalArgumentException("Hours must be positive"))
        if (isFeatureLockedByAdmin(featureId)) {
            return@withContext Result.failure(IllegalStateException("This feature is locked by Admin."))
        }

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
            payload = buildUnlockFeaturePayload(pricing.id, hours)
        )

        result.fold(
            onSuccess = { snap ->
                snap.getLong("newPointsBalance")?.toInt()?.let { updatedBal ->
                    _pointsBalance.value = updatedBal
                }
                var newExpiry = snap.getLong("expiryTimestamp")
                if (newExpiry != null) {
                    val updatedMap = _unlockedFeatures.value.toMutableMap()
                    updatedMap[pricing.id] = newExpiry
                    _unlockedFeatures.value = filterAuthoritativeUnlockedFeatures(updatedMap)
                    onEntitlementsChanged?.invoke()
                } else {
                    appContext?.let { refreshUserPassStatusFromServer(it) }
                    newExpiry = findFeatureUnlockExpiry(pricing.id)
                }

                if (newExpiry == null) {
                    Result.failure(
                        IllegalStateException("Feature unlock completed on server, awaiting profile sync.")
                    )
                } else {
                    Log.i(TAG, "UNLOCK_FEATURE completed via Action Queue for ${pricing.id}. New expiry: $newExpiry")
                    Result.success(newExpiry)
                }
            },
            onFailure = { err ->
                Log.e(TAG, "UNLOCK_FEATURE action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Unlocks one or multiple selected features/filters simultaneously in Free Mode using Points
     * via validated Action Queue requests.
     */
    suspend fun unlockMultipleFeatures(
        featureIds: List<String>,
        hours: Int = 1
    ): Result<Map<String, Long>> = withContext(Dispatchers.IO) {
        if (hours <= 0) return@withContext Result.failure(IllegalArgumentException("Hours must be positive"))
        val distinctIds = featureIds.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        if (distinctIds.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Select at least one filter or feature"))
        }

        val blocked = distinctIds.filter { isFeatureLockedByAdmin(it) }
        if (blocked.isNotEmpty()) {
            return@withContext Result.failure(IllegalStateException("Locked by Admin: ${blocked.joinToString()}"))
        }

        val pricings = distinctIds.map { id ->
            FeatureIds.getPricing(id)
                ?: return@withContext Result.failure(IllegalArgumentException("Unknown feature ID: $id"))
        }

        val totalCost = pricings.sumOf { it.pointsPerHour * hours }
        val currentPoints = _pointsBalance.value
        if (currentPoints < totalCost) {
            return@withContext Result.failure(
                IllegalStateException("Insufficient points. Need $totalCost pts for ${pricings.size} feature(s), you have $currentPoints pts.")
            )
        }

        val result = executeActionQueueRequest(
            actionType = "UNLOCK_MULTIPLE_FEATURES",
            payload = buildUnlockMultipleFeaturesPayload(distinctIds, hours)
        )

        result.fold(
            onSuccess = { snap ->
                val actionData = snap.data ?: buildMap {
                    snap.get("newPointsBalance")?.let { put("newPointsBalance", it) }
                    snap.get("pointsBalance")?.let { put("pointsBalance", it) }
                    snap.get("unlockedFeatures")?.let { put("unlockedFeatures", it) }
                }
                applyCompletedMultiFeatureUnlockSnapshot(
                    actionData = actionData,
                    requestedFeatureIds = pricings.map { it.id },
                    onMissingFeaturesRefresh = {
                        appContext?.let { refreshUserPassStatusFromServer(it) }
                    }
                ).also { applied ->
                    if (applied.isSuccess) {
                        Log.i(
                            TAG,
                            "UNLOCK_MULTIPLE_FEATURES completed atomically for ${applied.getOrNull()?.size ?: 0} feature(s)."
                        )
                    }
                }
            },
            onFailure = { err ->
                Log.e(TAG, "UNLOCK_MULTIPLE_FEATURES action failed: ${err.message}", err)
                Result.failure(err)
            }
        )
    }

    /**
     * Reads strictly canonical `unlockedFeatures` from a completed `UNLOCK_MULTIPLE_FEATURES` action snapshot.
     */
    @VisibleForTesting
    internal fun extractUnlockedFeaturesFromActionData(
        actionData: Map<String, Any?>?
    ): Map<String, Long> {
        if (actionData == null) return emptyMap()
        val map = actionData["unlockedFeatures"] as? Map<*, *> ?: return emptyMap()
        return map.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val exp = extractServerTimestampMs(v) ?: return@mapNotNull null
            key to exp
        }.toMap()
    }

    @VisibleForTesting
    internal suspend fun applyCompletedMultiFeatureUnlockSnapshot(
        actionData: Map<String, Any?>?,
        requestedFeatureIds: List<String>,
        onMissingFeaturesRefresh: suspend () -> Unit = {}
    ): Result<Map<String, Long>> {
        val newBalance = (actionData?.get("newPointsBalance") as? Number)?.toInt()
            ?: (actionData?.get("pointsBalance") as? Number)?.toInt()
        if (newBalance != null) {
            _pointsBalance.value = newBalance
        }

        val parsedExpiries = extractUnlockedFeaturesFromActionData(actionData)
        val allPresentOnActionDoc = requestedFeatureIds.isNotEmpty() &&
            requestedFeatureIds.all { parsedExpiries[it] != null }
        if (!allPresentOnActionDoc) {
            onMissingFeaturesRefresh()
        }

        val unlockedResults = mutableMapOf<String, Long>()
        val updatedMap = _unlockedFeatures.value.toMutableMap()
        for (featureId in requestedFeatureIds) {
            val serverExp = parsedExpiries[featureId]
                ?: findFeatureUnlockExpiry(featureId)
            if (serverExp != null) {
                updatedMap[featureId] = serverExp
                unlockedResults[featureId] = serverExp
            }
        }
        _unlockedFeatures.value = filterAuthoritativeUnlockedFeatures(updatedMap)
        onEntitlementsChanged?.invoke()
        return if (unlockedResults.isEmpty()) {
            Result.failure(
                IllegalStateException("Feature unlock completed on server, awaiting profile sync.")
            )
        } else {
            Result.success(unlockedResults)
        }
    }

    @VisibleForTesting
    internal fun setFeatureLocksForTesting(
        unlocked: Map<String, Long>,
        locked: Set<String> = emptySet()
    ) {
        _unlockedFeatures.value = filterAuthoritativeUnlockedFeatures(unlocked)
        _lockedFeatures.value = locked.map { it.lowercase() }.toSet()
        onEntitlementsChanged?.invoke()
    }

    @VisibleForTesting
    internal fun setLicensingForTesting(
        passTier: PassTier? = null,
        passExpiry: Long = 0L,
        trialClaimed: Long = 0L,
        trialExpiry: Long = 0L,
        trialUsed: Boolean = false,
        removeAdsExpiry: Long = 0L
    ) {
        serverPassTier = passTier?.takeIf { it.isProPass }
        serverPassExpiry = if (passTier?.isProPass == true) passExpiry else 0L
        serverTrialClaimed = trialClaimed
        serverTrialExpiry = trialExpiry
        serverTrialUsed = trialUsed
        serverRemoveAdsExpiry = maxOf(
            removeAdsExpiry,
            if (passTier == PassTier.REMOVE_ADS) passExpiry else 0L
        )
        _removeAdsExpiryMs.value = if (serverRemoveAdsExpiry > getCurrentServerTimeMs()) serverRemoveAdsExpiry else 0L
        hasLoadedTrial = true
        hasLoadedLicensing = true
        refreshAccessStatus()
    }

    private fun applyInitProfileResolvedFields(
        context: Context,
        hasPointsInActionDoc: Boolean,
        parsedPoints: Int?,
        parsedReferral: String?,
        hasTrialFieldsInActionDoc: Boolean,
        serverTrialClaimedMs: Long,
        serverTrialExpiryMs: Long,
        trialUsedInActionDoc: Boolean?
    ) {
        if (hasPointsInActionDoc && parsedPoints != null) {
            _pointsBalance.value = parsedPoints
        }
        if (!parsedReferral.isNullOrBlank()) {
            _referralCode.value = parsedReferral
        }

        val now = getCurrentServerTimeMs()
        val hasActiveTrialInActionDoc = serverTrialClaimedMs > 0L && serverTrialExpiryMs > now
        if (hasTrialFieldsInActionDoc || hasActiveTrialInActionDoc) {
            if (hasActiveTrialInActionDoc && DeviceUtils.isFreeTrialAllowed(context)) {
                serverTrialClaimed = serverTrialClaimedMs
                serverTrialExpiry = serverTrialExpiryMs
                serverTrialUsed = true
                hasLoadedTrial = true
                _currentFolder.value = computeCurrentFolder(
                    passTier = serverPassTier,
                    passExpiry = serverPassExpiry,
                    trialClaimed = serverTrialClaimed,
                    trialExpiry = serverTrialExpiry,
                    now = now
                )
                saveAuthoritativeLicensingCache(
                    context = context,
                    passTier = serverPassTier,
                    passExpiry = serverPassExpiry,
                    trialClaimed = serverTrialClaimed,
                    trialExpiry = serverTrialExpiry,
                    trialUsed = serverTrialUsed,
                    removeAdsExpiry = serverRemoveAdsExpiry
                )
                hasLoadedLicensing = true
            } else if (serverTrialExpiry <= now) {
                serverTrialClaimed = serverTrialClaimedMs
                serverTrialExpiry = serverTrialExpiryMs
                serverTrialUsed = trialUsedInActionDoc ?: true
                hasLoadedTrial = true
                _currentFolder.value = if (serverPassTier?.isProPass == true && serverPassExpiry > now) {
                    computeCurrentFolder(
                        passTier = serverPassTier,
                        passExpiry = serverPassExpiry,
                        trialClaimed = 0L,
                        trialExpiry = 0L,
                        now = now
                    )
                } else {
                    "free_mod"
                }
                hasLoadedLicensing = true
            } else {
                Log.d(
                    TAG,
                    "INIT_PROFILE actionDoc lacked active trial while live trial is active (expiry=$serverTrialExpiry > now=$now); preserving live trial state."
                )
            }
        } else {
            Log.d(
                TAG,
                "INIT_PROFILE actionDoc does not contain trialClaimed/trialExpiry; deferring trial state to active users/{uid} snapshot listener."
            )
        }
        refreshAccessStatus(context)
    }

    @VisibleForTesting
    internal fun applyInitProfileActionData(
        context: Context,
        actionData: Map<String, Any?>?
    ) {
        val hasPointsInActionDoc = actionData != null && actionData.containsKey("pointsBalance")
        val parsedPoints = if (hasPointsInActionDoc) {
            (actionData?.get("pointsBalance") as? Number)?.toInt()
        } else {
            null
        }
        val parsedReferral = (actionData?.get("referralCode") as? String)?.takeIf { it.isNotBlank() }
        val hasTrialFieldsInActionDoc = actionData != null &&
            (actionData.containsKey("trialClaimed") || actionData.containsKey("trialExpiry"))
        val serverTrialClaimedMs = extractServerTimestampMs(actionData?.get("trialClaimed")) ?: 0L
        val serverTrialExpiryMs = extractServerTimestampMs(actionData?.get("trialExpiry")) ?: 0L
        val trialUsedInActionDoc = actionData?.get("trialUsed") as? Boolean

        applyInitProfileResolvedFields(
            context = context,
            hasPointsInActionDoc = hasPointsInActionDoc,
            parsedPoints = parsedPoints,
            parsedReferral = parsedReferral,
            hasTrialFieldsInActionDoc = hasTrialFieldsInActionDoc,
            serverTrialClaimedMs = serverTrialClaimedMs,
            serverTrialExpiryMs = serverTrialExpiryMs,
            trialUsedInActionDoc = trialUsedInActionDoc
        )
    }

    @VisibleForTesting
    internal fun setProfileStateForTesting(
        points: Int? = null,
        referralCode: String? = null
    ) {
        if (points != null) {
            _pointsBalance.value = points
        }
        if (referralCode != null) {
            _referralCode.value = referralCode
        }
    }
}


package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.example.DeviceUtils
import com.example.FeatureIds
import com.example.PassPricingConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

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
 * Dynamic payment & pricing configuration model synced read-only from Firestore `admin_settings/payment_config`.
 * Uses the unified canonical schema shared with Admin App, with backward-compatible legacy fallbacks
 * and safe `PassPricingConfig` / `FeatureIds` defaults when remote overrides are absent.
 */
data class PaymentConfig(
    val adminUpiId: String = "",
    val adminPayeeName: String = "",
    val dailyPriceInr: Int = PassPricingConfig.DAILY_PRICE_INR,
    val weeklyPriceInr: Int = PassPricingConfig.WEEKLY_PRICE_INR,
    val monthlyPriceInr: Int = PassPricingConfig.MONTHLY_PRICE_INR,
    val removeAdsPriceInr: Int = PassPricingConfig.REMOVE_ADS_PRICE_INR,
    val dailyPoints: Int = PassPricingConfig.DAILY_POINTS,
    val weeklyPoints: Int = PassPricingConfig.WEEKLY_POINTS,
    val monthlyPoints: Int = PassPricingConfig.MONTHLY_POINTS,
    val removeAdsPoints: Int = PassPricingConfig.REMOVE_ADS_POINTS,
    val featureHourlyRates: Map<String, Int> = emptyMap(),
    val validPassAmountsInr: List<Int> = listOf(
        dailyPriceInr,
        weeklyPriceInr,
        monthlyPriceInr,
        removeAdsPriceInr
    ).distinct()
)

/**
 * Repository to observe and fetch remote administrative app configuration from Firebase Firestore.
 * Document paths: `admin_settings/app_config` and `admin_settings/payment_config` (strictly read-only).
 */
object AppConfigRepository {

    private const val TAG = "AppConfigRepository"
    const val COLL_ADMIN_SETTINGS = "admin_settings"
    const val DOC_APP_CONFIG = "app_config"
    const val DOC_PAYMENT_CONFIG = "payment_config"
    const val DOC_SECURITY_CONFIG = "security_config"
    const val DOC_SERVER_CLOCK = "server_clock"

    /**
     * Enforces unified `firestore.rules` contract for `admin_settings/{settingId}`:
     * public pre-login `get` is allowed ONLY for `app_config` and `payment_config`;
     * `security_config`, `server_clock`, and all other settings strictly require authentication.
     */
    @VisibleForTesting
    internal fun canReadAdminSetting(settingId: String, currentAuthUid: String?): Boolean {
        return settingId == DOC_APP_CONFIG ||
            settingId == DOC_PAYMENT_CONFIG ||
            !currentAuthUid.isNullOrBlank()
    }

    @Volatile
    private var appContext: Context? = null

    private val _currentAppConfig = MutableStateFlow(AppConfig())
    val currentAppConfig: StateFlow<AppConfig> = _currentAppConfig.asStateFlow()

    private val _currentPaymentConfig = MutableStateFlow(PaymentConfig())
    val currentPaymentConfig: StateFlow<PaymentConfig> = _currentPaymentConfig.asStateFlow()

    /**
     * Initializes the repository with the application context.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun isFirebaseReady(): Boolean {
        val ctx = appContext
        if (ctx != null) {
            return DeviceUtils.ensureFirebaseInitialized(ctx)
        }
        return try {
            FirebaseApp.getInstance()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parsePositiveInt(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it > 0 }
        is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    private fun parseIntFromMap(map: Map<*, *>?, vararg keys: String): Int? {
        if (map.isNullOrEmpty()) return null
        val normalizedMap = HashMap<String, Any?>(map.size)
        for ((k, v) in map) {
            val keyStr = (k as? String)?.trim()?.lowercase(Locale.ROOT) ?: continue
            if (keyStr.isNotEmpty()) {
                normalizedMap[keyStr] = v
            }
        }
        for (key in keys) {
            parsePositiveInt(normalizedMap[key.lowercase(Locale.ROOT)])?.let { return it }
        }
        return null
    }

    private fun parseFeatureRatesMap(raw: Any?): Map<String, Int> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        val parsed = LinkedHashMap<String, Int>()
        for ((k, v) in map) {
            val key = (k as? String)?.trim()?.lowercase(Locale.ROOT) ?: continue
            val pts = parsePositiveInt(v) ?: continue
            if (key.isNotEmpty()) {
                parsed[key] = pts
            }
        }
        return parsed
    }

    /**
     * Parses raw `admin_settings/payment_config` document fields using canonical Admin App keys first,
     * falling back cleanly to legacy keys during transition and hardcoded defaults if absent/invalid.
     *
     * Canonical schema:
     * - Pass Points Costs: `dailyPoints`, `weeklyPoints`, `monthlyPoints`, `removeAdsPoints`
     *   (fallbacks: `*PointsCost`, `passPointsCost` map)
     * - Pass INR Prices: `dailyPriceInr`, `weeklyPriceInr`, `monthlyPriceInr`, `removeAdsPriceInr`,
     *   optional `validPassAmountsInr`
     *   (fallbacks: `*Price`, `passPricesInr` map, `priceInInr` map)
     * - Feature Hourly Rates: `featureHourlyRates`
     *   (fallbacks: `featureRates`, `featurePricesPtsPerHr`, `featurePrices`)
     */
    @VisibleForTesting
    internal fun parsePaymentConfigMap(data: Map<String, Any?>?): PaymentConfig {
        if (data.isNullOrEmpty()) {
            return PaymentConfig()
        }

        val upi = (data["adminUpiId"] as? String)?.trim().orEmpty()
        val payee = (data["adminPayeeName"] as? String)?.trim().orEmpty()

        val passPricesInrMap = data["passPricesInr"] as? Map<*, *>
        val priceInInrMap = data["priceInInr"] as? Map<*, *>
        val passPointsCostMap = data["passPointsCost"] as? Map<*, *>

        val dailyPrice = parsePositiveInt(data["dailyPriceInr"])
            ?: parsePositiveInt(data["dailyPrice"])
            ?: parseIntFromMap(passPricesInrMap, "daily", "daily_pass")
            ?: parseIntFromMap(priceInInrMap, "daily", "daily_pass")
            ?: PassPricingConfig.DAILY_PRICE_INR

        val weeklyPrice = parsePositiveInt(data["weeklyPriceInr"])
            ?: parsePositiveInt(data["weeklyPrice"])
            ?: parseIntFromMap(passPricesInrMap, "weekly", "weekly_pass")
            ?: parseIntFromMap(priceInInrMap, "weekly", "weekly_pass")
            ?: PassPricingConfig.WEEKLY_PRICE_INR

        val monthlyPrice = parsePositiveInt(data["monthlyPriceInr"])
            ?: parsePositiveInt(data["monthlyPrice"])
            ?: parseIntFromMap(passPricesInrMap, "monthly", "monthly_pass")
            ?: parseIntFromMap(priceInInrMap, "monthly", "monthly_pass")
            ?: PassPricingConfig.MONTHLY_PRICE_INR

        val removeAdsPrice = parsePositiveInt(data["removeAdsPriceInr"])
            ?: parsePositiveInt(data["removeAdsPrice"])
            ?: parseIntFromMap(passPricesInrMap, "remove_ads", "remove_ads_pass")
            ?: parseIntFromMap(priceInInrMap, "remove_ads", "remove_ads_pass")
            ?: PassPricingConfig.REMOVE_ADS_PRICE_INR

        val dailyPts = parsePositiveInt(data["dailyPoints"])
            ?: parsePositiveInt(data["dailyPointsCost"])
            ?: parseIntFromMap(passPointsCostMap, "daily", "daily_pass")
            ?: PassPricingConfig.DAILY_POINTS

        val weeklyPts = parsePositiveInt(data["weeklyPoints"])
            ?: parsePositiveInt(data["weeklyPointsCost"])
            ?: parseIntFromMap(passPointsCostMap, "weekly", "weekly_pass")
            ?: PassPricingConfig.WEEKLY_POINTS

        val monthlyPts = parsePositiveInt(data["monthlyPoints"])
            ?: parsePositiveInt(data["monthlyPointsCost"])
            ?: parseIntFromMap(passPointsCostMap, "monthly", "monthly_pass")
            ?: PassPricingConfig.MONTHLY_POINTS

        val removeAdsPts = parsePositiveInt(data["removeAdsPoints"])
            ?: parsePositiveInt(data["removeAdsPointsCost"])
            ?: parseIntFromMap(passPointsCostMap, "remove_ads", "remove_ads_pass")
            ?: PassPricingConfig.REMOVE_ADS_POINTS

        val parsedValidAmounts = (data["validPassAmountsInr"] as? Iterable<*>)
            ?.mapNotNull { parsePositiveInt(it) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(dailyPrice, weeklyPrice, monthlyPrice, removeAdsPrice).distinct()

        // Layer legacy maps from lowest to highest precedence, then apply canonical `featureHourlyRates` on top
        val mergedRates = LinkedHashMap<String, Int>()
        mergedRates.putAll(parseFeatureRatesMap(data["featurePrices"]))
        mergedRates.putAll(parseFeatureRatesMap(data["featurePricesPtsPerHr"]))
        mergedRates.putAll(parseFeatureRatesMap(data["featureRates"]))
        mergedRates.putAll(parseFeatureRatesMap(data["featureHourlyRates"]))

        return PaymentConfig(
            adminUpiId = upi,
            adminPayeeName = payee,
            dailyPriceInr = dailyPrice,
            weeklyPriceInr = weeklyPrice,
            monthlyPriceInr = monthlyPrice,
            removeAdsPriceInr = removeAdsPrice,
            dailyPoints = dailyPts,
            weeklyPoints = weeklyPts,
            monthlyPoints = monthlyPts,
            removeAdsPoints = removeAdsPts,
            featureHourlyRates = mergedRates.toMap(),
            validPassAmountsInr = parsedValidAmounts
        )
    }

    /**
     * Parses and propagates a raw `admin_settings/payment_config` field map onto
     * `PassPricingConfig`, `FeatureIds`, and `currentPaymentConfig`.
     */
    @VisibleForTesting
    internal fun applyPaymentConfigFromMap(data: Map<String, Any?>?): PaymentConfig {
        if (data == null) {
            val defaults = PaymentConfig()
            PassPricingConfig.applyOverrides()
            FeatureIds.applyRateOverrides(emptyMap())
            _currentPaymentConfig.value = defaults
            return defaults
        }

        val config = parsePaymentConfigMap(data)
        PassPricingConfig.applyOverrides(
            dailyPriceInr = config.dailyPriceInr,
            weeklyPriceInr = config.weeklyPriceInr,
            monthlyPriceInr = config.monthlyPriceInr,
            removeAdsPriceInr = config.removeAdsPriceInr,
            dailyPoints = config.dailyPoints,
            weeklyPoints = config.weeklyPoints,
            monthlyPoints = config.monthlyPoints,
            removeAdsPoints = config.removeAdsPoints,
            validPassAmountsInr = config.validPassAmountsInr
        )
        FeatureIds.applyRateOverrides(config.featureHourlyRates)
        _currentPaymentConfig.value = config
        return config
    }

    /**
     * Applies optional dynamic pass/feature pricing overrides from `admin_settings/payment_config`
     * onto `PassPricingConfig` and `FeatureIds`, safely falling back to defaults when absent or invalid.
     */
    fun applyPaymentConfigFromSnapshot(snapshot: DocumentSnapshot?): PaymentConfig {
        val data = if (snapshot != null && snapshot.exists()) snapshot.data else null
        return applyPaymentConfigFromMap(data)
    }

    /**
     * Observes real-time changes to the `admin_settings/app_config` document.
     * Uses Firestore `addSnapshotListener` wrapped in a Kotlin `Flow`.
     * Never rethrows or crashes if Firebase is uninitialized or unavailable.
     */
    fun observeAppConfig(): Flow<AppConfig> = callbackFlow {
        // Immediately emit current in-memory / default configuration
        trySend(_currentAppConfig.value)

        if (!isFirebaseReady()) {
            Log.w(TAG, "FirebaseApp is not ready; emitted default AppConfig without error.")
            awaitClose { }
            return@callbackFlow
        }

        val firestore = try {
            FirebaseFirestore.getInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseFirestore instance not available: ${t.message}")
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

    @VisibleForTesting
    internal var paymentConfigTestListenerFactory:
        (((Map<String, Any?>?) -> Unit) -> ListenerRegistration?)? = null

    /**
     * Observes real-time changes to `admin_settings/payment_config` and synchronizes
     * live pass/feature pricing overrides cleanly with safe hardcoded fallbacks.
     *
     * Attaches immediately at startup (even before sign-in) and stays active across
     * sign-in/sign-out without `PERMISSION_DENIED`, matching Step 6A `firestore.rules`
     * (`allow get: if settingId in ['app_config', 'payment_config'] || isAuthenticated()`).
     */
    fun observePaymentConfig(): Flow<PaymentConfig> = callbackFlow {
        trySend(_currentPaymentConfig.value)

        if (!canReadAdminSetting(DOC_PAYMENT_CONFIG, currentAuthUid = null)) {
            awaitClose { }
            return@callbackFlow
        }

        val testFactory = paymentConfigTestListenerFactory
        if (testFactory != null) {
            val testRegistration = try {
                testFactory { rawData ->
                    val updated = applyPaymentConfigFromMap(rawData)
                    trySend(updated)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Test payment_config listener failed: ${t.message}")
                trySend(_currentPaymentConfig.value)
                null
            }
            awaitClose {
                testRegistration?.remove()
            }
            return@callbackFlow
        }

        if (!isFirebaseReady()) {
            Log.w(TAG, "FirebaseApp is not ready; emitted default PaymentConfig without error.")
            awaitClose { }
            return@callbackFlow
        }

        val firestore = try {
            FirebaseFirestore.getInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "FirebaseFirestore instance not available: ${t.message}")
            awaitClose { }
            return@callbackFlow
        }

        val listener: ListenerRegistration? = try {
            val docRef = firestore.collection(COLL_ADMIN_SETTINGS).document(DOC_PAYMENT_CONFIG)
            docRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Real-time payment_config snapshot listener error: ${error.message}")
                    return@addSnapshotListener
                }
                val updated = applyPaymentConfigFromSnapshot(snapshot)
                trySend(updated)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed adding snapshot listener on payment_config: ${t.message}")
            trySend(_currentPaymentConfig.value)
            null
        }

        awaitClose {
            listener?.remove()
        }
    }
}


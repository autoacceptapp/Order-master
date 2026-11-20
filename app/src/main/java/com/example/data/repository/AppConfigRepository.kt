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
import org.json.JSONArray

/**
 * App Configuration model synced with Firestore collection `admin_settings`, document `app_config`.
 *
 * @param minSupportedVersionCode Minimum supported version code. Older builds are force-updated.
 * @param latestVersionCode Latest available version code published.
 * @param dynamicAppDownloadUrl Unified dynamic download URL for updates and referral sharing.
 * @param paidPassOfflineGraceHours Remote offline grace hours for active paid pass drivers (clamped 2..72, default 24).
 * @param rapidoTargetPackage Target Rapido Captain package name (default `com.rapido.rider`).
 * @param rapidoAcceptButtonKeywords Remote keywords used to recognize Rapido Accept buttons.
 * @param rapidoAcceptViewIds Remote resource IDs (exact or `:id/` suffix) prioritized before text keywords.
 * @param rapidoRejectKeywords Remote keywords excluded from ever matching as Accept buttons.
 * @param rapidoSeeMoreOrdersKeywords Remote keywords used to detect "See N more orders >" expansion chips.
 * @param rapidoLeftSidebarWidthRatio Clamped width ratio (`0.05f..0.45f`, default `0.22f`) for excluding left mini-tabs.
 * @param rapidoFareRegex Optional remote regex override for extracting fare values (falls back safely if blank/invalid).
 * @param rapidoDistanceRegex Optional remote regex override for extracting distance values (falls back safely if blank/invalid).
 * @param rapidoAutoExpandMoreOrders When true, auto-taps "See N more orders" when top floating popup fails filters.
 */
data class AppConfig(
    val minSupportedVersionCode: Int = 1,
    val latestVersionCode: Int = 1,
    val dynamicAppDownloadUrl: String = "",
    val paidPassOfflineGraceHours: Int = AppConfigRepository.DEFAULT_PAID_PASS_OFFLINE_GRACE_HOURS,
    val rapidoTargetPackage: String = AppConfigRepository.DEFAULT_RAPIDO_TARGET_PACKAGE,
    val rapidoAcceptButtonKeywords: List<String> = AppConfigRepository.DEFAULT_RAPIDO_ACCEPT_KEYWORDS,
    val rapidoAcceptViewIds: List<String> = AppConfigRepository.DEFAULT_RAPIDO_ACCEPT_VIEW_IDS,
    val rapidoRejectKeywords: List<String> = AppConfigRepository.DEFAULT_RAPIDO_REJECT_KEYWORDS,
    val rapidoSeeMoreOrdersKeywords: List<String> = AppConfigRepository.DEFAULT_RAPIDO_SEE_MORE_ORDERS_KEYWORDS,
    val rapidoLeftSidebarWidthRatio: Float = AppConfigRepository.DEFAULT_LEFT_SIDEBAR_WIDTH_RATIO,
    val rapidoFareRegex: String = "",
    val rapidoDistanceRegex: String = "",
    val rapidoAutoExpandMoreOrders: Boolean = false
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

    const val PREFS_APP_CONFIG_CACHE = "app_config_cache"
    private const val KEY_CACHE_PRESENT = "has_cached_snapshot"
    private const val KEY_MIN_SUPPORTED_VERSION = "minSupportedVersionCode"
    private const val KEY_LATEST_VERSION = "latestVersionCode"
    private const val KEY_DYNAMIC_DOWNLOAD_URL = "dynamicAppDownloadUrl"
    private const val KEY_PAID_OFFLINE_GRACE_HOURS = "paidPassOfflineGraceHours"
    private const val KEY_RAPIDO_TARGET_PACKAGE = "rapidoTargetPackage"
    private const val KEY_RAPIDO_ACCEPT_KEYWORDS = "rapidoAcceptButtonKeywords"
    private const val KEY_RAPIDO_ACCEPT_VIEW_IDS = "rapidoAcceptViewIds"
    private const val KEY_RAPIDO_REJECT_KEYWORDS = "rapidoRejectKeywords"
    private const val KEY_RAPIDO_SEE_MORE_KEYWORDS = "rapidoSeeMoreOrdersKeywords"
    private const val KEY_RAPIDO_LEFT_SIDEBAR_RATIO = "rapidoLeftSidebarWidthRatio"
    private const val KEY_RAPIDO_FARE_REGEX = "rapidoFareRegex"
    private const val KEY_RAPIDO_DISTANCE_REGEX = "rapidoDistanceRegex"
    private const val KEY_RAPIDO_AUTO_EXPAND_MORE = "rapidoAutoExpandMoreOrders"

    const val DEFAULT_PAID_PASS_OFFLINE_GRACE_HOURS = 24
    const val MIN_PAID_PASS_OFFLINE_GRACE_HOURS = 2
    const val MAX_PAID_PASS_OFFLINE_GRACE_HOURS = 72
    const val DEFAULT_RAPIDO_TARGET_PACKAGE = "com.rapido.rider"

    const val DEFAULT_LEFT_SIDEBAR_WIDTH_RATIO = 0.22f
    const val MIN_LEFT_SIDEBAR_WIDTH_RATIO = 0.05f
    const val MAX_LEFT_SIDEBAR_WIDTH_RATIO = 0.45f

    val DEFAULT_RAPIDO_ACCEPT_KEYWORDS: List<String> = listOf(
        "Accept",
        "Accept Order",
        "Accept Ride",
        "Swipe to Accept",
        "Confirm",
        "Take Ride",
        "स्वीकार"
    )

    val DEFAULT_RAPIDO_ACCEPT_VIEW_IDS: List<String> = listOf(
        "btn_accept",
        "btn_accept_order",
        "accept_button"
    )

    val DEFAULT_RAPIDO_REJECT_KEYWORDS: List<String> = listOf(
        "Reject",
        "Decline",
        "Skip",
        "Pass"
    )

    val DEFAULT_RAPIDO_SEE_MORE_ORDERS_KEYWORDS: List<String> = listOf(
        "See",
        "more orders"
    )

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

    @Volatile
    private var cachedFareRegexSource: String = ""
    @Volatile
    private var cachedFareRegex: Regex? = null

    @Volatile
    private var cachedDistanceRegexSource: String = ""
    @Volatile
    private var cachedDistanceRegex: Regex? = null

    /**
     * Initializes the repository with the application context and immediately hydrates
     * `currentAppConfig` from persistent `SharedPreferences` (`app_config_cache`) so
     * offline/cold-boot sessions use the latest remote rules without network dependency.
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        hydrateFromCache(appCtx)
    }

    fun effectivePaidPassOfflineGraceHours(): Int {
        return _currentAppConfig.value.paidPassOfflineGraceHours
            .coerceIn(MIN_PAID_PASS_OFFLINE_GRACE_HOURS, MAX_PAID_PASS_OFFLINE_GRACE_HOURS)
    }

    fun effectiveRapidoTargetPackage(): String {
        val pkg = _currentAppConfig.value.rapidoTargetPackage.trim()
        return if (pkg.isNotEmpty() && pkg.contains('.')) pkg else DEFAULT_RAPIDO_TARGET_PACKAGE
    }

    fun effectiveRapidoAcceptKeywords(): List<String> {
        val configured = _currentAppConfig.value.rapidoAcceptButtonKeywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (configured.isNotEmpty()) configured else DEFAULT_RAPIDO_ACCEPT_KEYWORDS
    }

    fun effectiveRapidoAcceptViewIds(): List<String> {
        val configured = _currentAppConfig.value.rapidoAcceptViewIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (configured.isNotEmpty()) configured else DEFAULT_RAPIDO_ACCEPT_VIEW_IDS
    }

    fun effectiveRapidoRejectKeywords(): List<String> {
        val configured = _currentAppConfig.value.rapidoRejectKeywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (configured.isNotEmpty()) configured else DEFAULT_RAPIDO_REJECT_KEYWORDS
    }

    fun effectiveRapidoSeeMoreOrdersKeywords(): List<String> {
        val configured = _currentAppConfig.value.rapidoSeeMoreOrdersKeywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return if (configured.isNotEmpty()) configured else DEFAULT_RAPIDO_SEE_MORE_ORDERS_KEYWORDS
    }

    fun effectiveRapidoLeftSidebarWidthRatio(): Float {
        val ratio = _currentAppConfig.value.rapidoLeftSidebarWidthRatio
        return if (ratio.isFinite()) {
            ratio.coerceIn(MIN_LEFT_SIDEBAR_WIDTH_RATIO, MAX_LEFT_SIDEBAR_WIDTH_RATIO)
        } else {
            DEFAULT_LEFT_SIDEBAR_WIDTH_RATIO
        }
    }

    fun effectiveRapidoFareRegex(): Regex? {
        val source = _currentAppConfig.value.rapidoFareRegex.trim()
        if (source.isEmpty()) return null
        if (source == cachedFareRegexSource) return cachedFareRegex
        val compiled = compileRemoteRegexOrNull(source, "rapidoFareRegex")
        cachedFareRegexSource = source
        cachedFareRegex = compiled
        return compiled
    }

    fun effectiveRapidoDistanceRegex(): Regex? {
        val source = _currentAppConfig.value.rapidoDistanceRegex.trim()
        if (source.isEmpty()) return null
        if (source == cachedDistanceRegexSource) return cachedDistanceRegex
        val compiled = compileRemoteRegexOrNull(source, "rapidoDistanceRegex")
        cachedDistanceRegexSource = source
        cachedDistanceRegex = compiled
        return compiled
    }

    fun isRapidoAutoExpandMoreOrdersEnabled(): Boolean {
        return _currentAppConfig.value.rapidoAutoExpandMoreOrders
    }

    private fun compileRemoteRegexOrNull(pattern: String, fieldName: String): Regex? {
        if (pattern.isBlank() || pattern.length > 512) return null
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .onFailure { err ->
                Log.w(TAG, "Invalid remote $fieldName pattern ignored (falling back to built-in): ${err.message}")
            }
            .getOrNull()
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

    private fun parseIntOrNull(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun parseBooleanOrNull(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> when (value.trim().lowercase(Locale.ROOT)) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> null
        }
        else -> null
    }

    private fun parseFloatOrNull(value: Any?): Float? = runCatching {
        when (value) {
            is Number -> value.toFloat().takeIf { it.isFinite() }
            is String -> value.trim().toFloatOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }.getOrNull()

    private fun parseKeywordList(raw: Any?, defaults: List<String>): List<String> = runCatching {
        val items = when (raw) {
            is Iterable<*> -> raw.mapNotNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
            is String -> {
                val trimmed = raw.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    val arr = JSONArray(trimmed)
                    buildList {
                        for (i in 0 until arr.length()) {
                            val token = arr.optString(i, "").trim()
                            if (token.isNotEmpty()) add(token)
                        }
                    }
                } else {
                    trimmed.split(',', '\n', '|').map { it.trim() }.filter { it.isNotEmpty() }
                }
            }
            else -> emptyList()
        }.distinct()
        if (items.isNotEmpty()) items else defaults
    }.getOrDefault(defaults)

    /**
     * Parses raw `admin_settings/app_config` document fields with safe defaults, clamping,
     * and validation for remote Rapido parser settings and paid pass offline grace hours.
     * Wrapped in `runCatching` so malformed remote payloads never crash the app.
     */
    @VisibleForTesting
    internal fun parseAppConfigMap(data: Map<String, Any?>?): AppConfig {
        if (data.isNullOrEmpty()) {
            return AppConfig()
        }

        return runCatching {
            val minCode = parsePositiveInt(data[KEY_MIN_SUPPORTED_VERSION]) ?: 1
            val latestCode = parsePositiveInt(data[KEY_LATEST_VERSION]) ?: 1
            val downloadUrl = (data[KEY_DYNAMIC_DOWNLOAD_URL] as? String)?.trim().orEmpty()

            val rawGraceHours = parseIntOrNull(data[KEY_PAID_OFFLINE_GRACE_HOURS])
                ?: DEFAULT_PAID_PASS_OFFLINE_GRACE_HOURS
            val clampedGraceHours = rawGraceHours.coerceIn(
                MIN_PAID_PASS_OFFLINE_GRACE_HOURS,
                MAX_PAID_PASS_OFFLINE_GRACE_HOURS
            )

            val rawTargetPkg = (data[KEY_RAPIDO_TARGET_PACKAGE] as? String)?.trim().orEmpty()
            val targetPackage = if (rawTargetPkg.isNotEmpty() && rawTargetPkg.contains('.')) {
                rawTargetPkg
            } else {
                DEFAULT_RAPIDO_TARGET_PACKAGE
            }

            val acceptKeywords = parseKeywordList(data[KEY_RAPIDO_ACCEPT_KEYWORDS], DEFAULT_RAPIDO_ACCEPT_KEYWORDS)
            val acceptViewIds = parseKeywordList(data[KEY_RAPIDO_ACCEPT_VIEW_IDS], DEFAULT_RAPIDO_ACCEPT_VIEW_IDS)
            val rejectKeywords = parseKeywordList(data[KEY_RAPIDO_REJECT_KEYWORDS], DEFAULT_RAPIDO_REJECT_KEYWORDS)
            val seeMoreKeywords = parseKeywordList(
                data[KEY_RAPIDO_SEE_MORE_KEYWORDS],
                DEFAULT_RAPIDO_SEE_MORE_ORDERS_KEYWORDS
            )
            val rawSidebarRatio = parseFloatOrNull(data[KEY_RAPIDO_LEFT_SIDEBAR_RATIO])
                ?: DEFAULT_LEFT_SIDEBAR_WIDTH_RATIO
            val clampedSidebarRatio = rawSidebarRatio.coerceIn(
                MIN_LEFT_SIDEBAR_WIDTH_RATIO,
                MAX_LEFT_SIDEBAR_WIDTH_RATIO
            )

            val fareRegex = (data[KEY_RAPIDO_FARE_REGEX] as? String)?.trim().orEmpty()
            val distanceRegex = (data[KEY_RAPIDO_DISTANCE_REGEX] as? String)?.trim().orEmpty()
            val autoExpandMoreOrders = parseBooleanOrNull(data[KEY_RAPIDO_AUTO_EXPAND_MORE]) ?: false

            AppConfig(
                minSupportedVersionCode = minCode,
                latestVersionCode = latestCode,
                dynamicAppDownloadUrl = downloadUrl,
                paidPassOfflineGraceHours = clampedGraceHours,
                rapidoTargetPackage = targetPackage,
                rapidoAcceptButtonKeywords = acceptKeywords,
                rapidoAcceptViewIds = acceptViewIds,
                rapidoRejectKeywords = rejectKeywords,
                rapidoSeeMoreOrdersKeywords = seeMoreKeywords,
                rapidoLeftSidebarWidthRatio = clampedSidebarRatio,
                rapidoFareRegex = fareRegex,
                rapidoDistanceRegex = distanceRegex,
                rapidoAutoExpandMoreOrders = autoExpandMoreOrders
            )
        }.onFailure { err ->
            Log.w(TAG, "Malformed app_config payload ignored; falling back to defaults: ${err.message}")
        }.getOrDefault(AppConfig())
    }

    private fun encodeStringList(values: List<String>): String {
        val arr = JSONArray()
        values.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * Persists a validated remote [AppConfig] snapshot to local `SharedPreferences` (`app_config_cache`)
     * so offline/cold-boot sessions immediately inherit the latest remote rules.
     */
    @VisibleForTesting
    internal fun persistAppConfigToCache(context: Context, config: AppConfig) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS_APP_CONFIG_CACHE, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_CACHE_PRESENT, true)
                .putInt(KEY_MIN_SUPPORTED_VERSION, config.minSupportedVersionCode)
                .putInt(KEY_LATEST_VERSION, config.latestVersionCode)
                .putString(KEY_DYNAMIC_DOWNLOAD_URL, config.dynamicAppDownloadUrl)
                .putInt(KEY_PAID_OFFLINE_GRACE_HOURS, config.paidPassOfflineGraceHours)
                .putString(KEY_RAPIDO_TARGET_PACKAGE, config.rapidoTargetPackage)
                .putString(KEY_RAPIDO_ACCEPT_KEYWORDS, encodeStringList(config.rapidoAcceptButtonKeywords))
                .putString(KEY_RAPIDO_ACCEPT_VIEW_IDS, encodeStringList(config.rapidoAcceptViewIds))
                .putString(KEY_RAPIDO_REJECT_KEYWORDS, encodeStringList(config.rapidoRejectKeywords))
                .putString(KEY_RAPIDO_SEE_MORE_KEYWORDS, encodeStringList(config.rapidoSeeMoreOrdersKeywords))
                .putFloat(KEY_RAPIDO_LEFT_SIDEBAR_RATIO, config.rapidoLeftSidebarWidthRatio)
                .putString(KEY_RAPIDO_FARE_REGEX, config.rapidoFareRegex)
                .putString(KEY_RAPIDO_DISTANCE_REGEX, config.rapidoDistanceRegex)
                .putBoolean(KEY_RAPIDO_AUTO_EXPAND_MORE, config.rapidoAutoExpandMoreOrders)
                .apply()
        }.onFailure { err ->
            Log.w(TAG, "Failed persisting app_config_cache: ${err.message}")
        }
    }

    /**
     * Reads and validates any previously cached [AppConfig] snapshot from `SharedPreferences` (`app_config_cache`).
     */
    @VisibleForTesting
    internal fun loadCachedAppConfig(context: Context): AppConfig? {
        return runCatching {
            val prefs = context.getSharedPreferences(PREFS_APP_CONFIG_CACHE, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_CACHE_PRESENT, false)) {
                return null
            }
            val rawMap = mapOf<String, Any?>(
                KEY_MIN_SUPPORTED_VERSION to prefs.getInt(KEY_MIN_SUPPORTED_VERSION, 1),
                KEY_LATEST_VERSION to prefs.getInt(KEY_LATEST_VERSION, 1),
                KEY_DYNAMIC_DOWNLOAD_URL to prefs.getString(KEY_DYNAMIC_DOWNLOAD_URL, ""),
                KEY_PAID_OFFLINE_GRACE_HOURS to prefs.getInt(KEY_PAID_OFFLINE_GRACE_HOURS, DEFAULT_PAID_PASS_OFFLINE_GRACE_HOURS),
                KEY_RAPIDO_TARGET_PACKAGE to prefs.getString(KEY_RAPIDO_TARGET_PACKAGE, DEFAULT_RAPIDO_TARGET_PACKAGE),
                KEY_RAPIDO_ACCEPT_KEYWORDS to prefs.getString(KEY_RAPIDO_ACCEPT_KEYWORDS, null),
                KEY_RAPIDO_ACCEPT_VIEW_IDS to prefs.getString(KEY_RAPIDO_ACCEPT_VIEW_IDS, null),
                KEY_RAPIDO_REJECT_KEYWORDS to prefs.getString(KEY_RAPIDO_REJECT_KEYWORDS, null),
                KEY_RAPIDO_SEE_MORE_KEYWORDS to prefs.getString(KEY_RAPIDO_SEE_MORE_KEYWORDS, null),
                KEY_RAPIDO_LEFT_SIDEBAR_RATIO to prefs.getFloat(KEY_RAPIDO_LEFT_SIDEBAR_RATIO, DEFAULT_LEFT_SIDEBAR_WIDTH_RATIO),
                KEY_RAPIDO_FARE_REGEX to prefs.getString(KEY_RAPIDO_FARE_REGEX, ""),
                KEY_RAPIDO_DISTANCE_REGEX to prefs.getString(KEY_RAPIDO_DISTANCE_REGEX, ""),
                KEY_RAPIDO_AUTO_EXPAND_MORE to prefs.getBoolean(KEY_RAPIDO_AUTO_EXPAND_MORE, false)
            )
            parseAppConfigMap(rawMap)
        }.onFailure { err ->
            Log.w(TAG, "Failed loading app_config_cache; using defaults: ${err.message}")
        }.getOrNull()
    }

    /**
     * Hydrates `currentAppConfig` from `SharedPreferences` (`app_config_cache`) at startup
     * or cold boot before Firestore connects.
     */
    fun hydrateFromCache(context: Context): AppConfig {
        val cached = loadCachedAppConfig(context)
        val effective = cached ?: AppConfig()
        applyParsedAppConfigLocked(effective)
        return effective
    }

    @VisibleForTesting
    internal fun clearCachedAppConfig(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS_APP_CONFIG_CACHE, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun applyParsedAppConfigLocked(config: AppConfig) {
        cachedFareRegexSource = ""
        cachedFareRegex = null
        cachedDistanceRegexSource = ""
        cachedDistanceRegex = null
        _currentAppConfig.value = config
        // Eagerly validate remote regex patterns so malformed rules log once and fall back cleanly
        if (config.rapidoFareRegex.isNotEmpty()) {
            effectiveRapidoFareRegex()
        }
        if (config.rapidoDistanceRegex.isNotEmpty()) {
            effectiveRapidoDistanceRegex()
        }
    }

    /**
     * Parses and applies a raw `admin_settings/app_config` map to `currentAppConfig`,
     * refreshing compiled remote regex caches safely and persisting valid non-empty remote
     * snapshots to `SharedPreferences` (`app_config_cache`) when `appContext` is initialized.
     */
    @VisibleForTesting
    internal fun applyAppConfigFromMap(
        data: Map<String, Any?>?,
        persistToCache: Boolean = true
    ): AppConfig {
        val config = parseAppConfigMap(data)
        applyParsedAppConfigLocked(config)
        if (persistToCache && !data.isNullOrEmpty()) {
            appContext?.let { ctx -> persistAppConfigToCache(ctx, config) }
        }
        return config
    }

    /**
     * Applies remote `admin_settings/app_config` snapshot safely, persisting valid snapshots
     * to local `SharedPreferences` cache and falling back to defaults if absent.
     */
    fun applyAppConfigFromSnapshot(snapshot: DocumentSnapshot?): AppConfig {
        val data = if (snapshot != null && snapshot.exists()) snapshot.data else null
        return applyAppConfigFromMap(data, persistToCache = true)
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
                val config = applyAppConfigFromSnapshot(snapshot)
                trySend(config)
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


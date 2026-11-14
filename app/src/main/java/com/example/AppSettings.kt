package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Immutable
import com.example.data.OrderEntity
import com.example.data.OrderRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Immutable
data class SupportedLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    val sampleGreeting: String
)

val SUPPORTED_LANGUAGES = listOf(
    SupportedLanguage("en", "English", "English", "Hello"),
    SupportedLanguage("hi", "हिन्दी", "Hindi", "नमस्ते"),
    SupportedLanguage("gu", "ગુજરાતી", "Gujarati", "નમસ્તે"),
    SupportedLanguage("mr", "मराठी", "Marathi", "नमस्कार"),
    SupportedLanguage("bn", "বাংলা", "Bengali", "নমস্কার"),
    SupportedLanguage("ta", "தமிழ்", "Tamil", "வணக்கம்"),
    SupportedLanguage("te", "తెలుగు", "Telugu", "నమస్కారం"),
    SupportedLanguage("kn", "ಕನ್ನಡ", "Kannada", "ನಮಸ್ಕಾರ"),
    SupportedLanguage("ml", "മലയാളം", "Malayalam", "നമസ്കാരം"),
    SupportedLanguage("pa", "ਪੰਜਾਬੀ", "Punjabi", "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ")
)

enum class LogSeverity {
    INFO,
    MATCH_ACCEPTED,
    REJECTED,
    CLICK_EXECUTED,
    WARNING
}

@Immutable
data class ActivityLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val timeFormatted: String,
    val title: String,
    val message: String,
    val severity: LogSeverity,
    val evaluation: RideEvaluation? = null
)

@Immutable
data class SettingsState(
    val isAutoAcceptEnabled: Boolean = false,
    val isMinFareEnabled: Boolean = false,
    val minFare: Float = AppSettings.RESTRICTED_MIN_FARE,
    val isMaxFareEnabled: Boolean = false,
    val maxFare: Float = AppSettings.DEFAULT_MAX_FARE,
    val isMaxPickupDistanceEnabled: Boolean = false,
    val maxPickupDistance: Float = AppSettings.RESTRICTED_MAX_PICKUP_DISTANCE,
    val isMaxDropDistanceEnabled: Boolean = false,
    val maxDropDistance: Float = AppSettings.DEFAULT_MAX_DROP_DISTANCE,
    val clickDelayMs: Long = AppSettings.DEFAULT_CLICK_DELAY_MS,
    val isMinPerKmRateEnabled: Boolean = false,
    val minPerKmRate: Float = AppSettings.DEFAULT_MIN_PER_KM_RATE,
    val isDestinationBlacklistEnabled: Boolean = false,
    val blockedLocations: List<String> = emptyList(),
    val isVoiceAnnouncerEnabled: Boolean = false,
    val voiceLanguage: String = AppSettings.DEFAULT_VOICE_LANGUAGE,
    val speechRate: Float = AppSettings.DEFAULT_SPEECH_RATE,
    val speechPitch: Float = AppSettings.DEFAULT_SPEECH_PITCH,
    val isFloatingOverlayEnabled: Boolean = false,
    val isAutoClickEnabled: Boolean = false,
    val lastAcceptedFare: Double? = null,
    val activePassType: String? = null,
    val passExpiryTimestamp: Long = 0L,
    val unlockedFeatures: Map<String, Long> = emptyMap(),
    val lockedFeatures: Set<String> = emptySet()
) {
    val autoAcceptEnabled: Boolean get() = isAutoAcceptEnabled
    val minPrice: Float get() = minFare
    val maxPrice: Float get() = maxFare
    val maxDistance: Float get() = maxPickupDistance
    val selectedLanguage: String get() = voiceLanguage
}

@Immutable
data class UserAuthState(
    val isLoggedIn: Boolean = false,
    val userEmail: String? = null,
    val userName: String? = null,
    val photoUrl: String? = null,
    val uid: String? = null
)

/**
 * Zero-Trust Thin-Client Settings Store.
 *
 * Security & Persistence Model:
 * - All business logic filters, thresholds, automation flags, pass metadata, and premium feature
 *   toggles are read strictly in-memory (RAM StateFlows) from a real-time Firestore snapshot
 *   listener on `users/{uid}` and are NEVER written to SharedPreferences or written back to Firestore.
 * - Purely local UI/UX preferences (language selection, onboarding/tutorial completion, TTS pitch/rate,
 *   login banner dismissal) remain in SharedPreferences.
 * - Synchronous getters read directly from RAM (`StateFlow.value`) with <1ms overhead so order
 *   evaluation runs strictly offline/in-RAM well below the <300ms latency budget.
 */
object AppSettings {
    private const val TAG = "AppSettings"
    private const val COLL_USERS = "users"
    const val PREFS_NAME = "captain_auto_accept_prefs"
    const val USER_FILTER_PREFS_NAME = "order_master_user_filters"

    val SUPPORTED_LANGUAGES get() = com.example.SUPPORTED_LANGUAGES

    // Purely Local UI/UX SharedPreferences Keys (NEVER used for licensing or security)
    const val KEY_VOICE_LANGUAGE = "key_voice_language"
    const val KEY_APP_LANGUAGE = "key_app_language"
    const val KEY_LANGUAGE_SELECTED = "key_language_selected"
    const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
    const val KEY_HOME_TUTORIAL_COMPLETED = "key_home_tutorial_completed"
    const val KEY_SPEECH_RATE = "key_speech_rate"
    const val KEY_SPEECH_PITCH = "key_speech_pitch"
    const val KEY_LOGIN_DISMISSED = "key_login_dismissed"

    // User-Controlled Filter Preferences & Toggle Keys (stored in USER_FILTER_PREFS_NAME, never grant entitlements)
    private const val FKEY_AUTO_ACCEPT = "user_auto_accept_enabled"
    private const val FKEY_MIN_FARE_ENABLED = "user_min_fare_enabled"
    private const val FKEY_MIN_FARE = "user_min_fare"
    private const val FKEY_MAX_FARE_ENABLED = "user_max_fare_enabled"
    private const val FKEY_MAX_FARE = "user_max_fare"
    private const val FKEY_MAX_PICKUP_ENABLED = "user_max_pickup_enabled"
    private const val FKEY_MAX_PICKUP = "user_max_pickup_distance"
    private const val FKEY_MAX_DROP_ENABLED = "user_max_drop_enabled"
    private const val FKEY_MAX_DROP = "user_max_drop_distance"
    private const val FKEY_MIN_RATE_ENABLED = "user_min_rate_enabled"
    private const val FKEY_MIN_RATE = "user_min_rate"
    private const val FKEY_BLACKLIST_ENABLED = "user_blacklist_enabled"
    private const val FKEY_BLOCKED_LOCATIONS = "user_blocked_locations"
    private const val FKEY_VOICE_ENABLED = "user_voice_enabled"
    private const val FKEY_OVERLAY_ENABLED = "user_overlay_enabled"
    private const val FKEY_AUTO_CLICK_ENABLED = "user_auto_click_enabled"
    private const val FKEY_CLICK_DELAY_MS = "user_click_delay_ms"
    private const val FKEY_USER_CUSTOMIZED_PREFIX = "customized_"

    private val ALLOWED_LOCAL_UI_KEYS = setOf(
        KEY_VOICE_LANGUAGE,
        KEY_APP_LANGUAGE,
        KEY_LANGUAGE_SELECTED,
        KEY_ONBOARDING_COMPLETED,
        KEY_HOME_TUTORIAL_COMPLETED,
        KEY_SPEECH_RATE,
        KEY_SPEECH_PITCH,
        KEY_LOGIN_DISMISSED
    )

    // Legacy business/auth keys purged from PREFS_NAME on init
    private val LEGACY_KEYS_TO_PURGE = listOf(
        "key_auto_accept_enabled",
        "key_min_fare_enabled",
        "key_min_fare",
        "key_max_fare_enabled",
        "key_max_fare",
        "key_max_pickup_dist_enabled",
        "key_max_pickup_distance",
        "key_max_drop_dist_enabled",
        "key_max_drop_distance",
        "key_click_delay_ms",
        "key_min_per_km_rate_enabled",
        "key_min_per_km_rate",
        "key_destination_blacklist_enabled",
        "key_blocked_locations",
        "key_voice_announcer_enabled",
        "key_floating_overlay_enabled",
        "key_auto_click_enabled",
        "key_user_is_logged_in",
        "key_user_email",
        "key_user_name",
        "key_user_photo_url",
        "key_user_uid",
        "key_user_id_token",
        "key_service_enabled",
        "key_min_value",
        "key_max_value",
        "key_max_distance"
    )

    // Broadcast actions for service logging
    const val ACTION_ACCESSIBILITY_LOG = "com.example.ACCESSIBILITY_LOG"
    const val EXTRA_LOG_MESSAGE = "extra_log_message"

    // Baseline Fare Range Constants (UI validation boundaries)
    const val MIN_FARE_RANGE_START = 20.0f
    const val MIN_FARE_RANGE_END = 500.0f
    const val DEFAULT_MIN_FARE = 50.0f

    const val MAX_FARE_RANGE_START = 100.0f
    const val MAX_FARE_RANGE_END = 2000.0f
    const val DEFAULT_MAX_FARE = 500.0f

    // Strict Safe Restricted Defaults (RAM defaults until Firestore provisions user rules)
    const val RESTRICTED_AUTO_ACCEPT_ENABLED = false
    const val RESTRICTED_MIN_FARE = 0.0f
    const val RESTRICTED_MAX_PICKUP_DISTANCE = 0.0f

    // Other configuration defaults
    const val DEFAULT_MAX_PICKUP_DISTANCE = 3.0f
    const val DEFAULT_MAX_DROP_DISTANCE = 15.0f
    const val MIN_PICKUP_DISTANCE_KM = 0.0f
    const val MAX_PICKUP_DISTANCE_KM = 3.0f
    const val DEFAULT_AUTO_ACCEPT_ENABLED = false
    const val DEFAULT_MIN_FARE_ENABLED = false
    const val DEFAULT_MAX_FARE_ENABLED = false
    const val DEFAULT_MAX_PICKUP_DIST_ENABLED = false
    const val DEFAULT_MAX_DROP_DIST_ENABLED = false
    const val DEFAULT_CLICK_DELAY_MS = 500L
    const val DEFAULT_MIN_PER_KM_RATE_ENABLED = false
    const val DEFAULT_MIN_PER_KM_RATE = 10.0f
    const val DEFAULT_DESTINATION_BLACKLIST_ENABLED = false
    const val DEFAULT_VOICE_ANNOUNCER_ENABLED = false
    const val DEFAULT_VOICE_LANGUAGE = "en"
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val DEFAULT_SPEECH_PITCH = 1.0f
    const val DEFAULT_FLOATING_OVERLAY_ENABLED = false
    const val DEFAULT_AUTO_CLICK_ENABLED = false

    // Reactive StateFlows for granular Fare observation (RAM-only)
    private val _minFareFlow = MutableStateFlow(RESTRICTED_MIN_FARE)
    val minFare: StateFlow<Float> = _minFareFlow.asStateFlow()

    private val _isMinFareEnabledFlow = MutableStateFlow(DEFAULT_MIN_FARE_ENABLED)
    val isMinFareEnabled: StateFlow<Boolean> = _isMinFareEnabledFlow.asStateFlow()

    private val _maxFareFlow = MutableStateFlow(DEFAULT_MAX_FARE)
    val maxFare: StateFlow<Float> = _maxFareFlow.asStateFlow()

    private val _isMaxFareEnabledFlow = MutableStateFlow(DEFAULT_MAX_FARE_ENABLED)
    val isMaxFareEnabled: StateFlow<Boolean> = _isMaxFareEnabledFlow.asStateFlow()

    // Dedicated RAM StateFlows for Firestore-pushed fields
    private val _autoAcceptEnabledFlow = MutableStateFlow(RESTRICTED_AUTO_ACCEPT_ENABLED)
    val autoAcceptEnabledFlow: StateFlow<Boolean> = _autoAcceptEnabledFlow.asStateFlow()

    private val _maxPickupDistanceFlow = MutableStateFlow(RESTRICTED_MAX_PICKUP_DISTANCE)
    val maxPickupDistanceFlow: StateFlow<Float> = _maxPickupDistanceFlow.asStateFlow()

    private val _isFloatingOverlayEnabledFlow = MutableStateFlow(DEFAULT_FLOATING_OVERLAY_ENABLED)
    val isFloatingOverlayEnabledFlow: StateFlow<Boolean> = _isFloatingOverlayEnabledFlow.asStateFlow()

    private val _isVoiceAnnouncerEnabledFlow = MutableStateFlow(DEFAULT_VOICE_ANNOUNCER_ENABLED)
    val isVoiceAnnouncerEnabledFlow: StateFlow<Boolean> = _isVoiceAnnouncerEnabledFlow.asStateFlow()

    private val _activePassTypeFlow = MutableStateFlow<String?>(null)
    val activePassTypeFlow: StateFlow<String?> = _activePassTypeFlow.asStateFlow()

    private val _unlockedFeaturesFlow = MutableStateFlow<Map<String, Long>>(emptyMap())
    val unlockedFeaturesFlow: StateFlow<Map<String, Long>> = _unlockedFeaturesFlow.asStateFlow()

    private val _lockedFeaturesFlow = MutableStateFlow<Set<String>>(emptySet())
    val lockedFeaturesFlow: StateFlow<Set<String>> = _lockedFeaturesFlow.asStateFlow()

    private val _isFirestoreSyncedFlow = MutableStateFlow(false)
    val isFirestoreSyncedFlow: StateFlow<Boolean> = _isFirestoreSyncedFlow.asStateFlow()

    // In-memory properties for legacy fast-path access (Firestore snapshot controlled only)
    @Volatile
    var minCurrencyThreshold: Double = RESTRICTED_MIN_FARE.toDouble()
        private set
    @Volatile
    var maxCurrencyThreshold: Double = DEFAULT_MAX_FARE.toDouble()
        private set

    // Anti-Hack Memory Shield: XOR token masking with dynamic SystemClock.elapsedRealtime() clock salt
    private const val AUTO_ACCEPT_TOKEN_ENABLED = 0x4A7B2C91L
    private const val AUTO_ACCEPT_TOKEN_DISABLED = 0x1E5D8F33L

    private fun safeElapsedRealtime(): Long = try {
        SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    @Volatile
    private var autoAcceptClockSalt: Long = safeElapsedRealtime()

    @Volatile
    private var encodedAutoAcceptToken: Long = AUTO_ACCEPT_TOKEN_DISABLED xor autoAcceptClockSalt

    var autoAcceptEnabled: Boolean
        get() {
            if (LicenseManager.isClockTamperingDetected()) return false
            val decoded = encodedAutoAcceptToken xor autoAcceptClockSalt
            return decoded == AUTO_ACCEPT_TOKEN_ENABLED
        }
        private set(value) {
            val salt = safeElapsedRealtime()
            autoAcceptClockSalt = salt
            encodedAutoAcceptToken = (if (value) AUTO_ACCEPT_TOKEN_ENABLED else AUTO_ACCEPT_TOKEN_DISABLED) xor salt
            _autoAcceptEnabledFlow.value = value
        }

    val minPrice: Double get() = minCurrencyThreshold
    val maxPrice: Double get() = maxCurrencyThreshold
    val maxDistance: Double get() = _settingsState.value.maxPickupDistance.toDouble()
    val speechRate: Float get() = _settingsState.value.speechRate
    val speechPitch: Float get() = _settingsState.value.speechPitch
    val selectedLanguage: String get() = _settingsState.value.voiceLanguage

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Reactive StateFlow for UI & Accessibility Service consumption (RAM-only for business rules)
    private val _settingsState = MutableStateFlow(
        SettingsState(
            isAutoAcceptEnabled = RESTRICTED_AUTO_ACCEPT_ENABLED,
            isMinFareEnabled = DEFAULT_MIN_FARE_ENABLED,
            minFare = RESTRICTED_MIN_FARE,
            isMaxFareEnabled = DEFAULT_MAX_FARE_ENABLED,
            maxFare = DEFAULT_MAX_FARE,
            isMaxPickupDistanceEnabled = DEFAULT_MAX_PICKUP_DIST_ENABLED,
            maxPickupDistance = RESTRICTED_MAX_PICKUP_DISTANCE,
            isMaxDropDistanceEnabled = DEFAULT_MAX_DROP_DIST_ENABLED,
            maxDropDistance = DEFAULT_MAX_DROP_DISTANCE,
            clickDelayMs = DEFAULT_CLICK_DELAY_MS,
            isMinPerKmRateEnabled = DEFAULT_MIN_PER_KM_RATE_ENABLED,
            minPerKmRate = DEFAULT_MIN_PER_KM_RATE,
            isDestinationBlacklistEnabled = DEFAULT_DESTINATION_BLACKLIST_ENABLED,
            blockedLocations = emptyList(),
            isVoiceAnnouncerEnabled = DEFAULT_VOICE_ANNOUNCER_ENABLED,
            voiceLanguage = DEFAULT_VOICE_LANGUAGE,
            speechRate = DEFAULT_SPEECH_RATE,
            speechPitch = DEFAULT_SPEECH_PITCH,
            isFloatingOverlayEnabled = DEFAULT_FLOATING_OVERLAY_ENABLED,
            isAutoClickEnabled = DEFAULT_AUTO_CLICK_ENABLED,
            lastAcceptedFare = null,
            activePassType = null,
            passExpiryTimestamp = 0L,
            unlockedFeatures = emptyMap()
        )
    )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

    private val _lastAcceptedFareFlow = MutableStateFlow<Double?>(null)
    val lastAcceptedFareFlow: StateFlow<Double?> = _lastAcceptedFareFlow.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<ActivityLogEntry>>(
        listOf(
            ActivityLogEntry(
                timeFormatted = timeFormat.format(Date()),
                title = "Engine Initialized",
                message = "Zero-Trust RAM configuration active. Awaiting Firebase user rules.",
                severity = LogSeverity.INFO
            )
        )
    )
    val logsFlow: StateFlow<List<ActivityLogEntry>> = _logsFlow.asStateFlow()

    private val _userAuthState = MutableStateFlow(UserAuthState())
    val userAuthState: StateFlow<UserAuthState> = _userAuthState.asStateFlow()

    // Reactive StateFlows for Subscription Pass Expiry (RAM-only)
    private val _passExpiryTimestampFlow = MutableStateFlow(0L)
    val passExpiryTimestampFlow: StateFlow<Long> = _passExpiryTimestampFlow.asStateFlow()

    private val _isPassActiveFlow = MutableStateFlow(false)
    val isPassActiveFlow: StateFlow<Boolean> = _isPassActiveFlow.asStateFlow()

    // Reactive StateFlows for App & Voice Language Selection (Local UI Prefs)
    private val _isLanguageSelectedFlow = MutableStateFlow(false)
    val isLanguageSelectedFlow: StateFlow<Boolean> = _isLanguageSelectedFlow.asStateFlow()

    private val _appLanguageFlow = MutableStateFlow(DEFAULT_VOICE_LANGUAGE)
    val appLanguageFlow: StateFlow<String> = _appLanguageFlow.asStateFlow()

    // Reactive StateFlow for First-Time Onboarding Tutorial (Local UI Prefs)
    private val _isOnboardingCompletedFlow = MutableStateFlow(false)
    val isOnboardingCompletedFlow: StateFlow<Boolean> = _isOnboardingCompletedFlow.asStateFlow()

    // Reactive StateFlow for First-Time In-App Home Tutorial (Local UI Prefs)
    private val _isHomeTutorialCompletedFlow = MutableStateFlow(false)
    val isHomeTutorialCompletedFlow: StateFlow<Boolean> = _isHomeTutorialCompletedFlow.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firestoreExecutor = Dispatchers.IO.asExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val evaluationMutex = Mutex()
    private val listenerLock = Any()

    private val _recentEvaluationsFlow = MutableStateFlow<List<RideEvaluation>>(emptyList())
    val recentEvaluationsFlow: StateFlow<List<RideEvaluation>> = _recentEvaluationsFlow.asStateFlow()

    @Volatile
    private var isAuthListenerRegistered = false
    private var authStateListener: FirebaseAuth.AuthStateListener? = null

    @Volatile
    private var isLicenseObserverStarted = false

    private var userConfigListener: ListenerRegistration? = null
    @Volatile
    private var activeListeningUid: String? = null
    @Volatile
    private var appContextRef: Context? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getUserFilterPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun persistUserFilterPref(context: Context?, prefKey: String, action: SharedPreferences.Editor.() -> Unit) {
        val ctx = context ?: appContextRef ?: return
        val editor = getUserFilterPrefs(ctx).edit()
        action(editor)
        editor.putBoolean("${FKEY_USER_CUSTOMIZED_PREFIX}$prefKey", true)
        editor.apply()
    }

    private fun isUserCustomized(context: Context?, prefKey: String): Boolean {
        val ctx = context ?: appContextRef ?: return false
        return getUserFilterPrefs(ctx).getBoolean("${FKEY_USER_CUSTOMIZED_PREFIX}$prefKey", false)
    }

    private fun parseBlockedLocationsString(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    /**
     * Scoped persistence helper restricted strictly to safe local UI/UX preferences.
     * Any attempt to persist licensing, pass, or auth flags in PREFS_NAME is prevented by design.
     */
    private fun persistLocalUiPref(context: Context, key: String, action: SharedPreferences.Editor.() -> Unit) {
        require(key in ALLOWED_LOCAL_UI_KEYS) {
            "Zero-Trust violation: SharedPreferences storage is forbidden for '$key'."
        }
        val editor = getPrefs(context).edit()
        action(editor)
        editor.apply()
    }

    private fun purgeLegacyBusinessPrefs(prefs: SharedPreferences) {
        var needsEdit = false
        for (legacyKey in LEGACY_KEYS_TO_PURGE) {
            if (prefs.contains(legacyKey)) {
                needsEdit = true
                break
            }
        }
        if (needsEdit) {
            val editor = prefs.edit()
            for (legacyKey in LEGACY_KEYS_TO_PURGE) {
                editor.remove(legacyKey)
            }
            editor.apply()
            Log.i(TAG, "Purged legacy business/auth keys from SharedPreferences.")
        }
    }

    private fun loadUserFilterPreferencesIntoRam(context: Context) {
        val fPrefs = getUserFilterPrefs(context)
        val userAutoAccept = fPrefs.getBoolean(FKEY_AUTO_ACCEPT, RESTRICTED_AUTO_ACCEPT_ENABLED)
        val minFareVal = fPrefs.getFloat(FKEY_MIN_FARE, DEFAULT_MIN_FARE).coerceIn(MIN_FARE_RANGE_START, MIN_FARE_RANGE_END)
        val minFareOn = fPrefs.getBoolean(FKEY_MIN_FARE_ENABLED, DEFAULT_MIN_FARE_ENABLED)
        val maxFareVal = fPrefs.getFloat(FKEY_MAX_FARE, DEFAULT_MAX_FARE).coerceIn(MAX_FARE_RANGE_START, MAX_FARE_RANGE_END)
        val maxFareOn = fPrefs.getBoolean(FKEY_MAX_FARE_ENABLED, DEFAULT_MAX_FARE_ENABLED)
        val maxPickupVal = fPrefs.getFloat(FKEY_MAX_PICKUP, DEFAULT_MAX_PICKUP_DISTANCE).coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
        val maxPickupOn = fPrefs.getBoolean(FKEY_MAX_PICKUP_ENABLED, DEFAULT_MAX_PICKUP_DIST_ENABLED)
        val maxDropVal = fPrefs.getFloat(FKEY_MAX_DROP, DEFAULT_MAX_DROP_DISTANCE).coerceIn(1.0f, 50.0f)
        val maxDropOn = fPrefs.getBoolean(FKEY_MAX_DROP_ENABLED, DEFAULT_MAX_DROP_DIST_ENABLED)
        val minRateVal = fPrefs.getFloat(FKEY_MIN_RATE, DEFAULT_MIN_PER_KM_RATE).coerceIn(1.0f, 100.0f)
        val minRateOn = fPrefs.getBoolean(FKEY_MIN_RATE_ENABLED, DEFAULT_MIN_PER_KM_RATE_ENABLED)
        val blacklistOn = fPrefs.getBoolean(FKEY_BLACKLIST_ENABLED, DEFAULT_DESTINATION_BLACKLIST_ENABLED)
        val blockedList = parseBlockedLocationsString(fPrefs.getString(FKEY_BLOCKED_LOCATIONS, null))
        val voiceOn = fPrefs.getBoolean(FKEY_VOICE_ENABLED, DEFAULT_VOICE_ANNOUNCER_ENABLED)
        val overlayOn = fPrefs.getBoolean(FKEY_OVERLAY_ENABLED, DEFAULT_FLOATING_OVERLAY_ENABLED)
        val autoClickOn = fPrefs.getBoolean(FKEY_AUTO_CLICK_ENABLED, true)
        val clickDelay = fPrefs.getLong(FKEY_CLICK_DELAY_MS, DEFAULT_CLICK_DELAY_MS).coerceAtLeast(100L)

        autoAcceptEnabled = userAutoAccept
        minCurrencyThreshold = minFareVal.toDouble()
        maxCurrencyThreshold = maxFareVal.toDouble()

        _minFareFlow.value = minFareVal
        _isMinFareEnabledFlow.value = minFareOn
        _maxFareFlow.value = maxFareVal
        _isMaxFareEnabledFlow.value = maxFareOn
        _maxPickupDistanceFlow.value = maxPickupVal
        _isFloatingOverlayEnabledFlow.value = overlayOn
        _isVoiceAnnouncerEnabledFlow.value = voiceOn

        _settingsState.value = _settingsState.value.copy(
            isAutoAcceptEnabled = userAutoAccept,
            isMinFareEnabled = minFareOn,
            minFare = minFareVal,
            isMaxFareEnabled = maxFareOn,
            maxFare = maxFareVal,
            isMaxPickupDistanceEnabled = maxPickupOn,
            maxPickupDistance = maxPickupVal,
            isMaxDropDistanceEnabled = maxDropOn,
            maxDropDistance = maxDropVal,
            clickDelayMs = clickDelay,
            isMinPerKmRateEnabled = minRateOn,
            minPerKmRate = minRateVal,
            isDestinationBlacklistEnabled = blacklistOn,
            blockedLocations = blockedList,
            isVoiceAnnouncerEnabled = voiceOn,
            isFloatingOverlayEnabled = overlayOn,
            isAutoClickEnabled = autoClickOn
        )
    }

    /**
     * Initializes local UI preferences & user filter preferences from SharedPreferences, and attaches
     * the real-time FirebaseAuth + Firestore `users/{uid}` snapshot listener for Lock/Unlock & Pass state.
     */
    fun init(context: Context) {
        val appCtx = context.applicationContext ?: context
        appContextRef = appCtx

        val prefs = getPrefs(appCtx)
        purgeLegacyBusinessPrefs(prefs)
        loadUserFilterPreferencesIntoRam(appCtx)

        // Load ONLY safe, purely local UI preferences
        val hasSelectedLang = prefs.getBoolean(KEY_LANGUAGE_SELECTED, false) || prefs.contains(KEY_APP_LANGUAGE)
        val appLang = prefs.getString(KEY_APP_LANGUAGE, prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE))
            ?: DEFAULT_VOICE_LANGUAGE
        val onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        val homeTutorialCompleted = prefs.getBoolean(KEY_HOME_TUTORIAL_COMPLETED, false)
        val speechRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        val speechPitch = prefs.getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)

        _isLanguageSelectedFlow.value = hasSelectedLang
        _appLanguageFlow.value = appLang
        _isOnboardingCompletedFlow.value = onboardingCompleted
        _isHomeTutorialCompletedFlow.value = homeTutorialCompleted

        // Sync local UI fields onto current SettingsState
        _settingsState.value = _settingsState.value.copy(
            voiceLanguage = appLang,
            speechRate = speechRate,
            speechPitch = speechPitch
        )

        // Check authoritative FirebaseAuth session
        val authUser = try {
            if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                FirebaseAuth.getInstance().currentUser
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        if (authUser != null && authUser.uid.isNotBlank()) {
            _userAuthState.value = UserAuthState(
                isLoggedIn = true,
                userEmail = authUser.email,
                userName = authUser.displayName,
                photoUrl = authUser.photoUrl?.toString(),
                uid = authUser.uid
            )
            startUserConfigSnapshotListener(appCtx, authUser.uid)
        } else {
            _userAuthState.value = UserAuthState()
            stopUserConfigSnapshotListener()
            resetBusinessStateToRestrictedDefaults(appCtx)
        }

        // Register AuthStateListener once so sign-in / sign-out transitions attach/detach listeners automatically
        if (!isAuthListenerRegistered) {
            try {
                if (FirebaseApp.getApps(appCtx).isNotEmpty()) {
                    val listener = FirebaseAuth.AuthStateListener { auth ->
                        updateUserAuthFromFirebase(appCtx, auth.currentUser)
                    }
                    FirebaseAuth.getInstance().addAuthStateListener(listener)
                    authStateListener = listener
                    isAuthListenerRegistered = true
                    Log.d(TAG, "FirebaseAuth AuthStateListener registered.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FirebaseAuth state listener: ${e.message}")
            }
        }

        // Observe LicenseManager's authoritative server state in background without touching disk
        if (!isLicenseObserverStarted) {
            isLicenseObserverStarted = true
            appScope.launch {
                combine(
                    LicenseManager.accessStatus,
                    LicenseManager.isServiceActiveFlow
                ) { status, isServiceActive ->
                    Pair(status, isServiceActive)
                }.collect { (status, isServiceActive) ->
                    when (status) {
                        is AccessStatus.PassActive -> {
                            if (status.passTier.isProPass && status.expiryTimestamp > 0L) {
                                _passExpiryTimestampFlow.value = status.expiryTimestamp
                                _isPassActiveFlow.value = status.remainingTimeMs > 0
                            }
                        }
                        is AccessStatus.TrialActive -> {
                            if (status.expiryTimestamp > 0L) {
                                _passExpiryTimestampFlow.value = status.expiryTimestamp
                                _isPassActiveFlow.value = status.remainingTimeMs > 0
                            }
                        }
                        is AccessStatus.Expired -> {
                            _isPassActiveFlow.value = false
                            if (_passExpiryTimestampFlow.value <= LicenseManager.getCurrentServerTimeMs()) {
                                _passExpiryTimestampFlow.value = 0L
                            }
                        }
                        else -> {
                            if (!_isFirestoreSyncedFlow.value) {
                                _passExpiryTimestampFlow.value = 0L
                                _isPassActiveFlow.value = false
                            }
                        }
                    }

                    val effectiveEnabled = isServiceActive &&
                        LicenseManager.isServiceActive(appCtx) &&
                        !LicenseManager.isBannedFlow.value &&
                        autoAcceptEnabled
                    if (_settingsState.value.isAutoAcceptEnabled != effectiveEnabled) {
                        _settingsState.value = _settingsState.value.copy(isAutoAcceptEnabled = effectiveEnabled)
                    }
                    mainHandler.post {
                        syncOverlayService(appCtx)
                    }
                }
            }
        }
    }

    /**
     * Attaches a real-time Firestore `addSnapshotListener` on `users/{uid}` via Dispatchers.IO.
     * Strictly READ-ONLY: client reads rules from Firestore into RAM StateFlows and never writes back.
     */
    private fun startUserConfigSnapshotListener(context: Context, uid: String) {
        if (uid.isBlank()) {
            stopUserConfigSnapshotListener()
            resetBusinessStateToRestrictedDefaults(context)
            return
        }

        appScope.launch(Dispatchers.IO) {
            synchronized(listenerLock) {
                if (activeListeningUid == uid && userConfigListener != null) {
                    return@launch
                }
                userConfigListener?.remove()
                userConfigListener = null
                activeListeningUid = uid
            }

            try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    Log.w(TAG, "FirebaseApp not initialized; retaining restricted RAM defaults.")
                    return@launch
                }

                val db = FirebaseFirestore.getInstance()
                val docRef = db.collection(COLL_USERS).document(uid)

                val registration = docRef.addSnapshotListener(firestoreExecutor) { snapshot, error ->
                    if (error != null) {
                        // Network drop / transient failure: retain last known StateFlow values in RAM
                        Log.w(
                            TAG,
                            "Firestore snapshot error on users/$uid: ${error.message}. Retaining last known RAM values."
                        )
                        return@addSnapshotListener
                    }

                    if (snapshot != null && snapshot.exists()) {
                        applySnapshotDataToRam(context, snapshot)
                    } else {
                        Log.i(
                            TAG,
                            "Firestore users/$uid document missing; falling back to strict safe defaults in RAM."
                        )
                        _isFirestoreSyncedFlow.value = false
                        resetBusinessStateToRestrictedDefaults(context)
                    }
                }

                synchronized(listenerLock) {
                    // Guard against race where user signed out while listener setup ran
                    if (activeListeningUid == uid) {
                        userConfigListener = registration
                        Log.i(TAG, "Attached read-only Firestore snapshot listener on users/$uid")
                    } else {
                        registration.remove()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach Firestore snapshot listener on users/$uid: ${e.message}", e)
            }
        }
    }

    /**
     * Detaches the active Firestore snapshot listener cleanly.
     */
    private fun stopUserConfigSnapshotListener() {
        synchronized(listenerLock) {
            activeListeningUid = null
            try {
                userConfigListener?.remove()
            } catch (e: Exception) {
                Log.w(TAG, "Error removing Firestore config listener: ${e.message}")
            }
            userConfigListener = null
        }
        _isFirestoreSyncedFlow.value = false
    }

    /**
     * Parses fields from the `users/{uid}` Firestore document and pushes them into RAM StateFlows.
     */
    private fun applySnapshotDataToRam(context: Context, snapshot: DocumentSnapshot) {
        val data = snapshot.data
        applyRawMapToRam(context, data, docExists = true)
    }

    private fun applyRawMapToRam(context: Context?, data: Map<String, Any?>?, docExists: Boolean) {
        if (!docExists || data == null) {
            _isFirestoreSyncedFlow.value = false
            resetBusinessStateToRestrictedDefaults(context)
            return
        }

        val passTypeVal = (data["activePassType"] as? String) ?: (data["passType"] as? String)
        val passExpiryVal = (data["passExpiryTimestamp"] as? Number)?.toLong()
            ?: (data["passExpiry"] as? Number)?.toLong()
            ?: 0L

        val rawFeatures = (data["unlocked_features"] as? Map<*, *>)
            ?: (data["unlockedFeatures"] as? Map<*, *>)
        val unlockedFeaturesVal = rawFeatures?.mapNotNull { (k, v) ->
            val featureKey = k as? String ?: return@mapNotNull null
            val exp = when (v) {
                is Number -> v.toLong()
                true -> -1L
                else -> return@mapNotNull null
            }
            featureKey to exp
        }?.toMap() ?: emptyMap()

        val rawLocked = data["locked_features"] ?: data["lockedFeatures"]
        val lockedFeaturesVal: Set<String> = when (rawLocked) {
            is List<*> -> rawLocked.mapNotNull { (it as? String)?.trim()?.lowercase() }
                .filter { it.isNotEmpty() }.toSet()
            is Map<*, *> -> rawLocked.mapNotNull { (k, v) ->
                val key = (k as? String)?.trim()?.lowercase() ?: return@mapNotNull null
                if (v == true) key else null
            }.toSet()
            else -> emptySet()
        }

        // Preserve user's locally customized filter values & toggles; apply Firestore defaults only when not locally customized
        val current = _settingsState.value

        val serverAutoAccept = if (data.containsKey("autoAcceptEnabled") || data.containsKey("isAutoAcceptEnabled")) {
            (data["autoAcceptEnabled"] as? Boolean) ?: (data["isAutoAcceptEnabled"] as? Boolean) ?: current.isAutoAcceptEnabled
        } else current.isAutoAcceptEnabled
        val finalAutoAccept = if (isUserCustomized(context, FKEY_AUTO_ACCEPT)) current.isAutoAcceptEnabled else serverAutoAccept

        val rawMinFare = (data["minFare"] as? Number)?.toFloat()
        val finalMinFare = if (rawMinFare != null && !isUserCustomized(context, FKEY_MIN_FARE)) {
            if (rawMinFare > 0f) rawMinFare.coerceIn(MIN_FARE_RANGE_START, MIN_FARE_RANGE_END) else RESTRICTED_MIN_FARE
        } else _minFareFlow.value
        val finalMinFareEnabled = if (!isUserCustomized(context, FKEY_MIN_FARE_ENABLED)) {
            (data["isMinFareEnabled"] as? Boolean) ?: if (rawMinFare != null && rawMinFare > 0f) true else current.isMinFareEnabled
        } else current.isMinFareEnabled

        val rawMaxFare = (data["maxFare"] as? Number)?.toFloat()
        val finalMaxFare = if (rawMaxFare != null && !isUserCustomized(context, FKEY_MAX_FARE)) {
            if (rawMaxFare > 0f) rawMaxFare.coerceIn(MAX_FARE_RANGE_START, MAX_FARE_RANGE_END) else DEFAULT_MAX_FARE
        } else _maxFareFlow.value
        val finalMaxFareEnabled = if (!isUserCustomized(context, FKEY_MAX_FARE_ENABLED)) {
            (data["isMaxFareEnabled"] as? Boolean) ?: current.isMaxFareEnabled
        } else current.isMaxFareEnabled

        val rawMaxPickup = (data["maxPickupDistance"] as? Number)?.toFloat()
        val finalMaxPickup = if (rawMaxPickup != null && !isUserCustomized(context, FKEY_MAX_PICKUP)) {
            rawMaxPickup.coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
        } else _maxPickupDistanceFlow.value
        val finalMaxPickupEnabled = if (!isUserCustomized(context, FKEY_MAX_PICKUP_ENABLED)) {
            (data["isMaxPickupDistanceEnabled"] as? Boolean) ?: if (rawMaxPickup != null && rawMaxPickup > 0f) true else current.isMaxPickupDistanceEnabled
        } else current.isMaxPickupDistanceEnabled

        val rawMaxDrop = (data["maxDropDistance"] as? Number)?.toFloat()
        val finalMaxDrop = if (rawMaxDrop != null && !isUserCustomized(context, FKEY_MAX_DROP)) {
            rawMaxDrop.coerceIn(1.0f, 50.0f)
        } else current.maxDropDistance
        val finalMaxDropEnabled = if (!isUserCustomized(context, FKEY_MAX_DROP_ENABLED)) {
            (data["isMaxDropDistanceEnabled"] as? Boolean) ?: current.isMaxDropDistanceEnabled
        } else current.isMaxDropDistanceEnabled

        val rawMinRate = (data["minPerKmRate"] as? Number)?.toFloat()
        val finalMinRate = if (rawMinRate != null && !isUserCustomized(context, FKEY_MIN_RATE)) {
            rawMinRate.coerceIn(1.0f, 100.0f)
        } else current.minPerKmRate
        val finalMinRateEnabled = if (!isUserCustomized(context, FKEY_MIN_RATE_ENABLED)) {
            (data["isMinPerKmRateEnabled"] as? Boolean) ?: current.isMinPerKmRateEnabled
        } else current.isMinPerKmRateEnabled

        val finalClickDelay = if (data.containsKey("clickDelayMs") && !isUserCustomized(context, FKEY_CLICK_DELAY_MS)) {
            (data["clickDelayMs"] as? Number)?.toLong()?.coerceAtLeast(100L) ?: current.clickDelayMs
        } else current.clickDelayMs

        val finalBlacklistEnabled = if (data.containsKey("isDestinationBlacklistEnabled") && !isUserCustomized(context, FKEY_BLACKLIST_ENABLED)) {
            (data["isDestinationBlacklistEnabled"] as? Boolean) ?: current.isDestinationBlacklistEnabled
        } else current.isDestinationBlacklistEnabled

        val finalBlockedList = if (data.containsKey("blockedLocations") && !isUserCustomized(context, FKEY_BLOCKED_LOCATIONS)) {
            (data["blockedLocations"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?.sorted()
                ?: emptyList()
        } else current.blockedLocations

        val finalOverlayEnabled = if ((data.containsKey("isFloatingOverlayEnabled") || data.containsKey("floatingOverlayEnabled")) &&
            !isUserCustomized(context, FKEY_OVERLAY_ENABLED)
        ) {
            (data["isFloatingOverlayEnabled"] as? Boolean) ?: (data["floatingOverlayEnabled"] as? Boolean) ?: current.isFloatingOverlayEnabled
        } else current.isFloatingOverlayEnabled

        val finalVoiceEnabled = if ((data.containsKey("isVoiceAnnouncerEnabled") || data.containsKey("voiceAnnouncerEnabled")) &&
            !isUserCustomized(context, FKEY_VOICE_ENABLED)
        ) {
            (data["isVoiceAnnouncerEnabled"] as? Boolean) ?: (data["voiceAnnouncerEnabled"] as? Boolean) ?: current.isVoiceAnnouncerEnabled
        } else current.isVoiceAnnouncerEnabled

        val finalAutoClickEnabled = if ((data.containsKey("isAutoClickEnabled") || data.containsKey("autoClickEnabled")) &&
            !isUserCustomized(context, FKEY_AUTO_CLICK_ENABLED)
        ) {
            (data["isAutoClickEnabled"] as? Boolean) ?: (data["autoClickEnabled"] as? Boolean) ?: current.isAutoClickEnabled
        } else current.isAutoClickEnabled

        // Update all RAM properties & StateFlows atomically
        autoAcceptEnabled = finalAutoAccept
        minCurrencyThreshold = finalMinFare.toDouble()
        maxCurrencyThreshold = finalMaxFare.toDouble()

        _minFareFlow.value = finalMinFare
        _isMinFareEnabledFlow.value = finalMinFareEnabled
        _maxFareFlow.value = finalMaxFare
        _isMaxFareEnabledFlow.value = finalMaxFareEnabled
        _maxPickupDistanceFlow.value = finalMaxPickup
        _isFloatingOverlayEnabledFlow.value = finalOverlayEnabled
        _isVoiceAnnouncerEnabledFlow.value = finalVoiceEnabled
        _activePassTypeFlow.value = passTypeVal
        val parsedPassTier = PassTier.fromId(passTypeVal)
        val isProPassType = parsedPassTier == null || parsedPassTier.isProPass
        _passExpiryTimestampFlow.value = if (isProPassType) passExpiryVal else 0L
        _isPassActiveFlow.value = isProPassType && passExpiryVal > LicenseManager.getCurrentServerTimeMs()
        _unlockedFeaturesFlow.value = unlockedFeaturesVal
        _lockedFeaturesFlow.value = lockedFeaturesVal
        _isFirestoreSyncedFlow.value = true

        val effectiveAutoAccept = if (context != null) {
            !LicenseManager.isClockTamperingDetected(context) &&
                LicenseManager.isServiceActive(context) &&
                !LicenseManager.isBannedFlow.value &&
                finalAutoAccept
        } else {
            !LicenseManager.isClockTamperingDetected() &&
                !LicenseManager.isBannedFlow.value &&
                finalAutoAccept
        }

        _settingsState.value = current.copy(
            isAutoAcceptEnabled = effectiveAutoAccept,
            isMinFareEnabled = finalMinFareEnabled,
            minFare = finalMinFare,
            isMaxFareEnabled = finalMaxFareEnabled,
            maxFare = finalMaxFare,
            isMaxPickupDistanceEnabled = finalMaxPickupEnabled,
            maxPickupDistance = finalMaxPickup,
            isMaxDropDistanceEnabled = finalMaxDropEnabled,
            maxDropDistance = finalMaxDrop,
            clickDelayMs = finalClickDelay,
            isMinPerKmRateEnabled = finalMinRateEnabled,
            minPerKmRate = finalMinRate,
            isDestinationBlacklistEnabled = finalBlacklistEnabled,
            blockedLocations = finalBlockedList,
            isVoiceAnnouncerEnabled = finalVoiceEnabled,
            isFloatingOverlayEnabled = finalOverlayEnabled,
            isAutoClickEnabled = finalAutoClickEnabled,
            activePassType = passTypeVal,
            passExpiryTimestamp = passExpiryVal,
            unlockedFeatures = unlockedFeaturesVal,
            lockedFeatures = lockedFeaturesVal
        )

        Log.i(
            TAG,
            "Firestore RAM sync: unlocked=${unlockedFeaturesVal.keys}, locked=$lockedFeaturesVal, pass=$passTypeVal, autoAccept=$effectiveAutoAccept"
        )

        if (context != null) {
            mainHandler.post {
                syncOverlayService(context)
            }
        }
    }

    /**
     * Resets server entitlement/lock flows cleanly on sign-out or test reset.
     */
    private fun resetBusinessStateToRestrictedDefaults(context: Context?, clearUserFilterPrefs: Boolean = false) {
        if (clearUserFilterPrefs && context != null) {
            getUserFilterPrefs(context).edit().clear().commit()
        }
        autoAcceptEnabled = RESTRICTED_AUTO_ACCEPT_ENABLED
        minCurrencyThreshold = RESTRICTED_MIN_FARE.toDouble()
        maxCurrencyThreshold = DEFAULT_MAX_FARE.toDouble()

        _minFareFlow.value = RESTRICTED_MIN_FARE
        _isMinFareEnabledFlow.value = DEFAULT_MIN_FARE_ENABLED
        _maxFareFlow.value = DEFAULT_MAX_FARE
        _isMaxFareEnabledFlow.value = DEFAULT_MAX_FARE_ENABLED
        _maxPickupDistanceFlow.value = RESTRICTED_MAX_PICKUP_DISTANCE
        _isFloatingOverlayEnabledFlow.value = DEFAULT_FLOATING_OVERLAY_ENABLED
        _isVoiceAnnouncerEnabledFlow.value = DEFAULT_VOICE_ANNOUNCER_ENABLED
        _activePassTypeFlow.value = null
        _passExpiryTimestampFlow.value = 0L
        _isPassActiveFlow.value = false
        _unlockedFeaturesFlow.value = emptyMap()
        _lockedFeaturesFlow.value = emptySet()

        val current = _settingsState.value
        _settingsState.value = current.copy(
            isAutoAcceptEnabled = RESTRICTED_AUTO_ACCEPT_ENABLED,
            isMinFareEnabled = DEFAULT_MIN_FARE_ENABLED,
            minFare = RESTRICTED_MIN_FARE,
            isMaxFareEnabled = DEFAULT_MAX_FARE_ENABLED,
            maxFare = DEFAULT_MAX_FARE,
            isMaxPickupDistanceEnabled = DEFAULT_MAX_PICKUP_DIST_ENABLED,
            maxPickupDistance = RESTRICTED_MAX_PICKUP_DISTANCE,
            isMaxDropDistanceEnabled = DEFAULT_MAX_DROP_DIST_ENABLED,
            maxDropDistance = DEFAULT_MAX_DROP_DISTANCE,
            clickDelayMs = DEFAULT_CLICK_DELAY_MS,
            isMinPerKmRateEnabled = DEFAULT_MIN_PER_KM_RATE_ENABLED,
            minPerKmRate = DEFAULT_MIN_PER_KM_RATE,
            isDestinationBlacklistEnabled = DEFAULT_DESTINATION_BLACKLIST_ENABLED,
            blockedLocations = emptyList(),
            isVoiceAnnouncerEnabled = DEFAULT_VOICE_ANNOUNCER_ENABLED,
            isFloatingOverlayEnabled = DEFAULT_FLOATING_OVERLAY_ENABLED,
            isAutoClickEnabled = DEFAULT_AUTO_CLICK_ENABLED,
            activePassType = null,
            passExpiryTimestamp = 0L,
            unlockedFeatures = emptyMap(),
            lockedFeatures = emptySet()
        )

        if (context != null) {
            mainHandler.post {
                syncOverlayService(context)
            }
        }
    }

    @VisibleForTesting
    internal fun applyFirestoreSnapshotForTest(context: Context?, data: Map<String, Any?>?) {
        applyRawMapToRam(context, data, docExists = data != null)
    }

    @VisibleForTesting
    internal fun resetBusinessStateForTest(context: Context? = null) {
        stopUserConfigSnapshotListener()
        resetBusinessStateToRestrictedDefaults(context, clearUserFilterPrefs = true)
    }

    /**
     * Synchronizes RAM auth state with FirebaseUser and manages the Firestore snapshot listener.
     * User details and tokens are NEVER stored in SharedPreferences.
     */
    fun updateUserAuthFromFirebase(context: Context, user: FirebaseUser?) {
        val appCtx = context.applicationContext ?: context
        appContextRef = appCtx

        if (user != null && user.uid.isNotBlank()) {
            _userAuthState.value = UserAuthState(
                isLoggedIn = true,
                userEmail = user.email,
                userName = user.displayName,
                photoUrl = user.photoUrl?.toString(),
                uid = user.uid
            )
            startUserConfigSnapshotListener(appCtx, user.uid)
        } else {
            stopUserConfigSnapshotListener()
            _userAuthState.value = UserAuthState(
                isLoggedIn = false,
                userEmail = null,
                userName = null,
                photoUrl = null,
                uid = null
            )
            resetBusinessStateToRestrictedDefaults(appCtx)
        }
    }

    /**
     * Legacy delegation wrapper ensuring state never contradicts FirebaseAuth.
     */
    fun updateUserAuth(
        context: Context,
        isLoggedIn: Boolean,
        email: String?,
        name: String?,
        photoUrl: String?,
        uid: String? = null
    ) {
        val currentFirebaseUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) {
            null
        }
        if (!isLoggedIn || currentFirebaseUser == null) {
            updateUserAuthFromFirebase(context, null)
        } else {
            updateUserAuthFromFirebase(context, currentFirebaseUser)
        }
    }

    fun signOut(context: Context) {
        stopUserConfigSnapshotListener()
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance().signOut()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase sign out error: ${e.message}")
        }
        updateUserAuthFromFirebase(context, null)
    }

    // --- Purely Local UI Preferences (Kept in SharedPreferences) ---

    fun setLoginDismissed(context: Context, dismissed: Boolean) {
        persistLocalUiPref(context, KEY_LOGIN_DISMISSED) {
            putBoolean(KEY_LOGIN_DISMISSED, dismissed)
        }
    }

    fun isLoginDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOGIN_DISMISSED, false)
    }

    fun isLanguageSelected(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_LANGUAGE_SELECTED, false) || prefs.contains(KEY_APP_LANGUAGE)
    }

    fun getAppLanguage(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_APP_LANGUAGE, prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE))
            ?: DEFAULT_VOICE_LANGUAGE
    }

    fun setAppLanguage(context: Context, languageCode: String) {
        val cleanCode = languageCode.trim().lowercase(Locale.ROOT)
        _isLanguageSelectedFlow.value = true
        _appLanguageFlow.value = cleanCode
        _settingsState.value = _settingsState.value.copy(voiceLanguage = cleanCode)
        persistLocalUiPref(context, KEY_APP_LANGUAGE) {
            putBoolean(KEY_LANGUAGE_SELECTED, true)
            putString(KEY_APP_LANGUAGE, cleanCode)
            putString(KEY_VOICE_LANGUAGE, cleanCode)
        }
        try {
            TTSManager.setLanguage(context, cleanCode)
            TTSManager.applySettings(context)
            TTSManager.speakGreeting(context, cleanCode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update TTS settings on language change: ${e.message}")
        }
    }

    fun getVoiceLanguage(context: Context): String {
        return getAppLanguage(context)
    }

    fun setVoiceLanguage(context: Context, language: String) {
        setAppLanguage(context, language)
    }

    fun isOnboardingCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        _isOnboardingCompletedFlow.value = completed
        persistLocalUiPref(context, KEY_ONBOARDING_COMPLETED) {
            putBoolean(KEY_ONBOARDING_COMPLETED, completed)
        }
    }

    fun isHomeTutorialCompleted(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HOME_TUTORIAL_COMPLETED, false)
    }

    fun setHomeTutorialCompleted(context: Context, completed: Boolean) {
        _isHomeTutorialCompletedFlow.value = completed
        persistLocalUiPref(context, KEY_HOME_TUTORIAL_COMPLETED) {
            putBoolean(KEY_HOME_TUTORIAL_COMPLETED, completed)
        }
    }

    fun getSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        _settingsState.value = _settingsState.value.copy(speechRate = rate)
        persistLocalUiPref(context, KEY_SPEECH_RATE) {
            putFloat(KEY_SPEECH_RATE, rate)
        }
    }

    fun getSpeechPitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)
    }

    fun setSpeechPitch(context: Context, pitch: Float) {
        _settingsState.value = _settingsState.value.copy(speechPitch = pitch)
        persistLocalUiPref(context, KEY_SPEECH_PITCH) {
            putFloat(KEY_SPEECH_PITCH, pitch)
        }
    }

    // --- Subscription & Pass Expiry Management (100% RAM-Only, Server-Driven, Read-Only) ---

    private fun showAdminManagedToast(context: Context?) {
        val ctx = context ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, "Settings are managed by Admin app", Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post {
                Toast.makeText(ctx, "Settings are managed by Admin app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun getPassExpiryTimestamp(context: Context? = null): Long {
        val ramExpiry = _passExpiryTimestampFlow.value
        return if (ramExpiry > 0L) ramExpiry else PassManager.getPassExpiryTimestamp(context)
    }

    fun setPassExpiryTimestamp(context: Context? = null, expiryTimestamp: Long, tierId: String? = null) {
        Log.w(TAG, "setPassExpiryTimestamp blocked: Settings are managed by Admin app.")
        showAdminManagedToast(context)
    }

    fun isPassActive(context: Context? = null): Boolean {
        return _isPassActiveFlow.value || PassManager.isPassActive(context)
    }

    fun extendPass(context: Context? = null, durationMs: Long, tierId: String? = null): Long {
        Log.w(TAG, "extendPass blocked: Settings are managed by Admin app.")
        showAdminManagedToast(context)
        return getPassExpiryTimestamp(context)
    }

    fun getActivePassTierId(context: Context? = null): String? {
        return _activePassTypeFlow.value ?: LicenseManager.activePassInfo.value?.passTier?.id
    }

    fun clearPass(context: Context? = null) {
        Log.w(TAG, "clearPass blocked: Settings are managed by Admin app.")
        showAdminManagedToast(context)
    }

    private fun showToast(context: Context?, msg: String) {
        val ctx = context ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFeatureLockedFeedback(context: Context?, featureId: String, label: String): Boolean {
        if (LicenseManager.isFeatureLockedByAdmin(featureId)) {
            Log.w(TAG, "Feature '$featureId' is locked by Admin.")
            showToast(context, "$label is locked by Admin")
            return false
        }
        if (!LicenseManager.isFeatureUnlocked(featureId)) {
            Log.w(TAG, "Feature '$featureId' requires Pro Pass or Points unlock.")
            showToast(context, "Unlock $label with Points or Pro Pass")
            return false
        }
        return true
    }

    // --- Synchronous In-Memory Business Rules Getters (<1ms RAM Lookup, ZERO Network/Disk) ---
    // Security: Every getter enforces server-authoritative LicenseManager lock/unlock status!

    fun isAutoAcceptEnabled(context: Context): Boolean {
        if (DeviceUtils.isRuntimeHookOrDebuggerDetected(context)) {
            Log.w(TAG, "isAutoAcceptEnabled: Runtime hooking/debugger detected! Auto-accept disabled.")
            return false
        }
        if (LicenseManager.isClockTamperingDetected(context)) {
            Log.w(TAG, "isAutoAcceptEnabled: Clock tampering detected! Auto-accept disabled.")
            return false
        }
        return LicenseManager.isServiceActive(context) && !LicenseManager.isBannedFlow.value && autoAcceptEnabled
    }

    fun setAutoAcceptEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            if (DeviceUtils.isRuntimeHookOrDebuggerDetected(context)) {
                showToast(context, "Security check failed")
                return
            }
            if (!LicenseManager.isServiceActive(context) || LicenseManager.isBannedFlow.value) {
                showToast(context, "Service offline or account restricted by Admin")
                return
            }
        }
        autoAcceptEnabled = enabled
        _settingsState.value = _settingsState.value.copy(isAutoAcceptEnabled = enabled)
        persistUserFilterPref(context, FKEY_AUTO_ACCEPT) { putBoolean(FKEY_AUTO_ACCEPT, enabled) }
        syncOverlayService(context)
    }

    fun isVoiceAnnouncerEnabled(context: Context): Boolean =
        _settingsState.value.isVoiceAnnouncerEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)

    fun setVoiceAnnouncerEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.TTS_VOICE, "Voice Announcements")) return
        _isVoiceAnnouncerEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isVoiceAnnouncerEnabled = enabled)
        persistUserFilterPref(context, FKEY_VOICE_ENABLED) { putBoolean(FKEY_VOICE_ENABLED, enabled) }
    }

    fun isMinFareEnabled(context: Context): Boolean =
        _isMinFareEnabledFlow.value && LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)

    fun setMinFareEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.MIN_FARE, "Minimum Fare Filter")) return
        _isMinFareEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isMinFareEnabled = enabled)
        persistUserFilterPref(context, FKEY_MIN_FARE_ENABLED) { putBoolean(FKEY_MIN_FARE_ENABLED, enabled) }
    }

    fun isMaxFareEnabled(context: Context): Boolean =
        _isMaxFareEnabledFlow.value && LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE)

    fun setMaxFareEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.MAX_FARE, "Maximum Fare Filter")) return
        _isMaxFareEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isMaxFareEnabled = enabled)
        persistUserFilterPref(context, FKEY_MAX_FARE_ENABLED) { putBoolean(FKEY_MAX_FARE_ENABLED, enabled) }
    }

    fun isMaxPickupDistanceEnabled(context: Context): Boolean =
        _settingsState.value.isMaxPickupDistanceEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)

    fun setMaxPickupDistanceEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.MAX_PICKUP_DISTANCE, "Pickup Distance Filter")) return
        _settingsState.value = _settingsState.value.copy(isMaxPickupDistanceEnabled = enabled)
        persistUserFilterPref(context, FKEY_MAX_PICKUP_ENABLED) { putBoolean(FKEY_MAX_PICKUP_ENABLED, enabled) }
    }

    fun isMaxDropDistanceEnabled(context: Context): Boolean =
        _settingsState.value.isMaxDropDistanceEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE)

    fun setMaxDropDistanceEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.MAX_DROP_DISTANCE, "Drop Distance Filter")) return
        _settingsState.value = _settingsState.value.copy(isMaxDropDistanceEnabled = enabled)
        persistUserFilterPref(context, FKEY_MAX_DROP_ENABLED) { putBoolean(FKEY_MAX_DROP_ENABLED, enabled) }
    }

    fun getMaxDropDistance(context: Context): Float = _settingsState.value.maxDropDistance

    fun setMaxDropDistance(context: Context, value: Float) {
        if (!showFeatureLockedFeedback(context, FeatureIds.MAX_DROP_DISTANCE, "Drop Distance Filter")) return
        val clamped = value.coerceIn(1.0f, 50.0f)
        _settingsState.value = _settingsState.value.copy(maxDropDistance = clamped)
        persistUserFilterPref(context, FKEY_MAX_DROP) { putFloat(FKEY_MAX_DROP, clamped) }
    }

    fun isMinPerKmRateEnabled(context: Context): Boolean =
        _settingsState.value.isMinPerKmRateEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE)

    fun setMinPerKmRateEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.MIN_PER_KM_RATE, "Minimum Per-KM Filter")) return
        _settingsState.value = _settingsState.value.copy(isMinPerKmRateEnabled = enabled)
        persistUserFilterPref(context, FKEY_MIN_RATE_ENABLED) { putBoolean(FKEY_MIN_RATE_ENABLED, enabled) }
    }

    fun getMinPerKmRate(context: Context): Float = _settingsState.value.minPerKmRate

    fun setMinPerKmRate(context: Context, rate: Float) {
        if (!showFeatureLockedFeedback(context, FeatureIds.MIN_PER_KM_RATE, "Minimum Per-KM Filter")) return
        val clamped = rate.coerceIn(1.0f, 100.0f)
        _settingsState.value = _settingsState.value.copy(minPerKmRate = clamped)
        persistUserFilterPref(context, FKEY_MIN_RATE) { putFloat(FKEY_MIN_RATE, clamped) }
    }

    fun isDestinationBlacklistEnabled(context: Context): Boolean =
        _settingsState.value.isDestinationBlacklistEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST)

    fun setDestinationBlacklistEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.DESTINATION_BLACKLIST, "Destination Blacklist")) return
        _settingsState.value = _settingsState.value.copy(isDestinationBlacklistEnabled = enabled)
        persistUserFilterPref(context, FKEY_BLACKLIST_ENABLED) { putBoolean(FKEY_BLACKLIST_ENABLED, enabled) }
    }

    fun getBlockedLocations(context: Context): List<String> = _settingsState.value.blockedLocations

    fun setBlockedLocations(context: Context, locations: List<String>) {
        if (!showFeatureLockedFeedback(context, FeatureIds.DESTINATION_BLACKLIST, "Destination Blacklist")) return
        val cleanList = locations.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        _settingsState.value = _settingsState.value.copy(blockedLocations = cleanList)
        persistUserFilterPref(context, FKEY_BLOCKED_LOCATIONS) {
            putString(FKEY_BLOCKED_LOCATIONS, cleanList.joinToString("|"))
        }
    }

    fun addBlockedLocation(context: Context, location: String) {
        if (!showFeatureLockedFeedback(context, FeatureIds.DESTINATION_BLACKLIST, "Destination Blacklist")) return
        val clean = location.trim()
        if (clean.isEmpty()) return
        val updated = (_settingsState.value.blockedLocations + clean).distinct().sorted()
        setBlockedLocations(context, updated)
    }

    fun removeBlockedLocation(context: Context, location: String) {
        if (!showFeatureLockedFeedback(context, FeatureIds.DESTINATION_BLACKLIST, "Destination Blacklist")) return
        val updated = _settingsState.value.blockedLocations.filterNot { it.equals(location.trim(), ignoreCase = true) }
        setBlockedLocations(context, updated)
    }

    fun isFloatingOverlayEnabled(context: Context): Boolean =
        _settingsState.value.isFloatingOverlayEnabled && LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY)

    fun setFloatingOverlayEnabled(context: Context, enabled: Boolean) {
        if (enabled && !showFeatureLockedFeedback(context, FeatureIds.FLOATING_OVERLAY, "Floating Overlay")) return
        _isFloatingOverlayEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isFloatingOverlayEnabled = enabled)
        persistUserFilterPref(context, FKEY_OVERLAY_ENABLED) { putBoolean(FKEY_OVERLAY_ENABLED, enabled) }
        syncOverlayService(context)
    }

    fun isAutoClickEnabled(context: Context): Boolean =
        _settingsState.value.isAutoClickEnabled && LicenseManager.hasAnyAutoAcceptCapability()

    fun setAutoClickEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            if (LicenseManager.isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK)) {
                showToast(context, "Auto-Click is locked by Admin")
                return
            }
            if (!LicenseManager.hasAnyAutoAcceptCapability()) {
                showToast(context, "Unlock filters with Points or buy Daily/Weekly/Monthly Pass")
                return
            }
        }
        _settingsState.value = _settingsState.value.copy(isAutoClickEnabled = enabled)
        persistUserFilterPref(context, FKEY_AUTO_CLICK_ENABLED) { putBoolean(FKEY_AUTO_CLICK_ENABLED, enabled) }
    }

    fun toggleAutoClick(context: Context): Boolean {
        val next = !isAutoClickEnabled(context)
        setAutoClickEnabled(context, next)
        return isAutoClickEnabled(context)
    }

    // --- Last Accepted Fare Communication ---
    fun getLastAcceptedFare(): Double? = _lastAcceptedFareFlow.value

    fun updateLastAcceptedFare(context: Context, fare: Double) {
        _lastAcceptedFareFlow.value = fare
        _settingsState.value = _settingsState.value.copy(lastAcceptedFare = fare)
        FloatingOverlayService.updateFare(context, fare)
    }

    fun clearSessionFare() {
        _lastAcceptedFareFlow.value = null
        _settingsState.value = _settingsState.value.copy(lastAcceptedFare = null)
    }

    /**
     * Synchronizes FloatingOverlayService lifecycle with RAM settings, locks, & overlay permission.
     */
    fun syncOverlayService(context: Context) {
        val masterOn = isAutoAcceptEnabled(context)
        val overlayOn = isFloatingOverlayEnabled(context)
        val canDraw = PermissionUtils.canDrawOverlays(context)

        if (masterOn && overlayOn && canDraw) {
            FloatingOverlayService.start(context)
        } else {
            FloatingOverlayService.stop(context)
        }
    }

    fun applyPreset(context: Context, presetName: String) {
        val minFareUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)
        val maxFareUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE)
        val pickupUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)
        val dropUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE)
        val rateUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE)

        if (!minFareUnlocked && !maxFareUnlocked && !pickupUnlocked && !dropUnlocked && !rateUnlocked) {
            showToast(context, "Unlock filters or activate Pro Pass to apply presets")
            return
        }

        when (presetName.trim().lowercase(Locale.ROOT)) {
            "peak" -> {
                if (minFareUnlocked) { setMinFareEnabled(context, true); setMinFare(context, 100f) }
                if (maxFareUnlocked) { setMaxFareEnabled(context, false) }
                if (pickupUnlocked) { setMaxPickupDistanceEnabled(context, true); setMaxPickupDistance(context, 1.5f) }
                if (dropUnlocked) { setMaxDropDistanceEnabled(context, true); setMaxDropDistance(context, 8.0f) }
                if (rateUnlocked) { setMinPerKmRateEnabled(context, true); setMinPerKmRate(context, 12.0f) }
            }
            "short" -> {
                if (minFareUnlocked) { setMinFareEnabled(context, true); setMinFare(context, 40f) }
                if (maxFareUnlocked) { setMaxFareEnabled(context, true); setMaxFare(context, 250f) }
                if (pickupUnlocked) { setMaxPickupDistanceEnabled(context, true); setMaxPickupDistance(context, 1.0f) }
                if (dropUnlocked) { setMaxDropDistanceEnabled(context, true); setMaxDropDistance(context, 4.0f) }
                if (rateUnlocked) { setMinPerKmRateEnabled(context, false) }
            }
            "high" -> {
                if (minFareUnlocked) { setMinFareEnabled(context, true); setMinFare(context, 150f) }
                if (maxFareUnlocked) { setMaxFareEnabled(context, false) }
                if (pickupUnlocked) { setMaxPickupDistanceEnabled(context, true); setMaxPickupDistance(context, 3.0f) }
                if (dropUnlocked) { setMaxDropDistanceEnabled(context, false) }
                if (rateUnlocked) { setMinPerKmRateEnabled(context, true); setMinPerKmRate(context, 15.0f) }
            }
            else -> { // "default"
                if (minFareUnlocked) { setMinFareEnabled(context, true); setMinFare(context, DEFAULT_MIN_FARE) }
                if (maxFareUnlocked) { setMaxFareEnabled(context, false); setMaxFare(context, DEFAULT_MAX_FARE) }
                if (pickupUnlocked) { setMaxPickupDistanceEnabled(context, true); setMaxPickupDistance(context, DEFAULT_MAX_PICKUP_DISTANCE) }
                if (dropUnlocked) { setMaxDropDistanceEnabled(context, false); setMaxDropDistance(context, DEFAULT_MAX_DROP_DISTANCE) }
                if (rateUnlocked) { setMinPerKmRateEnabled(context, false); setMinPerKmRate(context, DEFAULT_MIN_PER_KM_RATE) }
            }
        }
    }

    // --- Fare Range Validation Functions ---

    fun isValidMinFare(candidateMin: Float, currentMax: Float = _maxFareFlow.value): Boolean {
        return candidateMin in MIN_FARE_RANGE_START..MIN_FARE_RANGE_END &&
            (!_isMaxFareEnabledFlow.value || candidateMin <= currentMax)
    }

    fun isValidMaxFare(candidateMax: Float, currentMin: Float = _minFareFlow.value): Boolean {
        return candidateMax in MAX_FARE_RANGE_START..MAX_FARE_RANGE_END &&
            (!_isMinFareEnabledFlow.value || candidateMax >= currentMin)
    }

    fun validateMinFare(value: Float, currentMax: Float = _maxFareFlow.value): Float {
        val clamped = value.coerceIn(MIN_FARE_RANGE_START, MIN_FARE_RANGE_END)
        return if (_isMaxFareEnabledFlow.value && clamped > currentMax) {
            currentMax.coerceAtLeast(MIN_FARE_RANGE_START)
        } else {
            clamped
        }
    }

    fun validateMaxFare(value: Float, currentMin: Float = _minFareFlow.value): Float {
        val clamped = value.coerceIn(MAX_FARE_RANGE_START, MAX_FARE_RANGE_END)
        return if (_isMinFareEnabledFlow.value && clamped < currentMin) {
            currentMin.coerceAtMost(MAX_FARE_RANGE_END)
        } else {
            clamped
        }
    }

    fun getMinFare(context: Context): Float = _minFareFlow.value

    fun setMinFare(context: Context, value: Float) {
        if (!showFeatureLockedFeedback(context, FeatureIds.MIN_FARE, "Minimum Fare Filter")) return
        val clamped = value.coerceIn(MIN_FARE_RANGE_START, MIN_FARE_RANGE_END)
        minCurrencyThreshold = clamped.toDouble()
        _minFareFlow.value = clamped
        _settingsState.value = _settingsState.value.copy(minFare = clamped)
        persistUserFilterPref(context, FKEY_MIN_FARE) { putFloat(FKEY_MIN_FARE, clamped) }
    }

    fun getMaxFare(context: Context): Float = _maxFareFlow.value

    fun setMaxFare(context: Context, value: Float) {
        if (!showFeatureLockedFeedback(context, FeatureIds.MAX_FARE, "Maximum Fare Filter")) return
        val clamped = value.coerceIn(MAX_FARE_RANGE_START, MAX_FARE_RANGE_END)
        maxCurrencyThreshold = clamped.toDouble()
        _maxFareFlow.value = clamped
        _settingsState.value = _settingsState.value.copy(maxFare = clamped)
        persistUserFilterPref(context, FKEY_MAX_FARE) { putFloat(FKEY_MAX_FARE, clamped) }
    }

    fun getMaxPickupDistance(context: Context): Float = _settingsState.value.maxPickupDistance

    fun setMaxPickupDistance(context: Context, value: Float) {
        if (!showFeatureLockedFeedback(context, FeatureIds.MAX_PICKUP_DISTANCE, "Pickup Distance Filter")) return
        val clamped = value.coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
        _maxPickupDistanceFlow.value = clamped
        _settingsState.value = _settingsState.value.copy(maxPickupDistance = clamped)
        persistUserFilterPref(context, FKEY_MAX_PICKUP) { putFloat(FKEY_MAX_PICKUP, clamped) }
    }

    fun getClickDelayMs(context: Context): Long = _settingsState.value.clickDelayMs

    fun setClickDelayMs(context: Context, delay: Long) {
        if (LicenseManager.isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK)) {
            showToast(context, "Auto-Click is locked by Admin")
            return
        }
        val clamped = delay.coerceIn(100L, 5000L)
        _settingsState.value = _settingsState.value.copy(clickDelayMs = clamped)
        persistUserFilterPref(context, FKEY_CLICK_DELAY_MS) { putLong(FKEY_CLICK_DELAY_MS, clamped) }
    }

    // Legacy method aliases
    fun isServiceEnabled(context: Context): Boolean = isAutoAcceptEnabled(context)
    fun setServiceEnabled(context: Context, enabled: Boolean) = setAutoAcceptEnabled(context, enabled)
    fun getMinValue(context: Context): Float = getMinFare(context)
    fun setMinValue(context: Context, value: Float) = setMinFare(context, value)
    fun getMaxDistance(context: Context): Float = getMaxPickupDistance(context)
    fun setMaxDistance(context: Context, value: Float) = setMaxPickupDistance(context, value)
    fun getMinPrice(context: Context): Float = getMinFare(context)
    fun setMinPrice(context: Context, value: Float) = setMinFare(context, value)
    fun getMaxPrice(context: Context): Float = getMaxFare(context)
    fun setMaxPrice(context: Context, value: Float) = setMaxFare(context, value)
    fun getSelectedLanguage(context: Context): String = getVoiceLanguage(context)
    fun setSelectedLanguage(context: Context, lang: String) = setVoiceLanguage(context, lang)

    /**
     * Appends an activity log entry to the reactive live feed (purely volatile in-memory).
     */
    fun addLog(
        title: String,
        message: String,
        severity: LogSeverity = LogSeverity.INFO,
        evaluation: RideEvaluation? = null
    ) {
        val entry = ActivityLogEntry(
            timeFormatted = timeFormat.format(Date()),
            title = title,
            message = message,
            severity = severity,
            evaluation = evaluation
        )
        val current = _logsFlow.value.toMutableList()
        current.add(0, entry)
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        _logsFlow.value = current
    }

    fun clearLogs() {
        _logsFlow.value = emptyList()
    }

    /**
     * Records an evaluated order into volatile in-memory session data and persists
     * structured order history entries for the Order History screen.
     */
    fun recordEvaluation(context: Context, evaluation: RideEvaluation) {
        appScope.launch {
            evaluationMutex.withLock {
                val current = _recentEvaluationsFlow.value.toMutableList()
                current.add(0, evaluation)
                if (current.size > 100) {
                    current.removeAt(current.lastIndex)
                }
                _recentEvaluationsFlow.value = current

                try {
                    val offer = evaluation.offer
                    val target = offer.targetOffer
                    val pickupLoc = target?.pickupLocation?.ifBlank { offer.pickupLocation } ?: offer.pickupLocation
                    val dropLoc = target?.dropLocation?.ifBlank { offer.dropLocation } ?: offer.dropLocation

                    val orderEntity = OrderEntity(
                        timestamp = System.currentTimeMillis(),
                        fare = target?.fare ?: offer.totalFare ?: offer.baseFare ?: 0.0,
                        pickupDistanceKm = target?.pickupDistance?.takeIf { it > 0.0 } ?: offer.pickupDistanceKm,
                        dropDistanceKm = target?.dropDistance?.takeIf { it > 0.0 } ?: offer.dropDistanceKm,
                        pickupLocation = pickupLoc,
                        dropLocation = dropLoc,
                        isAccepted = evaluation.isAccepted,
                        decisionReason = evaluation.decisionReason,
                        rawText = offer.rawText
                    )
                    OrderRepository.getInstance(context).insertOrder(orderEntity)
                } catch (e: Exception) {
                    Log.w(TAG, "Order persistence to Room skipped: ${e.message}")
                }
            }
        }
    }

    fun getRecentEvaluations(): List<RideEvaluation> = _recentEvaluationsFlow.value

    fun clearEvaluations() {
        _recentEvaluationsFlow.value = emptyList()
    }

    /**
     * Inspects system settings to check if our Accessibility Service is currently active.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        if (MyAccessibilityService.isServiceRunning) {
            return true
        }

        val expectedService1 = "${context.packageName}/${MyAccessibilityService::class.java.canonicalName}"
        val expectedService2 = "${context.packageName}/${SmartTextService::class.java.canonicalName}"
        val expectedService3 = "${context.packageName}/${RapidoAccessibilityService::class.java.canonicalName}"
        val expectedShort1 = "${context.packageName}/.MyAccessibilityService"
        val expectedShort2 = "${context.packageName}/.SmartTextService"
        val expectedShort3 = "${context.packageName}/.RapidoAccessibilityService"

        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            while (colonSplitter.hasNext()) {
                val service = colonSplitter.next()
                if (service.equals(expectedService1, ignoreCase = true) ||
                    service.equals(expectedService2, ignoreCase = true) ||
                    service.equals(expectedService3, ignoreCase = true) ||
                    service.equals(expectedShort1, ignoreCase = true) ||
                    service.equals(expectedShort2, ignoreCase = true) ||
                    service.equals(expectedShort3, ignoreCase = true)
                ) {
                    return true
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    /**
     * Checks if a specific Accessibility Service class is currently active.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        if (serviceClass == MyAccessibilityService::class.java && MyAccessibilityService.isServiceRunning) {
            return true
        }
        val expectedCanonical = "${context.packageName}/${serviceClass.canonicalName}"
        val expectedShort = "${context.packageName}/.${serviceClass.simpleName}"

        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)

            while (colonSplitter.hasNext()) {
                val service = colonSplitter.next()
                if (service.equals(expectedCanonical, ignoreCase = true) ||
                    service.equals(expectedShort, ignoreCase = true)
                ) {
                    return true
                }
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

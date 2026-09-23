package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val isAutoAcceptEnabled: Boolean = true,
    val isMinFareEnabled: Boolean = true,
    val minFare: Float = AppSettings.DEFAULT_MIN_FARE,
    val isMaxFareEnabled: Boolean = true,
    val maxFare: Float = AppSettings.DEFAULT_MAX_FARE,
    val isMaxPickupDistanceEnabled: Boolean = true,
    val maxPickupDistance: Float = 3.0f,
    val isMaxDropDistanceEnabled: Boolean = false,
    val maxDropDistance: Float = 15.0f,
    val clickDelayMs: Long = 500L,
    val isVoiceAnnouncerEnabled: Boolean = true,
    val voiceLanguage: String = "en",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val isFloatingOverlayEnabled: Boolean = false,
    val isAutoClickEnabled: Boolean = true,
    val lastAcceptedFare: Double? = null
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
    val idToken: String? = null
)

object AppSettings {
    const val PREFS_NAME = "captain_auto_accept_prefs"

    // Primary preference keys
    const val KEY_AUTO_ACCEPT_ENABLED = "key_auto_accept_enabled"
    const val KEY_MIN_FARE_ENABLED = "key_min_fare_enabled"
    const val KEY_MIN_FARE = "key_min_fare"
    const val KEY_MAX_FARE_ENABLED = "key_max_fare_enabled"
    const val KEY_MAX_FARE = "key_max_fare"
    const val KEY_MAX_PICKUP_DIST_ENABLED = "key_max_pickup_dist_enabled"
    const val KEY_MAX_PICKUP_DISTANCE = "key_max_pickup_distance"
    const val KEY_MAX_DROP_DIST_ENABLED = "key_max_drop_dist_enabled"
    const val KEY_MAX_DROP_DISTANCE = "key_max_drop_distance"
    const val KEY_CLICK_DELAY_MS = "key_click_delay_ms"
    const val KEY_VOICE_ANNOUNCER_ENABLED = "key_voice_announcer_enabled"
    const val KEY_VOICE_LANGUAGE = "key_voice_language"
    const val KEY_SPEECH_RATE = "key_speech_rate"
    const val KEY_SPEECH_PITCH = "key_speech_pitch"
    const val KEY_FLOATING_OVERLAY_ENABLED = "key_floating_overlay_enabled"
    const val KEY_AUTO_CLICK_ENABLED = "key_auto_click_enabled"

    // User Authentication Keys
    const val KEY_USER_IS_LOGGED_IN = "key_user_is_logged_in"
    const val KEY_USER_EMAIL = "key_user_email"
    const val KEY_USER_NAME = "key_user_name"
    const val KEY_USER_PHOTO_URL = "key_user_photo_url"
    const val KEY_USER_ID_TOKEN = "key_user_id_token"
    const val KEY_LOGIN_DISMISSED = "key_login_dismissed"

    // Subscription & Pass Management Keys
    const val KEY_PASS_EXPIRY_TIMESTAMP = "pass_expiry_timestamp"
    const val KEY_ACTIVE_PASS_TIER_ID = "key_active_pass_tier_id"

    // Backward-compatibility keys
    const val KEY_SERVICE_ENABLED = "key_service_enabled"
    const val KEY_MIN_VALUE = "key_min_value"
    const val KEY_MAX_VALUE = "key_max_value"
    const val KEY_MAX_DISTANCE = "key_max_distance"

    // Broadcast actions for service logging
    const val ACTION_ACCESSIBILITY_LOG = "com.example.ACCESSIBILITY_LOG"
    const val EXTRA_LOG_MESSAGE = "extra_log_message"

    // Baseline Fare Range Constants
    const val MIN_FARE_RANGE_START = 20.0f
    const val MIN_FARE_RANGE_END = 500.0f
    const val DEFAULT_MIN_FARE = 50.0f

    const val MAX_FARE_RANGE_START = 100.0f
    const val MAX_FARE_RANGE_END = 2000.0f
    const val DEFAULT_MAX_FARE = 500.0f

    // Other configuration defaults
    const val DEFAULT_MAX_PICKUP_DISTANCE = 3.0f
    const val DEFAULT_MAX_DROP_DISTANCE = 15.0f
    const val MIN_PICKUP_DISTANCE_KM = 0.0f
    const val MAX_PICKUP_DISTANCE_KM = 3.0f
    const val DEFAULT_AUTO_ACCEPT_ENABLED = true
    const val DEFAULT_MIN_FARE_ENABLED = true
    const val DEFAULT_MAX_FARE_ENABLED = true
    const val DEFAULT_MAX_PICKUP_DIST_ENABLED = true
    const val DEFAULT_MAX_DROP_DIST_ENABLED = false
    const val DEFAULT_CLICK_DELAY_MS = 500L
    const val DEFAULT_VOICE_ANNOUNCER_ENABLED = true
    const val DEFAULT_VOICE_LANGUAGE = "en"
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val DEFAULT_SPEECH_PITCH = 1.0f
    const val DEFAULT_FLOATING_OVERLAY_ENABLED = false
    const val DEFAULT_AUTO_CLICK_ENABLED = true

    // Reactive StateFlows for granular Fare observation
    private val _minFareFlow = MutableStateFlow(DEFAULT_MIN_FARE)
    val minFare: StateFlow<Float> = _minFareFlow.asStateFlow()

    private val _isMinFareEnabledFlow = MutableStateFlow(DEFAULT_MIN_FARE_ENABLED)
    val isMinFareEnabled: StateFlow<Boolean> = _isMinFareEnabledFlow.asStateFlow()

    private val _maxFareFlow = MutableStateFlow(DEFAULT_MAX_FARE)
    val maxFare: StateFlow<Float> = _maxFareFlow.asStateFlow()

    private val _isMaxFareEnabledFlow = MutableStateFlow(DEFAULT_MAX_FARE_ENABLED)
    val isMaxFareEnabled: StateFlow<Boolean> = _isMaxFareEnabledFlow.asStateFlow()

    // In-memory properties for legacy access
    var minCurrencyThreshold: Double = DEFAULT_MIN_FARE.toDouble()
    var maxCurrencyThreshold: Double = DEFAULT_MAX_FARE.toDouble()
    var autoAcceptEnabled: Boolean = true
    val minPrice: Double get() = minCurrencyThreshold
    val maxPrice: Double get() = maxCurrencyThreshold
    val maxDistance: Double get() = _settingsState.value.maxPickupDistance.toDouble()
    val speechRate: Float get() = _settingsState.value.speechRate
    val speechPitch: Float get() = _settingsState.value.speechPitch
    val selectedLanguage: String get() = _settingsState.value.voiceLanguage

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Reactive StateFlow for UI consumption
    private val _settingsState = MutableStateFlow(
        SettingsState(
            isAutoAcceptEnabled = DEFAULT_AUTO_ACCEPT_ENABLED,
            minFare = DEFAULT_MIN_FARE,
            maxFare = DEFAULT_MAX_FARE,
            maxPickupDistance = DEFAULT_MAX_PICKUP_DISTANCE,
            clickDelayMs = DEFAULT_CLICK_DELAY_MS,
            isVoiceAnnouncerEnabled = DEFAULT_VOICE_ANNOUNCER_ENABLED,
            voiceLanguage = DEFAULT_VOICE_LANGUAGE,
            isFloatingOverlayEnabled = DEFAULT_FLOATING_OVERLAY_ENABLED,
            isAutoClickEnabled = DEFAULT_AUTO_CLICK_ENABLED,
            lastAcceptedFare = null
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
                message = "Monitoring service loaded. Configure thresholds and enable Accessibility.",
                severity = LogSeverity.INFO
            )
        )
    )
    val logsFlow: StateFlow<List<ActivityLogEntry>> = _logsFlow.asStateFlow()

    private val _userAuthState = MutableStateFlow(UserAuthState())
    val userAuthState: StateFlow<UserAuthState> = _userAuthState.asStateFlow()

    // Reactive StateFlows for Subscription Pass Expiry
    private val _passExpiryTimestampFlow = MutableStateFlow(0L)
    val passExpiryTimestampFlow: StateFlow<Long> = _passExpiryTimestampFlow.asStateFlow()

    private val _isPassActiveFlow = MutableStateFlow(false)
    val isPassActiveFlow: StateFlow<Boolean> = _isPassActiveFlow.asStateFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes state from SharedPreferences.
     */
    fun init(context: Context) {
        val prefs = getPrefs(context)
        val enabled = prefs.getBoolean(KEY_AUTO_ACCEPT_ENABLED, prefs.getBoolean(KEY_SERVICE_ENABLED, DEFAULT_AUTO_ACCEPT_ENABLED))
        val minFareEnabled = prefs.getBoolean(KEY_MIN_FARE_ENABLED, DEFAULT_MIN_FARE_ENABLED)
        val minFare = prefs.getFloat(KEY_MIN_FARE, prefs.getFloat(KEY_MIN_VALUE, DEFAULT_MIN_FARE))
        val maxFareEnabled = prefs.getBoolean(KEY_MAX_FARE_ENABLED, DEFAULT_MAX_FARE_ENABLED)
        val maxFare = prefs.getFloat(KEY_MAX_FARE, prefs.getFloat(KEY_MAX_VALUE, DEFAULT_MAX_FARE))
        val pickupDistEnabled = prefs.getBoolean(KEY_MAX_PICKUP_DIST_ENABLED, DEFAULT_MAX_PICKUP_DIST_ENABLED)
        val rawMaxDist = prefs.getFloat(KEY_MAX_PICKUP_DISTANCE, prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE))
        val maxDist = rawMaxDist.coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
        val dropDistEnabled = prefs.getBoolean(KEY_MAX_DROP_DIST_ENABLED, DEFAULT_MAX_DROP_DIST_ENABLED)
        val dropDist = prefs.getFloat(KEY_MAX_DROP_DISTANCE, DEFAULT_MAX_DROP_DISTANCE)
        val delay = prefs.getLong(KEY_CLICK_DELAY_MS, DEFAULT_CLICK_DELAY_MS)
        val voiceEnabled = prefs.getBoolean(KEY_VOICE_ANNOUNCER_ENABLED, DEFAULT_VOICE_ANNOUNCER_ENABLED)
        val voiceLang = prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE) ?: DEFAULT_VOICE_LANGUAGE
        val speechRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        val speechPitch = prefs.getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)
        val overlayEnabled = prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, DEFAULT_FLOATING_OVERLAY_ENABLED)
        val autoClickEnabled = prefs.getBoolean(KEY_AUTO_CLICK_ENABLED, DEFAULT_AUTO_CLICK_ENABLED)

        val userLoggedIn = prefs.getBoolean(KEY_USER_IS_LOGGED_IN, false)
        val userEmail = prefs.getString(KEY_USER_EMAIL, null)
        val userName = prefs.getString(KEY_USER_NAME, null)
        val userPhoto = prefs.getString(KEY_USER_PHOTO_URL, null)
        val userIdToken = prefs.getString(KEY_USER_ID_TOKEN, null)
        _userAuthState.value = UserAuthState(
            isLoggedIn = userLoggedIn,
            userEmail = userEmail,
            userName = userName,
            photoUrl = userPhoto,
            idToken = userIdToken
        )

        minCurrencyThreshold = minFare.toDouble()
        maxCurrencyThreshold = maxFare.toDouble()
        autoAcceptEnabled = enabled

        _minFareFlow.value = minFare
        _isMinFareEnabledFlow.value = minFareEnabled
        _maxFareFlow.value = maxFare
        _isMaxFareEnabledFlow.value = maxFareEnabled

        _settingsState.value = SettingsState(
            isAutoAcceptEnabled = enabled,
            isMinFareEnabled = minFareEnabled,
            minFare = minFare,
            isMaxFareEnabled = maxFareEnabled,
            maxFare = maxFare,
            isMaxPickupDistanceEnabled = pickupDistEnabled,
            maxPickupDistance = maxDist,
            isMaxDropDistanceEnabled = dropDistEnabled,
            maxDropDistance = dropDist,
            clickDelayMs = delay,
            isVoiceAnnouncerEnabled = voiceEnabled,
            voiceLanguage = voiceLang,
            speechRate = speechRate,
            speechPitch = speechPitch,
            isFloatingOverlayEnabled = overlayEnabled,
            isAutoClickEnabled = autoClickEnabled,
            lastAcceptedFare = _lastAcceptedFareFlow.value
        )

        // Read and initialize Pass Expiry StateFlow
        val passExpiry = prefs.getLong(KEY_PASS_EXPIRY_TIMESTAMP, 0L)
        _passExpiryTimestampFlow.value = passExpiry
        _isPassActiveFlow.value = passExpiry > System.currentTimeMillis()
    }

    fun updateUserAuth(
        context: Context,
        isLoggedIn: Boolean,
        email: String?,
        name: String?,
        photoUrl: String?,
        idToken: String? = null
    ) {
        persistAsync(context) {
            putBoolean(KEY_USER_IS_LOGGED_IN, isLoggedIn)
            putString(KEY_USER_EMAIL, email)
            putString(KEY_USER_NAME, name)
            putString(KEY_USER_PHOTO_URL, photoUrl)
            putString(KEY_USER_ID_TOKEN, idToken)
        }
        _userAuthState.value = UserAuthState(
            isLoggedIn = isLoggedIn,
            userEmail = email,
            userName = name,
            photoUrl = photoUrl,
            idToken = idToken
        )
    }

    fun signOut(context: Context) {
        updateUserAuth(context, false, null, null, null, null)
    }

    fun setLoginDismissed(context: Context, dismissed: Boolean) {
        persistAsync(context) {
            putBoolean(KEY_LOGIN_DISMISSED, dismissed)
        }
    }

    fun isLoginDismissed(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_LOGIN_DISMISSED, false)
    }

    // --- Subscription & Pass Expiry Management ---

    fun getPassExpiryTimestamp(context: Context): Long {
        val ts = getPrefs(context).getLong(KEY_PASS_EXPIRY_TIMESTAMP, 0L)
        _passExpiryTimestampFlow.value = ts
        _isPassActiveFlow.value = ts > System.currentTimeMillis()
        return ts
    }

    fun setPassExpiryTimestamp(context: Context, expiryTimestamp: Long, tierId: String? = null) {
        persistAsync(context) {
            putLong(KEY_PASS_EXPIRY_TIMESTAMP, expiryTimestamp)
            if (tierId != null) {
                putString(KEY_ACTIVE_PASS_TIER_ID, tierId)
            }
        }
        _passExpiryTimestampFlow.value = expiryTimestamp
        _isPassActiveFlow.value = expiryTimestamp > System.currentTimeMillis()
    }

    fun isPassActive(context: Context): Boolean {
        val expiry = getPassExpiryTimestamp(context)
        return System.currentTimeMillis() < expiry
    }

    fun extendPass(context: Context, durationMs: Long, tierId: String? = null): Long {
        val now = System.currentTimeMillis()
        val currentExpiry = getPassExpiryTimestamp(context)
        val baseTime = if (currentExpiry > now) currentExpiry else now
        val newExpiry = baseTime + durationMs
        setPassExpiryTimestamp(context, newExpiry, tierId)
        return newExpiry
    }

    fun getActivePassTierId(context: Context): String? {
        return getPrefs(context).getString(KEY_ACTIVE_PASS_TIER_ID, null)
    }

    fun clearPass(context: Context) {
        setPassExpiryTimestamp(context, 0L, null)
    }

    private fun persistAsync(context: Context, action: SharedPreferences.Editor.() -> Unit) {
        val editor = getPrefs(context).edit()
        action(editor)
        editor.apply()
    }

    fun isAutoAcceptEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_AUTO_ACCEPT_ENABLED, prefs.getBoolean(KEY_SERVICE_ENABLED, DEFAULT_AUTO_ACCEPT_ENABLED))
    }

    fun setAutoAcceptEnabled(context: Context, enabled: Boolean) {
        autoAcceptEnabled = enabled
        _settingsState.value = _settingsState.value.copy(isAutoAcceptEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_AUTO_ACCEPT_ENABLED, enabled)
            putBoolean(KEY_SERVICE_ENABLED, enabled)
        }
        syncOverlayService(context)
    }

    fun isVoiceAnnouncerEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_VOICE_ANNOUNCER_ENABLED, DEFAULT_VOICE_ANNOUNCER_ENABLED)
    }

    fun setVoiceAnnouncerEnabled(context: Context, enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(isVoiceAnnouncerEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_VOICE_ANNOUNCER_ENABLED, enabled)
        }
    }

    fun getVoiceLanguage(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE) ?: DEFAULT_VOICE_LANGUAGE
    }

    fun setVoiceLanguage(context: Context, language: String) {
        _settingsState.value = _settingsState.value.copy(voiceLanguage = language)
        persistAsync(context) {
            putString(KEY_VOICE_LANGUAGE, language)
        }
    }

    fun isMinFareEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_MIN_FARE_ENABLED, DEFAULT_MIN_FARE_ENABLED)
    }

    fun setMinFareEnabled(context: Context, enabled: Boolean) {
        _isMinFareEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isMinFareEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_MIN_FARE_ENABLED, enabled)
        }
    }

    fun isMaxFareEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_MAX_FARE_ENABLED, DEFAULT_MAX_FARE_ENABLED)
    }

    fun setMaxFareEnabled(context: Context, enabled: Boolean) {
        _isMaxFareEnabledFlow.value = enabled
        _settingsState.value = _settingsState.value.copy(isMaxFareEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_MAX_FARE_ENABLED, enabled)
        }
    }

    fun isMaxPickupDistanceEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_MAX_PICKUP_DIST_ENABLED, DEFAULT_MAX_PICKUP_DIST_ENABLED)
    }

    fun setMaxPickupDistanceEnabled(context: Context, enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(isMaxPickupDistanceEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_MAX_PICKUP_DIST_ENABLED, enabled)
        }
    }

    fun isMaxDropDistanceEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_MAX_DROP_DIST_ENABLED, DEFAULT_MAX_DROP_DIST_ENABLED)
    }

    fun setMaxDropDistanceEnabled(context: Context, enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(isMaxDropDistanceEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_MAX_DROP_DIST_ENABLED, enabled)
        }
    }

    fun getMaxDropDistance(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MAX_DROP_DISTANCE, DEFAULT_MAX_DROP_DISTANCE)
    }

    fun setMaxDropDistance(context: Context, value: Float) {
        val clamped = value.coerceIn(1.0f, 50.0f)
        _settingsState.value = _settingsState.value.copy(maxDropDistance = clamped)
        persistAsync(context) {
            putFloat(KEY_MAX_DROP_DISTANCE, clamped)
        }
    }

    // --- Floating Overlay Button Controls ---
    fun isFloatingOverlayEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_FLOATING_OVERLAY_ENABLED, DEFAULT_FLOATING_OVERLAY_ENABLED)
    }

    fun setFloatingOverlayEnabled(context: Context, enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(isFloatingOverlayEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_FLOATING_OVERLAY_ENABLED, enabled)
        }
        syncOverlayService(context)
    }

    // --- Auto Click Controls (Full Automation vs Voice-Only) ---
    fun isAutoClickEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_AUTO_CLICK_ENABLED, DEFAULT_AUTO_CLICK_ENABLED)
    }

    fun setAutoClickEnabled(context: Context, enabled: Boolean) {
        _settingsState.value = _settingsState.value.copy(isAutoClickEnabled = enabled)
        persistAsync(context) {
            putBoolean(KEY_AUTO_CLICK_ENABLED, enabled)
        }
    }

    fun toggleAutoClick(context: Context): Boolean {
        val current = isAutoClickEnabled(context)
        val next = !current
        setAutoClickEnabled(context, next)
        return next
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
     * Synchronizes FloatingOverlayService lifecycle with user settings & overlay permission.
     * Starts the overlay if and only if:
     * 1. Master Automation Toggle is ON
     * 2. Floating Toggle is enabled
     * 3. System Overlay Permission is granted
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
        when (presetName.lowercase(Locale.ROOT)) {
            "peak", "peak hours" -> {
                setMinFareEnabled(context, true)
                setMinFare(context, 80.0f)
                setMaxFareEnabled(context, true)
                setMaxFare(context, 5000.0f)
                setMaxPickupDistanceEnabled(context, true)
                setMaxPickupDistance(context, 2.0f)
                setMaxDropDistanceEnabled(context, false)
            }
            "short", "short trips" -> {
                setMinFareEnabled(context, true)
                setMinFare(context, 40.0f)
                setMaxFareEnabled(context, true)
                setMaxFare(context, 150.0f)
                setMaxPickupDistanceEnabled(context, true)
                setMaxPickupDistance(context, 1.5f)
                setMaxDropDistanceEnabled(context, true)
                setMaxDropDistance(context, 5.0f)
            }
            "high", "high value" -> {
                setMinFareEnabled(context, true)
                setMinFare(context, 150.0f)
                setMaxFareEnabled(context, true)
                setMaxFare(context, 10000.0f)
                setMaxPickupDistanceEnabled(context, true)
                setMaxPickupDistance(context, 3.0f)
                setMaxDropDistanceEnabled(context, false)
            }
            else -> {
                // Default / Reset
                setMinFareEnabled(context, true)
                setMinFare(context, DEFAULT_MIN_FARE)
                setMaxFareEnabled(context, true)
                setMaxFare(context, DEFAULT_MAX_FARE)
                setMaxPickupDistanceEnabled(context, true)
                setMaxPickupDistance(context, DEFAULT_MAX_PICKUP_DISTANCE)
                setMaxDropDistanceEnabled(context, false)
                setMaxDropDistance(context, DEFAULT_MAX_DROP_DISTANCE)
            }
        }
    }

    // --- Fare Range Validation Functions ---

    /**
     * Checks if a min fare value is valid (within baseline range and <= max fare if max fare is enabled).
     */
    fun isValidMinFare(candidateMin: Float, currentMax: Float = _maxFareFlow.value): Boolean {
        return candidateMin in MIN_FARE_RANGE_START..MIN_FARE_RANGE_END &&
                (!_isMaxFareEnabledFlow.value || candidateMin <= currentMax)
    }

    /**
     * Checks if a max fare value is valid (within baseline range and >= min fare if min fare is enabled).
     */
    fun isValidMaxFare(candidateMax: Float, currentMin: Float = _minFareFlow.value): Boolean {
        return candidateMax in MAX_FARE_RANGE_START..MAX_FARE_RANGE_END &&
                (!_isMinFareEnabledFlow.value || candidateMax >= currentMin)
    }

    /**
     * Clamps and validates minFare so it stays within [MIN_FARE_RANGE_START, MIN_FARE_RANGE_END]
     * and does not exceed maxFare if maxFare is enabled.
     */
    fun validateMinFare(value: Float, currentMax: Float = _maxFareFlow.value): Float {
        val clamped = value.coerceIn(MIN_FARE_RANGE_START, MIN_FARE_RANGE_END)
        return if (_isMaxFareEnabledFlow.value && clamped > currentMax) {
            currentMax.coerceAtLeast(MIN_FARE_RANGE_START)
        } else {
            clamped
        }
    }

    /**
     * Clamps and validates maxFare so it stays within [MAX_FARE_RANGE_START, MAX_FARE_RANGE_END]
     * and is not lower than minFare if minFare is enabled.
     */
    fun validateMaxFare(value: Float, currentMin: Float = _minFareFlow.value): Float {
        val clamped = value.coerceIn(MAX_FARE_RANGE_START, MAX_FARE_RANGE_END)
        return if (_isMinFareEnabledFlow.value && clamped < currentMin) {
            currentMin.coerceAtMost(MAX_FARE_RANGE_END)
        } else {
            clamped
        }
    }

    fun getMinFare(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MIN_FARE, prefs.getFloat(KEY_MIN_VALUE, DEFAULT_MIN_FARE))
    }

    fun setMinFare(context: Context, value: Float) {
        val validated = validateMinFare(value)
        minCurrencyThreshold = validated.toDouble()
        _minFareFlow.value = validated
        _settingsState.value = _settingsState.value.copy(minFare = validated)
        persistAsync(context) {
            putFloat(KEY_MIN_FARE, validated)
            putFloat(KEY_MIN_VALUE, validated)
        }
    }

    fun getMaxFare(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MAX_FARE, prefs.getFloat(KEY_MAX_VALUE, DEFAULT_MAX_FARE))
    }

    fun setMaxFare(context: Context, value: Float) {
        val validated = validateMaxFare(value)
        maxCurrencyThreshold = validated.toDouble()
        _maxFareFlow.value = validated
        _settingsState.value = _settingsState.value.copy(maxFare = validated)
        persistAsync(context) {
            putFloat(KEY_MAX_FARE, validated)
            putFloat(KEY_MAX_VALUE, validated)
        }
    }

    fun getMaxPickupDistance(context: Context): Float {
        val prefs = getPrefs(context)
        val raw = prefs.getFloat(KEY_MAX_PICKUP_DISTANCE, prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE))
        return raw.coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
    }

    fun setMaxPickupDistance(context: Context, value: Float) {
        val clampedValue = value.coerceIn(MIN_PICKUP_DISTANCE_KM, MAX_PICKUP_DISTANCE_KM)
        _settingsState.value = _settingsState.value.copy(maxPickupDistance = clampedValue)
        persistAsync(context) {
            putFloat(KEY_MAX_PICKUP_DISTANCE, clampedValue)
            putFloat(KEY_MAX_DISTANCE, clampedValue)
        }
    }

    fun getClickDelayMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_CLICK_DELAY_MS, DEFAULT_CLICK_DELAY_MS)
    }

    fun setClickDelayMs(context: Context, delay: Long) {
        _settingsState.value = _settingsState.value.copy(clickDelayMs = delay)
        persistAsync(context) {
            putLong(KEY_CLICK_DELAY_MS, delay)
        }
    }

    fun getSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        _settingsState.value = _settingsState.value.copy(speechRate = rate)
        persistAsync(context) {
            putFloat(KEY_SPEECH_RATE, rate)
        }
    }

    fun getSpeechPitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)
    }

    fun setSpeechPitch(context: Context, pitch: Float) {
        _settingsState.value = _settingsState.value.copy(speechPitch = pitch)
        persistAsync(context) {
            putFloat(KEY_SPEECH_PITCH, pitch)
        }
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
     * Appends an activity log entry to the reactive live feed.
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
        current.add(0, entry) // prepend newest
        if (current.size > 100) {
            current.removeAt(current.lastIndex)
        }
        _logsFlow.value = current
    }

    fun clearLogs() {
        _logsFlow.value = emptyList()
    }

    /**
     * Persists an evaluated order into the Room database for historical review.
     */
    fun recordEvaluation(context: Context, evaluation: RideEvaluation) {
        try {
            val repo = com.example.data.OrderRepository.getInstance(context)
            val offer = evaluation.offer
            val totalFare = offer.totalFare ?: offer.targetOffer?.fare ?: 0.0
            val pickupDist = offer.pickupDistanceKm ?: offer.targetOffer?.pickupDistance
            val dropDist = offer.dropDistanceKm ?: offer.targetOffer?.dropDistance
            val pickupLoc = offer.pickupLocation
            val dropLoc = offer.dropLocation
            CoroutineScope(Dispatchers.IO).launch {
                repo.insertOrder(
                    com.example.data.OrderEntity(
                        timestamp = evaluation.timestamp,
                        fare = totalFare,
                        pickupDistanceKm = pickupDist,
                        dropDistanceKm = dropDist,
                        pickupLocation = pickupLoc,
                        dropLocation = dropLoc,
                        isAccepted = evaluation.isAccepted,
                        decisionReason = evaluation.decisionReason,
                        rawText = offer.rawText
                    )
                )
            }
        } catch (_: Exception) {
            // Fail gracefully if DB is initializing
        }
    }

    /**
     * Inspects system settings to check if our Accessibility Service is currently active.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        // Fast-path: Check live in-memory service instance
        if (MyAccessibilityService.isServiceRunning) {
            return true
        }

        val expectedService1 = "${context.packageName}/${MyAccessibilityService::class.java.canonicalName}"
        val expectedService2 = "${context.packageName}/${SmartTextService::class.java.canonicalName}"
        val expectedShort1 = "${context.packageName}/.MyAccessibilityService"
        val expectedShort2 = "${context.packageName}/.SmartTextService"

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
                    service.equals(expectedShort1, ignoreCase = true) ||
                    service.equals(expectedShort2, ignoreCase = true)
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

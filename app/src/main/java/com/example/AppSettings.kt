package com.example

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.text.TextUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

data class ActivityLogEntry(
    val id: Long = System.nanoTime(),
    val timestamp: Long = System.currentTimeMillis(),
    val timeFormatted: String,
    val title: String,
    val message: String,
    val severity: LogSeverity,
    val evaluation: RideEvaluation? = null
)

data class SettingsState(
    val isAutoAcceptEnabled: Boolean = true,
    val minFare: Float = 60.0f,
    val maxFare: Float = 5000.0f,
    val maxPickupDistance: Float = 3.0f,
    val clickDelayMs: Long = 500L,
    val isVoiceAnnouncerEnabled: Boolean = true,
    val voiceLanguage: String = "en",
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f
) {
    val autoAcceptEnabled: Boolean get() = isAutoAcceptEnabled
    val minPrice: Float get() = minFare
    val maxPrice: Float get() = maxFare
    val maxDistance: Float get() = maxPickupDistance
    val selectedLanguage: String get() = voiceLanguage
}

object AppSettings {
    const val PREFS_NAME = "captain_auto_accept_prefs"

    // Primary preference keys
    const val KEY_AUTO_ACCEPT_ENABLED = "key_auto_accept_enabled"
    const val KEY_MIN_FARE = "key_min_fare"
    const val KEY_MAX_FARE = "key_max_fare"
    const val KEY_MAX_PICKUP_DISTANCE = "key_max_pickup_distance"
    const val KEY_CLICK_DELAY_MS = "key_click_delay_ms"
    const val KEY_VOICE_ANNOUNCER_ENABLED = "key_voice_announcer_enabled"
    const val KEY_VOICE_LANGUAGE = "key_voice_language"
    const val KEY_SPEECH_RATE = "key_speech_rate"
    const val KEY_SPEECH_PITCH = "key_speech_pitch"

    // Backward-compatibility keys
    const val KEY_SERVICE_ENABLED = "key_service_enabled"
    const val KEY_MIN_VALUE = "key_min_value"
    const val KEY_MAX_VALUE = "key_max_value"
    const val KEY_MAX_DISTANCE = "key_max_distance"

    // Broadcast actions for service logging
    const val ACTION_ACCESSIBILITY_LOG = "com.example.ACCESSIBILITY_LOG"
    const val EXTRA_LOG_MESSAGE = "extra_log_message"

    // Default configuration values
    const val DEFAULT_MIN_FARE = 60.0f
    const val DEFAULT_MAX_FARE = 5000.0f
    const val DEFAULT_MAX_PICKUP_DISTANCE = 3.0f
    const val DEFAULT_AUTO_ACCEPT_ENABLED = true
    const val DEFAULT_CLICK_DELAY_MS = 500L
    const val DEFAULT_VOICE_ANNOUNCER_ENABLED = true
    const val DEFAULT_VOICE_LANGUAGE = "en"
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val DEFAULT_SPEECH_PITCH = 1.0f

    // In-memory properties for legacy access
    var minCurrencyThreshold: Double = 60.0
    var maxCurrencyThreshold: Double = 5000.0
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
            voiceLanguage = DEFAULT_VOICE_LANGUAGE
        )
    )
    val settingsState: StateFlow<SettingsState> = _settingsState.asStateFlow()

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

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes state from SharedPreferences.
     */
    fun init(context: Context) {
        val prefs = getPrefs(context)
        val enabled = prefs.getBoolean(KEY_AUTO_ACCEPT_ENABLED, prefs.getBoolean(KEY_SERVICE_ENABLED, DEFAULT_AUTO_ACCEPT_ENABLED))
        val minFare = prefs.getFloat(KEY_MIN_FARE, prefs.getFloat(KEY_MIN_VALUE, DEFAULT_MIN_FARE))
        val maxFare = prefs.getFloat(KEY_MAX_FARE, prefs.getFloat(KEY_MAX_VALUE, DEFAULT_MAX_FARE))
        val maxDist = prefs.getFloat(KEY_MAX_PICKUP_DISTANCE, prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE))
        val delay = prefs.getLong(KEY_CLICK_DELAY_MS, DEFAULT_CLICK_DELAY_MS)
        val voiceEnabled = prefs.getBoolean(KEY_VOICE_ANNOUNCER_ENABLED, DEFAULT_VOICE_ANNOUNCER_ENABLED)
        val voiceLang = prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE) ?: DEFAULT_VOICE_LANGUAGE
        val speechRate = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        val speechPitch = prefs.getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)

        minCurrencyThreshold = minFare.toDouble()
        maxCurrencyThreshold = maxFare.toDouble()
        autoAcceptEnabled = enabled

        _settingsState.value = SettingsState(
            isAutoAcceptEnabled = enabled,
            minFare = minFare,
            maxFare = maxFare,
            maxPickupDistance = maxDist,
            clickDelayMs = delay,
            isVoiceAnnouncerEnabled = voiceEnabled,
            voiceLanguage = voiceLang,
            speechRate = speechRate,
            speechPitch = speechPitch
        )
    }

    fun isAutoAcceptEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_AUTO_ACCEPT_ENABLED, prefs.getBoolean(KEY_SERVICE_ENABLED, DEFAULT_AUTO_ACCEPT_ENABLED))
    }

    fun setAutoAcceptEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_AUTO_ACCEPT_ENABLED, enabled)
            .putBoolean(KEY_SERVICE_ENABLED, enabled)
            .apply()
        autoAcceptEnabled = enabled
        _settingsState.value = _settingsState.value.copy(isAutoAcceptEnabled = enabled)
    }

    fun isVoiceAnnouncerEnabled(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_VOICE_ANNOUNCER_ENABLED, DEFAULT_VOICE_ANNOUNCER_ENABLED)
    }

    fun setVoiceAnnouncerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOICE_ANNOUNCER_ENABLED, enabled).apply()
        _settingsState.value = _settingsState.value.copy(isVoiceAnnouncerEnabled = enabled)
    }

    fun getVoiceLanguage(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_VOICE_LANGUAGE, DEFAULT_VOICE_LANGUAGE) ?: DEFAULT_VOICE_LANGUAGE
    }

    fun setVoiceLanguage(context: Context, language: String) {
        getPrefs(context).edit().putString(KEY_VOICE_LANGUAGE, language).apply()
        _settingsState.value = _settingsState.value.copy(voiceLanguage = language)
    }

    fun getMinFare(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MIN_FARE, prefs.getFloat(KEY_MIN_VALUE, DEFAULT_MIN_FARE))
    }

    fun setMinFare(context: Context, value: Float) {
        getPrefs(context).edit()
            .putFloat(KEY_MIN_FARE, value)
            .putFloat(KEY_MIN_VALUE, value)
            .apply()
        minCurrencyThreshold = value.toDouble()
        _settingsState.value = _settingsState.value.copy(minFare = value)
    }

    fun getMaxFare(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MAX_FARE, prefs.getFloat(KEY_MAX_VALUE, DEFAULT_MAX_FARE))
    }

    fun setMaxFare(context: Context, value: Float) {
        getPrefs(context).edit()
            .putFloat(KEY_MAX_FARE, value)
            .putFloat(KEY_MAX_VALUE, value)
            .apply()
        maxCurrencyThreshold = value.toDouble()
        _settingsState.value = _settingsState.value.copy(maxFare = value)
    }

    fun getMaxPickupDistance(context: Context): Float {
        val prefs = getPrefs(context)
        return prefs.getFloat(KEY_MAX_PICKUP_DISTANCE, prefs.getFloat(KEY_MAX_DISTANCE, DEFAULT_MAX_PICKUP_DISTANCE))
    }

    fun setMaxPickupDistance(context: Context, value: Float) {
        getPrefs(context).edit()
            .putFloat(KEY_MAX_PICKUP_DISTANCE, value)
            .putFloat(KEY_MAX_DISTANCE, value)
            .apply()
        _settingsState.value = _settingsState.value.copy(maxPickupDistance = value)
    }

    fun getClickDelayMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_CLICK_DELAY_MS, DEFAULT_CLICK_DELAY_MS)
    }

    fun setClickDelayMs(context: Context, delay: Long) {
        getPrefs(context).edit().putLong(KEY_CLICK_DELAY_MS, delay).apply()
        _settingsState.value = _settingsState.value.copy(clickDelayMs = delay)
    }

    fun getSpeechRate(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
    }

    fun setSpeechRate(context: Context, rate: Float) {
        getPrefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()
        _settingsState.value = _settingsState.value.copy(speechRate = rate)
    }

    fun getSpeechPitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPEECH_PITCH, DEFAULT_SPEECH_PITCH)
    }

    fun setSpeechPitch(context: Context, pitch: Float) {
        getPrefs(context).edit().putFloat(KEY_SPEECH_PITCH, pitch).apply()
        _settingsState.value = _settingsState.value.copy(speechPitch = pitch)
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

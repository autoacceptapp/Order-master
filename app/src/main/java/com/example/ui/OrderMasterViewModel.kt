package com.example.ui

import android.app.Application
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ActivityLogEntry
import com.example.AppSettings
import com.example.LogSeverity
import com.example.ParsedRideOffer
import com.example.RideEvaluation
import com.example.SettingsState
import com.example.TargetRideOffer
import com.example.TextAnalysisEngine
import com.example.data.OrderEntity
import com.example.data.OrderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

enum class HistoryFilter {
    ALL,
    ACCEPTED,
    IGNORED
}

class OrderMasterViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = OrderRepository.getInstance(application)
    private var tts: TextToSpeech? = null
    private val _ttsStatus = MutableStateFlow("TTS Ready")
    val ttsStatus: StateFlow<String> = _ttsStatus.asStateFlow()

    // AppSettings states
    val settingsState: StateFlow<SettingsState> = AppSettings.settingsState
    val liveLogs: StateFlow<List<ActivityLogEntry>> = AppSettings.logsFlow

    // History search and filter state
    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    private val _historyStatusFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyStatusFilter: StateFlow<HistoryFilter> = _historyStatusFilter.asStateFlow()

    // Filtered order history from Room Database
    val orderHistory: StateFlow<List<OrderEntity>> = combine(
        repository.allOrders,
        _historySearchQuery,
        _historyStatusFilter
    ) { orders, query, filter ->
        orders.filter { order ->
            val matchesFilter = when (filter) {
                HistoryFilter.ALL -> true
                HistoryFilter.ACCEPTED -> order.isAccepted
                HistoryFilter.IGNORED -> !order.isAccepted
            }
            val matchesQuery = if (query.isBlank()) {
                true
            } else {
                order.pickupLocation.contains(query, ignoreCase = true) ||
                order.dropLocation.contains(query, ignoreCase = true) ||
                order.decisionReason.contains(query, ignoreCase = true) ||
                order.fare.toString().contains(query)
            }
            matchesFilter && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Historical statistics
    val totalOrdersCount = repository.allOrders
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val acceptedOrdersCount = repository.allOrders
        .map { list -> list.count { it.isAccepted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    init {
        AppSettings.init(application)
        OrderRepository.seedInitialDataIfEmpty(application)
        initTts(application)
    }

    private fun initTts(app: Application) {
        try {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val lang = if (settingsState.value.voiceLanguage == "hi") Locale("hi", "IN") else Locale.US
                    tts?.language = lang
                    _ttsStatus.value = "TTS Active (${settingsState.value.voiceLanguage.uppercase()})"
                } else {
                    _ttsStatus.value = "TTS Unavailable"
                }
            }
        } catch (_: Exception) {
            _ttsStatus.value = "TTS Idle"
        }
    }

    // --- Master Toggle ---
    fun toggleMasterAutomation() {
        val app = getApplication<Application>()
        val nextState = !settingsState.value.isAutoAcceptEnabled
        AppSettings.setAutoAcceptEnabled(app, nextState)
        AppSettings.addLog(
            title = if (nextState) "Master Automation ACTIVE" else "Master Automation PAUSED",
            message = if (nextState) "Accessibility listener active. Evaluating screen overlay cards."
                     else "Screen overlay processing paused by captain.",
            severity = if (nextState) LogSeverity.INFO else LogSeverity.WARNING
        )
    }

    // --- Modular Filter Controls ---
    fun toggleMinFareFilter(enabled: Boolean) {
        val app = getApplication<Application>()
        AppSettings.setMinFareEnabled(app, enabled)
    }

    fun setMinFare(value: Float) {
        val app = getApplication<Application>()
        AppSettings.setMinFare(app, value)
    }

    fun toggleMaxFareFilter(enabled: Boolean) {
        val app = getApplication<Application>()
        AppSettings.setMaxFareEnabled(app, enabled)
    }

    fun setMaxFare(value: Float) {
        val app = getApplication<Application>()
        AppSettings.setMaxFare(app, value)
    }

    fun toggleMaxPickupDistFilter(enabled: Boolean) {
        val app = getApplication<Application>()
        AppSettings.setMaxPickupDistanceEnabled(app, enabled)
    }

    fun setMaxPickupDistance(value: Float) {
        val app = getApplication<Application>()
        AppSettings.setMaxPickupDistance(app, value)
    }

    fun toggleMaxDropDistFilter(enabled: Boolean) {
        val app = getApplication<Application>()
        AppSettings.setMaxDropDistanceEnabled(app, enabled)
    }

    fun setMaxDropDistance(value: Float) {
        val app = getApplication<Application>()
        AppSettings.setMaxDropDistance(app, value)
    }

    fun toggleVoiceAnnouncer(enabled: Boolean) {
        val app = getApplication<Application>()
        AppSettings.setVoiceAnnouncerEnabled(app, enabled)
    }

    fun setVoiceLanguage(lang: String) {
        val app = getApplication<Application>()
        AppSettings.setVoiceLanguage(app, lang)
        val targetLocale = if (lang == "hi") Locale("hi", "IN") else Locale.US
        tts?.language = targetLocale
        _ttsStatus.value = "TTS Active (${lang.uppercase()})"
    }

    fun testTtsAnnouncement() {
        val lang = settingsState.value.voiceLanguage
        val message = if (lang == "hi") {
            "नया राइड प्रस्ताव: एक सौ बीस रुपये, पिकअप एक दशमलव दो किलोमीटर"
        } else {
            "New ride offer detected: Fare 120 rupees, pickup 1.2 kilometers"
        }
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "TEST_TTS")
    }

    fun applyPreset(presetName: String) {
        val app = getApplication<Application>()
        AppSettings.applyPreset(app, presetName)
        AppSettings.addLog(
            title = "Preset Applied: $presetName",
            message = "All modular filter thresholds updated according to $presetName profile.",
            severity = LogSeverity.INFO
        )
    }

    // --- Order History Actions ---
    fun setHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    fun setHistoryStatusFilter(filter: HistoryFilter) {
        _historyStatusFilter.value = filter
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
        }
    }

    fun deleteOrder(orderId: Long) {
        viewModelScope.launch {
            repository.deleteOrder(orderId)
        }
    }

    fun clearLiveFeed() {
        AppSettings.clearLogs()
    }

    // --- Sandbox Simulation for Quick Testing ---
    fun simulateRideOffer(
        fare: Double = 95.0,
        pickupDist: Double = 1.8,
        dropDist: Double = 6.4,
        pickupLoc: String = "Lalpari River",
        dropLoc: String = "Race Course"
    ) {
        val app = getApplication<Application>()
        val state = settingsState.value
        val parsedOffer = ParsedRideOffer(
            rawText = "₹${fare.toInt()} • ${pickupDist}km away • $pickupLoc to $dropLoc",
            totalFare = fare,
            fareBreakdown = listOf(fare),
            pickupDistanceKm = pickupDist,
            dropDistanceKm = dropDist,
            hasAcceptButton = true,
            targetOffer = TargetRideOffer(
                fare = fare,
                pickupDistance = pickupDist,
                pickupLocation = pickupLoc,
                dropDistance = dropDist,
                dropLocation = dropLoc
            )
        )

        val evaluation = TextAnalysisEngine.evaluateRideOffer(
            offer = parsedOffer,
            minFare = state.minFare,
            maxFare = state.maxFare,
            maxPickupDistance = state.maxPickupDistance,
            isAutoAcceptEnabled = state.isAutoAcceptEnabled,
            isMinFareEnabled = state.isMinFareEnabled,
            isMaxFareEnabled = state.isMaxFareEnabled,
            isMaxPickupDistanceEnabled = state.isMaxPickupDistanceEnabled,
            isMaxDropDistanceEnabled = state.isMaxDropDistanceEnabled,
            maxDropDistance = state.maxDropDistance
        )

        val logSeverity = if (evaluation.isAccepted) LogSeverity.MATCH_ACCEPTED else LogSeverity.REJECTED
        AppSettings.addLog(
            title = if (evaluation.isAccepted) "Simulated: Offer Accepted" else "Simulated: Offer Filtered",
            message = "${if (evaluation.isAccepted) "ACCEPTED" else "IGNORED"}: ₹${fare.toInt()} | Pickup: ${pickupDist}km | Drop: ${dropDist}km (${evaluation.decisionReason})",
            severity = logSeverity,
            evaluation = evaluation
        )

        // Persist to Room Database
        AppSettings.recordEvaluation(app, evaluation)

        // Optional speech announcement
        if (state.isVoiceAnnouncerEnabled) {
            val announcement = if (evaluation.isAccepted) {
                "Ride Accepted: Fare ${fare.toInt()} rupees, pickup ${pickupDist} kilometers"
            } else {
                "Ride Ignored: ${evaluation.decisionReason}"
            }
            tts?.speak(announcement, TextToSpeech.QUEUE_ADD, null, "SIM_TTS")
        }
    }

    override fun onCleared() {
        super.onCleared()
        tts?.stop()
        tts?.shutdown()
    }
}

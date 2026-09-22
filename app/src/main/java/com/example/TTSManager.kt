package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Dedicated Text-To-Speech (TTS) Engine and Audio Announcer for Order Master.
 *
 * Implements:
 * 1. Strict Order Deduplication & Cooldown:
 *    Maintains a timestamp map of spoken offer keys (Fare + Distance combination)
 *    enforcing a minimum 10-second cooldown per unique ride offer to eliminate repeated speech.
 * 2. Guaranteed Single Announcement:
 *    Ensures the voice announcer speaks the offer details EXACTLY ONCE per new valid ride popup.
 * 3. Locale & Speed Management:
 *    Dynamically adjusts between Hindi (hi-IN) and English (en-IN/US), with custom speech rate and pitch.
 * 4. Sequential Queue Coordination:
 *    Uses TextToSpeech.QUEUE_ADD for sequential readouts and TextToSpeech.QUEUE_FLUSH for immediate alerts/tests.
 */
object TTSManager {

    private const val TAG = "TTSManager"

    // Minimum cooldown per unique ride offer (10 seconds) to prevent duplicate speech
    const val OFFER_COOLDOWN_MS = 10_000L

    // Minimum throttle between ANY speech utterance to prevent speech overlap (1.5 seconds)
    const val MIN_SPEECH_INTERVAL_MS = 1500L

    @Volatile
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    var isTtsReady: Boolean = false
        private set

    // Strict deduplication map: maps offerKey -> lastSpokenTimestamp
    private val spokenOffersHistory = ConcurrentHashMap<String, Long>()

    @Volatile
    private var lastSpokenOfferKey: String? = null

    @Volatile
    private var lastSpokenTimestamp: Long = 0L

    /**
     * Initializes the native Android TextToSpeech engine.
     */
    fun init(context: Context) {
        if (textToSpeech != null) return
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    applySettings(context)
                    Log.i(TAG, "Native TextToSpeech engine initialized successfully.")
                } else {
                    isTtsReady = false
                    Log.w(TAG, "Native TextToSpeech initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            isTtsReady = false
            Log.e(TAG, "Exception while instantiating TextToSpeech", e)
        }
    }

    /**
     * Applies the configured voice language (Hindi / English), speech rate, and pitch from AppSettings.
     */
    fun applySettings(context: Context) {
        val tts = textToSpeech ?: return
        val lang = AppSettings.getVoiceLanguage(context)
        val targetLocale = if (lang.equals("hi", ignoreCase = true)) {
            Locale.Builder().setLanguage("hi").setRegion("IN").build()
        } else {
            Locale.Builder().setLanguage("en").setRegion("IN").build()
        }

        try {
            val result = tts.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $targetLocale not supported/missing data, falling back to English.")
                tts.setLanguage(Locale.ENGLISH)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS language to $targetLocale", e)
            try {
                tts.setLanguage(Locale.getDefault())
            } catch (_: Exception) {}
        }

        try {
            val rate = AppSettings.getSpeechRate(context)
            val pitch = AppSettings.getSpeechPitch(context)
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS rate/pitch", e)
        }
    }

    /**
     * Generates a canonical deduplication key for a ride offer based on
     * Fare and Pickup/Drop distance (e.g., "fare_110_dist_2.3").
     */
    fun getOfferDeduplicationKey(offer: ParsedRideOffer): String {
        val target = offer.targetOffer
        val fare = target?.fare?.toInt() ?: offer.baseFare?.toInt() ?: offer.totalFare?.toInt() ?: 0
        val dist = target?.pickupDistance?.takeIf { it > 0.0 }
            ?: offer.pickupDistanceKm
            ?: target?.dropDistance?.takeIf { it > 0.0 }
            ?: offer.dropDistanceKm
            ?: 0.0
        val distStr = String.format(Locale.US, "%.1f", dist)
        return "fare_${fare}_dist_${distStr}"
    }

    /**
     * Generates a canonical deduplication key for raw values.
     */
    fun getOfferDeduplicationKey(fare: Double?, distance: Double?): String {
        val fareInt = fare?.toInt() ?: 0
        val distStr = String.format(Locale.US, "%.1f", distance ?: 0.0)
        return "fare_${fareInt}_dist_${distStr}"
    }

    /**
     * Strict Debounce & Cooldown Check:
     * Returns true ONLY if this offer has NOT been spoken within the 10-second cooldown window.
     * Records the speech timestamp to prevent multiple announcements.
     */
    @Synchronized
    fun shouldAnnounceOffer(offerKey: String): Boolean {
        val now = System.currentTimeMillis()

        // Clean up old entries from history older than 60 seconds
        val cutoff = now - 60_000L
        spokenOffersHistory.entries.removeIf { it.value < cutoff }

        val lastSpokenTime = spokenOffersHistory[offerKey]
        if (lastSpokenTime != null && (now - lastSpokenTime < OFFER_COOLDOWN_MS)) {
            val elapsed = now - lastSpokenTime
            Log.d(TAG, "Debounce active: offer '$offerKey' was spoken ${elapsed}ms ago (10s cooldown active). Skipping.")
            return false
        }

        // Check overall speech interval to prevent rapid-fire speech
        if (now - lastSpokenTimestamp < MIN_SPEECH_INTERVAL_MS && offerKey == lastSpokenOfferKey) {
            Log.d(TAG, "Debounce active: identical offer key '$offerKey' within minimum speech interval. Skipping.")
            return false
        }

        // Mark as spoken
        spokenOffersHistory[offerKey] = now
        lastSpokenOfferKey = offerKey
        lastSpokenTimestamp = now
        return true
    }

    /**
     * Checks if an offer has already been announced within cooldown without updating timestamps.
     */
    fun isOfferInCooldown(offerKey: String): Boolean {
        val now = System.currentTimeMillis()
        val lastSpokenTime = spokenOffersHistory[offerKey] ?: return false
        return (now - lastSpokenTime < OFFER_COOLDOWN_MS)
    }

    /**
     * Announces a newly detected ride offer with fare and distance details in natural spoken Hindi/Hinglish.
     * Guaranteed to speak EXACTLY ONCE per new valid ride request popup.
     *
     * @return true if the announcement was spoken; false if suppressed by debounce/cooldown.
     */
    fun announceNewRide(context: Context, offer: ParsedRideOffer, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) {
            return false
        }

        val offerKey = getOfferDeduplicationKey(offer)
        if (!shouldAnnounceOffer(offerKey)) {
            Log.i(TAG, "TTSManager: Suppressed duplicate announcement for offer: $offerKey")
            return false
        }

        val target = offer.targetOffer ?: TextAnalysisEngine.parseTargetOffer(offer.rawText)
        val message = if (target != null && target.fare > 0.0) {
            formatCleanVoiceAnnouncement(target)
        } else {
            val fare = offer.totalFare?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            val dist = (offer.pickupDistanceKm ?: offer.dropDistanceKm)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            "Kiraya $fare rupaye. Pickup $dist kilometer."
        }

        speak(context, message, queueMode)
        return true
    }

    /**
     * Speaks the given text using the configured queue management mode.
     */
    fun speak(context: Context, text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) {
            return
        }

        if (!isTtsReady || textToSpeech == null) {
            Log.w(TAG, "TextToSpeech engine is not ready, initializing now...")
            init(context)
            return
        }

        try {
            applySettings(context)
            val utteranceId = "tts_ride_${System.currentTimeMillis()}"
            textToSpeech?.speak(text, queueMode, null, utteranceId)
            Log.d(TAG, "TTS announced [queue=$queueMode]: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak TTS announcement", e)
        }
    }

    /**
     * Formats the voice announcement to speak strictly the 5 extracted clean fields in natural spoken Hindi/Hinglish:
     * "Kiraya {fare} rupaye. Pickup {pickupDistance} kilometer {pickupLocation}. Drop {dropDistance} kilometer {dropLocation}."
     */
    fun formatCleanVoiceAnnouncement(target: TargetRideOffer): String {
        val fareStr = if (target.fare % 1.0 == 0.0) target.fare.toInt().toString() else String.format(Locale.US, "%.1f", target.fare)
        val pickupDistStr = if (target.pickupDistance % 1.0 == 0.0) target.pickupDistance.toInt().toString() else String.format(Locale.US, "%.1f", target.pickupDistance)
        val dropDistStr = if (target.dropDistance % 1.0 == 0.0) target.dropDistance.toInt().toString() else String.format(Locale.US, "%.1f", target.dropDistance)

        val pickupLoc = target.pickupLocation.trim()
        val dropLoc = target.dropLocation.trim()

        val sb = StringBuilder()
        sb.append("Kiraya $fareStr rupaye.")

        if (pickupLoc.isNotEmpty()) {
            sb.append(" Pickup $pickupDistStr kilometer $pickupLoc.")
        } else {
            sb.append(" Pickup $pickupDistStr kilometer.")
        }

        if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) {
            sb.append(" Drop $dropDistStr kilometer $dropLoc.")
        } else if (target.dropDistance > 0.0) {
            sb.append(" Drop $dropDistStr kilometer.")
        } else if (dropLoc.isNotEmpty()) {
            sb.append(" Drop $dropLoc.")
        }

        return sb.toString().trim()
    }

    /**
     * Announces an order acceptance confirmation.
     */
    fun announceRideAccepted(context: Context, fare: Double?, distance: Double?) {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) return

        val lang = AppSettings.getVoiceLanguage(context)
        val fareInt = fare?.toInt()

        val message = if (lang.equals("hi", ignoreCase = true)) {
            if (fareInt != null) {
                "राइड स्वीकार कर लिया गया! किराया $fareInt रुपये।"
            } else {
                "राइड स्वीकार कर लिया गया!"
            }
        } else {
            if (fareInt != null) {
                "Ride accepted! Fare is $fareInt rupees."
            } else {
                "Ride accepted!"
            }
        }

        speak(context, message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Announces when an offer does not meet captain criteria.
     */
    fun announceRideSkipped(context: Context, reason: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) return

        val lang = AppSettings.getVoiceLanguage(context)
        val isHindi = lang.equals("hi", ignoreCase = true)

        val message = when {
            reason.contains("< Min Limit", ignoreCase = true) || reason.contains("below minimum", ignoreCase = true) -> {
                if (isHindi) "राइड छोड़ दिया गया: किराया न्यूनतम से कम है।"
                else "Ride skipped: fare is below minimum."
            }
            reason.contains("> Max Limit", ignoreCase = true) || reason.contains("exceeds maximum", ignoreCase = true) -> {
                if (isHindi) "राइड छोड़ दिया गया: किराया अधिकतम सीमा से अधिक है।"
                else "Ride skipped: fare exceeds maximum limit."
            }
            reason.contains("Pickup distance", ignoreCase = true) || reason.contains("exceeds set maximum limit", ignoreCase = true) -> {
                if (isHindi) "राइड छोड़ दिया गया: पिकअप दूरी सीमा से अधिक है।"
                else "Ride skipped: pickup distance exceeds maximum limit threshold."
            }
            else -> {
                if (isHindi) "राइड छोड़ दिया गया।"
                else "Ride skipped."
            }
        }

        speak(context, message, queueMode)
    }

    /**
     * Helper to test the voice announcer from UI.
     */
    fun testAnnouncement(context: Context, customMessage: String? = null) {
        val lang = AppSettings.getVoiceLanguage(context)
        val message = customMessage ?: if (lang.equals("hi", ignoreCase = true)) {
            "आवाज़ उद्घोषक चालू है! किराया 120 रुपये, दूरी 3 किलोमीटर।"
        } else {
            "Voice announcer is active! Fare is 120 rupees, distance 3 kilometers."
        }
        speak(context, message, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Clears history and releases TextToSpeech resources.
     */
    fun shutdown() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsReady = false
            spokenOffersHistory.clear()
            lastSpokenOfferKey = null
            Log.d(TAG, "TTSManager released and shutdown.")
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTSManager", e)
        }
    }
}

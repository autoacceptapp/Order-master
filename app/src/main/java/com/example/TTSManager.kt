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
     * Parses a regional or ISO language code into a canonical Locale with Indian region targeting.
     */
    fun parseLocale(languageCode: String): Locale {
        val clean = languageCode.trim().lowercase(Locale.ROOT)
        return when (clean) {
            "hi" -> Locale.Builder().setLanguage("hi").setRegion("IN").build()
            "gu" -> Locale.Builder().setLanguage("gu").setRegion("IN").build()
            "mr" -> Locale.Builder().setLanguage("mr").setRegion("IN").build()
            "bn" -> Locale.Builder().setLanguage("bn").setRegion("IN").build()
            "ta" -> Locale.Builder().setLanguage("ta").setRegion("IN").build()
            "te" -> Locale.Builder().setLanguage("te").setRegion("IN").build()
            "kn" -> Locale.Builder().setLanguage("kn").setRegion("IN").build()
            "ml" -> Locale.Builder().setLanguage("ml").setRegion("IN").build()
            "pa" -> Locale.Builder().setLanguage("pa").setRegion("IN").build()
            "en" -> Locale.Builder().setLanguage("en").setRegion("IN").build()
            else -> {
                if (clean.contains("-")) {
                    Locale.forLanguageTag(clean)
                } else {
                    Locale.Builder().setLanguage(clean).setRegion("IN").build()
                }
            }
        }
    }

    /**
     * Explicitly switches the Android Text-To-Speech engine to the given regional language code.
     * Enforces fallback to language-only locale and then English if voice data is missing.
     *
     * @return true if target regional language was successfully applied, false if fell back to English.
     */
    fun setLanguage(context: Context, languageCode: String): Boolean {
        if (!isTtsReady || textToSpeech == null) {
            Log.w(TAG, "setLanguage called while TTS not ready. Initializing engine for language: $languageCode")
            init(context)
        }
        val tts = textToSpeech ?: return false
        val targetLocale = parseLocale(languageCode)

        return try {
            var result = tts.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Try language-only without region code
                val langOnly = Locale.Builder().setLanguage(targetLocale.language).build()
                result = tts.setLanguage(langOnly)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Voice data for $targetLocale is missing or not supported on this device. Gracefully falling back to English.")
                    tts.setLanguage(Locale.ENGLISH)
                    false
                } else {
                    Log.i(TAG, "Applied TTS language using language-only fallback: $langOnly")
                    true
                }
            } else {
                Log.i(TAG, "Applied TTS regional language successfully: $targetLocale")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception applying TTS language for $targetLocale: ${e.message}", e)
            try {
                tts.setLanguage(Locale.ENGLISH)
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Speaks an immediate greeting in the selected regional language.
     */
    fun speakGreeting(context: Context, languageCode: String) {
        val langObj = AppSettings.SUPPORTED_LANGUAGES.find { it.code.equals(languageCode, ignoreCase = true) }
        val greeting = langObj?.sampleGreeting ?: when (languageCode.trim().lowercase(Locale.ROOT)) {
            "hi" -> "नमस्ते"
            "gu" -> "નમસ્તે"
            "mr" -> "नमस्कार"
            "bn" -> "নমস্কার"
            "ta" -> "வணக்கம்"
            "te" -> "నమస్కారం"
            "kn" -> "ನಮಸ್ಕಾರ"
            "ml" -> "നമസ്കാരം"
            "pa" -> "ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ"
            else -> "Hello"
        }
        speak(context, greeting, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Applies the configured voice language, speech rate, and pitch from AppSettings.
     */
    fun applySettings(context: Context) {
        val tts = textToSpeech ?: return
        val lang = AppSettings.getVoiceLanguage(context)
        setLanguage(context, lang)

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
        val lang = AppSettings.getVoiceLanguage(context)
        val message = if (target != null && target.fare > 0.0) {
            formatCleanVoiceAnnouncement(target, lang)
        } else {
            val fare = offer.totalFare?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            val dist = (offer.pickupDistanceKm ?: offer.dropDistanceKm)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            if (lang.equals("hi", ignoreCase = true)) {
                "किराया $fare रुपये। पिकअप $dist किलोमीटर।"
            } else {
                "Fare $fare rupees. Pickup $dist km."
            }
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
     * Speaks the given text with flush control.
     * When flush is true, cancels ongoing speech to immediately read out new slide or alert.
     */
    fun speak(context: Context, text: String, flush: Boolean) {
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        speak(context, text, queueMode)
    }

    /**
     * Immediately stops any ongoing speech playback.
     */
    fun stop() {
        try {
            textToSpeech?.stop()
            Log.d(TAG, "TTS playback stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    /**
     * Formats the voice announcement to speak strictly the 5 extracted clean fields in natural spoken Hindi/Hinglish:
     * "Kiraya {fare} rupaye. Pickup {pickupDistance} kilometer {pickupLocation}. Drop {dropDistance} kilometer {dropLocation}."
     */
    /**
     * Formats the voice announcement to speak strictly the 5 extracted clean fields in natural spoken regional language:
     * "Fare {fare} rupees. Pickup {pickupDistance} km {pickupLocation}. Drop {dropDistance} km {dropLocation}."
     */
    fun formatCleanVoiceAnnouncement(target: TargetRideOffer, lang: String = "hi"): String {
        val fareStr = if (target.fare % 1.0 == 0.0) target.fare.toInt().toString() else String.format(Locale.US, "%.1f", target.fare)
        val pickupDistStr = if (target.pickupDistance % 1.0 == 0.0) target.pickupDistance.toInt().toString() else String.format(Locale.US, "%.1f", target.pickupDistance)
        val dropDistStr = if (target.dropDistance % 1.0 == 0.0) target.dropDistance.toInt().toString() else String.format(Locale.US, "%.1f", target.dropDistance)

        val pickupLoc = target.pickupLocation.trim()
        val dropLoc = target.dropLocation.trim()

        return when (lang.lowercase(Locale.ROOT)) {
            "en" -> {
                val sb = StringBuilder("Fare $fareStr rupees.")
                if (pickupLoc.isNotEmpty()) sb.append(" Pickup $pickupDistStr km $pickupLoc.")
                else sb.append(" Pickup $pickupDistStr km.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" Drop $dropDistStr km $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" Drop $dropDistStr km.")
                else if (dropLoc.isNotEmpty()) sb.append(" Drop $dropLoc.")
                sb.toString().trim()
            }
            "gu" -> {
                val sb = StringBuilder("ભાડું $fareStr રૂપિયા.")
                if (pickupLoc.isNotEmpty()) sb.append(" પીકઅપ $pickupDistStr કિલોમીટર $pickupLoc.")
                else sb.append(" પીકઅપ $pickupDistStr કિલોમીટર.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ડ્રોપ $dropDistStr કિલોમીટર $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" ડ્રોપ $dropDistStr કિલોમીટર.")
                else if (dropLoc.isNotEmpty()) sb.append(" ડ્રોપ $dropLoc.")
                sb.toString().trim()
            }
            "mr" -> {
                val sb = StringBuilder("भाडे $fareStr रुपये.")
                if (pickupLoc.isNotEmpty()) sb.append(" पिकअप $pickupDistStr किलोमीटर $pickupLoc.")
                else sb.append(" पिकअप $pickupDistStr किलोमीटर.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ड्रॉप $dropDistStr किलोमीटर $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" ड्रॉप $dropDistStr किलोमीटर.")
                else if (dropLoc.isNotEmpty()) sb.append(" ड्रॉप $dropLoc.")
                sb.toString().trim()
            }
            "bn" -> {
                val sb = StringBuilder("ভাড়া $fareStr টাকা।")
                if (pickupLoc.isNotEmpty()) sb.append(" পিকআপ $pickupDistStr কিলোমিটার $pickupLoc।")
                else sb.append(" পিকআপ $pickupDistStr কিলোমিটার।")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ড্রপ $dropDistStr কিলোমিটার $dropLoc।")
                else if (target.dropDistance > 0.0) sb.append(" ড্রপ $dropDistStr কিলোমিটার।")
                else if (dropLoc.isNotEmpty()) sb.append(" ড্রপ $dropLoc।")
                sb.toString().trim()
            }
            "ta" -> {
                val sb = StringBuilder("கட்டணம் $fareStr ரூபாய்.")
                if (pickupLoc.isNotEmpty()) sb.append(" பிக்கப் $pickupDistStr கிலோமீட்டர் $pickupLoc.")
                else sb.append(" பிக்கப் $pickupDistStr கிலோமீட்டர்.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" டிராப் $dropDistStr கிலோமீட்டர் $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" டிராப் $dropDistStr கிலோமீட்டர்.")
                else if (dropLoc.isNotEmpty()) sb.append(" டிராப் $dropLoc.")
                sb.toString().trim()
            }
            "te" -> {
                val sb = StringBuilder("ఛార్జీ $fareStr రూపాయలు.")
                if (pickupLoc.isNotEmpty()) sb.append(" పికప్ $pickupDistStr కిలోమీటర్లు $pickupLoc.")
                else sb.append(" పికప్ $pickupDistStr కిలోమీటర్లు.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" డ్రాప్ $dropDistStr కిలోమీటర్లు $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" డ్రాప్ $dropDistStr కిలోమీటర్లు.")
                else if (dropLoc.isNotEmpty()) sb.append(" డ్రాప్ $dropLoc.")
                sb.toString().trim()
            }
            "kn" -> {
                val sb = StringBuilder("ಬಾಡಿಗೆ $fareStr ರೂಪಾಯಿ.")
                if (pickupLoc.isNotEmpty()) sb.append(" ಪಿಕಪ್ $pickupDistStr ಕಿಲೋಮೀಟರ್ $pickupLoc.")
                else sb.append(" ಪಿಕಪ್ $pickupDistStr ಕಿಲೋಮೀಟರ್.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ಡ್ರಾಪ್ $dropDistStr ಕಿಲೋಮೀಟರ್ $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" ಡ್ರಾಪ್ $dropDistStr ಕಿಲೋಮೀಟರ್.")
                else if (dropLoc.isNotEmpty()) sb.append(" ಡ್ರಾಪ್ $dropLoc.")
                sb.toString().trim()
            }
            "ml" -> {
                val sb = StringBuilder("കൂലി $fareStr രൂപ.")
                if (pickupLoc.isNotEmpty()) sb.append(" പിക്കപ്പ് $pickupDistStr കിലോമീറ്റർ $pickupLoc.")
                else sb.append(" പിക്കപ്പ് $pickupDistStr കിലോമീറ്റർ.")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ഡ്രോപ്പ് $dropDistStr കിലോമീറ്റർ $dropLoc.")
                else if (target.dropDistance > 0.0) sb.append(" ഡ്രോപ്പ് $dropDistStr കിലോമീറ്റർ.")
                else if (dropLoc.isNotEmpty()) sb.append(" ഡ്രോപ്പ് $dropLoc.")
                sb.toString().trim()
            }
            "pa" -> {
                val sb = StringBuilder("ਕਿਰਾਇਆ $fareStr ਰੁਪਏ।")
                if (pickupLoc.isNotEmpty()) sb.append(" ਪਿਕਅੱਪ $pickupDistStr ਕਿਲੋਮੀਟਰ $pickupLoc।")
                else sb.append(" ਪਿਕਅੱਪ $pickupDistStr ਕਿਲੋਮੀਟਰ।")
                if (target.dropDistance > 0.0 && dropLoc.isNotEmpty()) sb.append(" ਡ੍ਰੌਪ $dropDistStr ਕਿਲੋਮੀਟਰ $dropLoc।")
                else if (target.dropDistance > 0.0) sb.append(" ਡ੍ਰੌਪ $dropDistStr ਕਿਲੋਮੀਟਰ।")
                else if (dropLoc.isNotEmpty()) sb.append(" ਡ੍ਰੌਪ $dropLoc।")
                sb.toString().trim()
            }
            else -> {
                // Natural spoken Hindi/Hinglish format
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
                sb.toString().trim()
            }
        }
    }

    /**
     * Announces an order acceptance confirmation in the selected language.
     */
    fun announceRideAccepted(context: Context, fare: Double?, distance: Double?) {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) return

        val lang = AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT)
        val fareInt = fare?.toInt()

        val message = when (lang) {
            "hi" -> if (fareInt != null) "राइड स्वीकार कर लिया गया! किराया $fareInt रुपये।" else "राइड स्वीकार कर लिया गया!"
            "gu" -> if (fareInt != null) "રાઇડ સ્વીકારવામાં આવી! ભાડું $fareInt રૂપિયા." else "રાઇડ સ્વીકારવામાં આવી!"
            "mr" -> if (fareInt != null) "राइड स्वीकारली गेली! भाडे $fareInt रुपये." else "राइड स्वीकारली गेली!"
            "bn" -> if (fareInt != null) "রাইড গ্রহণ করা হয়েছে! ভাড়া $fareInt টাকা।" else "রাইড গ্রহণ করা হয়েছে!"
            "ta" -> if (fareInt != null) "சவாரி ஏற்கப்பட்டது! கட்டணம் $fareInt ரூபாய்." else "சவாரி ஏற்கப்பட்டது!"
            "te" -> if (fareInt != null) "రైడ్ ఆమోదించబడింది! ఛార్జీ $fareInt రూపాయలు." else "రైడ్ ఆమోదించబడింది!"
            "kn" -> if (fareInt != null) "ರೈಡ್ ಸ್ವೀಕರಿಸಲಾಗಿದೆ! ಬಾಡಿಗೆ $fareInt ರೂಪಾಯಿ." else "ರೈಡ್ ಸ್ವೀಕರಿಸಲಾಗಿದೆ!"
            "ml" -> if (fareInt != null) "റൈഡ് സ്വീകരിച്ചു! കൂലി $fareInt രൂപ." else "റൈഡ് സ്വീകരിച്ചു!"
            "pa" -> if (fareInt != null) "ਰਾਈਡ ਸਵੀਕਾਰ ਕਰ ਲਈ ਗਈ! ਕਿਰਾਇਆ $fareInt ਰੁਪਏ।" else "ਰਾਈਡ ਸਵੀਕਾਰ ਕਰ ਲਈ ਗਈ!"
            else -> if (fareInt != null) "Ride accepted! Fare is $fareInt rupees." else "Ride accepted!"
        }

        speak(context, message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Announces when an offer does not meet captain criteria in the selected language.
     */
    fun announceRideSkipped(context: Context, reason: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!AppSettings.isVoiceAnnouncerEnabled(context)) return

        val lang = AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT)

        val message = when (lang) {
            "hi" -> when {
                reason.contains("< Min Limit", ignoreCase = true) || reason.contains("below minimum", ignoreCase = true) -> "राइड छोड़ दिया गया: किराया न्यूनतम से कम है।"
                reason.contains("> Max Limit", ignoreCase = true) || reason.contains("exceeds maximum", ignoreCase = true) -> "राइड छोड़ दिया गया: किराया अधिकतम सीमा से अधिक है।"
                reason.contains("Pickup distance", ignoreCase = true) || reason.contains("exceeds set maximum limit", ignoreCase = true) -> "राइड छोड़ दिया गया: पिकअप दूरी सीमा से अधिक है।"
                else -> "राइड छोड़ दिया गया।"
            }
            "gu" -> when {
                reason.contains("< Min Limit", ignoreCase = true) || reason.contains("below minimum", ignoreCase = true) -> "રાઇડ છોડી દેવામાં આવી: ભાડું ન્યૂનતમથી ઓછું છે."
                reason.contains("> Max Limit", ignoreCase = true) || reason.contains("exceeds maximum", ignoreCase = true) -> "રાઇડ છોડી દેવામાં આવી: ભાડું મહત્તમથી વધુ છે."
                else -> "રાઇડ છોડી દેવામાં આવી."
            }
            "mr" -> when {
                reason.contains("< Min Limit", ignoreCase = true) || reason.contains("below minimum", ignoreCase = true) -> "राइड सोडली गेली: भाडे किमान पेक्षा कमी आहे."
                else -> "राइड सोडली गेली."
            }
            else -> when {
                reason.contains("< Min Limit", ignoreCase = true) || reason.contains("below minimum", ignoreCase = true) -> "Ride skipped: fare is below minimum."
                reason.contains("> Max Limit", ignoreCase = true) || reason.contains("exceeds maximum", ignoreCase = true) -> "Ride skipped: fare exceeds maximum limit."
                reason.contains("Pickup distance", ignoreCase = true) || reason.contains("exceeds set maximum limit", ignoreCase = true) -> "Ride skipped: pickup distance exceeds maximum limit threshold."
                else -> "Ride skipped."
            }
        }

        speak(context, message, queueMode)
    }

    /**
     * Helper to test the voice announcer from UI.
     */
    fun testAnnouncement(context: Context, customMessage: String? = null) {
        val lang = AppSettings.getVoiceLanguage(context).lowercase(Locale.ROOT)
        val message = customMessage ?: when (lang) {
            "hi" -> "आवाज़ उद्घोषक चालू है! किराया 120 रुपये, दूरी 3 किलोमीटर।"
            "gu" -> "અવાજ ઉદ્ઘોષક ચાલુ છે! ભાડું 120 રૂપિયા, અંતર 3 કિલોમીટર."
            "mr" -> "व्हॉइस अनाउंसर चालू आहे! भाडे 120 रुपये, अंतर 3 किलोमीटर."
            "bn" -> "ভয়েস অ্যানাউন্সার চালু আছে! ভাড়া 120 টাকা, দূরত্ব 3 কিলোমিটার।"
            "ta" -> "குரல் அறிவிப்பாளர் செயல்படுகிறது! கட்டணம் 120 ரூபாய், தூரம் 3 கிலோமீட்டர்."
            "te" -> "వాయిస్ అనౌన్సర్ యాక్టివ్‌గా ఉంది! ఛార్జీ 120 రూపాయలు, దూరం 3 కిలోమీటర్లు."
            "kn" -> "ಧ್ವನಿ ಘೋಷಕ ಸಕ್ರಿಯವಾಗಿದೆ! ಬಾಡಿಗೆ 120 ರೂಪಾಯಿ, ದೂರ 3 ಕಿಲೋಮೀಟರ್."
            "ml" -> "വോയ്‌സ് അനൗൺസർ പ്രവർത്തനക്ഷമമാണ്! കൂലി 120 രൂപ, ദൂരം 3 കിലോമീറ്റർ."
            "pa" -> "ਵਾਇਸ ਅਨਾਊਂਸਰ ਸਰਗਰਮ ਹੈ! ਕਿਰਾਇਆ 120 ਰੁਪਏ, ਦੂਰੀ 3 ਕਿਲੋਮੀਟਰ।"
            else -> "Voice announcer is active! Fare is 120 rupees, distance 3 kilometers."
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

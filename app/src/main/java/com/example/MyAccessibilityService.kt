package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Production-ready Android Accessibility Service that monitors on-screen ride/order requests
 * from driver apps (specifically Rapido Captain: com.rapido.rider) and automatically
 * executes an "Accept" action when user-configured criteria (Min Fare, Max Pickup Distance) are met.
 *
 * Maintains BFS tree traversal, clickable parent discovery, event debouncing, duplicate suppression,
 * native Text-to-Speech voice announcements, and thread-safe volatile execution guards.
 */
open class MyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "MyAccessibilityService"
        const val TARGET_PACKAGE = "com.rapido.rider"
        const val COOLDOWN_MS = 2000L // 2 seconds delay between checks

        @Volatile
        var isServiceRunning: Boolean = false
            protected set

        @Volatile
        var instance: MyAccessibilityService? = null
            protected set
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isPendingExecution: Boolean = false

    @Volatile
    private var lastProcessTime: Long = 0L

    @Volatile
    private var lastEvaluatedText: String = ""

    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var isTtsReady: Boolean = false

    override fun onCreate() {
        super.onCreate()
        initTextToSpeech()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this
        initTextToSpeech()
        Log.d(TAG, "Accessibility Service connected and operational.")
        AppSettings.addLog(
            title = "Service Connected",
            message = "Rapido Captain Auto-Accept Accessibility Service is active and monitoring.",
            severity = LogSeverity.INFO
        )
        broadcastLog("Accessibility Service started and monitoring events.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (instance === this) {
            instance = null
        }
        mainHandler.removeCallbacksAndMessages(null)
        cleanupTextToSpeech()
        Log.d(TAG, "Accessibility Service stopped.")
        AppSettings.addLog(
            title = "Service Stopped",
            message = "Accessibility Service has been stopped or unbound.",
            severity = LogSeverity.INFO
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted.")
        isPendingExecution = false
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Strict Package Check: Trigger on Rapido Captain App (com.rapido.rider) or in-app simulation testing
        val eventPackage = event.packageName?.toString() ?: ""
        if (eventPackage != TARGET_PACKAGE &&
            !eventPackage.contains("rapido", ignoreCase = true) &&
            eventPackage != packageName
        ) {
            return
        }

        // Only monitor state and content change events
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // Check if master switch is enabled
        if (!AppSettings.isAutoAcceptEnabled(this)) {
            return
        }

        // Debounce check: skip frequent event spams within cooldown window
        val currentTime = System.currentTimeMillis()
        if (isPendingExecution || (currentTime - lastProcessTime < COOLDOWN_MS)) {
            return
        }

        val rootNode = rootInActiveWindow ?: return
        val extractedText = extractAllText(rootNode)

        // 2. Duplicate Offer Check: Skip if exact same content was already evaluated
        if (extractedText == lastEvaluatedText || extractedText.isBlank()) {
            recycleNode(rootNode)
            return
        }

        // Processing Logic
        val offer = TextAnalysisEngine.parse(extractedText)
        lastEvaluatedText = extractedText
        lastProcessTime = currentTime

        evaluateAndAction(offer, rootNode)
    }

    /**
     * Evaluates parsed ride offer parameters against configured thresholds
     * and triggers automatic acceptance if criteria are satisfied.
     */
    private fun evaluateAndAction(offer: ParsedRideOffer, rootNode: AccessibilityNodeInfo) {
        try {
            // If offer has no detectable fare or distance, skip action
            if (offer.totalFare == null && offer.pickupDistanceKm == null) {
                return
            }

            val minFare = AppSettings.getMinFare(this)
            val maxPickupDist = AppSettings.getMaxPickupDistance(this)
            val isEnabled = AppSettings.isAutoAcceptEnabled(this)

            val evaluation = TextAnalysisEngine.evaluateRideOffer(
                offer = offer,
                minFare = minFare,
                maxPickupDistance = maxPickupDist,
                isAutoAcceptEnabled = isEnabled
            )

            // Voice announcement of incoming ride request (QUEUE_FLUSH to cancel any older speech)
            announceNewRide(offer)

            if (evaluation.isAccepted) {
                isPendingExecution = true

                val delayMs = AppSettings.getClickDelayMs(this)
                val breakdownText = if (offer.fareBreakdown.size > 1) {
                    " (${offer.fareBreakdown.joinToString(" + ") { "₹$it" }})"
                } else ""

                AppSettings.addLog(
                    title = "Ride Match Found!",
                    message = "Fare: ₹${offer.totalFare}$breakdownText, Pickup: ${offer.pickupDistanceKm}km. Auto-clicking Accept in ${delayMs}ms...",
                    severity = LogSeverity.MATCH_ACCEPTED,
                    evaluation = evaluation
                )
                broadcastLog("Matched: Fare ₹${offer.totalFare}, Pickup ${offer.pickupDistanceKm}km. Accepting in ${delayMs}ms...")

                mainHandler.postDelayed({
                    executeAcceptClick(evaluation)
                }, delayMs)
            } else {
                // Log and announce rejected/skipped offer without re-triggering
                AppSettings.addLog(
                    title = "Offer Evaluated (Skipped)",
                    message = evaluation.decisionReason,
                    severity = LogSeverity.REJECTED,
                    evaluation = evaluation
                )
                announceRideSkipped(evaluation.decisionReason)
            }
        } finally {
            recycleNode(rootNode)
        }
    }

    /**
     * Extracts all text and content descriptions from the active node tree.
     * Recursively traverses nodes with safe node recycling.
     */
    private fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        collectAllTextRecursive(node, sb)
        return sb.toString().trim()
    }

    private fun collectAllTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return

        val text = node.text
        if (!text.isNullOrEmpty()) {
            sb.append(text).append(" ")
        }

        val desc = node.contentDescription
        if (!desc.isNullOrEmpty()) {
            sb.append(desc).append(" ")
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectAllTextRecursive(child, sb)
            } finally {
                recycleNode(child)
            }
        }
    }

    /**
     * Traverses the active window hierarchy to locate and trigger ACTION_CLICK on the Accept button.
     */
    private fun executeAcceptClick(evaluation: RideEvaluation) {
        isPendingExecution = false
        lastProcessTime = System.currentTimeMillis()

        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "Cannot execute Accept click: rootInActiveWindow is null")
            return
        }

        var acceptNode: AccessibilityNodeInfo? = null
        var targetClickable: AccessibilityNodeInfo? = null

        try {
            acceptNode = findAcceptNode(rootNode)
            if (acceptNode == null) {
                Log.w(TAG, "Accept node was not found during execution phase")
                AppSettings.addLog(
                    title = "Click Failed",
                    message = "Accept node disappeared before click could be delivered.",
                    severity = LogSeverity.WARNING,
                    evaluation = evaluation
                )
                return
            }

            // Find nearest clickable container (either the button itself or clickable parent)
            targetClickable = AccessibilityNodeInfo.obtain(acceptNode)
            while (targetClickable != null && !targetClickable.isClickable) {
                val parent = targetClickable.parent
                recycleNode(targetClickable)
                targetClickable = parent
            }

            val finalNode = targetClickable ?: acceptNode
            val clickSucceeded = finalNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            Log.i(TAG, "Executed ACTION_CLICK on Accept. Success = $clickSucceeded")
            AppSettings.addLog(
                title = if (clickSucceeded) "Order Accepted!" else "Click Attempted",
                message = "ACTION_CLICK delivered to '${finalNode.className}'. Result = $clickSucceeded",
                severity = if (clickSucceeded) LogSeverity.CLICK_EXECUTED else LogSeverity.WARNING,
                evaluation = evaluation
            )
            broadcastLog("ACTION_CLICK executed on Accept node! Success: $clickSucceeded")

            if (clickSucceeded) {
                announceRideAccepted(evaluation.totalCurrency, evaluation.distanceKm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while executing accept click", e)
        } finally {
            if (targetClickable != null && targetClickable !== acceptNode) {
                recycleNode(targetClickable)
            }
            if (acceptNode != null) {
                recycleNode(acceptNode)
            }
            recycleNode(rootNode)
        }
    }

    /**
     * Finds and returns the first Accept node in the hierarchy using Breadth-First Search (BFS).
     * The caller is responsible for recycling the returned node when done.
     */
    private fun findAcceptNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            if (isAcceptAction(text) || isAcceptAction(desc)) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                return node
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
            recycleNode(node)
        }
        return null
    }

    private fun isAcceptAction(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.equals("Accept", ignoreCase = true) ||
                trimmed.equals("Accept Order", ignoreCase = true) ||
                trimmed.equals("Accept Ride", ignoreCase = true) ||
                trimmed.contains("Swipe to Accept", ignoreCase = true) ||
                trimmed.contains("Accept", ignoreCase = true)
    }

    /**
     * Recycles an AccessibilityNodeInfo instance on Android versions < 14 (API 34).
     * On Android 14+, node recycling is handled automatically by the system.
     */
    protected fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        } catch (_: IllegalStateException) {
            // Already recycled or invalid instance
        }
    }

    private fun broadcastLog(message: String) {
        try {
            val intent = Intent(AppSettings.ACTION_ACCESSIBILITY_LOG).apply {
                putExtra(AppSettings.EXTRA_LOG_MESSAGE, message)
                setPackage(packageName)
            }
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send log broadcast", e)
        }
    }

    // =========================================================================================
    // TEXT-TO-SPEECH (TTS) ENGINE & ANNOUNCEMENTS
    // =========================================================================================

    /**
     * Safely initializes the native Android TextToSpeech engine with OnInitListener.
     */
    private fun initTextToSpeech() {
        if (textToSpeech != null) return
        try {
            textToSpeech = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsReady = true
                    applyTtsLanguage()
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
     * Sets speech language to localized Hindi or English based on user settings,
     * with graceful fallbacks if language data is missing.
     */
    fun applyTtsLanguage() {
        val tts = textToSpeech ?: return
        val lang = AppSettings.getVoiceLanguage(this)
        val targetLocale = if (lang.equals("hi", ignoreCase = true)) {
            Locale("hi", "IN")
        } else {
            Locale("en", "IN")
        }

        try {
            val result = tts.setLanguage(targetLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $targetLocale not supported/missing data (code $result), falling back to English.")
                tts.setLanguage(Locale.ENGLISH)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS language to $targetLocale", e)
            try {
                tts.setLanguage(Locale.getDefault())
            } catch (_: Exception) {}
        }
    }

    /**
     * Shuts down and cleans up the native TextToSpeech engine instance.
     */
    private fun cleanupTextToSpeech() {
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
            isTtsReady = false
            Log.d(TAG, "TextToSpeech engine released and cleaned up.")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up TextToSpeech instance", e)
        }
    }

    /**
     * Speaks the given text using the configured queue management mode.
     * QUEUE_FLUSH cancels any current speech immediately for new high-priority offers.
     * QUEUE_ADD appends to speech queue for sequential alerts.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) {
            return
        }

        if (!isTtsReady || textToSpeech == null) {
            Log.w(TAG, "TextToSpeech engine is not ready, skipping speech: $text")
            return
        }

        try {
            applyTtsLanguage()
            val utteranceId = "tts_ride_${System.currentTimeMillis()}"
            textToSpeech?.speak(text, queueMode, null, utteranceId)
            Log.d(TAG, "TTS announced [queue=$queueMode]: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to speak TTS announcement", e)
        }
    }

    /**
     * Announces a newly detected ride offer with fare and distance details.
     */
    fun announceNewRide(offer: ParsedRideOffer) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) return

        val lang = AppSettings.getVoiceLanguage(this)
        val fare = offer.totalFare?.toInt()
        val dist = offer.pickupDistanceKm ?: offer.dropDistanceKm

        val message = if (lang.equals("hi", ignoreCase = true)) {
            when {
                fare != null && dist != null -> "नया राइड मिला! किराया $fare रुपये, दूरी $dist किलोमीटर।"
                fare != null -> "नया राइड मिला! किराया $fare रुपये।"
                dist != null -> "नया राइड मिला! दूरी $dist किलोमीटर।"
                else -> "नया राइड मिला!"
            }
        } else {
            when {
                fare != null && dist != null -> "New ride received! Fare is $fare rupees, distance $dist kilometers."
                fare != null -> "New ride received! Fare is $fare rupees."
                dist != null -> "New ride received! Distance $dist kilometers."
                else -> "New ride received!"
            }
        }

        speak(message, TextToSpeech.QUEUE_FLUSH)
    }

    /**
     * Announces an order acceptance confirmation.
     */
    fun announceRideAccepted(fare: Double?, distance: Double?) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) return

        val lang = AppSettings.getVoiceLanguage(this)
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

        // QUEUE_ADD allows confirmation to follow the new ride announcement smoothly
        speak(message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Announces when an offer does not meet captain criteria.
     */
    fun announceRideSkipped(reason: String) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) return

        val lang = AppSettings.getVoiceLanguage(this)
        val message = if (lang.equals("hi", ignoreCase = true)) {
            "राइड छोड़ दिया गया।"
        } else {
            "Ride skipped."
        }

        speak(message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Public helper to test the voice announcer from UI.
     */
    fun testAnnouncement(customMessage: String? = null) {
        val lang = AppSettings.getVoiceLanguage(this)
        val message = customMessage ?: if (lang.equals("hi", ignoreCase = true)) {
            "आवाज़ उद्घोषक चालू है! किराया 120 रुपये, दूरी 3 किलोमीटर।"
        } else {
            "Voice announcer is active! Fare is 120 rupees, distance 3 kilometers."
        }
        speak(message, TextToSpeech.QUEUE_FLUSH)
    }
}

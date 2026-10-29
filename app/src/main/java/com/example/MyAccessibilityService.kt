package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Production-ready Android Accessibility Service that monitors on-screen ride/order requests
 * from driver apps (specifically Rapido Captain: com.rapido.rider) and automatically
 * executes an "Accept" action when user-configured criteria (Min Fare, Max Pickup Distance) are met.
 *
 * Implements isolated UI Tree Parsing and Grouping to prevent text cross-contamination between
 * simultaneous offers, a sequential multi-order evaluation queue, bounds-targeted click execution,
 * duplicate suppression via offer hashing, and sequential Text-to-Speech voice announcements (QUEUE_ADD).
 */
open class MyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "MyAccessibilityService"
        const val TARGET_PACKAGE = "com.rapido.rider"
        const val DEBOUNCE_WINDOW_MS = 100L // 100ms debounce to balance ultra-fast response with frame rate stability
        const val COOLDOWN_MS = 2000L // 2 seconds delay between order acceptance

        @Volatile
        var isServiceRunning: Boolean = false
            protected set

        @Volatile
        var instance: MyAccessibilityService? = null
            protected set

        // Compiled regular expressions for resilient multi-language offer action detection
        val ACCEPT_REGEX = Regex(
            pattern = """(?i)^(?:.*?\b(?:accept|auto[\s_-]*accept|confirm|select|take\s*(?:the\s*)?(?:ride|order)|swipe\s*to\s*accept)\b.*?|^go$|(?:.*?(?:स्वीकार|स्वीकारें|कन्फर्म|चुनें|चुने|राइड\s*लें|ऑर्डर\s*लें|sweekar|sweekarein|swikar|swikarein|accept\s*karein|confirm\s*karein).*?))$""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        val REJECT_REGEX = Regex(
            pattern = """(?i)^(?:.*?\b(?:reject|decline|skip|cancel|pass)\b.*?|(?:.*?(?:अस्वीकार|अस्वीकारें|छोड़ें|छोड़े|रद्द|रद्द\s*करें|मना\s*करें|नकारें|asveekar|asweekar|aswikar|chhodein|chodein|radd|cancel\s*karein|reject\s*karein|decline\s*karein|mana\s*karein).*?))$""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        val RAPIDO_ACCEPT_ID_REGEX = Regex(
            pattern = """(?i)(?=.*(?:accept|confirm|auto[_-]?accept|take[_-]?ride))(?!.*(?:reject|cancel|decline|unconfirm)).*"""
        )

        val RAPIDO_REJECT_ID_REGEX = Regex(
            pattern = """(?i)(?=.*(?:reject|decline|cancel|skip|pass))(?!.*(?:accept|confirm)).*"""
        )

        // Low-cost signal detection regex for fast event pre-filtering
        private val OFFER_SIGNAL_REGEX = Regex(
            pattern = """(?i)(?:[₹₹]|inr|rs\.?|\bkm\b|pickup|drop|accept|reject|confirm|decline|pass|skip|order|ride|fare|cash|स्वीकार|अस्वीकार|sweekar|asweekar|captain)"""
        )

        // Class names that indicate high-frequency telemetry / map rendering events that should be dropped immediately
        private val IGNORED_EVENT_CLASS_REGEX = Regex(
            pattern = """(?i)(?:map|surfaceview|textureview|glsurface|chronometer|progressbar|compass|sensor)"""
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var isPendingExecution: Boolean = false

    @Volatile
    private var lastProcessTime: Long = 0L

    // Cache of recently processed offer hashes to prevent re-evaluating the same order repeatedly
    private val processedOfferIds = LinkedHashSet<String>()
    private val maxProcessedHistory = 50

    private val accessCheckRunnable = object : Runnable {
        override fun run() {
            val isPro = PassManager.isAccessGranted(this@MyAccessibilityService)
            Log.d(TAG, "Periodic 30s check: Mode = ${if (isPro) "PRO MODE (Active Pass/Trial)" else "FREE MODE (Blind Accept)"}")
            mainHandler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        TTSManager.init(this)

        // Continuous 30-second background verification of licensing & pass status
        mainHandler.postDelayed(accessCheckRunnable, 30_000L)

        // Reactive listener for licensing & pass status changes
        serviceScope.launch {
            LicenseManager.accessStatus.collectLatest { status ->
                val isPro = when (status) {
                    is AccessStatus.PassActive -> status.remainingTimeMs > 0
                    is AccessStatus.TrialActive -> status.remainingTimeMs > 0
                    else -> false
                }
                Log.i(TAG, "LicenseManager access status changed: $status -> Operating in ${if (isPro) "PRO MODE" else "FREE MODE"}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this

        // CRITICAL: Explicitly set AccessibilityServiceInfo packageNames to target driver app only
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.packageNames = arrayOf(TARGET_PACKAGE)
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        serviceInfo = info

        TTSManager.init(this)
        Log.d(TAG, "Accessibility Service connected and operational for package: $TARGET_PACKAGE.")
        AppSettings.addLog(
            title = "Service Connected",
            message = "Rapido Captain Auto-Accept Accessibility Service active for $TARGET_PACKAGE.",
            severity = LogSeverity.INFO
        )
        broadcastLog("Accessibility Service started and monitoring $TARGET_PACKAGE.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (instance === this) {
            instance = null
        }
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        TTSManager.shutdown()
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

        // 1. Strict Target Package Filtering (Prevent Self-Scanning & Infinite Loops)
        // If event.packageName is null, empty, equals this app's own packageName, or DOES NOT equal "com.rapido.rider", ignore it immediately.
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg.isEmpty() || eventPkg == packageName || eventPkg != TARGET_PACKAGE) {
            return
        }

        // 2. Monitor specified event types: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_CLICKED
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        // 3. Master switch check: skip if auto-accept is turned off
        if (!AppSettings.isAutoAcceptEnabled(this)) {
            return
        }

        // 3b. Operating Mode Detection (Freemium Model)
        // Free Mode runs without stopping the accessibility service; Pro Mode enables custom filters.
        val isProMode = PassManager.isAccessGranted(this)

        // 4. Low-Cost Event Pre-Filtering (Battery Drain & CPU Optimization):
        // Inspect event text, content description, class name, and shallow source node
        // to immediately drop high-frequency non-offer events (map movements, GPS telemetry, clock ticks)
        // before executing heavy window retrieval and deep recursive UI tree traversal.
        if (!isPotentialOfferEvent(event)) {
            return
        }

        // 5. Event Debouncing & Throttle: minimum delay between processing events to prevent millisecond spam
        val currentTime = System.currentTimeMillis()
        if (isPendingExecution || (currentTime - lastProcessTime < DEBOUNCE_WINDOW_MS)) {
            return
        }
        // Advance timestamp immediately so subsequent events within the debounce window are discarded
        lastProcessTime = currentTime

        // 6. Multi-Window & Overlay Support: Retrieve root nodes strictly belonging to target driver app
        val rawRootWindows = getAllRootWindows()
        if (rawRootWindows.isEmpty()) return

        // 1. Strict Rapido Overlay Popup Window Detection:
        // Filter out floating overlay widget bubble background node trees (which contain stored history values)
        val candidateWindows = rawRootWindows.filter { !isFloatingWidgetOrBubble(it) }
        if (candidateWindows.isEmpty()) {
            for (root in rawRootWindows) {
                recycleNode(root)
            }
            return
        }

        // STRICT REQUIREMENT: If no valid "Accept", "Reject", or "Order Offer" view ID/text node is found on screen,
        // immediately exit node evaluation.
        if (!hasActiveRideOfferOnScreen(candidateWindows)) {
            for (root in rawRootWindows) {
                recycleNode(root)
            }
            return
        }

        var parsedOffers: List<ParsedRideOffer>? = null
        try {
            // 1. Card-Level Layout Isolation: Extract distinct order card containers across candidate windows
            // and parse each container independently into a strictly isolated ParsedRideOffer.
            // Texts from different cards are NEVER merged.
            val offers = RideOfferParser.parseCards(candidateWindows)
            parsedOffers = offers

            if (offers.isEmpty()) {
                return
            }

            // Deduplication Check: Check if all detected offers have already been processed in current cycle
            val newOffers = offers.filter { !processedOfferIds.contains(it.id) }
            if (newOffers.isEmpty()) {
                return
            }

            // 2. Multi-Order Processing Queue
            processOrderQueue(offers, candidateWindows, isProMode)

        } finally {
            parsedOffers?.forEach { offer ->
                offer.acceptButton?.let { recycleNode(it) }
            }
            for (root in rawRootWindows) {
                recycleNode(root)
            }
        }
    }

    /**
     * Fast, low-cost pre-filtering check to eliminate battery drain and CPU spikes.
     * Prevents invoking getAllRootWindows() and deep UI tree scans on frequent non-offer events
     * such as continuous GPS map re-rendering, animations, and clock ticks.
     */
    private fun isPotentialOfferEvent(event: AccessibilityEvent): Boolean {
        // TYPE_WINDOW_STATE_CHANGED represents new dialogs, bottom sheets, or activities popping up.
        // Always permit this event type for full evaluation.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return true
        }

        val className = event.className?.toString() ?: ""

        // Discard high-frequency map animations, GL surfaces, and background sensor widgets immediately
        if (className.isNotEmpty() && IGNORED_EVENT_CLASS_REGEX.containsMatchIn(className)) {
            return false
        }

        // 1. Fast inspection of event text and content description for offer indicators
        val eventContent = buildString {
            if (event.text.isNotEmpty()) {
                append(event.text.joinToString(separator = " "))
            }
            event.contentDescription?.let {
                if (isNotEmpty()) append(" ")
                append(it)
            }
        }

        if (eventContent.isNotEmpty()) {
            if (OFFER_SIGNAL_REGEX.containsMatchIn(eventContent) ||
                isAcceptAction(eventContent) ||
                isRejectAction(eventContent)
            ) {
                return true
            }
        }

        // 2. Shallow inspection of event source node (depth <= 1, max 8 children)
        val source = try {
            event.source
        } catch (_: Exception) {
            null
        }

        if (source != null) {
            try {
                val sourceText = source.text?.toString() ?: ""
                val sourceDesc = source.contentDescription?.toString() ?: ""
                val sourceId = source.viewIdResourceName ?: ""

                if (isAcceptAction(sourceText) || isAcceptAction(sourceDesc) || isRapidoAcceptId(sourceId) ||
                    isRejectAction(sourceText) || isRejectAction(sourceDesc) || isRapidoRejectId(sourceId) ||
                    OFFER_SIGNAL_REGEX.containsMatchIn(sourceText) ||
                    OFFER_SIGNAL_REGEX.containsMatchIn(sourceDesc)
                ) {
                    return true
                }

                // Check immediate children only (bounded to 8 nodes max)
                val childLimit = minOf(source.childCount, 8)
                for (i in 0 until childLimit) {
                    val child = source.getChild(i)
                    if (child != null) {
                        val cText = child.text?.toString() ?: ""
                        val cDesc = child.contentDescription?.toString() ?: ""
                        val cId = child.viewIdResourceName ?: ""

                        val match = isAcceptAction(cText) || isAcceptAction(cDesc) || isRapidoAcceptId(cId) ||
                                isRejectAction(cText) || isRejectAction(cDesc) || isRapidoRejectId(cId) ||
                                OFFER_SIGNAL_REGEX.containsMatchIn(cText) ||
                                OFFER_SIGNAL_REGEX.containsMatchIn(cDesc)
                        recycleNode(child)
                        if (match) {
                            return true
                        }
                    }
                }
            } finally {
                recycleNode(source)
            }
        }

        // If no offer signal is detected in the event text, description, or immediate source node, discard it.
        return false
    }

    /**
     * Sequential multi-order processing queue.
     * Evaluates multiple simultaneous order offers isolated by tree grouping, logs card-level validation,
     * speaks announcements in order (QUEUE_ADD), and executes ACTION_CLICK strictly within the bounds of the best offer.
     */
    private fun processOrderQueue(offers: List<ParsedRideOffer>, rootWindows: List<AccessibilityNodeInfo>, isProMode: Boolean) {
        val isAutoAccept = AppSettings.isAutoAcceptEnabled(this)

        // UI Logging Requirement: Log total distinct order cards detected
        AppSettings.addLog(
            title = "Orders Detected",
            message = "Detected ${offers.size} distinct order card${if (offers.size > 1) "s" else ""} on screen (${if (isProMode) "Pro Mode" else "Free Mode"}).",
            severity = LogSeverity.INFO
        )
        broadcastLog("Detected ${offers.size} distinct order card${if (offers.size > 1) "s" else ""} [${if (isProMode) "Pro" else "Free"}]")

        // Pay-Per-Use Feature Checks:
        val isFareFiltersUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.FARE_FILTERS)
        val isDistanceFiltersUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.DISTANCE_FILTERS)
        val isBlacklistUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST)
        val isAutoClickSpeedUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK)
        val isTtsVoiceUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)

        val minFare = AppSettings.getMinFare(this)
        val maxFare = AppSettings.getMaxFare(this)
        val maxPickupDist = AppSettings.getMaxPickupDistance(this)
        val maxDropDist = AppSettings.getMaxDropDistance(this)
        val minPerKmRate = AppSettings.getMinPerKmRate(this)
        val blockedLocations = AppSettings.getBlockedLocations(this)

        // Dynamically override user's filter toggles based on feature unlock status
        val isMinFareEnabled = isFareFiltersUnlocked && AppSettings.isMinFareEnabled(this)
        val isMaxFareEnabled = isFareFiltersUnlocked && AppSettings.isMaxFareEnabled(this)
        val isMinPerKmRateEnabled = isFareFiltersUnlocked && AppSettings.isMinPerKmRateEnabled(this)
        val isMaxPickupDistEnabled = isDistanceFiltersUnlocked && AppSettings.isMaxPickupDistanceEnabled(this)
        val isMaxDropDistEnabled = isDistanceFiltersUnlocked && AppSettings.isMaxDropDistanceEnabled(this)
        val isDestinationBlacklistEnabled = isBlacklistUnlocked && AppSettings.isDestinationBlacklistEnabled(this)
        val effectiveBlockedLocations = if (isBlacklistUnlocked) blockedLocations else emptyList()

        val evaluatedOffers = mutableListOf<Pair<ParsedRideOffer, RideEvaluation>>()

        // Sequential Queue Evaluation: Always evaluate offers via TextAnalysisEngine with effective filter settings
        offers.forEachIndexed { index, offer ->
            // Record in deduplication set
            synchronized(processedOfferIds) {
                if (processedOfferIds.size >= maxProcessedHistory) {
                    val firstKey = processedOfferIds.iterator().next()
                    processedOfferIds.remove(firstKey)
                }
                processedOfferIds.add(offer.id)
            }

            val evaluation = TextAnalysisEngine.evaluateRideOffer(
                offer = offer,
                minFare = minFare,
                maxFare = maxFare,
                maxPickupDistance = maxPickupDist,
                isAutoAcceptEnabled = isAutoAccept,
                isMinFareEnabled = isMinFareEnabled,
                isMaxFareEnabled = isMaxFareEnabled,
                isMaxPickupDistanceEnabled = isMaxPickupDistEnabled,
                isMaxDropDistanceEnabled = isMaxDropDistEnabled,
                maxDropDistance = maxDropDist,
                minPerKmRate = minPerKmRate,
                isMinPerKmRateEnabled = isMinPerKmRateEnabled,
                blockedLocations = effectiveBlockedLocations,
                isDestinationBlacklistEnabled = isDestinationBlacklistEnabled
            )

            evaluatedOffers.add(offer to evaluation)
            AppSettings.recordEvaluation(this, evaluation)

            val cardNum = index + 1
            val fareVal = offer.totalFare?.toInt() ?: 0
            val distVal = String.format(Locale.US, "%.1f", offer.pickupDistanceKm ?: offer.dropDistanceKm ?: 0.0)

            // UI Logging Requirement: Card 1: ₹120, 1.5km - VALID / INVALID
            if (evaluation.isAccepted) {
                AppSettings.addLog(
                    title = "Card $cardNum Valid",
                    message = "Card $cardNum: ₹$fareVal, ${distVal}km - VALID",
                    severity = LogSeverity.MATCH_ACCEPTED,
                    evaluation = evaluation
                )
                broadcastLog("Card $cardNum: ₹$fareVal, ${distVal}km - VALID")
            } else {
                AppSettings.addLog(
                    title = "Card $cardNum Filtered",
                    message = "Card $cardNum: ₹$fareVal, ${distVal}km - INVALID (${evaluation.decisionReason})",
                    severity = LogSeverity.REJECTED,
                    evaluation = evaluation
                )
                broadcastLog("Card $cardNum: ₹$fareVal, ${distVal}km - INVALID")
            }

            // TTS Announcements: Only announce if TTS Voice feature is unlocked
            if (isTtsVoiceUnlocked) {
                val spoke = announceNewRide(offer, queueMode = TextToSpeech.QUEUE_ADD)
                if (!evaluation.isAccepted && spoke) {
                    announceRideSkipped(evaluation.decisionReason, queueMode = TextToSpeech.QUEUE_ADD)
                }
            }
        }

        // Find best matching valid offer for auto-accept
        val matchingOffers = evaluatedOffers.filter { it.second.isAccepted }
        if (matchingOffers.isNotEmpty() && isAutoAccept) {
            val isAutoClick = AppSettings.isAutoClickEnabled(this)
            if (!isAutoClick) {
                // Voice-Only Mode (Auto Click Disabled / TTS Voice Announcement Only)
                val topOffer = matchingOffers.first().first
                AppSettings.addLog(
                    title = "Voice-Only Mode Active",
                    message = "Matching ride detected (₹${topOffer.totalFare?.toInt() ?: 0}). Auto-click disabled; please tap Accept manually.",
                    severity = LogSeverity.INFO
                )
                broadcastLog("Voice-only mode: ride matches filters, auto-click skipped.")
                return
            }

            // Select the highest fare offer among matching valid offers
            val bestCandidate = matchingOffers.maxByOrNull { it.first.totalFare ?: 0.0 } ?: matchingOffers.first()
            val bestOffer = bestCandidate.first
            val bestEvaluation = bestCandidate.second

            isPendingExecution = true
            // Acceptance Speed: If AUTO_CLICK is unlocked, use fast delay (300-700ms), else slow delay (1000-2000ms)
            val acceptDelayMs = if (isAutoClickSpeedUnlocked) {
                kotlin.random.Random.nextLong(300L, 700L)
            } else {
                kotlin.random.Random.nextLong(1000L, 2000L)
            }

            val speedLabel = if (isAutoClickSpeedUnlocked) "Fast (300-700ms)" else "Standard (1000-2000ms)"
            AppSettings.addLog(
                title = "Auto-Accept Scheduled",
                message = "Selected best offer: ₹${bestOffer.totalFare?.toInt() ?: 0}, ${bestOffer.pickupDistanceKm ?: 0.0}km. Auto-clicking Accept in ${acceptDelayMs}ms ($speedLabel)...",
                severity = LogSeverity.MATCH_ACCEPTED,
                evaluation = bestEvaluation
            )
            broadcastLog("Auto-clicking Accept for best offer (₹${bestOffer.totalFare?.toInt() ?: 0}) in ${acceptDelayMs}ms [$speedLabel]...")

            mainHandler.postDelayed({
                executeCardAcceptClick(bestOffer, bestEvaluation)
            }, acceptDelayMs)
        } else if (isAutoAccept && evaluatedOffers.isNotEmpty()) {
            // Smart Auto-Reject: Only execute if FARE_FILTERS or another relevant filter is unlocked
            val hasAnyFilterUnlocked = isFareFiltersUnlocked || isDistanceFiltersUnlocked || isBlacklistUnlocked
            if (hasAnyFilterUnlocked) {
                val isAutoClick = AppSettings.isAutoClickEnabled(this)
                if (isAutoClick) {
                    val (rejectedOffer, rejectedEval) = evaluatedOffers.first()
                    val delayMs = if (isAutoClickSpeedUnlocked) {
                        kotlin.random.Random.nextLong(300L, 700L)
                    } else {
                        kotlin.random.Random.nextLong(1000L, 2000L)
                    }
                    mainHandler.postDelayed({
                        executeCardRejectClick(rejectedOffer, rejectedEval)
                    }, delayMs)
                }
            } else {
                Log.d(TAG, "Auto-reject skipped: filters locked, allowing offer to time out.")
            }
        }
    }

    /**
     * Executes ACTION_CLICK strictly targeting the Reject/Pass button node
     * located inside the spatial bounds of an invalid/rejected order card to clear the screen.
     */
    private fun executeCardRejectClick(offer: ParsedRideOffer, evaluation: RideEvaluation) {
        // Security check: Active pass/trial OR relevant filter feature unlocked
        val hasAnyFilterUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.FARE_FILTERS) ||
                LicenseManager.isFeatureUnlocked(FeatureIds.DISTANCE_FILTERS) ||
                LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST)
        if (!hasAnyFilterUnlocked) {
            Log.w(TAG, "executeCardRejectClick: Auto-reject blocked. No filters unlocked.")
            return
        }

        // Debounce / Cooldown check: respect strict COOLDOWN_MS
        val now = System.currentTimeMillis()
        if (now < lastProcessTime) {
            Log.d(TAG, "executeCardRejectClick: Suppressed due to active cooldown.")
            return
        }

        var clicked = false
        val cardBounds = offer.bounds

        try {
            // 1. Search strictly within card bounds across active windows
            val rootWindows = getAllRootWindows()
            try {
                for (root in rootWindows) {
                    val targetNode = findRejectNodeInBounds(root, cardBounds) ?: continue
                    try {
                        val clickable = findClickableParentOrSelf(targetNode) ?: targetNode
                        clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "executeCardRejectClick: in-bounds ACTION_CLICK success=$clicked")
                        if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            clicked = dispatchTapGesture(clickable)
                            Log.i(TAG, "executeCardRejectClick: fallback gesture success=$clicked")
                        }
                        if (clickable !== targetNode) {
                            recycleNode(clickable)
                        }
                        if (clicked) break
                    } finally {
                        recycleNode(targetNode)
                    }
                }
            } finally {
                for (root in rootWindows) {
                    recycleNode(root)
                }
            }

            // 2. Fallback coordinate tap heuristic targeting typical bottom-left area of card bounds
            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !cardBounds.isEmpty) {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()

                // Target the left-most 15-20% and bottom 20% of the offer card (Pass/Reject button location)
                val rawTapX = cardBounds.left + (cardBounds.width() * 0.15f)
                val rawTapY = cardBounds.bottom - (cardBounds.height() * 0.2f)

                // Clamp strictly within card bounds and physical screen resolution
                val tapX = rawTapX.coerceIn(cardBounds.left.toFloat(), (cardBounds.right - 1).toFloat())
                    .coerceIn(0f, screenWidth - 1f)
                val tapY = rawTapY.coerceIn(cardBounds.top.toFloat(), (cardBounds.bottom - 1).toFloat())
                    .coerceIn(0f, screenHeight - 1f)

                clicked = dispatchTapAtCoordinates(tapX, tapY)
                Log.i(TAG, "executeCardRejectClick: coordinate fallback tap at ($tapX, $tapY) success=$clicked")
            }

            // Cooldown: 2000ms cooldown upon success to ignore lingering UI elements; standard debounce on failure
            lastProcessTime = if (clicked) {
                System.currentTimeMillis() + COOLDOWN_MS
            } else {
                System.currentTimeMillis()
            }

            AppSettings.addLog(
                title = if (clicked) "Order Auto-Dismissed" else "Reject Attempted",
                message = if (clicked) "Dismissed card via Reject action. (${evaluation.decisionReason}). Cooldown active (2000ms)."
                else "Reject button not found in card bounds; letting offer time out naturally.",
                severity = if (clicked) LogSeverity.REJECTED else LogSeverity.INFO,
                evaluation = evaluation
            )
            broadcastLog("Auto-reject executed: $clicked (${evaluation.decisionReason})")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing card reject click", e)
            lastProcessTime = System.currentTimeMillis()
        }
    }

    /**
     * Finds an actionable reject node strictly located inside the provided screen bounding box.
     */
    fun findRejectNodeInBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (isRejectAction(text) || isRejectAction(desc) || isRapidoRejectId(viewId)) {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                // Verify that the node lies inside the card's vertical and horizontal range
                val isInBounds = bounds.contains(nodeBounds.centerX(), nodeBounds.centerY()) ||
                        (nodeBounds.top >= bounds.top - 20 && nodeBounds.bottom <= bounds.bottom + 20)

                if (isInBounds && isActionableNodeOrChild(node)) {
                    while (queue.isNotEmpty()) {
                        recycleNode(queue.removeFirst())
                    }
                    return node
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            recycleNode(node)
        }
        return null
    }

    /**
     * Executes the ACTION_CLICK strictly targeting the Accept button node
     * located inside the spatial bounds of the selected best matching order card.
     */
    private fun executeCardAcceptClick(offer: ParsedRideOffer, evaluation: RideEvaluation) {
        isPendingExecution = false
        lastProcessTime = System.currentTimeMillis()

        var clicked = false
        val cardBounds = offer.bounds

        try {
            // 1. If the isolated raw card already has an acceptButton reference, try clicking that first
            val initialNode = offer.acceptButton
            if (initialNode != null) {
                try {
                    val clickableTarget = findClickableParentOrSelf(initialNode) ?: initialNode
                    clicked = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "executeCardAcceptClick: direct acceptButton click success=$clicked")
                    if (clickableTarget !== initialNode) {
                        recycleNode(clickableTarget)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "executeCardAcceptClick: direct acceptButton click failed, trying search in bounds", e)
                }
            }

            // 2. Search strictly within card bounds across active windows
            if (!clicked) {
                val rootWindows = getAllRootWindows()
                try {
                    for (root in rootWindows) {
                        val targetNode = findAcceptNodeInBounds(root, cardBounds) ?: continue
                        try {
                            val clickable = findClickableParentOrSelf(targetNode) ?: targetNode
                            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.i(TAG, "executeCardAcceptClick: in-bounds ACTION_CLICK success=$clicked")
                            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                clicked = dispatchTapGesture(clickable)
                                Log.i(TAG, "executeCardAcceptClick: fallback gesture success=$clicked")
                            }
                            if (clickable !== targetNode) {
                                recycleNode(clickable)
                            }
                            if (clicked) break
                        } finally {
                            recycleNode(targetNode)
                        }
                    }
                } finally {
                    for (root in rootWindows) {
                        recycleNode(root)
                    }
                }
            }

            // 3. Fallback coordinate tap heuristic targeting bottom-right corner of card bounds
            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !cardBounds.isEmpty) {
                val screenWidth = resources.displayMetrics.widthPixels.toFloat()
                val screenHeight = resources.displayMetrics.heightPixels.toFloat()

                // Target the right-most 15-20% and bottom 20% of the offer card
                val rawTapX = cardBounds.right - (cardBounds.width() * 0.15f)
                val rawTapY = cardBounds.bottom - (cardBounds.height() * 0.2f)

                // Clamp strictly within card bounds and physical screen resolution
                val tapX = rawTapX.coerceIn(cardBounds.left.toFloat(), (cardBounds.right - 1).toFloat())
                    .coerceIn(0f, screenWidth - 1f)
                val tapY = rawTapY.coerceIn(cardBounds.top.toFloat(), (cardBounds.bottom - 1).toFloat())
                    .coerceIn(0f, screenHeight - 1f)

                clicked = dispatchTapAtCoordinates(tapX, tapY)
                Log.i(TAG, "executeCardAcceptClick: coordinate fallback tap at ($tapX, $tapY) success=$clicked")
            }

            // Click Cooldown: 2000ms cooldown upon success to ignore lingering UI elements; standard debounce on failure
            lastProcessTime = if (clicked) {
                System.currentTimeMillis() + COOLDOWN_MS
            } else {
                System.currentTimeMillis()
            }

            AppSettings.addLog(
                title = if (clicked) "Order Accepted!" else "Click Attempted",
                message = if (clicked) "ACTION_CLICK delivered strictly to Accept button for ₹${offer.totalFare?.toInt()} order. Cooldown active (2000ms)."
                else "Accept button could not be clicked within card boundaries.",
                severity = if (clicked) LogSeverity.CLICK_EXECUTED else LogSeverity.WARNING,
                evaluation = evaluation
            )
            broadcastLog("Order Accepted! Click executed: $clicked")

            if (clicked) {
                val fare = offer.totalFare ?: evaluation.totalCurrency ?: 0.0
                if (fare > 0) {
                    AppSettings.updateLastAcceptedFare(this, fare)
                }
                announceRideAccepted(evaluation.totalCurrency, evaluation.distanceKm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing card accept click", e)
            lastProcessTime = System.currentTimeMillis()
        }
    }

    /**
     * Finds an actionable accept node strictly located inside the provided screen bounding box.
     */
    fun findAcceptNodeInBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
                val nodeBounds = Rect()
                node.getBoundsInScreen(nodeBounds)

                // Verify that the node lies inside the card's vertical and horizontal range
                val isInBounds = bounds.contains(nodeBounds.centerX(), nodeBounds.centerY()) ||
                        (nodeBounds.top >= bounds.top - 20 && nodeBounds.bottom <= bounds.bottom + 20)

                if (isInBounds && isActionableNodeOrChild(node)) {
                    while (queue.isNotEmpty()) {
                        recycleNode(queue.removeFirst())
                    }
                    return node
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
            recycleNode(node)
        }
        return null
    }

    /**
     * Traversal logic that checks both rootInActiveWindow and loops through windows using
     * window.root (for API level 21+) to capture text and nodes from background overlays,
     * floating dialogs, and SYSTEM_ALERT_WINDOW overlays.
     */
    fun getAllRootWindows(): List<AccessibilityNodeInfo> {
        val rootList = mutableListOf<AccessibilityNodeInfo>()
        val seenWindowIds = mutableSetOf<Int>()

        // 1. Check primary active window root (strictly ignore own app and non-target packages)
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val rootPkg = activeRoot.packageName?.toString() ?: ""
            if (rootPkg == packageName || (rootPkg.isNotEmpty() && rootPkg != TARGET_PACKAGE)) {
                recycleNode(activeRoot)
            } else {
                rootList.add(activeRoot)
                seenWindowIds.add(activeRoot.windowId)
            }
        }

        // 2. Multi-Window & Overlay Support: loop through windows for system overlays and dialogs (API 21+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val currentWindows = windows
                for (window in currentWindows) {
                    if (seenWindowIds.contains(window.id)) {
                        continue
                    }
                    val windowRoot = window.root ?: continue
                    val winPkg = windowRoot.packageName?.toString() ?: ""
                    if (winPkg == packageName || (winPkg.isNotEmpty() && winPkg != TARGET_PACKAGE)) {
                        recycleNode(windowRoot)
                        continue
                    }
                    rootList.add(windowRoot)
                    seenWindowIds.add(window.id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error traversing multi-window hierarchy", e)
            }
        }

        return rootList
    }

    /**
     * Recursive Depth-First Search (DFS) function that traverses the AccessibilityNodeInfo hierarchy.
     * Retrieves text from both node.text and node.contentDescription fields.
     */
    fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L

        fun dfs(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 50) return
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Log.w(TAG, "extractAllText: DFS traversal timeout budget reached ($timeoutMs ms).")
                return
            }

            val text = current.text
            if (!text.isNullOrBlank()) {
                val cleanText = text.toString().trim()
                if (cleanText.isNotEmpty()) {
                    sb.append(cleanText).append(" ")
                }
            }

            val desc = current.contentDescription
            if (!desc.isNullOrBlank()) {
                val cleanDesc = desc.toString().trim()
                if (cleanDesc.isNotEmpty() && !cleanDesc.equals(text?.toString()?.trim(), ignoreCase = true)) {
                    sb.append(cleanDesc).append(" ")
                }
            }

            val childCount = current.childCount
            for (i in 0 until childCount) {
                val child = current.getChild(i) ?: continue
                try {
                    dfs(child, depth + 1)
                } finally {
                    recycleNode(child)
                }
            }
        }

        dfs(node, 0)
        return sb.toString().trim()
    }

    /**
     * Traverses all active and overlay windows, extracting and aggregating all text.
     */
    fun extractAllTextFromAllWindows(): String {
        val rootWindows = getAllRootWindows()
        val sb = StringBuilder()
        try {
            for (root in rootWindows) {
                val windowText = extractAllText(root)
                if (windowText.isNotEmpty()) {
                    sb.append(windowText).append(" ")
                }
            }
        } finally {
            for (root in rootWindows) {
                recycleNode(root)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Searches for specific UI target labels and executes click on nearest clickable container.
     */
    fun executeAssistiveClick(
        root: AccessibilityNodeInfo? = null,
        targetLabels: List<String> = listOf("Confirm", "Accept", "Select", "Auto Accept")
    ): Boolean {
        val rootsToSearch = if (root != null) {
            listOf(root)
        } else {
            getAllRootWindows()
        }

        var executed = false
        val shouldRecycleRoots = (root == null)

        try {
            for (r in rootsToSearch) {
                val targetNode = findTargetActionNode(r, targetLabels) ?: continue
                try {
                    val clickableNode = findClickableParentOrSelf(targetNode)
                    if (clickableNode != null) {
                        executed = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        recycleNode(clickableNode)
                        if (executed) break
                    }

                    if (!executed && targetNode.isClickable) {
                        executed = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (executed) break
                    }

                    if (!executed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        executed = dispatchTapGesture(targetNode)
                        if (executed) break
                    }
                } finally {
                    recycleNode(targetNode)
                }
            }
        } finally {
            if (shouldRecycleRoots) {
                for (r in rootsToSearch) {
                    recycleNode(r)
                }
            }
        }

        return executed
    }

    fun findTargetActionNode(root: AccessibilityNodeInfo, targetLabels: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            val matchesLabel = targetLabels.any { label ->
                text.equals(label, ignoreCase = true) ||
                desc.equals(label, ignoreCase = true) ||
                text.contains(label, ignoreCase = true) ||
                desc.contains(label, ignoreCase = true)
            }

            if (matchesLabel && isActionableNodeOrChild(node)) {
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

    fun dispatchTapGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
                return false
            }
            dispatchTapAtCoordinates(rect.centerX().toFloat(), rect.centerY().toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchTapGesture", e)
            false
        }
    }

    private fun dispatchTapAtCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            val clickPath = Path().apply {
                moveTo(x, y)
            }
            val stroke = GestureDescription.StrokeDescription(clickPath, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()

            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "dispatchTapAtCoordinates completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "dispatchTapAtCoordinates cancelled at ($x, $y)")
                }
            }, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchTapAtCoordinates", e)
            false
        }
    }

    fun findAndClickAcceptButton(root: AccessibilityNodeInfo? = null): Boolean {
        val rootsToSearch = if (root != null) {
            listOf(root)
        } else {
            getAllRootWindows()
        }

        var clicked = false
        val shouldRecycleRoots = (root == null)

        try {
            for (r in rootsToSearch) {
                val acceptNode = findAcceptNode(r) ?: continue
                try {
                    val targetToClick = findClickableParentOrSelf(acceptNode) ?: acceptNode
                    clicked = targetToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        clicked = dispatchTapGesture(targetToClick)
                    }
                    if (targetToClick !== acceptNode) {
                        recycleNode(targetToClick)
                    }
                    if (clicked) {
                        break
                    }
                } finally {
                    recycleNode(acceptNode)
                }
            }
        } finally {
            if (shouldRecycleRoots) {
                for (r in rootsToSearch) {
                    recycleNode(r)
                }
            }
        }
        return clicked
    }

    fun findClickableParentOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        val maxDepth = 10

        while (current != null && depth < maxDepth) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            val parent = current.parent
            recycleNode(current)
            current = parent
            depth++
        }
        current?.let { recycleNode(it) }
        return null
    }

    fun findAcceptNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
                if (isActionableNodeOrChild(node)) {
                    while (queue.isNotEmpty()) {
                        recycleNode(queue.removeFirst())
                    }
                    return node
                }
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

    fun isAcceptAction(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        return ACCEPT_REGEX.matches(trimmed)
    }

    fun isRapidoAcceptId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return RAPIDO_ACCEPT_ID_REGEX.containsMatchIn(viewId)
    }

    fun isRejectAction(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        return REJECT_REGEX.matches(trimmed)
    }

    fun isRapidoRejectId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        return RAPIDO_REJECT_ID_REGEX.containsMatchIn(viewId)
    }

    fun findRejectNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 120L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (isRejectAction(text) || isRejectAction(desc) || isRapidoRejectId(viewId)) {
                if (isActionableNodeOrChild(node)) {
                    while (queue.isNotEmpty()) {
                        recycleNode(queue.removeFirst())
                    }
                    return node
                }
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

    /**
     * Identifies background floating overlay widgets, chatheads, and history mini-views
     * that do not represent active incoming order cards.
     */
    fun isFloatingWidgetOrBubble(node: AccessibilityNodeInfo): Boolean {
        val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        val className = (node.className?.toString() ?: "").lowercase(Locale.ROOT)

        val widgetKeywords = listOf(
            "bubble",
            "floating",
            "chathead",
            "chat_head",
            "overlay_head",
            "widget_layout",
            "floating_view",
            "overlay_bubble",
            "mini_widget",
            "pip_layout",
            "bubble_root"
        )

        val matchesWidget = widgetKeywords.any { viewId.contains(it) || className.contains(it) }
        if (matchesWidget) {
            val accept = findAcceptNode(node)
            if (accept != null) {
                recycleNode(accept)
                return false
            }
            return true
        }

        // Small floating bubble dimensions check (e.g., chatheads are usually small pills/circles)
        // Convert raw pixel bounds into density-independent pixels (dp) using Resources.getSystem().displayMetrics.density
        // before evaluating the size to ensure consistency across varying screen densities (HD, FHD, 4K).
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.isEmpty) {
            val density = Resources.getSystem().displayMetrics.density.let { if (it > 0f) it else 1.0f }
            val widthDp = bounds.width() / density
            val heightDp = bounds.height() / density

            if (widthDp in 1f..120f && heightDp in 1f..120f) {
                val accept = findAcceptNode(node)
                if (accept != null) {
                    recycleNode(accept)
                    return false
                }
                val reject = findRejectNode(node)
                if (reject != null) {
                    recycleNode(reject)
                    return false
                }
                return true
            }
        }

        return false
    }

    /**
     * Strict Check: Returns true ONLY if any candidate window contains an active Accept button,
     * Reject button, or explicit ride request popup card container.
     */
    fun hasActiveRideOfferOnScreen(rootWindows: List<AccessibilityNodeInfo>): Boolean {
        for (root in rootWindows) {
            // 1. Check for Accept button
            val accept = findAcceptNode(root)
            if (accept != null) {
                recycleNode(accept)
                return true
            }

            // 2. Check for Reject button
            val reject = findRejectNode(root)
            if (reject != null) {
                recycleNode(reject)
                return true
            }

            // 3. Check for explicit Ride Request Popup container
            val viewId = root.viewIdResourceName ?: ""
            if (TextAnalysisEngine.isExplicitRideRequest(viewId)) {
                return true
            }
        }
        return false
    }

    private fun isActionableNodeOrChild(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) return true

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.isEnabled) {
                recycleNode(current)
                return true
            }
            val parent = current.parent
            recycleNode(current)
            current = parent
            depth++
        }
        current?.let { recycleNode(it) }

        return node.isEnabled
    }

    protected fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                @Suppress("DEPRECATION")
                node.recycle()
            }
        } catch (_: IllegalStateException) {
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
    // TEXT-TO-SPEECH (TTS) ENGINE & ANNOUNCEMENTS (DELEGATING TO TTSManager)
    // =========================================================================================

    fun applyTtsLanguage() {
        TTSManager.applySettings(this)
    }

    /**
     * Speaks the given text using the configured queue management mode.
     * Default queue mode is TextToSpeech.QUEUE_ADD to allow sequential announcements without interrupting.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        TTSManager.speak(this, text, queueMode)
    }

    /**
     * Formats the voice announcement to speak strictly the 5 extracted clean fields in natural spoken Hindi/Hinglish:
     * "Kiraya {fare} rupaye. Pickup {pickupDistance} kilometer {pickupLocation}. Drop {dropDistance} kilometer {dropLocation}."
     */
    fun formatCleanVoiceAnnouncement(target: TargetRideOffer): String {
        return TTSManager.formatCleanVoiceAnnouncement(target)
    }

    /**
     * Announces a newly detected ride offer with fare and distance details in natural spoken Hindi/Hinglish.
     * Enforces strict 10-second deduplication cooldown per unique offer key to prevent 4x repeat speech.
     *
     * @return true if spoken; false if suppressed by deduplication cooldown.
     */
    fun announceNewRide(offer: ParsedRideOffer, queueMode: Int = TextToSpeech.QUEUE_ADD): Boolean {
        return TTSManager.announceNewRide(this, offer, queueMode)
    }

    /**
     * Announces an order acceptance confirmation.
     */
    fun announceRideAccepted(fare: Double?, distance: Double?) {
        TTSManager.announceRideAccepted(this, fare, distance)
    }

    /**
     * Announces when an offer does not meet captain criteria.
     */
    fun announceRideSkipped(reason: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        TTSManager.announceRideSkipped(this, reason, queueMode)
    }

    /**
     * Public helper to test the voice announcer from UI.
     */
    fun testAnnouncement(customMessage: String? = null) {
        TTSManager.testAnnouncement(this, customMessage)
    }
}

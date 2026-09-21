package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
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
 * Implements isolated UI Tree Parsing and Grouping to prevent text cross-contamination between
 * simultaneous offers, a sequential multi-order evaluation queue, bounds-targeted click execution,
 * duplicate suppression via offer hashing, and sequential Text-to-Speech voice announcements (QUEUE_ADD).
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

    // Cache of recently processed offer hashes to prevent re-evaluating the same order repeatedly
    private val processedOfferIds = LinkedHashSet<String>()
    private val maxProcessedHistory = 50

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

        // 1. Monitor specified event types: TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_CLICKED
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_CLICKED
        ) {
            return
        }

        // 2. Master switch check: skip if auto-accept is turned off
        if (!AppSettings.isAutoAcceptEnabled(this)) {
            return
        }

        // 3. Debounce check: skip event spams within cooldown window to protect UI thread
        val currentTime = System.currentTimeMillis()
        if (isPendingExecution || (currentTime - lastProcessTime < COOLDOWN_MS)) {
            return
        }

        // 4. Multi-Window & Overlay Support: Retrieve root nodes across active window and overlays
        val rootWindows = getAllRootWindows()
        if (rootWindows.isEmpty()) return

        try {
            // 5. Node Tree Grouping: Extract distinct order card containers across windows
            val allRawCards = mutableListOf<RawOrderCard>()
            for (root in rootWindows) {
                val cards = TextAnalysisEngine.extractOrderCards(root)
                allRawCards.addAll(cards)
            }

            // 6. Order Separation & Unique Parsing: Parse each card into a distinct RideOffer
            val parsedOffers = TextAnalysisEngine.parseAllOrderCards(allRawCards)

            if (parsedOffers.isEmpty()) {
                // Fallback check: If no structured cards matched, check if an accept node exists with single offer
                val singleAcceptRoot = rootWindows.firstOrNull { findAcceptNode(it) != null }
                if (singleAcceptRoot != null) {
                    val extractedText = extractAllText(singleAcceptRoot)
                    val fallbackOffer = TextAnalysisEngine.parse(extractedText)
                    if (fallbackOffer.totalFare != null && (fallbackOffer.pickupDistanceKm != null || fallbackOffer.dropDistanceKm != null)) {
                        val cardBounds = Rect()
                        singleAcceptRoot.getBoundsInScreen(cardBounds)
                        val rawCard = RawOrderCard(
                            id = "fallback_card",
                            nodeTexts = listOf(extractedText),
                            bounds = cardBounds,
                            hasAcceptButton = true
                        )
                        val offer = RideOffer(
                            id = TextAnalysisEngine.generateOfferId(fallbackOffer.totalFare, fallbackOffer.pickupDistanceKm, ""),
                            rawCard = rawCard,
                            totalFare = fallbackOffer.totalFare,
                            fareBreakdown = fallbackOffer.fareBreakdown,
                            pickupDistanceKm = fallbackOffer.pickupDistanceKm,
                            dropDistanceKm = fallbackOffer.dropDistanceKm,
                            hasAcceptButton = true
                        )
                        processOrderQueue(listOf(offer), rootWindows)
                    }
                }
                return
            }

            // Deduplication Check: Check if all detected offers have already been processed in current cycle
            val newOffers = parsedOffers.filter { !processedOfferIds.contains(it.id) }
            if (newOffers.isEmpty()) {
                return
            }

            // Update timestamp
            lastProcessTime = currentTime

            // 7. Multi-Order Processing Queue
            processOrderQueue(parsedOffers, rootWindows)

        } finally {
            for (root in rootWindows) {
                recycleNode(root)
            }
        }
    }

    /**
     * Sequential multi-order processing queue.
     * Evaluates multiple simultaneous order offers isolated by tree grouping, logs card-level validation,
     * speaks announcements in order (QUEUE_ADD), and executes ACTION_CLICK strictly within the bounds of the best offer.
     */
    private fun processOrderQueue(offers: List<RideOffer>, rootWindows: List<AccessibilityNodeInfo>) {
        val minFare = AppSettings.getMinFare(this)
        val maxFare = AppSettings.getMaxFare(this)
        val maxPickupDist = AppSettings.getMaxPickupDistance(this)
        val isAutoAccept = AppSettings.isAutoAcceptEnabled(this)

        // UI Logging Requirement: Log total distinct order cards detected
        AppSettings.addLog(
            title = "Orders Detected",
            message = "Detected ${offers.size} distinct order card${if (offers.size > 1) "s" else ""} on screen.",
            severity = LogSeverity.INFO
        )
        broadcastLog("Detected ${offers.size} distinct order card${if (offers.size > 1) "s" else ""}")

        val evaluatedOffers = mutableListOf<Pair<RideOffer, RideEvaluation>>()

        // Sequential Queue Evaluation
        offers.forEachIndexed { index, offer ->
            // Record in deduplication set
            synchronized(processedOfferIds) {
                if (processedOfferIds.size >= maxProcessedHistory) {
                    val firstKey = processedOfferIds.iterator().next()
                    processedOfferIds.remove(firstKey)
                }
                processedOfferIds.add(offer.id)
            }

            val parsedOffer = offer.toParsedRideOffer()
            val evaluation = TextAnalysisEngine.evaluateRideOffer(
                offer = parsedOffer,
                minFare = minFare,
                maxFare = maxFare,
                maxPickupDistance = maxPickupDist,
                isAutoAcceptEnabled = isAutoAccept
            )

            evaluatedOffers.add(offer to evaluation)

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

            // Sequential TTS Requirement: Use QUEUE_ADD so multiple order readouts do not interrupt each other
            announceNewRide(parsedOffer, queueMode = TextToSpeech.QUEUE_ADD)
            if (!evaluation.isAccepted) {
                announceRideSkipped(evaluation.decisionReason, queueMode = TextToSpeech.QUEUE_ADD)
            }
        }

        // Find best matching valid offer for auto-accept
        val matchingOffers = evaluatedOffers.filter { it.second.isAccepted }
        if (matchingOffers.isNotEmpty() && isAutoAccept) {
            // Select the highest fare offer among matching valid offers
            val bestCandidate = matchingOffers.maxByOrNull { it.first.totalFare ?: 0.0 } ?: matchingOffers.first()
            val bestOffer = bestCandidate.first
            val bestEvaluation = bestCandidate.second

            isPendingExecution = true
            val delayMs = AppSettings.getClickDelayMs(this)

            AppSettings.addLog(
                title = "Auto-Accept Scheduled",
                message = "Selected best offer: ₹${bestOffer.totalFare?.toInt()}, ${bestOffer.pickupDistanceKm}km. Auto-clicking Accept in ${delayMs}ms...",
                severity = LogSeverity.MATCH_ACCEPTED,
                evaluation = bestEvaluation
            )
            broadcastLog("Auto-clicking Accept for best offer (₹${bestOffer.totalFare?.toInt()}) in ${delayMs}ms...")

            mainHandler.postDelayed({
                executeCardAcceptClick(bestOffer, bestEvaluation)
            }, delayMs)
        }
    }

    /**
     * Executes the ACTION_CLICK strictly targeting the Accept button node
     * located inside the spatial bounds of the selected best matching order card.
     */
    private fun executeCardAcceptClick(offer: RideOffer, evaluation: RideEvaluation) {
        isPendingExecution = false
        lastProcessTime = System.currentTimeMillis()

        var clicked = false
        val cardBounds = offer.rawCard.bounds

        try {
            // 1. If the isolated raw card already has an acceptNode reference, try clicking that first
            val initialNode = offer.acceptNode ?: offer.rawCard.acceptNode
            if (initialNode != null) {
                try {
                    val clickableTarget = findClickableParentOrSelf(initialNode) ?: initialNode
                    clicked = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "executeCardAcceptClick: direct acceptNode click success=$clicked")
                    if (clickableTarget !== initialNode) {
                        recycleNode(clickableTarget)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "executeCardAcceptClick: direct acceptNode click failed, trying search in bounds", e)
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

            // 3. Fallback gesture dispatch centered directly on the card's action area or accept button center
            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !cardBounds.isEmpty) {
                val tapX = cardBounds.centerX().toFloat()
                val tapY = (cardBounds.bottom - (cardBounds.height() * 0.2f)).coerceAtLeast(cardBounds.top.toFloat())
                clicked = dispatchTapAtCoordinates(tapX, tapY)
                Log.i(TAG, "executeCardAcceptClick: coordinate fallback tap at ($tapX, $tapY) success=$clicked")
            }

            AppSettings.addLog(
                title = if (clicked) "Order Accepted!" else "Click Attempted",
                message = if (clicked) "ACTION_CLICK delivered strictly to Accept button for ₹${offer.totalFare?.toInt()} order."
                else "Accept button could not be clicked within card boundaries.",
                severity = if (clicked) LogSeverity.CLICK_EXECUTED else LogSeverity.WARNING,
                evaluation = evaluation
            )
            broadcastLog("Order Accepted! Click executed: $clicked")

            if (clicked) {
                announceRideAccepted(evaluation.totalCurrency, evaluation.distanceKm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing card accept click", e)
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

        // 1. Check primary active window root
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            rootList.add(activeRoot)
            seenWindowIds.add(activeRoot.windowId)
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

        return trimmed.equals("Accept", ignoreCase = true) ||
                trimmed.equals("Auto Accept", ignoreCase = true) ||
                trimmed.equals("Confirm", ignoreCase = true) ||
                trimmed.equals("Select", ignoreCase = true) ||
                trimmed.equals("Accept Order", ignoreCase = true) ||
                trimmed.equals("Accept Ride", ignoreCase = true) ||
                trimmed.equals("Go", ignoreCase = true) ||
                trimmed.contains("Swipe to Accept", ignoreCase = true) ||
                trimmed.equals("Take Ride", ignoreCase = true) ||
                trimmed.equals("Confirm Order", ignoreCase = true) ||
                trimmed.equals("Confirm Ride", ignoreCase = true)
    }

    private fun isRapidoAcceptId(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val lower = viewId.lowercase(Locale.ROOT)
        return lower.contains("btn_accept") ||
                lower.contains("accept_order") ||
                lower.contains("accept_button") ||
                lower.contains("accept_ride") ||
                lower.contains("action_accept") ||
                lower.contains("btn_confirm") ||
                lower.contains("auto_accept")
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
    // TEXT-TO-SPEECH (TTS) ENGINE & ANNOUNCEMENTS
    // =========================================================================================

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

        try {
            val rate = AppSettings.getSpeechRate(this)
            val pitch = AppSettings.getSpeechPitch(this)
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS rate/pitch", e)
        }
    }

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
     * Default queue mode is TextToSpeech.QUEUE_ADD to allow sequential announcements without interrupting.
     */
    fun speak(text: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
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
     * Announces a newly detected ride offer with fare and distance details in natural spoken Hindi/Hinglish.
     * Uses QUEUE_ADD to sequence announcements without cutting off previous speech.
     */
    fun announceNewRide(offer: ParsedRideOffer, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) return

        val target = offer.targetOffer ?: TextAnalysisEngine.parseTargetOffer(offer.rawText)
        val message = if (target != null && target.fare > 0.0) {
            formatCleanVoiceAnnouncement(target)
        } else {
            val fare = offer.totalFare?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            val dist = (offer.pickupDistanceKm ?: offer.dropDistanceKm)?.let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) } ?: "0"
            "Kiraya $fare rupaye. Pickup $dist kilometer."
        }

        speak(message, queueMode)
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

        speak(message, TextToSpeech.QUEUE_ADD)
    }

    /**
     * Announces when an offer does not meet captain criteria.
     */
    fun announceRideSkipped(reason: String, queueMode: Int = TextToSpeech.QUEUE_ADD) {
        if (!AppSettings.isVoiceAnnouncerEnabled(this)) return

        val lang = AppSettings.getVoiceLanguage(this)
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

        speak(message, queueMode)
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

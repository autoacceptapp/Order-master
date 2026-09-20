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

        var activeTargetRoot: AccessibilityNodeInfo? = null
        try {
            // Check across all active windows and system overlays for an actionable accept button
            for (root in rootWindows) {
                val acceptNode = findAcceptNode(root)
                if (acceptNode != null) {
                    recycleNode(acceptNode)
                    activeTargetRoot = root
                    break
                }
            }

            // If no actionable button is present on any screen or overlay, return early silently
            if (activeTargetRoot == null) {
                return
            }

            // 5. Recursive DFS text extraction across node hierarchy
            val extractedText = extractAllText(activeTargetRoot)

            // Duplicate Offer Check: Skip if exact same content was already evaluated
            if (extractedText == lastEvaluatedText || extractedText.isBlank()) {
                return
            }

            // Processing Logic: Parse offer parameters from extracted screen text
            val offer = TextAnalysisEngine.parse(extractedText)
            lastEvaluatedText = extractedText
            lastProcessTime = currentTime

            @Suppress("DEPRECATION")
            val rootToPreserve = AccessibilityNodeInfo.obtain(activeTargetRoot)
            evaluateAndAction(offer, rootToPreserve)
        } finally {
            for (root in rootWindows) {
                recycleNode(root)
            }
        }
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
     * Evaluates parsed ride offer parameters against configured thresholds
     * and triggers automatic acceptance if criteria are satisfied.
     * Only called when an active ride request popup with an Accept action has been strictly verified.
     */
    private fun evaluateAndAction(offer: ParsedRideOffer, rootNode: AccessibilityNodeInfo) {
        try {
            // Verify offer has detectable fare or distance indicators
            if (offer.totalFare == null && offer.pickupDistanceKm == null) {
                return
            }

            val minFare = AppSettings.getMinFare(this)
            val maxFare = AppSettings.getMaxFare(this)
            val maxPickupDist = AppSettings.getMaxPickupDistance(this)
            val isEnabled = AppSettings.isAutoAcceptEnabled(this)

            val evaluation = TextAnalysisEngine.evaluateRideOffer(
                offer = offer,
                minFare = minFare,
                maxFare = maxFare,
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
                // Log and announce evaluated offer (filtered out by fare or distance criteria)
                AppSettings.addLog(
                    title = "Offer Evaluated (Filtered)",
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
     * Recursive Depth-First Search (DFS) function that traverses the entire AccessibilityNodeInfo hierarchy.
     * Retrieves text from both node.text and node.contentDescription fields to ensure no hidden labels
     * or custom UI elements are missed.
     * Aggregates all extracted text into a clean string format for downstream logic processing.
     *
     * Features:
     * - DFS tree traversal with depth limit (max 50) and time budget (150ms) to prevent UI thread blocking.
     * - Extracts both node.text and node.contentDescription cleanly.
     * - Null-safe child navigation with proper node recycling.
     */
    fun extractAllText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L // Prevents UI thread blocking during rapid windowContentChanged events

        fun dfs(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 50) return
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                Log.w(TAG, "extractAllText: DFS traversal timeout budget reached ($timeoutMs ms).")
                return
            }

            // 1. Retrieve text from node.text
            val text = current.text
            if (!text.isNullOrBlank()) {
                val cleanText = text.toString().trim()
                if (cleanText.isNotEmpty()) {
                    sb.append(cleanText).append(" ")
                }
            }

            // 2. Retrieve text from node.contentDescription
            val desc = current.contentDescription
            if (!desc.isNullOrBlank()) {
                val cleanDesc = desc.toString().trim()
                // Avoid duplicate tokens when text and contentDescription are identical
                if (cleanDesc.isNotEmpty() && !cleanDesc.equals(text?.toString()?.trim(), ignoreCase = true)) {
                    sb.append(cleanDesc).append(" ")
                }
            }

            // 3. Recurse through children (Depth-First Search)
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
     * Traverses all active and overlay windows, extracting and aggregating all text
     * across system overlays, floating dialogs, and active app screens.
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
     * Hands-Free Action Execution:
     * Searches for specific UI target labels (e.g. "Confirm", "Accept", "Select").
     * If a target node is not directly clickable (isClickable == false), recursively traverses up
     * to find the nearest clickable parent container and performs ACTION_CLICK.
     * Includes a fallback gesture dispatch using dispatchGesture() for custom-drawn UI components.
     *
     * @param root Optional specific root node to search. If null, searches across all active & overlay windows.
     * @param targetLabels Action labels to locate and click.
     * @return true if an action was executed successfully (via ACTION_CLICK or gesture dispatch), false otherwise.
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
                    // 1. Try finding nearest clickable parent or self
                    val clickableNode = findClickableParentOrSelf(targetNode)
                    if (clickableNode != null) {
                        executed = clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "executeAssistiveClick: ACTION_CLICK on '${clickableNode.className}', success=$executed")
                        recycleNode(clickableNode)
                        if (executed) break
                    }

                    // 2. Direct click attempt on the target node if not already attempted
                    if (!executed && targetNode.isClickable) {
                        executed = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "executeAssistiveClick: Direct ACTION_CLICK on '${targetNode.className}', success=$executed")
                        if (executed) break
                    }

                    // 3. Fallback gesture dispatch using dispatchGesture() for custom-drawn UI components
                    if (!executed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        executed = dispatchTapGesture(targetNode)
                        Log.i(TAG, "executeAssistiveClick: Fallback dispatchTapGesture executed=$executed")
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

    /**
     * Searches for a target action node matching any of the specified target labels.
     */
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

    /**
     * Dispatches a tap gesture on the screen center of the given AccessibilityNodeInfo.
     * Useful as a fallback for custom-drawn or canvas UI components that don't support ACTION_CLICK.
     */
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

            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()

            val clickPath = Path().apply {
                moveTo(x, y)
            }

            val stroke = GestureDescription.StrokeDescription(
                clickPath,
                0L,
                50L // 50ms tap duration
            )
            val gesture = GestureDescription.Builder()
                .addStroke(stroke)
                .build()

            dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "dispatchTapGesture completed at ($x, $y)")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.w(TAG, "dispatchTapGesture cancelled at ($x, $y)")
                }
            }, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in dispatchTapGesture", e)
            false
        }
    }

    /**
     * Generic Click Logic:
     * Robust matching mechanism for target action keywords (e.g. "Accept", "Auto Accept", "Confirm").
     * If the matching AccessibilityNodeInfo is clickable (isClickable == true), triggers ACTION_CLICK on that node.
     * If the node itself is not clickable, recursively walks up the view hierarchy to locate the nearest
     * clickable parent container and executes ACTION_CLICK on it.
     *
     * @param root Optional specific root node to search. If null, searches across rootInActiveWindow
     *             and all multi-window overlays (window.root).
     * @return true if an accept button was found and clicked successfully, false otherwise.
     */
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
                    Log.i(TAG, "findAndClickAcceptButton: Clicked on '${targetToClick.className}', success=$clicked")
                    if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        clicked = dispatchTapGesture(targetToClick)
                        Log.i(TAG, "findAndClickAcceptButton: Fallback dispatchTapGesture executed=$clicked")
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

    /**
     * If the node itself is clickable and enabled, returns a reference.
     * If not clickable, recursively walks up the view hierarchy to locate the nearest clickable parent container.
     */
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

    private fun executeAcceptClick(evaluation: RideEvaluation) {
        isPendingExecution = false
        lastProcessTime = System.currentTimeMillis()

        try {
            val clickSucceeded = findAndClickAcceptButton()

            Log.i(TAG, "Executed accept click via findAndClickAcceptButton. Success = $clickSucceeded")
            AppSettings.addLog(
                title = if (clickSucceeded) "Order Accepted!" else "Click Attempted",
                message = if (clickSucceeded) "ACTION_CLICK delivered successfully to Accept button/container."
                else "Accept button could not be clicked or disappeared.",
                severity = if (clickSucceeded) LogSeverity.CLICK_EXECUTED else LogSeverity.WARNING,
                evaluation = evaluation
            )
            broadcastLog("ACTION_CLICK executed on Accept node! Success: $clickSucceeded")

            if (clickSucceeded) {
                announceRideAccepted(evaluation.totalCurrency, evaluation.distanceKm)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception while executing accept click", e)
        }
    }

    /**
     * Strict Accept Button Detection:
     * Finds and returns an actionable "Accept" / "Auto Accept" / "Confirm" node in the hierarchy using Breadth-First Search (BFS).
     * The node or one of its parents must be clickable or actionable to qualify as a valid offer acceptance component.
     * The caller is responsible for recycling the returned node when done.
     */
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
                // Verify actionable status: the node itself or an ancestor should be clickable/enabled
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
     * Strict verification that text corresponds to an interactive order acceptance action
     * (e.g. "Accept", "Auto Accept", "Confirm", "Accept Order", "Accept Ride", "Go", "Swipe to Accept", "Take Ride").
     */
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

    /**
     * Verifies that the node or its immediate container is enabled and either clickable
     * or contains an actionable gesture target.
     */
    private fun isActionableNodeOrChild(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.isEnabled) return true

        // Check parent container
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

        // Fallback: If node has the exact text and is enabled, accept as actionable
        return node.isEnabled
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

        try {
            val rate = AppSettings.getSpeechRate(this)
            val pitch = AppSettings.getSpeechPitch(this)
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
        } catch (e: Exception) {
            Log.e(TAG, "Error applying TTS rate/pitch", e)
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
     * Articulates specific threshold reason (e.g. fare too low, fare exceeds maximum, or pickup too far).
     */
    fun announceRideSkipped(reason: String) {
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
            reason.contains("Pickup distance", ignoreCase = true) -> {
                if (isHindi) "राइड छोड़ दिया गया: पिकअप बहुत दूर है।"
                else "Ride skipped: pickup is too far."
            }
            else -> {
                if (isHindi) "राइड छोड़ दिया गया।"
                else "Ride skipped."
            }
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

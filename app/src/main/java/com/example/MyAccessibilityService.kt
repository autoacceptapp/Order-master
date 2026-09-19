package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Production-ready Android Accessibility Service that monitors on-screen ride/order requests
 * from driver apps (specifically Rapido Captain: com.rapido.rider) and automatically
 * executes an "Accept" action when user-configured criteria (Min Fare, Max Pickup Distance) are met.
 *
 * Maintains BFS tree traversal, clickable parent discovery, event debouncing, duplicate suppression,
 * and thread-safe volatile execution guards.
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this
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
                // Log rejected offer without re-triggering
                AppSettings.addLog(
                    title = "Offer Evaluated (Skipped)",
                    message = evaluation.decisionReason,
                    severity = LogSeverity.REJECTED,
                    evaluation = evaluation
                )
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
}

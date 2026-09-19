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
 * (e.g. Rapido Captain, Uber Driver, Ola) and automatically executes an "Accept" action
 * when user-configured criteria (Min Fare, Max Pickup Distance) are satisfied.
 */
open class MyAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "MyAccessibilityService"
        private const val DEBOUNCE_MS = 2500L

        @Volatile
        var isServiceRunning: Boolean = false
            protected set

        @Volatile
        var instance: MyAccessibilityService? = null
            protected set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPendingExecution: Boolean = false
    private var lastExecutionTime: Long = 0L
    private var lastProcessedContentHash: Int = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this
        Log.d(TAG, "Accessibility Service connected and operational.")
        AppSettings.addLog(
            title = "Service Connected",
            message = "Ride Auto-Accept Accessibility Service is active and monitoring screen events.",
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
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Monitor required event types
        val eventType = event.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }

        // 2. Check master switch setting
        if (!AppSettings.isAutoAcceptEnabled(this)) {
            return
        }

        // 3. Debounce check: prevent double-clicks or re-triggering while execution is queued
        val now = System.currentTimeMillis()
        if (isPendingExecution || (now - lastExecutionTime < DEBOUNCE_MS)) {
            return
        }

        // Retrieve active window root node safely
        val rootNode = rootInActiveWindow ?: return

        try {
            // 4. Quick check: Is there any Accept button/label in the current hierarchy?
            val hasAcceptButton = containsAcceptNode(rootNode)
            if (!hasAcceptButton) {
                recycleNode(rootNode)
                return
            }

            // 5. Collect all text from current hierarchy nodes
            val stringCollector = StringBuilder()
            collectHierarchyText(rootNode, stringCollector)
            val combinedText = stringCollector.toString().trim()

            recycleNode(rootNode)

            if (combinedText.isBlank()) return

            // Avoid reprocessing the exact same screen content repeatedly
            val contentHash = combinedText.hashCode()
            if (contentHash == lastProcessedContentHash && (now - lastExecutionTime < DEBOUNCE_MS * 2)) {
                return
            }

            // 6. Parse and evaluate ride parameters through TextAnalysisEngine
            val parsedOffer = TextAnalysisEngine.parseRideOffer(combinedText)
            val minFare = AppSettings.getMinFare(this)
            val maxPickupDist = AppSettings.getMaxPickupDistance(this)
            val isEnabled = AppSettings.isAutoAcceptEnabled(this)

            val evaluation = TextAnalysisEngine.evaluateRideOffer(
                offer = parsedOffer,
                minFare = minFare,
                maxPickupDistance = maxPickupDist,
                isAutoAcceptEnabled = isEnabled
            )

            // 7. Decision Handling
            if (evaluation.isAccepted) {
                isPendingExecution = true
                lastProcessedContentHash = contentHash

                val delayMs = AppSettings.getClickDelayMs(this)
                val breakdownText = if (parsedOffer.fareBreakdown.size > 1) {
                    " (${parsedOffer.fareBreakdown.joinToString(" + ") { "₹$it" }})"
                } else ""

                AppSettings.addLog(
                    title = "Ride Match Found!",
                    message = "Fare: ₹${parsedOffer.totalFare}$breakdownText, Pickup: ${parsedOffer.pickupDistanceKm}km. Auto-clicking Accept in ${delayMs}ms...",
                    severity = LogSeverity.MATCH_ACCEPTED,
                    evaluation = evaluation
                )
                broadcastLog("Matched: Fare ₹${parsedOffer.totalFare}, Pickup ${parsedOffer.pickupDistanceKm}km. Accepting in ${delayMs}ms...")

                mainHandler.postDelayed({
                    executeAcceptClick(evaluation)
                }, delayMs)
            } else if (parsedOffer.totalFare != null || parsedOffer.pickupDistanceKm != null) {
                // Log evaluated ride request that didn't meet thresholds (with debounce)
                lastProcessedContentHash = contentHash
                AppSettings.addLog(
                    title = "Offer Evaluated (Skipped)",
                    message = evaluation.decisionReason,
                    severity = LogSeverity.REJECTED,
                    evaluation = evaluation
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating accessibility event", e)
        }
    }

    /**
     * Traverses the active window hierarchy to locate and trigger ACTION_CLICK on the Accept button.
     */
    private fun executeAcceptClick(evaluation: RideEvaluation) {
        isPendingExecution = false
        lastExecutionTime = System.currentTimeMillis()

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
                    severity = LogSeverity.REJECTED,
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
     * Efficiently checks if any node in the hierarchy displays "Accept".
     * Cleans up traversed node instances to avoid memory leaks.
     */
    private fun containsAcceptNode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""

            if (isAcceptAction(text) || isAcceptAction(desc)) {
                recycleNode(node)
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                return true
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
        return false
    }

    /**
     * Finds and returns the first Accept node in the hierarchy.
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
     * Recursive tree collector gathering text from nodes.
     * Manages child references and ensures each node is safely recycled.
     */
    private fun collectHierarchyText(node: AccessibilityNodeInfo, out: StringBuilder) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) {
            out.append(text).append(" ")
        }

        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) {
            out.append(desc).append(" ")
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectHierarchyText(child, out)
            } finally {
                recycleNode(child)
            }
        }
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

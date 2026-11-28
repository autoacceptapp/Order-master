package com.example

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.repository.AppConfigRepository
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
open class RapidoAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "RapidoAccessibilityService"
        const val TARGET_PACKAGE = "com.rapido.rider"
        const val DEBOUNCE_WINDOW_MS = 100L // 100ms debounce to balance ultra-fast response with frame rate stability
        const val COOLDOWN_MS = 2000L // 2 seconds delay between order acceptance
        const val REJECT_DEBOUNCE_MS = 400L // 400ms debounce after rejecting an invalid card
        const val MAX_TRAVERSAL_TIMEOUT_MS = 120L // Increased from 45L to 120L to prevent early aborts on deeply nested Compose layouts
        const val MAX_TRAVERSAL_DEPTH = 25       // Increased from 12 to 25 to fully traverse deeply nested Rapido hierarchies
        const val MIN_COORDINATE_TAP_INTERVAL_MS = 2000L // Strict rate-limiter: min 2000ms delay between coordinate taps
        const val MORE_ORDERS_EXPAND_COOLDOWN_MS = 1500L // Prevent rapid re-tapping of "See N more orders"
        const val UI_SNAPSHOT_CAPTURE_INTERVAL_MS = 2000L
        const val MAX_DIAGNOSTIC_NODES = 120

        @Volatile
        var isServiceRunning: Boolean = false
            protected set

        @Volatile
        var instance: RapidoAccessibilityService? = null
            protected set

        @Volatile
        private var lastSanitizedUiSnapshotJson: String? = null

        @Volatile
        private var lastSnapshotCapturedAtMs: Long = 0L

        fun getLastSanitizedUiSnapshotJson(): String? = lastSanitizedUiSnapshotJson

        fun getLastSnapshotCapturedAtMs(): Long = lastSnapshotCapturedAtMs

        fun clearSanitizedUiSnapshotForTesting() {
            lastSanitizedUiSnapshotJson = null
            lastSnapshotCapturedAtMs = 0L
        }

        const val FREE_MODE_MIN_ACCEPT_DELAY_MS = 1000L
        const val FREE_MODE_DEFAULT_ACCEPT_DELAY_MS = 1200L
        const val FREE_MODE_MAX_ACCEPT_DELAY_MS = 2000L
        const val PRO_MODE_MIN_ACCEPT_DELAY_MS = 300L
        const val PRO_MODE_MAX_ACCEPT_DELAY_MS = 5000L

        @androidx.annotation.VisibleForTesting
        fun isOfferProcessedForTesting(service: RapidoAccessibilityService, offerId: String): Boolean {
            return synchronized(service.processedOfferIds) { service.processedOfferIds.contains(offerId) }
        }

        @androidx.annotation.VisibleForTesting
        fun addProcessedOfferForTesting(service: RapidoAccessibilityService, offerId: String) {
            synchronized(service.processedOfferIds) { service.processedOfferIds.add(offerId) }
        }

        @androidx.annotation.VisibleForTesting
        fun removeProcessedOfferForTesting(service: RapidoAccessibilityService, offerId: String) {
            synchronized(service.processedOfferIds) { service.processedOfferIds.remove(offerId) }
        }

        @androidx.annotation.VisibleForTesting
        fun clearProcessedOffersForTesting(service: RapidoAccessibilityService) {
            synchronized(service.processedOfferIds) { service.processedOfferIds.clear() }
        }

        @androidx.annotation.VisibleForTesting
        fun getProcessedOfferCountForTesting(service: RapidoAccessibilityService): Int {
            return synchronized(service.processedOfferIds) { service.processedOfferIds.size }
        }

        /**
         * Computes the effective auto-accept click delay:
         * - Pro Mode (Active Trial / Daily / Weekly / Monthly Pass) or Point-unlocked AUTO_CLICK Boost:
         *   honors configured speed clamped to [300ms, 5000ms].
         * - Free Mode (no active Pro Pass/Trial and no AUTO_CLICK boost):
         *   strictly enforces a fast 1–2s human-safe delay in [1000ms, 2000ms].
         */
        @androidx.annotation.VisibleForTesting
        fun computeEffectiveAcceptDelayMs(
            configuredDelayMs: Long,
            isProMode: Boolean,
            isAutoClickSpeedUnlocked: Boolean
        ): Long {
            return if (isProMode || isAutoClickSpeedUnlocked) {
                configuredDelayMs.coerceIn(PRO_MODE_MIN_ACCEPT_DELAY_MS, PRO_MODE_MAX_ACCEPT_DELAY_MS)
            } else {
                when {
                    configuredDelayMs in FREE_MODE_MIN_ACCEPT_DELAY_MS..FREE_MODE_MAX_ACCEPT_DELAY_MS -> configuredDelayMs
                    configuredDelayMs < FREE_MODE_MIN_ACCEPT_DELAY_MS -> FREE_MODE_DEFAULT_ACCEPT_DELAY_MS
                    else -> FREE_MODE_MAX_ACCEPT_DELAY_MS
                }
            }
        }

        /**
         * Evaluates an incoming ride offer using live server-authoritative entitlements:
         * - Free Mode (0 unlocked filters): bypasses ALL filter checks (including stale Pro/Trial thresholds)
         *   so incoming offers pass cleanly for fast 1–2s auto-accept.
         * - Free Mode (Point-unlocked filters): enforces ONLY actively unlocked & enabled filters
         *   (`expiry > now`), ignoring locked/expired filters.
         * - Pro Mode (Active Trial or Daily/Weekly/Monthly Pass): enforces ALL enabled filters.
         */
        @androidx.annotation.VisibleForTesting
        fun evaluateOfferWithEffectiveLicensing(
            context: Context,
            offer: ParsedRideOffer
        ): RideEvaluation {
            val isAutoAccept = AppSettings.isAutoAcceptEnabled(context)

            val isMinFareUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE)
            val isMaxFareUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE)
            val isMinPerKmRateUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MIN_PER_KM_RATE)
            val isPickupDistUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE)
            val isDropDistUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.MAX_DROP_DISTANCE)
            val isBlacklistUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST)

            val isMinFareEnabled = isMinFareUnlocked && AppSettings.isMinFareEnabled(context)
            val isMaxFareEnabled = isMaxFareUnlocked && AppSettings.isMaxFareEnabled(context)
            val isMinPerKmRateEnabled = isMinPerKmRateUnlocked && AppSettings.isMinPerKmRateEnabled(context)
            val isMaxPickupDistEnabled = isPickupDistUnlocked && AppSettings.isMaxPickupDistanceEnabled(context)
            val isMaxDropDistEnabled = isDropDistUnlocked && AppSettings.isMaxDropDistanceEnabled(context)
            val isDestinationBlacklistEnabled = isBlacklistUnlocked && AppSettings.isDestinationBlacklistEnabled(context)
            val effectiveBlockedLocations = if (isBlacklistUnlocked && isDestinationBlacklistEnabled) {
                AppSettings.getBlockedLocations(context)
            } else {
                emptyList()
            }

            return TextAnalysisEngine.evaluateRideOffer(
                offer = offer,
                minFare = AppSettings.getMinFare(context),
                maxFare = AppSettings.getMaxFare(context),
                maxPickupDistance = AppSettings.getMaxPickupDistance(context),
                isAutoAcceptEnabled = isAutoAccept,
                isMinFareEnabled = isMinFareEnabled,
                isMaxFareEnabled = isMaxFareEnabled,
                isMaxPickupDistanceEnabled = isMaxPickupDistEnabled,
                isMaxDropDistanceEnabled = isMaxDropDistEnabled,
                maxDropDistance = AppSettings.getMaxDropDistance(context),
                minPerKmRate = AppSettings.getMinPerKmRate(context),
                isMinPerKmRateEnabled = isMinPerKmRateEnabled,
                blockedLocations = effectiveBlockedLocations,
                isDestinationBlacklistEnabled = isDestinationBlacklistEnabled
            )
        }

        private val DIAGNOSTIC_LOCATION_VIEW_ID_REGEX = Regex(
            """(?i)(?:address|location|pickup_loc|drop_loc|dest|source|street|landmark|locality|route|place|waypoint)"""
        )

        private val DIAGNOSTIC_CONTACT_VIEW_ID_REGEX = Regex(
            """(?i)(?:customer|passenger|rider_name|user_name|phone|mobile|contact|email|otp|pin|chat|message|note)"""
        )

        private val DIAGNOSTIC_PHONE_OR_PIN_REGEX = Regex(
            """(?:\+?\d[\d\s\-]{8,}\d|\b\d{6}\b)"""
        )

        private val DIAGNOSTIC_EMAIL_REGEX = Regex(
            """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""
        )

        private val DIAGNOSTIC_ADDRESS_HINT_REGEX = Regex(
            """(?i)\b(?:road|rd|street|st|nagar|layout|sector|block|colony|society|lane|cross|main|phase|floor|flat|near|opp|opposite|gate|chowk|circle|hospital|station|airport|mall|market|park|apartment|apartments|residency|enclave|vihar|puram|marg|bazaar|bazar|complex|tower|plaza|heights|avenue|extn|extension|stage|ward|taluka|district)\b"""
        )

        private val DIAGNOSTIC_SAFE_CHROME_REGEX = Regex(
            """(?i)^(?:auto|bike|cab|parcel|boost|boosted|cash|online|offline|upi|wallet|cod|pickup|drop|\d+\s*orders?|new\s*order|incoming\s*order|order\s*details)$"""
        )

        private val DIAGNOSTIC_BUTTON_VIEW_ID_REGEX = Regex(
            """(?i)(?:btn|button|accept|reject|decline|skip|pass|cancel|close|more|expand|tab|action|confirm)"""
        )

        /**
         * Privacy-safe sanitizer for "Report UI Issue" diagnostic snapshots.
         * Preserves action button labels and structural tokens while strictly redacting all
         * passenger addresses, street names, landmarks, customer names, phone numbers, and free text.
         */
        fun sanitizeNodeTextForDiagnostic(
            text: String,
            desc: String,
            viewId: String,
            className: String,
            isClickable: Boolean
        ): String {
            val raw = text.trim().ifEmpty { desc.trim() }
            if (raw.isEmpty()) return ""

            // 1. Always redact nodes whose View ID indicates contact/PII or location/address data
            if (DIAGNOSTIC_CONTACT_VIEW_ID_REGEX.containsMatchIn(viewId)) {
                return "[REDACTED_CONTACT]"
            }
            if (DIAGNOSTIC_LOCATION_VIEW_ID_REGEX.containsMatchIn(viewId)) {
                return "[REDACTED_LOCATION]"
            }

            // 2. Always redact emails, phone numbers, and 6-digit postal pincodes
            if (DIAGNOSTIC_EMAIL_REGEX.containsMatchIn(raw) || DIAGNOSTIC_PHONE_OR_PIN_REGEX.containsMatchIn(raw)) {
                return "[REDACTED_CONTACT]"
            }

            // 3. Mask tip badges, fares, and distances into privacy-safe structural tokens
            if (TextAnalysisEngine.isTipBadgeNoise(raw)) {
                return "[TIP_BADGE]"
            }
            if (RideOfferParser.isFareNode(raw, viewId)) {
                return if (raw.contains("+")) "[FARE_WITH_TIP]" else "[FARE]"
            }
            if (RideOfferParser.extractDistanceValue(raw) != null) {
                return when {
                    RideOfferParser.isPickupNode(raw, viewId) -> "[PICKUP_DISTANCE]"
                    RideOfferParser.isDropNode(raw, viewId) -> "[DROP_DISTANCE]"
                    else -> "[DISTANCE]"
                }
            }

            // 4. Reject anything containing street/address cues or multi-part address commas
            val hasAddressCue = raw.contains(',') || DIAGNOSTIC_ADDRESS_HINT_REGEX.containsMatchIn(raw)
            if (hasAddressCue) {
                return "[REDACTED_LOCATION]"
            }

            // 5. Preserve known action button labels (Accept / Reject / See More Orders) verbatim
            val isActionLabel = RideOfferParser.isRapidoAcceptId(viewId) ||
                RideOfferParser.isRapidoRejectId(viewId) ||
                RideOfferParser.isAcceptAction(raw) ||
                RideOfferParser.isRejectAction(raw) ||
                RideOfferParser.isSeeMoreOrdersText(raw)
            if (isActionLabel && raw.length <= 40) {
                return raw
            }

            // 6. Preserve generic UI chrome labels (AUTO / BIKE / 3 Orders / Online)
            if (DIAGNOSTIC_SAFE_CHROME_REGEX.matches(raw)) {
                return raw
            }

            // 7. Preserve short clickable button labels explicitly backed by a Button class or button viewId
            val isButtonWidget = className.contains("Button", ignoreCase = true) ||
                DIAGNOSTIC_BUTTON_VIEW_ID_REGEX.containsMatchIn(viewId)
            val wordCount = raw.split(Regex("""\s+""")).count { it.isNotEmpty() }
            if (isClickable && isButtonWidget && raw.length <= 24 && wordCount <= 3 && !raw.any { it.isDigit() }) {
                return raw
            }

            // 8. Default fail-closed redaction for all remaining free-text, names, and locations
            return "[REDACTED_TEXT]"
        }

        /**
         * Captures a bounded, strictly privacy-sanitized JSON snapshot of the Rapido UI hierarchy
         * in memory for 1-tap troubleshooting via "Report UI Issue".
         */
        fun captureSanitizedUiSnapshot(
            rootWindows: List<AccessibilityNodeInfo>,
            packageName: String,
            screenWidthPx: Int,
            screenHeightPx: Int,
            capturedAtMs: Long = System.currentTimeMillis()
        ): String {
            val nodesJson = JSONArray()
            val startTime = SystemClock.elapsedRealtime()
            var capturedCount = 0

            for ((windowIndex, root) in rootWindows.withIndex()) {
                if (capturedCount >= MAX_DIAGNOSTIC_NODES) break
                val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
                queue.add(root to 0)

                try {
                    while (queue.isNotEmpty() && capturedCount < MAX_DIAGNOSTIC_NODES) {
                        if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                            break
                        }
                        val (node, depth) = queue.removeFirst()
                        try {
                            val bounds = Rect()
                            node.getBoundsInScreen(bounds)
                            val viewId = node.viewIdResourceName ?: ""
                            val className = node.className?.toString() ?: ""
                            val text = node.text?.toString() ?: ""
                            val desc = node.contentDescription?.toString() ?: ""
                            val sanitizedLabel = sanitizeNodeTextForDiagnostic(
                                text = text,
                                desc = desc,
                                viewId = viewId,
                                className = className,
                                isClickable = node.isClickable
                            )

                            val hasUsefulStructure = viewId.isNotEmpty() ||
                                sanitizedLabel.isNotEmpty() ||
                                node.isClickable ||
                                depth <= 1
                            if (hasUsefulStructure) {
                                val entry = JSONObject().apply {
                                    put("w", windowIndex)
                                    put("d", depth)
                                    put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                                    if (viewId.isNotEmpty()) put("viewId", viewId)
                                    if (className.isNotEmpty()) {
                                        put("class", className.substringAfterLast('.'))
                                    }
                                    put("clickable", node.isClickable)
                                    put("enabled", node.isEnabled)
                                    if (sanitizedLabel.isNotEmpty()) put("label", sanitizedLabel)
                                }
                                nodesJson.put(entry)
                                capturedCount++
                            }

                            if (depth < MAX_TRAVERSAL_DEPTH && capturedCount < MAX_DIAGNOSTIC_NODES) {
                                val childCount = node.childCount
                                for (i in 0 until childCount) {
                                    val child = node.getChild(i)
                                    if (child != null) {
                                        queue.add(child to (depth + 1))
                                    }
                                }
                            }
                        } finally {
                            if (depth > 0) {
                                try {
                                    @Suppress("DEPRECATION")
                                    node.recycle()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }
                } finally {
                    while (queue.isNotEmpty()) {
                        val (queuedNode, queuedDepth) = queue.removeFirst()
                        if (queuedDepth > 0) {
                            try {
                                @Suppress("DEPRECATION")
                                queuedNode.recycle()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            val activeConfig = AppConfigRepository.currentAppConfig.value
            val rootJson = JSONObject().apply {
                put("schema", "rapido_ui_diagnostic_v1")
                put("capturedAtMs", capturedAtMs)
                put("appVersionCode", BuildConfig.APP_VERSION_CODE)
                put("androidSdk", Build.VERSION.SDK_INT)
                put("packageName", packageName)
                put("screenWidthPx", screenWidthPx)
                put("screenHeightPx", screenHeightPx)
                put("windowCount", rootWindows.size)
                put(
                    "activeConfig",
                    JSONObject().apply {
                        put("targetPackage", AppConfigRepository.effectiveRapidoTargetPackage())
                        put("leftSidebarWidthRatio", AppConfigRepository.effectiveRapidoLeftSidebarWidthRatio().toDouble())
                        put("acceptViewIds", JSONArray(AppConfigRepository.effectiveRapidoAcceptViewIds()))
                        put("acceptKeywordsCount", AppConfigRepository.effectiveRapidoAcceptKeywords().size)
                        put("rejectKeywordsCount", AppConfigRepository.effectiveRapidoRejectKeywords().size)
                        put("seeMoreKeywords", JSONArray(AppConfigRepository.effectiveRapidoSeeMoreOrdersKeywords()))
                        put("autoExpandMoreOrders", activeConfig.rapidoAutoExpandMoreOrders)
                    }
                )
                put("nodeCount", nodesJson.length())
                put("nodes", nodesJson)
            }

            val formatted = rootJson.toString(2).replace("\\/", "/")
            lastSanitizedUiSnapshotJson = formatted
            lastSnapshotCapturedAtMs = capturedAtMs
            return formatted
        }

        /**
         * Returns the latest sanitized Rapido UI diagnostic snapshot if available, or a privacy-safe
         * fallback summary with device/permission/parser config state when no Rapido screen was captured yet.
         */
        fun buildDiagnosticClipboardPayload(context: Context): Pair<String, Boolean> {
            val existing = lastSanitizedUiSnapshotJson
            if (!existing.isNullOrBlank()) {
                return existing to true
            }
            val activeConfig = AppConfigRepository.currentAppConfig.value
            val fallback = JSONObject().apply {
                put("schema", "rapido_ui_diagnostic_v1")
                put("status", "NO_RAPIDO_SCREEN_CAPTURED_YET")
                put("generatedAtMs", System.currentTimeMillis())
                put("appVersionCode", BuildConfig.APP_VERSION_CODE)
                put("androidSdk", Build.VERSION.SDK_INT)
                put("deviceManufacturer", Build.MANUFACTURER ?: "unknown")
                put("deviceModel", Build.MODEL ?: "unknown")
                put("accessibilityServiceRunning", isServiceRunning)
                put("accessibilityEnabledInSettings", PermissionUtils.isAccessibilityServiceEnabled(context))
                put("overlayPermissionGranted", PermissionUtils.canDrawOverlays(context))
                put("batteryOptimizationIgnored", PermissionUtils.isIgnoringBatteryOptimizations(context))
                put(
                    "activeConfig",
                    JSONObject().apply {
                        put("targetPackage", AppConfigRepository.effectiveRapidoTargetPackage())
                        put("leftSidebarWidthRatio", AppConfigRepository.effectiveRapidoLeftSidebarWidthRatio().toDouble())
                        put("acceptViewIds", JSONArray(AppConfigRepository.effectiveRapidoAcceptViewIds()))
                        put("acceptKeywordsCount", AppConfigRepository.effectiveRapidoAcceptKeywords().size)
                        put("rejectKeywordsCount", AppConfigRepository.effectiveRapidoRejectKeywords().size)
                        put("seeMoreKeywords", JSONArray(AppConfigRepository.effectiveRapidoSeeMoreOrdersKeywords()))
                        put("autoExpandMoreOrders", activeConfig.rapidoAutoExpandMoreOrders)
                    }
                )
            }.toString(2).replace("\\/", "/")
            return fallback to false
        }

        /**
         * Invokes the system accessibility configuration to activate the Rapido Accessibility Service.
         */
        fun invoke(context: Context) {
            Log.d(TAG, "Invoking RapidoAccessibilityService...")
            PermissionUtils.openAccessibilitySettings(context)
        }

        // Compiled regular expressions for resilient multi-language offer action detection.
        // Specifically tailored to match button actions without matching conversational sentences or chat messages.
        val ACCEPT_REGEX = Regex(
            pattern = """(?i)(?:^\s*(?:accept(?:\s+(?:order|ride|request))?|auto[\s_-]*accept|confirm(?:\s+(?:order|ride))?|select|take\s*(?:the\s*)?(?:ride|order)|swipe\s*to\s*(?:accept|confirm)|go|(?:(?:राइड|ऑर्डर)\s*)?स्वीकार(?:\s*करें|ें)?|कन्फर्म(?:\s*करें)?|चुनें|चुने|राइड\s*लें|ऑर्डर\s*लें|sweekar(?:ein)?|swikar(?:ein)?|accept\s*karein|confirm\s*karein)(?:\s*[\(\[]?\d+s?[\)\]]?)?\s*$|\b(?:swipe\s*to\s*accept|auto[\s_-]*accept|accept\s*(?:order|ride|request)|take\s*ride|confirm\s*(?:order|ride)|accept\s*karein|confirm\s*karein)\b|(?:(?:राइड|ऑर्डर)\s*)?स्वीकार(?:\s*करें)?|कन्फर्म(?:\s*करें)?)"""
        )

        val REJECT_REGEX = Regex(
            pattern = """\b(?:reject|decline|skip|cancel|pass)\b|अस्वीकार|अस्वीकारें|छोड़ें|छोड़े|रद्द|रद्द\s*करें|मना\s*करें|नकारें|asveekar|asweekar|aswikar|chhodein|chodein|radd|cancel\s*karein|reject\s*karein|decline\s*karein|mana\s*karein""",
            options = setOf(RegexOption.IGNORE_CASE)
        )

        val RAPIDO_ACCEPT_ID_REGEX = Regex(
            pattern = """(?i)(?=.*(?:accept|confirm|auto[_-]?accept|take[_-]?ride))(?!.*(?:reject|cancel|decline|unconfirm)).*"""
        )

        val RAPIDO_REJECT_ID_REGEX = Regex(
            pattern = """(?i)(?=.*(?:reject|decline|cancel|skip|pass))(?!.*(?:accept|confirm)).*"""
        )

        // Low-cost signal detection regex for fast event pre-filtering.
        // Strictly handles Indian Rupee symbol variants ('₹', '\u20B9', '\u20A8', 'Rs', 'INR') amidst mixed Hinglish text
        // without triggering on standard system notifications, status updates, or chat messages.
        private val OFFER_SIGNAL_REGEX = Regex(
            pattern = """(?i)(?:(?:[₹\u20B9\u20A8]|(?:\b(?:Rs\.?|INR)\b))\s*\d{2,4}(?:\.\d{1,2})?|\b\d{2,4}(?:\.\d{1,2})?\s*(?:[₹\u20B9\u20A8]|(?:\b(?:Rs\.?|INR)\b))|\b\d+(?:\.\d+)?\s*(?:km|kms|kilometers?)\b|\b(?:ride\s*request|order\s*request|incoming\s*order|new\s*order|new\s*ride|incoming\s*ride|pickup\s*distance|drop\s*distance|swipe\s*to\s*accept|auto[\s_-]*accept|accept\s*(?:order|ride|request)|take\s*ride|rider\s*fare|trip\s*fare|total\s*fare|pickup\s*location|drop\s*location)\b|\b(?:राइड\s*(?:रिक्वेस्ट|ऑर्डर|स्वीकार)|ऑर्डर\s*स्वीकार|स्वीकार\s*करें|कन्फर्म\s*करें|sweekar\s*karein|accept\s*karein)\b)"""
        )

        // Conversational chat and standard notification noise patterns to reject false-positives
        val CHAT_OR_NOTIFICATION_REGEX = Regex(
            pattern = """(?i)\b(?:type\s*(?:a\s*)?message|new\s*message|chat|reply|whatsapp|notification|can\s*you|please|karo|kardo|karoge|mat\s*karo|nahi|bhaiya|bhai|sir|customer|rated|rating|completed|delivered|cancelled|history|battery|signal)\b|[?!]"""
        )

        // Class names that indicate high-frequency telemetry / map rendering events that should be dropped immediately
        private val IGNORED_EVENT_CLASS_REGEX = Regex(
            pattern = """(?i)(?:map|surfaceview|textureview|glsurface|chronometer|progressbar|compass|sensor)"""
        )

        // Indicators on screen that offer was missed, expired, or already taken by another captain
        val REJECTION_INDICATORS_REGEX = Regex(
            pattern = """(?i)(?:another\s+captain|already\s+booked|expired|missed|ride\s+taken|taken\s+by|too\s+late|could\s+not\s+be\s+assigned|assigned\s+to\s+another|ride\s+cancelled|booking\s+failed|offer\s+expired)"""
        )

        // Indicators on screen that ride acceptance succeeded and active trip/navigation has started.
        // Unambiguous trip patterns only; strictly excludes generic offer card addresses ("Pickup", "Drop", "Emergency").
        val ACTIVE_TRIP_INDICATORS_REGEX = Regex(
            pattern = """(?i)(?:\benter\s+otp\b|\botp\s*:\s*\d+|\bstart\s+(?:ride|trip)\b|\bcall\s+(?:customer|rider)\b|\bcontact\s+rider\b|\bnavigate\s+to\b|\barrived?\s+at\s+(?:passenger\s+location|pickup|location|customer)\b|\bdrop[- ]?off\s+at\b|\bpick[\s-]?up\s+(?:customer|rider|passenger)\b)"""
        )
    }

    enum class AcceptanceState {
        CONFIRMED_ACCEPTED,
        REJECTED_OR_TAKEN,
        STALLED_OR_TIMED_OUT
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var isPendingExecution: Boolean = false

    @Volatile
    private var isPollingRetryActive: Boolean = false

    @Volatile
    private var lastProcessTime: Long = 0L

    @Volatile
    private var lastCoordinateTapTime: Long = 0L

    @Volatile
    private var lastMoreOrdersExpandTime: Long = 0L

    @Volatile
    private var lastUiSnapshotCaptureElapsedMs: Long = 0L

    @Volatile
    private var lastAppliedTargetPackage: String = TARGET_PACKAGE

    @Volatile
    private var pendingRetainedAcceptNode: AccessibilityNodeInfo? = null
    private var pendingAcceptRunnable: Runnable? = null
    private var pendingRejectRunnable: Runnable? = null

    private var evalWakeLock: PowerManager.WakeLock? = null

    fun getEffectiveTargetPackage(): String = AppConfigRepository.effectiveRapidoTargetPackage()

    private fun applyServiceTargetPackage(targetPkg: String) {
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.packageNames = arrayOf(targetPkg)
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            serviceInfo = info
            lastAppliedTargetPackage = targetPkg
        } catch (e: Exception) {
            Log.w(TAG, "Unable to update AccessibilityServiceInfo packageNames to $targetPkg: ${e.message}")
        }
    }

    private fun getScreenDimensionsPx(): Pair<Float, Float> {
        return try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width().toFloat().coerceAtLeast(1f) to bounds.height().toFloat().coerceAtLeast(1f)
            } else {
                val dm = resources.displayMetrics
                dm.widthPixels.toFloat().coerceAtLeast(1f) to dm.heightPixels.toFloat().coerceAtLeast(1f)
            }
        } catch (_: Exception) {
            val dm = resources.displayMetrics
            dm.widthPixels.toFloat().coerceAtLeast(1f) to dm.heightPixels.toFloat().coerceAtLeast(1f)
        }
    }

    private fun clearPendingCallbacksAndNodes() {
        pendingAcceptRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingAcceptRunnable = null
        pendingRejectRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRejectRunnable = null
        val retained = pendingRetainedAcceptNode
        pendingRetainedAcceptNode = null
        recycleNode(retained)
    }

    private fun acquireBriefWakeLock(timeoutMs: Long = 4000L) {
        try {
            if (evalWakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                evalWakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OrderMaster:AccessibilityEvalWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            evalWakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire(timeoutMs)
                    Log.d(TAG, "Acquired partial WakeLock for ${timeoutMs}ms")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            evalWakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                    Log.d(TAG, "Released partial WakeLock")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    // Cache of recently processed offer hashes to prevent re-evaluating the same order repeatedly
    private val processedOfferIds = LinkedHashSet<String>()
    private val maxProcessedHistory = 50

    private val accessCheckRunnable = object : Runnable {
        override fun run() {
            val isPro = LicenseManager.isAccessGranted()
            val canAuto = LicenseManager.hasAnyAutoAcceptCapability()
            Log.d(TAG, "Periodic 30s check: Mode = ${if (isPro) "PRO MODE" else if (canAuto) "FREE MODE (Custom Filters)" else "FREE MODE (Locked)"}")
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
                    is AccessStatus.PassActive -> status.passTier.isProPass && status.remainingTimeMs > 0
                    is AccessStatus.TrialActive -> status.remainingTimeMs > 0
                    else -> false
                }
                Log.i(TAG, "LicenseManager access status changed: $status -> Operating in ${if (isPro) "PRO MODE" else "FREE MODE"}")
            }
        }

        // Reactive listener for remote Rapido target package updates from admin_settings/app_config
        serviceScope.launch {
            AppConfigRepository.currentAppConfig.collectLatest {
                val targetPkg = getEffectiveTargetPackage()
                if (isServiceRunning && targetPkg != lastAppliedTargetPackage) {
                    applyServiceTargetPackage(targetPkg)
                    Log.i(TAG, "Updated monitored Rapido package dynamically to $targetPkg")
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isServiceRunning = true
        instance = this

        // CRITICAL: Explicitly set AccessibilityServiceInfo packageNames to effective remote target driver app
        val targetPkg = getEffectiveTargetPackage()
        applyServiceTargetPackage(targetPkg)

        TTSManager.init(this)
        Log.d(TAG, "Accessibility Service connected and operational for package: $targetPkg.")
        AppSettings.addLog(
            title = "Service Connected",
            message = "Rapido Captain Auto-Accept Accessibility Service active for $targetPkg.",
            severity = LogSeverity.INFO
        )
        broadcastLog("Accessibility Service started and monitoring $targetPkg.")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        if (instance === this) {
            instance = null
        }
        isPendingExecution = false
        clearPendingCallbacksAndNodes()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        TTSManager.shutdown()
        releaseWakeLock()
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
        clearPendingCallbacksAndNodes()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 0. Strict Global Kill Switch & Ban Check: Block all background MacroDroid / Accessibility actions immediately
        if (!GlobalAdminManager.canExecuteActions(this)) {
            return
        }

        // 1. Strict Target Package Filtering (Prevent Self-Scanning & Infinite Loops)
        // Uses remote-configurable rapidoTargetPackage (default: "com.rapido.rider").
        val targetPkg = getEffectiveTargetPackage()
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg.isEmpty() || eventPkg == packageName || eventPkg != targetPkg) {
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
        val currentTime = SystemClock.elapsedRealtime()
        if (isPendingExecution || (currentTime - lastProcessTime < DEBOUNCE_WINDOW_MS)) {
            return
        }
        // Advance timestamp immediately so subsequent events within the debounce window are discarded
        lastProcessTime = currentTime

        // 6. Multi-Window & Overlay Support: Retrieve root nodes strictly belonging to target driver app
        val rawRootWindows = getAllRootWindows()
        if (rawRootWindows.isEmpty()) return

        var parsedOffers: List<ParsedRideOffer>? = null
        try {
            // 1. Strict Rapido Overlay Popup Window Detection:
            // Filter out floating overlay widget bubble background node trees (which contain stored history values)
            val candidateWindows = rawRootWindows.filter { !isFloatingWidgetOrBubble(it) }
            if (candidateWindows.isEmpty()) {
                return
            }

            val (screenWFloat, screenHFloat) = getScreenDimensionsPx()
            val screenWidthPx = screenWFloat.toInt()
            val screenHeightPx = screenHFloat.toInt()

            // Throttled privacy-safe UI structure capture for "Report UI Issue" diagnostics
            if (currentTime - lastUiSnapshotCaptureElapsedMs >= UI_SNAPSHOT_CAPTURE_INTERVAL_MS) {
                lastUiSnapshotCaptureElapsedMs = currentTime
                runCatching {
                    captureSanitizedUiSnapshot(
                        rootWindows = candidateWindows,
                        packageName = targetPkg,
                        screenWidthPx = screenWidthPx,
                        screenHeightPx = screenHeightPx
                    )
                }
            }

            // STRICT REQUIREMENT: If no valid "Accept", "Reject", or "Order Offer" view ID/text node is found on screen,
            // trigger a lightweight retry loop in case the offer card layout is still rendering asynchronously.
            if (!hasActiveRideOfferOnScreen(candidateWindows)) {
                scheduleNodeRenderRetry()
                return
            }

            // 1. Card-Level Layout Isolation: Extract distinct order card containers across candidate windows
            // and parse each container independently into a strictly isolated ParsedRideOffer
            // (excluding Rapido's left-side mini-tab strip on multi-order screens).
            val offers = RideOfferParser.parseCards(candidateWindows, screenWidthPx)
            parsedOffers = offers

            if (offers.isEmpty()) {
                // Capture latest sanitized tree whenever an active offer screen fails to parse cards
                lastUiSnapshotCaptureElapsedMs = currentTime
                runCatching {
                    captureSanitizedUiSnapshot(
                        rootWindows = candidateWindows,
                        packageName = targetPkg,
                        screenWidthPx = screenWidthPx,
                        screenHeightPx = screenHeightPx
                    )
                }
                scheduleNodeRenderRetry()
                return
            }

            // Deduplication Check: Check if all detected offers have already been processed in current cycle
            val newOffers = synchronized(processedOfferIds) {
                offers.filter { !processedOfferIds.contains(it.id) }
            }
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
     * Schedules a lightweight retry polling loop (every 100ms for up to 1000ms)
     * when a potential offer event arrives before the Rapido card UI has fully rendered.
     * Prevents missed order clicks due to asynchronous UI transitions.
     */
    private fun scheduleNodeRenderRetry() {
        if (isPollingRetryActive || isPendingExecution) return
        isPollingRetryActive = true
        acquireBriefWakeLock(3500L)

        serviceScope.launch(Dispatchers.Default) {
            try {
                var attempts = 0
                val maxAttempts = 10 // 10 x 100ms = 1000ms max
                while (attempts < maxAttempts && !isPendingExecution && isServiceRunning) {
                    delay(100L)
                    attempts++

                    if (!AppSettings.isAutoAcceptEnabled(this@RapidoAccessibilityService) ||
                        !GlobalAdminManager.canExecuteActions(this@RapidoAccessibilityService)
                    ) {
                        break
                    }

                    val rawWindows = getAllRootWindows()
                    if (rawWindows.isEmpty()) continue

                    var offers: List<ParsedRideOffer>? = null
                    var foundNewOffer = false
                    try {
                        val candidate = rawWindows.filter { !isFloatingWidgetOrBubble(it) }
                        if (candidate.isEmpty() || !hasActiveRideOfferOnScreen(candidate)) {
                            continue
                        }

                        val isPro = PassManager.isAccessGranted(this@RapidoAccessibilityService)
                        val screenWidthPx = getScreenDimensionsPx().first.toInt()
                        offers = RideOfferParser.parseCards(candidate, screenWidthPx)
                        if (offers.isNotEmpty()) {
                            val newOffers = synchronized(processedOfferIds) {
                                offers.filter { !processedOfferIds.contains(it.id) }
                            }
                            if (newOffers.isNotEmpty()) {
                                Log.i(TAG, "Retry loop successfully detected ${offers.size} offer card(s) at attempt #$attempts")
                                processOrderQueue(offers, candidate, isPro)
                                foundNewOffer = true
                            }
                        }
                    } finally {
                        offers?.forEach { it.acceptButton?.let { b -> recycleNode(b) } }
                        for (w in rawWindows) recycleNode(w)
                    }
                    if (foundNewOffer) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Node render retry loop failed", e)
            } finally {
                isPollingRetryActive = false
                releaseWakeLock()
            }
        }
    }

    /**
     * Fast, low-cost pre-filtering check to eliminate battery drain and CPU spikes.
     * Prevents invoking getAllRootWindows() and deep UI tree scans on frequent non-offer events
     * such as continuous GPS map re-rendering, animations, and clock ticks.
     */
    internal fun isPotentialOfferEvent(event: AccessibilityEvent): Boolean {
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

        // Permit presentation containers like dialogs, bottom sheets, alert cards, or popups
        val classNameLower = className.lowercase(Locale.ROOT)
        if (classNameLower.contains("dialog") ||
            classNameLower.contains("bottomsheet") ||
            classNameLower.contains("popup") ||
            classNameLower.contains("alert") ||
            classNameLower.contains("banner") ||
            classNameLower.contains("sheet")
        ) {
            return true
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
            if (CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(eventContent)) {
                return false
            }
        }

        // 2. Shallow inspection of event source node (depth <= 2, bounded nodes)
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

                // Check immediate children (bounded to 10 nodes max)
                val childLimit = minOf(source.childCount, 10)
                for (i in 0 until childLimit) {
                    val child = source.getChild(i)
                    if (child != null) {
                        try {
                            val cText = child.text?.toString() ?: ""
                            val cDesc = child.contentDescription?.toString() ?: ""
                            val cId = child.viewIdResourceName ?: ""

                            val match = isAcceptAction(cText) || isAcceptAction(cDesc) || isRapidoAcceptId(cId) ||
                                    isRejectAction(cText) || isRejectAction(cDesc) || isRapidoRejectId(cId) ||
                                    OFFER_SIGNAL_REGEX.containsMatchIn(cText) ||
                                    OFFER_SIGNAL_REGEX.containsMatchIn(cDesc)
                            if (match) {
                                return true
                            }

                            // Shallow inspection of grandchildren (bounded to 6 max)
                            val grandChildLimit = minOf(child.childCount, 6)
                            for (j in 0 until grandChildLimit) {
                                val gc = child.getChild(j)
                                if (gc != null) {
                                    try {
                                        val gcText = gc.text?.toString() ?: ""
                                        val gcDesc = gc.contentDescription?.toString() ?: ""
                                        val gcId = gc.viewIdResourceName ?: ""

                                        val gcMatch = isAcceptAction(gcText) || isAcceptAction(gcDesc) || isRapidoAcceptId(gcId) ||
                                                isRejectAction(gcText) || isRejectAction(gcDesc) || isRapidoRejectId(gcId) ||
                                                OFFER_SIGNAL_REGEX.containsMatchIn(gcText) ||
                                                OFFER_SIGNAL_REGEX.containsMatchIn(gcDesc)
                                        if (gcMatch) {
                                            return true
                                        }
                                    } finally {
                                        recycleNode(gc)
                                    }
                                }
                            }
                        } finally {
                            recycleNode(child)
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
    @androidx.annotation.VisibleForTesting
    internal fun processOrderQueue(offers: List<ParsedRideOffer>, rootWindows: List<AccessibilityNodeInfo>, isProMode: Boolean) {
        val unprocessedOffers = synchronized(processedOfferIds) {
            offers.filter { !processedOfferIds.contains(it.id) }
        }
        if (unprocessedOffers.isEmpty()) {
            return
        }

        val isAutoAccept = AppSettings.isAutoAcceptEnabled(this)
        val isAutoClick = AppSettings.isAutoClickEnabled(this)
        val hasActiveAccess = LicenseManager.hasAnyAutoAcceptCapability()
        val shouldRecordProcessed = isAutoClick && hasActiveAccess

        // UI Logging Requirement: Log total distinct unprocessed order cards detected
        AppSettings.addLog(
            title = "Orders Detected",
            message = "Detected ${unprocessedOffers.size} distinct order card${if (unprocessedOffers.size > 1) "s" else ""} on screen (${if (isProMode) "Pro Mode" else "Free Mode"}).",
            severity = LogSeverity.INFO
        )
        broadcastLog("Detected ${unprocessedOffers.size} distinct order card${if (unprocessedOffers.size > 1) "s" else ""} [${if (isProMode) "Pro" else "Free"}]")

        val isAutoClickSpeedUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK)
        val isTtsVoiceUnlocked = LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE)
        val hasAnyActiveFilterEnabled = AppSettings.hasActiveEvaluationFilter(this)

        val evaluatedOffers = mutableListOf<Pair<ParsedRideOffer, RideEvaluation>>()

        // Sequential Queue Evaluation: Always evaluate offers via live entitlement-gated evaluator
        // (Free Mode with 0 active filters bypasses all locked/stale filters; Point-unlocked filters enforce only active unlocks; Pro Mode enforces all enabled filters)
        unprocessedOffers.forEachIndexed { index, offer ->
            // Record in deduplication set ONLY when Auto-Click is enabled and access is active,
            // so switching from VOICE to AUTO while the card is still visible picks it up immediately.
            if (shouldRecordProcessed) {
                synchronized(processedOfferIds) {
                    if (processedOfferIds.size >= maxProcessedHistory) {
                        val firstKey = processedOfferIds.iterator().next()
                        processedOfferIds.remove(firstKey)
                    }
                    processedOfferIds.add(offer.id)
                }
            }

            val evaluation = evaluateOfferWithEffectiveLicensing(this, offer)

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
            val hasActiveAccess = LicenseManager.hasAnyAutoAcceptCapability()
            if (!hasActiveAccess) {
                val topOffer = matchingOffers.first().first
                AppSettings.addLog(
                    title = "Auto-Accept Restricted",
                    message = "Matching ride detected (₹${topOffer.totalFare?.toInt() ?: 0}), but automation is currently restricted or locked by Admin.",
                    severity = LogSeverity.WARNING,
                    evaluation = matchingOffers.first().second
                )
                broadcastLog("Auto-Accept paused: Service offline or restricted by Admin.")
                return
            }

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
            // Acceptance Speed:
            // - Pro Mode or Point-unlocked AUTO_CLICK boost: 300–5000ms (user-configured)
            // - Free Mode (default / unfiltered or standard point-filter mode): strictly 1–2s (1000–2000ms)
            val configuredDelayMs = AppSettings.getClickDelayMs(this)
            val acceptDelayMs = computeEffectiveAcceptDelayMs(
                configuredDelayMs = configuredDelayMs,
                isProMode = isProMode,
                isAutoClickSpeedUnlocked = isAutoClickSpeedUnlocked
            )

            val speedLabel = if (isProMode || isAutoClickSpeedUnlocked) {
                "Boost (${acceptDelayMs}ms)"
            } else {
                "Free Mode 1-2s (${acceptDelayMs}ms)"
            }
            AppSettings.addLog(
                title = "Auto-Accept Scheduled",
                message = "Selected best offer: ₹${bestOffer.totalFare?.toInt() ?: 0}, ${bestOffer.pickupDistanceKm ?: 0.0}km. Auto-clicking Accept in ${acceptDelayMs}ms ($speedLabel)...",
                severity = LogSeverity.MATCH_ACCEPTED,
                evaluation = bestEvaluation
            )
            broadcastLog("Auto-clicking Accept for best offer (₹${bestOffer.totalFare?.toInt() ?: 0}) in ${acceptDelayMs}ms [$speedLabel]...")

            val clonedAcceptNode = bestOffer.acceptButton?.let {
                try {
                    @Suppress("DEPRECATION")
                    AccessibilityNodeInfo.obtain(it)
                } catch (_: Exception) {
                    null
                }
            }

            clearPendingCallbacksAndNodes()
            pendingRetainedAcceptNode = clonedAcceptNode
            val acceptRunnable = Runnable {
                val nodeToUse = pendingRetainedAcceptNode
                pendingRetainedAcceptNode = null
                pendingAcceptRunnable = null
                serviceScope.launch {
                    executeCardAcceptClick(bestOffer, bestEvaluation, nodeToUse)
                }
            }
            pendingAcceptRunnable = acceptRunnable
            mainHandler.postDelayed(acceptRunnable, acceptDelayMs)
        } else if (isAutoAccept && evaluatedOffers.isNotEmpty()) {
            // Optional Remote-Controlled Expansion: When top floating popup fails filters and
            // "See N more orders >" is visible, auto-tap it to inspect the full order list instead of rejecting.
            if (tryExpandMoreOrdersIfConfigured(
                    rootWindows = rootWindows,
                    isProMode = isProMode,
                    hasAnyActiveFilterEnabled = hasAnyActiveFilterEnabled
                )
            ) {
                return
            }

            // Smart Auto-Reject: Execute only if Pro Mode or at least 1 evaluation filter is BOTH unlocked & enabled
            val canAutoReject = (isProMode || hasAnyActiveFilterEnabled) &&
                !LicenseManager.isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK) &&
                LicenseManager.hasAnyAutoAcceptCapability()
            if (canAutoReject) {
                val isAutoClick = AppSettings.isAutoClickEnabled(this)
                if (isAutoClick) {
                    val invalidOffers = evaluatedOffers
                        .filterNot { it.second.isAccepted }
                        .sortedWith(compareBy({ it.first.bounds.top }, { it.first.bounds.left }))

                    if (invalidOffers.isNotEmpty()) {
                        val configuredDelayMs = AppSettings.getClickDelayMs(this)
                        val delayMs = computeEffectiveAcceptDelayMs(
                            configuredDelayMs = configuredDelayMs,
                            isProMode = isProMode,
                            isAutoClickSpeedUnlocked = isAutoClickSpeedUnlocked
                        )
                        pendingRejectRunnable?.let { mainHandler.removeCallbacks(it) }
                        isPendingExecution = true
                        val rejectRunnable = Runnable {
                            pendingRejectRunnable = null
                            serviceScope.launch {
                                try {
                                    for ((index, item) in invalidOffers.withIndex()) {
                                        val (rejectedOffer, rejectedEval) = item
                                        if (!isServiceRunning ||
                                            !AppSettings.isAutoAcceptEnabled(this@RapidoAccessibilityService) ||
                                            !AppSettings.isAutoClickEnabled(this@RapidoAccessibilityService) ||
                                            !GlobalAdminManager.canExecuteActions(this@RapidoAccessibilityService)
                                        ) {
                                            break
                                        }
                                        val clicked = executeCardRejectClick(rejectedOffer, rejectedEval)
                                        if (index < invalidOffers.size - 1) {
                                            if (clicked) {
                                                delay(REJECT_DEBOUNCE_MS + 25L)
                                            } else {
                                                delay(50L)
                                            }
                                        }
                                    }
                                } finally {
                                    isPendingExecution = false
                                }
                            }
                        }
                        pendingRejectRunnable = rejectRunnable
                        mainHandler.postDelayed(rejectRunnable, delayMs)
                    }
                }
            } else {
                Log.d(TAG, "Auto-reject skipped: filters locked, allowing offer to time out.")
            }
        }
    }

    /**
     * When `rapidoAutoExpandMoreOrders` is enabled remotely and the current popup offer fails
     * active filters while "See N more orders >" is visible, taps that chip to inspect the full
     * order list without prematurely dismissing it.
     */
    private fun tryExpandMoreOrdersIfConfigured(
        rootWindows: List<AccessibilityNodeInfo>,
        isProMode: Boolean,
        hasAnyActiveFilterEnabled: Boolean
    ): Boolean {
        if (!AppConfigRepository.isRapidoAutoExpandMoreOrdersEnabled()) return false
        if (!AppSettings.isAutoClickEnabled(this)) return false
        if (!GlobalAdminManager.canExecuteActions(this)) return false
        if (!LicenseManager.hasAnyAutoAcceptCapability()) return false
        if (!isProMode && !hasAnyActiveFilterEnabled) return false

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastMoreOrdersExpandTime < MORE_ORDERS_EXPAND_COOLDOWN_MS) {
            return false
        }

        val screenWidthPx = getScreenDimensionsPx().first.toInt()
        for (root in rootWindows) {
            val expandNode = RideOfferParser.findSeeMoreOrdersNode(root, screenWidthPx) ?: continue
            try {
                val clicked = expandNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    lastMoreOrdersExpandTime = nowElapsed
                    AppSettings.addLog(
                        title = "Expanded More Orders",
                        message = "Top offer failed filters; tapped 'See more orders' to inspect full order list.",
                        severity = LogSeverity.INFO
                    )
                    broadcastLog("Tapped 'See more orders' to inspect remaining rides.")
                    return true
                }
                val bounds = Rect()
                expandNode.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    lastMoreOrdersExpandTime = nowElapsed
                    val cx = bounds.centerX().toFloat()
                    val cy = bounds.centerY().toFloat()
                    serviceScope.launch(Dispatchers.Main) {
                        val path = Path().apply { moveTo(cx, cy) }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
                            .build()
                        dispatchGesture(gesture, null, null)
                    }
                    AppSettings.addLog(
                        title = "Expanded More Orders",
                        message = "Top offer failed filters; dispatched tap on 'See more orders' chip.",
                        severity = LogSeverity.INFO
                    )
                    broadcastLog("Tapped 'See more orders' to inspect remaining rides.")
                    return true
                }
            } finally {
                recycleNode(expandNode)
            }
        }
        return false
    }

    /**
     * Checks if the currently active window's package matches the effective Rapido package.
     * If not, dispatches an Intent to bring the target app to the foreground
     * so that gesture dispatches hit the correct application.
     */
    fun bringTargetAppToForeground() {
        val targetPkg = getEffectiveTargetPackage()
        try {
            val currentPkg = rootInActiveWindow?.packageName?.toString()
            if (currentPkg != targetPkg) {
                Log.i(TAG, "Current active window package ($currentPkg) is not $targetPkg. Bringing target app to foreground.")
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Log.i(TAG, "Dispatched startActivity for $targetPkg")
                } else {
                    Log.w(TAG, "Launch intent for $targetPkg returned null.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in bringTargetAppToForeground: ${e.message}", e)
        }
    }

    /**
     * Executes ACTION_CLICK strictly targeting the Reject/Pass button node
     * located inside the spatial bounds of an invalid/rejected order card to clear the screen.
     */
    private suspend fun executeCardRejectClick(offer: ParsedRideOffer, evaluation: RideEvaluation): Boolean {
        acquireBriefWakeLock(4000L)
        try {
            if (!GlobalAdminManager.canExecuteActions(this)) {
                Log.w(TAG, "executeCardRejectClick blocked: Global kill switch active or user banned.")
                return false
            }
            if (!AppSettings.isAutoAcceptEnabled(this) || !AppSettings.isAutoClickEnabled(this)) {
                Log.d(TAG, "executeCardRejectClick skipped: Auto-Accept or Auto-Click disabled.")
                return false
            }
            // Security check: Active pass/trial OR at least one unlocked & enabled granular filter (and AUTO_CLICK not Admin-locked)
            val isProModeNow = LicenseManager.isAccessGranted()
            val canAutoReject = (isProModeNow || AppSettings.hasActiveEvaluationFilter(this)) &&
                !LicenseManager.isFeatureLockedByAdmin(FeatureIds.AUTO_CLICK) &&
                LicenseManager.hasAnyAutoAcceptCapability()
            if (!canAutoReject) {
                Log.w(TAG, "executeCardRejectClick: Auto-reject blocked. No active filters unlocked/enabled.")
                return false
            }

            // Debounce / Cooldown check: respect active debounce/cooldown
            val now = SystemClock.elapsedRealtime()
            if (now < lastProcessTime) {
                Log.d(TAG, "executeCardRejectClick: Suppressed due to active cooldown.")
                return false
            }

            var clicked = false
            val cardBounds = offer.bounds

            // 1. Search strictly within card bounds across active windows
            val rootWindows = getAllRootWindows()
            try {
                for (root in rootWindows) {
                    val targetNode = findRejectNodeInBounds(root, cardBounds) ?: continue
                    try {
                        val clickable = findClickableParentWithinCard(targetNode, cardBounds)
                            ?: if (targetNode.isClickable) {
                                val b = Rect()
                                targetNode.getBoundsInScreen(b)
                                if (b.height() <= 280 && (cardBounds.isEmpty || b.height() <= cardBounds.height() * 0.45f)) {
                                    @Suppress("DEPRECATION")
                                    AccessibilityNodeInfo.obtain(targetNode)
                                } else null
                            } else null

                        if (clickable != null) {
                            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.i(TAG, "executeCardRejectClick: in-bounds ACTION_CLICK success=$clicked")
                            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                bringTargetAppToForeground()
                                delay(250L)
                                clicked = dispatchTapGesture(clickable)
                                Log.i(TAG, "executeCardRejectClick: fallback gesture success=$clicked")
                            }
                            if (clickable !== targetNode) {
                                recycleNode(clickable)
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            bringTargetAppToForeground()
                            delay(250L)
                            clicked = dispatchTapGesture(targetNode)
                            Log.i(TAG, "executeCardRejectClick: fallback gesture on targetNode success=$clicked")
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
                val (screenWidth, screenHeight) = getScreenDimensionsPx()

                // Target the left-most 15-20% and bottom 20% of the offer card (Pass/Reject button location)
                val rawTapX = cardBounds.left + (cardBounds.width() * 0.15f)
                val rawTapY = cardBounds.bottom - (cardBounds.height() * 0.2f)

                // Clamp strictly within card bounds and physical screen resolution
                val tapX = rawTapX.coerceIn(cardBounds.left.toFloat(), (cardBounds.right - 1).toFloat())
                    .coerceIn(0f, screenWidth - 1f)
                val tapY = rawTapY.coerceIn(cardBounds.top.toFloat(), (cardBounds.bottom - 1).toFloat())
                    .coerceIn(0f, screenHeight - 1f)

                bringTargetAppToForeground()
                delay(250L)
                clicked = dispatchTapAtCoordinates(tapX, tapY)
                Log.i(TAG, "executeCardRejectClick: coordinate fallback tap at ($tapX, $tapY) success=$clicked")
            }

            // Debounce: 400ms debounce upon success so next offer is not missed; standard debounce on failure
            lastProcessTime = if (clicked) {
                SystemClock.elapsedRealtime() + REJECT_DEBOUNCE_MS
            } else {
                SystemClock.elapsedRealtime()
            }

            AppSettings.addLog(
                title = if (clicked) "Order Auto-Dismissed" else "Reject Attempted",
                message = if (clicked) "Dismissed card via Reject action. (${evaluation.decisionReason}). Debounce active (400ms)."
                else "Reject button not found in card bounds; letting offer time out naturally.",
                severity = if (clicked) LogSeverity.REJECTED else LogSeverity.INFO,
                evaluation = evaluation
            )
            broadcastLog("Auto-reject executed: $clicked (${evaluation.decisionReason})")
            return clicked
        } catch (e: Exception) {
            Log.e(TAG, "Error executing card reject click", e)
            lastProcessTime = SystemClock.elapsedRealtime()
            return false
        } finally {
            releaseWakeLock()
        }
    }

    /**
     * Finds an actionable reject node strictly located inside the provided screen bounding box.
     * Enforces depth-limiting (<= 12) and strict timeout (< 50ms) to prevent ANRs on heavily nested layouts.
     */
    fun findRejectNodeInBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(root), 0))

        val startTime = SystemClock.elapsedRealtime()
        try {
            while (queue.isNotEmpty()) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                    break
                }

                val (node, depth) = queue.removeFirst()
                var keepNode = false
                try {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    if (isRejectAction(text) || isRejectAction(desc) || isRapidoRejectId(viewId)) {
                        val nodeBounds = Rect()
                        node.getBoundsInScreen(nodeBounds)

                        // Verify that the node has valid bounds inside the card's range
                        val isInBounds = !nodeBounds.isEmpty && (
                            bounds.contains(nodeBounds.centerX(), nodeBounds.centerY()) ||
                                (nodeBounds.top >= bounds.top - 20 && nodeBounds.bottom <= bounds.bottom + 20)
                            )

                        if ((isInBounds || nodeBounds.isEmpty) && isActionableNodeOrChild(node)) {
                            keepNode = true
                            return node
                        }
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                                break
                            }
                            val child = node.getChild(i) ?: continue
                            queue.add(Pair(child, depth + 1))
                        }
                    }
                } finally {
                    if (!keepNode) {
                        recycleNode(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst().first)
            }
        }
        return null
    }

    /**
     * Executes the acceptance click specifically within the card's bounding box and verifies transition
     * to eliminate false-positive acceptance logs and announcements.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun executeCardAcceptClick(
        offer: ParsedRideOffer,
        evaluation: RideEvaluation,
        retainedAcceptNode: AccessibilityNodeInfo? = null
    ) {
        acquireBriefWakeLock(5000L)
        isPendingExecution = true
        lastProcessTime = SystemClock.elapsedRealtime()

        var clicked = false
        val cardBounds = offer.bounds

        try {
            // Re-verify Global Kill Switch, Ban status, Licensing & Auto-Click toggle after the randomized delay
            if (!GlobalAdminManager.canExecuteActions(this)) {
                Log.w(TAG, "executeCardAcceptClick blocked: Global kill switch active or user banned.")
                synchronized(processedOfferIds) { processedOfferIds.remove(offer.id) }
                return
            }
            if (!LicenseManager.hasAnyAutoAcceptCapability()) {
                Log.w(TAG, "executeCardAcceptClick blocked: License expired or no filter/feature unlocked.")
                synchronized(processedOfferIds) { processedOfferIds.remove(offer.id) }
                return
            }
            if (!AppSettings.isAutoAcceptEnabled(this) || !AppSettings.isAutoClickEnabled(this)) {
                Log.i(TAG, "executeCardAcceptClick skipped: Auto-Click disabled during delay (Voice-Only TTS mode).")
                synchronized(processedOfferIds) { processedOfferIds.remove(offer.id) }
                return
            }

            // 1. Fresh Accept Node Lookup: Always prefer a fresh findAcceptNodeInBounds lookup
            // from current getAllRootWindows() first (since retainedAcceptNode becomes stale when
            // Rapido's countdown ring updates the UI).
            val rootWindows = getAllRootWindows()
            try {
                for (root in rootWindows) {
                    val targetNode = findAcceptNodeInBounds(root, cardBounds) ?: continue
                    try {
                        val clickable = findClickableParentWithinCard(targetNode, cardBounds)
                            ?: if (targetNode.isClickable) {
                                val b = Rect()
                                targetNode.getBoundsInScreen(b)
                                if (b.height() <= 280 && (cardBounds.isEmpty || b.height() <= cardBounds.height() * 0.45f)) {
                                    @Suppress("DEPRECATION")
                                    AccessibilityNodeInfo.obtain(targetNode)
                                } else null
                            } else null

                        if (clickable != null) {
                            clicked = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.i(TAG, "executeCardAcceptClick: fresh in-bounds ACTION_CLICK success=$clicked")
                        } else {
                            Log.i(TAG, "executeCardAcceptClick: no clickable button widget found within button size constraints")
                        }

                        // If ACTION_CLICK succeeded, verify whether the card or accept button is still on screen after 450ms
                        if (clicked) {
                            delay(450L)
                            val postClickWindows = getAllRootWindows()
                            var stillPresent = false
                            try {
                                for (postRoot in postClickWindows) {
                                    val stillNode = findAcceptNodeInBounds(postRoot, cardBounds)
                                    if (stillNode != null) {
                                        stillPresent = true
                                        recycleNode(stillNode)
                                        break
                                    }
                                }
                                if (!stillPresent) {
                                    stillPresent = hasActiveRideOfferOnScreen(postClickWindows)
                                }
                            } finally {
                                for (postRoot in postClickWindows) {
                                    recycleNode(postRoot)
                                }
                            }

                            if (stillPresent) {
                                Log.w(TAG, "executeCardAcceptClick: card/accept button still on screen after 450ms despite ACTION_CLICK=true. Falling back to coordinate tap on Accept button node.")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                    bringTargetAppToForeground()
                                    delay(100L)
                                    FloatingOverlayService.setTemporarilyUntouchable(true)
                                    val freshRoots = getAllRootWindows()
                                    var gestureDispatched = false
                                    try {
                                        for (freshRoot in freshRoots) {
                                            val freshAccept = findAcceptNodeInBounds(freshRoot, cardBounds) ?: continue
                                            try {
                                                gestureDispatched = executeAutoAcceptGesture(freshAccept)
                                                if (gestureDispatched) break
                                            } finally {
                                                recycleNode(freshAccept)
                                            }
                                        }
                                    } finally {
                                        for (freshRoot in freshRoots) {
                                            recycleNode(freshRoot)
                                        }
                                    }
                                    if (!gestureDispatched) {
                                        gestureDispatched = executeAutoAcceptGesture(targetNode)
                                    }
                                    clicked = gestureDispatched
                                    Log.i(TAG, "executeCardAcceptClick: accept button node coordinate fallback success=$clicked")
                                }
                            }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            // If ACTION_CLICK was false or no clickable parent widget existed within size constraints,
                            // immediately fallback to coordinate tap on the actual Accept button node
                            bringTargetAppToForeground()
                            delay(250L)
                            FloatingOverlayService.setTemporarilyUntouchable(true)
                            clicked = executeAutoAcceptGesture(targetNode)
                            Log.i(TAG, "executeCardAcceptClick: fresh in-bounds gesture success=$clicked")
                        }

                        if (clickable != null && clickable !== targetNode) {
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

            // 2. Fall back to pre-delay retainedAcceptNode if fresh lookup did not succeed
            if (!clicked && retainedAcceptNode != null) {
                try {
                    val clickableTarget = findClickableParentWithinCard(retainedAcceptNode, cardBounds)
                        ?: if (retainedAcceptNode.isClickable) {
                            val b = Rect()
                            retainedAcceptNode.getBoundsInScreen(b)
                            if (b.height() <= 280 && (cardBounds.isEmpty || b.height() <= cardBounds.height() * 0.45f)) {
                                @Suppress("DEPRECATION")
                                AccessibilityNodeInfo.obtain(retainedAcceptNode)
                            } else null
                        } else null

                    if (clickableTarget != null) {
                        clicked = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.i(TAG, "executeCardAcceptClick: fallback retainedAcceptNode click success=$clicked")
                        if (clicked) {
                            delay(450L)
                            val postClickWindows = getAllRootWindows()
                            var stillPresent = false
                            try {
                                for (postRoot in postClickWindows) {
                                    val stillNode = findAcceptNodeInBounds(postRoot, cardBounds)
                                    if (stillNode != null) {
                                        stillPresent = true
                                        recycleNode(stillNode)
                                        break
                                    }
                                }
                                if (!stillPresent) {
                                    stillPresent = hasActiveRideOfferOnScreen(postClickWindows)
                                }
                            } finally {
                                for (postRoot in postClickWindows) {
                                    recycleNode(postRoot)
                                }
                            }
                            if (stillPresent && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                Log.w(TAG, "executeCardAcceptClick: retainedAcceptNode still on screen after 450ms. Dispatching coordinate tap on retainedAcceptNode.")
                                FloatingOverlayService.setTemporarilyUntouchable(true)
                                clicked = executeAutoAcceptGesture(retainedAcceptNode)
                            }
                        }
                    }
                    if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        FloatingOverlayService.setTemporarilyUntouchable(true)
                        clicked = executeAutoAcceptGesture(retainedAcceptNode)
                    }
                    if (clickableTarget != null && clickableTarget !== retainedAcceptNode) {
                        recycleNode(clickableTarget)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "executeCardAcceptClick: fallback retainedAcceptNode click failed", e)
                }
            }

            // 3. Fallback coordinate tap heuristic targeting bottom-right corner of card bounds
            if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !cardBounds.isEmpty) {
                val (screenWidth, screenHeight) = getScreenDimensionsPx()

                // Target the right-most 15-20% and bottom 20% of the offer card
                val rawTapX = cardBounds.right - (cardBounds.width() * 0.15f)
                val rawTapY = cardBounds.bottom - (cardBounds.height() * 0.2f)

                // Clamp strictly within card bounds and physical screen resolution
                val tapX = rawTapX.coerceIn(cardBounds.left.toFloat(), (cardBounds.right - 1).toFloat())
                    .coerceIn(0f, screenWidth - 1f)
                val tapY = rawTapY.coerceIn(cardBounds.top.toFloat(), (cardBounds.bottom - 1).toFloat())
                    .coerceIn(0f, screenHeight - 1f)

                bringTargetAppToForeground()
                delay(250L)
                FloatingOverlayService.setTemporarilyUntouchable(true)
                clicked = dispatchTapAtCoordinates(tapX, tapY)
                Log.i(TAG, "executeCardAcceptClick: coordinate fallback tap at ($tapX, $tapY) success=$clicked")
            }

            if (clicked) {
                // Wait-for-Success State Machine: Verify screen transition before marking order as accepted
                broadcastLog("Accept clicked. Verifying order confirmation with Rapido...")
                val result = verifyAcceptanceTransition(offer, evaluation, cardBounds)

                when (result) {
                    AcceptanceState.CONFIRMED_ACCEPTED -> {
                        lastProcessTime = SystemClock.elapsedRealtime() + COOLDOWN_MS
                        val fare = offer.totalFare ?: evaluation.totalCurrency ?: 0.0
                        if (fare > 0) {
                            AppSettings.updateLastAcceptedFare(this, fare)
                        }
                        AppSettings.addLog(
                            title = "Order Confirmed Accepted!",
                            message = "Order #₹${fare.toInt()} verified and confirmed accepted! Trip active. Cooldown active (2000ms).",
                            severity = LogSeverity.CLICK_EXECUTED,
                            evaluation = evaluation
                        )
                        broadcastLog("Order Confirmed Accepted! Fare ₹${fare.toInt()}")
                        announceRideAccepted(evaluation.totalCurrency, evaluation.distanceKm)
                    }
                    AcceptanceState.REJECTED_OR_TAKEN -> {
                        lastProcessTime = SystemClock.elapsedRealtime()
                        AppSettings.addLog(
                            title = "Order Missed / Already Taken",
                            message = "Accept click was dispatched for ₹${offer.totalFare?.toInt()}, but order was taken by another captain or expired.",
                            severity = LogSeverity.WARNING,
                            evaluation = evaluation
                        )
                        broadcastLog("Order missed: Taken by another captain or expired.")
                    }
                    AcceptanceState.STALLED_OR_TIMED_OUT -> {
                        lastProcessTime = SystemClock.elapsedRealtime()
                        // Remove from processedOfferIds so the still-visible card can be retried on next accessibility event
                        synchronized(processedOfferIds) {
                            processedOfferIds.remove(offer.id)
                        }
                        AppSettings.addLog(
                            title = "Order Acceptance Stalled",
                            message = "Accept button was clicked, but order screen did not transition within 3.5s. Removed from processedOfferIds for retry.",
                            severity = LogSeverity.WARNING,
                            evaluation = evaluation
                        )
                        broadcastLog("Order acceptance stalled / not responded.")
                    }
                }
            } else {
                lastProcessTime = SystemClock.elapsedRealtime()
                // Remove from processedOfferIds so the still-visible card can be retried on next accessibility event
                synchronized(processedOfferIds) {
                    processedOfferIds.remove(offer.id)
                }
                AppSettings.addLog(
                    title = "Click Attempt Failed",
                    message = "Accept button could not be clicked within card boundaries. Removed from processedOfferIds for retry.",
                    severity = LogSeverity.WARNING,
                    evaluation = evaluation
                )
                broadcastLog("Accept button could not be clicked.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing card accept click", e)
            lastProcessTime = SystemClock.elapsedRealtime()
            synchronized(processedOfferIds) {
                processedOfferIds.remove(offer.id)
            }
        } finally {
            recycleNode(retainedAcceptNode)
            isPendingExecution = false
            releaseWakeLock()
        }
    }

    /**
     * Polls the active screen state for up to 3.5 seconds after an accept click has been dispatched
     * to eliminate false-positives.
     * Verifies that the order was actually won (active navigation/trip UI appears or offer card
     * cleanly disappears without error toasts).
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun verifyAcceptanceTransition(
        offer: ParsedRideOffer,
        evaluation: RideEvaluation,
        cardBounds: Rect
    ): AcceptanceState = withContext(Dispatchers.Default) {
        acquireBriefWakeLock(4000L)
        val startTime = SystemClock.elapsedRealtime()
        val timeoutMs = 3500L
        val pollIntervalMs = 250L

        // Wait initial 250ms for Rapido network request and UI response dispatch
        delay(pollIntervalMs)

        while (SystemClock.elapsedRealtime() - startTime < timeoutMs && isServiceRunning) {
            val rootWindows = getAllRootWindows()
            try {
                // 1. Check for Rejection / Missed / Error indicators
                if (hasRejectionOrMissedIndicators(rootWindows)) {
                    Log.w(TAG, "verifyAcceptanceTransition: detected rejection/expired toast on screen.")
                    return@withContext AcceptanceState.REJECTED_OR_TAKEN
                }

                // 2. Check for Active Trip / Navigation nodes
                if (hasActiveTripIndicators(rootWindows)) {
                    Log.i(TAG, "verifyAcceptanceTransition: active trip transition confirmed (OTP/Start Ride/Navigate detected).")
                    return@withContext AcceptanceState.CONFIRMED_ACCEPTED
                }
            } finally {
                for (root in rootWindows) {
                    recycleNode(root)
                }
            }

            delay(pollIntervalMs)
        }

        // Final inspection after timeout
        val finalWindows = getAllRootWindows()
        try {
            if (hasRejectionOrMissedIndicators(finalWindows)) {
                return@withContext AcceptanceState.REJECTED_OR_TAKEN
            }
            if (hasActiveTripIndicators(finalWindows)) {
                Log.i(TAG, "verifyAcceptanceTransition: active trip transition confirmed on final check.")
                return@withContext AcceptanceState.CONFIRMED_ACCEPTED
            }
            val stillHasOffer = hasActiveRideOfferOnScreen(finalWindows)
            if (!stillHasOffer) {
                // The offer card disappeared/expired without active trip confirmation -> order expired or taken
                Log.w(TAG, "verifyAcceptanceTransition: offer card disappeared but no active trip indicators found. Marked as expired/taken.")
                return@withContext AcceptanceState.REJECTED_OR_TAKEN
            }
        } finally {
            for (root in finalWindows) {
                recycleNode(root)
            }
        }

        Log.w(TAG, "verifyAcceptanceTransition: offer card still present on screen after timeout. Accept failed.")
        return@withContext AcceptanceState.STALLED_OR_TIMED_OUT
    }

    @androidx.annotation.VisibleForTesting
    internal fun hasRejectionOrMissedIndicators(rootWindows: List<AccessibilityNodeInfo>): Boolean {
        for (root in rootWindows) {
            if (scanTreeForTextPatterns(root, REJECTION_INDICATORS_REGEX)) {
                return true
            }
        }
        return false
    }

    @androidx.annotation.VisibleForTesting
    internal fun hasActiveTripIndicators(rootWindows: List<AccessibilityNodeInfo>): Boolean {
        for (root in rootWindows) {
            if (scanTreeForTextPatterns(root, ACTIVE_TRIP_INDICATORS_REGEX)) {
                return true
            }
        }
        return false
    }

    private fun scanTreeForTextPatterns(node: AccessibilityNodeInfo, regex: Regex): Boolean {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(node), 0))
        val startTime = SystemClock.elapsedRealtime()

        while (queue.isNotEmpty()) {
            if (SystemClock.elapsedRealtime() - startTime > 40L) {
                while (queue.isNotEmpty()) recycleNode(queue.removeFirst().first)
                break
            }
            val (curr, depth) = queue.removeFirst()
            val text = curr.text?.toString() ?: ""
            val desc = curr.contentDescription?.toString() ?: ""
            val viewId = curr.viewIdResourceName ?: ""

            if (regex.containsMatchIn(text) || regex.containsMatchIn(desc) || regex.containsMatchIn(viewId)) {
                recycleNode(curr)
                while (queue.isNotEmpty()) recycleNode(queue.removeFirst().first)
                return true
            }

            if (depth < 8) {
                val count = curr.childCount
                for (i in 0 until count) {
                    val child = curr.getChild(i) ?: continue
                    queue.add(Pair(child, depth + 1))
                }
            }
            recycleNode(curr)
        }
        return false
    }

    /**
     * Finds an actionable accept node strictly located inside the provided screen bounding box.
     * Enforces depth-limiting (<= 12) and strict timeout (< 50ms) to prevent ANRs on heavily nested layouts.
     */
    fun findAcceptNodeInBounds(root: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(root), 0))

        val startTime = SystemClock.elapsedRealtime()
        try {
            while (queue.isNotEmpty()) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                    break
                }

                val (node, depth) = queue.removeFirst()
                var keepNode = false
                try {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
                        val nodeBounds = Rect()
                        node.getBoundsInScreen(nodeBounds)

                        // Verify node bounds lie inside card bounds (or handle async 0,0,0,0 nodeBounds gracefully if actionable)
                        val isInBounds = !nodeBounds.isEmpty && (
                            bounds.contains(nodeBounds.centerX(), nodeBounds.centerY()) ||
                                (nodeBounds.top >= bounds.top - 20 && nodeBounds.bottom <= bounds.bottom + 20)
                            )

                        if ((isInBounds || nodeBounds.isEmpty) && isActionableNodeOrChild(node)) {
                            keepNode = true
                            return node
                        }
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                                break
                            }
                            val child = node.getChild(i) ?: continue
                            queue.add(Pair(child, depth + 1))
                        }
                    }
                } finally {
                    if (!keepNode) {
                        recycleNode(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst().first)
            }
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

        val targetPkg = getEffectiveTargetPackage()

        // 1. Check primary active window root (strictly ignore own app and non-target packages)
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val rootPkg = activeRoot.packageName?.toString() ?: ""
            if (rootPkg == packageName || (rootPkg.isNotEmpty() && rootPkg != targetPkg)) {
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
                    try {
                        val windowId = window.id
                        if (seenWindowIds.contains(windowId)) {
                            continue
                        }
                        val windowRoot = window.root ?: continue
                        val winPkg = windowRoot.packageName?.toString() ?: ""
                        if (winPkg == packageName || (winPkg.isNotEmpty() && winPkg != targetPkg)) {
                            recycleNode(windowRoot)
                            continue
                        }
                        rootList.add(windowRoot)
                        seenWindowIds.add(windowId)
                    } finally {
                        @Suppress("DEPRECATION")
                        try {
                            window.recycle()
                        } catch (_: Exception) {
                        }
                    }
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
        val startTime = SystemClock.elapsedRealtime()
        val timeoutMs = 150L

        fun dfs(current: AccessibilityNodeInfo?, depth: Int) {
            if (current == null || depth > 50) return
            if (SystemClock.elapsedRealtime() - startTime > timeoutMs) {
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
    suspend fun executeAssistiveClick(
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
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(root), 0))

        val startTime = SystemClock.elapsedRealtime()
        try {
            while (queue.isNotEmpty()) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                    break
                }

                val (node, depth) = queue.removeFirst()
                var keepNode = false
                try {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""

                    val matchesLabel = targetLabels.any { label ->
                        text.equals(label, ignoreCase = true) ||
                            desc.equals(label, ignoreCase = true) ||
                            text.contains(label, ignoreCase = true) ||
                            desc.contains(label, ignoreCase = true)
                    }

                    if (matchesLabel && isActionableNodeOrChild(node)) {
                        keepNode = true
                        return node
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                                break
                            }
                            val child = node.getChild(i)
                            if (child != null) {
                                queue.add(Pair(child, depth + 1))
                            }
                        }
                    }
                } finally {
                    if (!keepNode) {
                        recycleNode(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst().first)
            }
        }
        return null
    }

    /**
     * Bounds-targeted gesture execution for Accept nodes.
     * Enforces GlobalAdminManager kill-switch, LicenseManager access, Auto-Click enabled check,
     * and gracefully handles async (0,0,0,0) node bounds before calling dispatchGesture().
     */
    suspend fun executeAutoAcceptGesture(node: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }
        if (!GlobalAdminManager.canExecuteActions(this)) {
            Log.w(TAG, "executeAutoAcceptGesture blocked: Global kill switch active or user banned.")
            return false
        }
        if (!LicenseManager.hasAnyAutoAcceptCapability()) {
            Log.w(TAG, "executeAutoAcceptGesture blocked: License expired or no filter/feature unlocked.")
            return false
        }
        if (!AppSettings.isAutoAcceptEnabled(this) || !AppSettings.isAutoClickEnabled(this)) {
            Log.i(TAG, "executeAutoAcceptGesture skipped: Auto-Click disabled (Voice-Only TTS fallback).")
            return false
        }

        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)

            // Handle async UI rendering where leaf node bounds may still be (0,0,0,0)
            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
                val clickableParent = findClickableParentOrSelf(node)
                if (clickableParent != null) {
                    try {
                        clickableParent.getBoundsInScreen(rect)
                    } finally {
                        if (clickableParent !== node) {
                            recycleNode(clickableParent)
                        }
                    }
                }
            }

            if (rect.isEmpty || rect.width() <= 0 || rect.height() <= 0) {
                Log.w(TAG, "executeAutoAcceptGesture aborted: target node bounds still (0,0,0,0) during async render.")
                return false
            }

            val (screenWidth, screenHeight) = getScreenDimensionsPx()
            val centerX = rect.centerX().toFloat().coerceIn(0f, screenWidth - 1f)
            val centerY = rect.centerY().toFloat().coerceIn(0f, screenHeight - 1f)

            dispatchTapAtCoordinates(centerX, centerY)
        } catch (e: Exception) {
            Log.e(TAG, "Error in executeAutoAcceptGesture", e)
            false
        }
    }

    suspend fun dispatchTapGesture(node: AccessibilityNodeInfo): Boolean {
        return executeAutoAcceptGesture(node)
    }

    @androidx.annotation.VisibleForTesting
    internal suspend fun dispatchTapAtCoordinates(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (!GlobalAdminManager.canExecuteActions(this)) {
            Log.w(TAG, "dispatchTapAtCoordinates blocked: Service offline or user is banned.")
            return false
        }
        if (!LicenseManager.hasAnyAutoAcceptCapability()) {
            Log.w(TAG, "dispatchTapAtCoordinates blocked: License expired or locked.")
            return false
        }
        if (!AppSettings.isAutoAcceptEnabled(this) || !AppSettings.isAutoClickEnabled(this)) {
            Log.i(TAG, "dispatchTapAtCoordinates skipped: Auto-Click disabled.")
            return false
        }

        // Strict rate-limiter (minimum 2000ms delay) on fallback coordinate taps to prevent rapid screen-tapping spam
        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastCoordinateTapTime
        if (elapsed < MIN_COORDINATE_TAP_INTERVAL_MS) {
            Log.w(TAG, "dispatchTapAtCoordinates rate-limited: only ${elapsed}ms elapsed since last tap (min: ${MIN_COORDINATE_TAP_INTERVAL_MS}ms). Screen-tapping spam prevented.")
            return false
        }
        lastCoordinateTapTime = now

        // Prevent Floating Overlay Tap Interception:
        // Set FloatingOverlayService to temporarily untouchable so synthesized touch passes through to Rapido.
        val overlayBounds = FloatingOverlayService.getCurrentOverlayBoundsOnScreen()
        val isOverOverlay = overlayBounds != null && overlayBounds.contains(x.toInt(), y.toInt())
        FloatingOverlayService.setTemporarilyUntouchable(true)
        if (isOverOverlay) {
            delay(50L)
        }

        return try {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    FloatingOverlayService.setTemporarilyUntouchable(false)
                }
                try {
                    val clickPath = Path().apply {
                        moveTo(x, y)
                    }
                    val stroke = GestureDescription.StrokeDescription(clickPath, 0L, 50L)
                    val gesture = GestureDescription.Builder().addStroke(stroke).build()

                    val dispatched = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            super.onCompleted(gestureDescription)
                            Log.d(TAG, "dispatchTapAtCoordinates completed at ($x, $y)")
                            if (continuation.isActive) {
                                continuation.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            super.onCancelled(gestureDescription)
                            Log.w(TAG, "dispatchTapAtCoordinates cancelled at ($x, $y)")
                            if (continuation.isActive) {
                                continuation.resume(false)
                            }
                        }
                    }, null)

                    if (!dispatched) {
                        Log.w(TAG, "dispatchGesture returned false immediately at ($x, $y)")
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in dispatchTapAtCoordinates", e)
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }
        } finally {
            serviceScope.launch {
                delay(200L)
                FloatingOverlayService.setTemporarilyUntouchable(false)
            }
        }
    }

    suspend fun findAndClickAcceptButton(root: AccessibilityNodeInfo? = null): Boolean {
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

    /**
     * Finds a clickable parent node for [node] within [cardBounds], strictly rejecting any
     * parent container that is larger than standard button dimensions (e.g. height > cardBounds.height() * 0.45f,
     * height > 280px, or area > 70% of the entire card area). This prevents accessibility clicks from climbing
     * onto the outer order card container (CardView / ConstraintLayout) which consumes the click without accepting.
     */
    fun findClickableParentWithinCard(node: AccessibilityNodeInfo, cardBounds: Rect): AccessibilityNodeInfo? {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)

        fun isOversized(bounds: Rect): Boolean {
            if (bounds.isEmpty) return false
            if (bounds.height() > 280) return true
            if (!cardBounds.isEmpty) {
                if (bounds.height() > cardBounds.height() * 0.45f) return true
                val cardArea = cardBounds.width().toLong() * cardBounds.height()
                val nodeArea = bounds.width().toLong() * bounds.height()
                if (cardArea > 0 && nodeArea > (cardArea * 0.70)) return true
            }
            return false
        }

        if (node.isClickable && node.isEnabled && !isOversized(nodeBounds)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        val maxDepth = 10

        while (current != null && depth < maxDepth) {
            val parentBounds = Rect()
            current.getBoundsInScreen(parentBounds)

            // If the parent is oversized (e.g. full card container), stop climbing immediately
            if (isOversized(parentBounds)) {
                recycleNode(current)
                break
            }

            if (current.isClickable && current.isEnabled) {
                return current
            }

            val parent = current.parent
            recycleNode(current)
            current = parent
            depth++
        }

        current?.let { recycleNode(it) }

        if (node.isClickable && node.isEnabled && !isOversized(nodeBounds)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        return null
    }

    fun findClickableParentOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        if (node.isClickable && node.isEnabled && (nodeBounds.isEmpty || nodeBounds.height() <= 280)) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }

        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        val maxDepth = 10

        while (current != null && depth < maxDepth) {
            val parentBounds = Rect()
            current.getBoundsInScreen(parentBounds)
            if (!parentBounds.isEmpty && parentBounds.height() > 280) {
                // Reject climbing onto outer screen or card containers (>280px height)
                recycleNode(current)
                break
            }
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
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(root), 0))

        val startTime = SystemClock.elapsedRealtime()
        var textFallbackNode: AccessibilityNodeInfo? = null
        try {
            while (queue.isNotEmpty()) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                    break
                }

                val (node, depth) = queue.removeFirst()
                var keepNode = false
                try {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    val isRejectNode = isRapidoRejectId(viewId) || isRejectAction(text) || isRejectAction(desc)
                    if (!isRejectNode) {
                        // Priority 1: Authoritative viewIdResourceName match (language-independent)
                        if (isRapidoAcceptId(viewId) && isActionableNodeOrChild(node)) {
                            textFallbackNode?.let { recycleNode(it) }
                            textFallbackNode = null
                            keepNode = true
                            return node
                        }
                        // Priority 2: Localized/English text keyword match fallback
                        if (textFallbackNode == null &&
                            (isAcceptAction(text) || isAcceptAction(desc)) &&
                            isActionableNodeOrChild(node)
                        ) {
                            @Suppress("DEPRECATION")
                            textFallbackNode = AccessibilityNodeInfo.obtain(node)
                        }
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                                break
                            }
                            val child = node.getChild(i)
                            if (child != null) {
                                queue.add(Pair(child, depth + 1))
                            }
                        }
                    }
                } finally {
                    if (!keepNode) {
                        recycleNode(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst().first)
            }
        }
        return textFallbackNode
    }

    fun isAcceptAction(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty() || trimmed.length > 50) return false
        if (isRejectAction(trimmed)) return false
        if (CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(trimmed)) return false
        if (ACCEPT_REGEX.containsMatchIn(trimmed)) return true
        return AppConfigRepository.effectiveRapidoAcceptKeywords().any { kw ->
            trimmed.equals(kw, ignoreCase = true)
        }
    }

    fun isRapidoAcceptId(viewId: String): Boolean {
        return RideOfferParser.isRapidoAcceptId(viewId)
    }

    fun isRejectAction(content: String): Boolean {
        return RideOfferParser.isRejectAction(content)
    }

    fun isRapidoRejectId(viewId: String): Boolean {
        return RideOfferParser.isRapidoRejectId(viewId)
    }

    fun findRejectNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        @Suppress("DEPRECATION")
        queue.add(Pair(AccessibilityNodeInfo.obtain(root), 0))

        val startTime = SystemClock.elapsedRealtime()
        try {
            while (queue.isNotEmpty()) {
                if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                    break
                }

                val (node, depth) = queue.removeFirst()
                var keepNode = false
                try {
                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    if (isRejectAction(text) || isRejectAction(desc) || isRapidoRejectId(viewId)) {
                        if (isActionableNodeOrChild(node)) {
                            keepNode = true
                            return node
                        }
                    }

                    if (depth < MAX_TRAVERSAL_DEPTH) {
                        val childCount = node.childCount
                        for (i in 0 until childCount) {
                            if (SystemClock.elapsedRealtime() - startTime > MAX_TRAVERSAL_TIMEOUT_MS) {
                                break
                            }
                            val child = node.getChild(i)
                            if (child != null) {
                                queue.add(Pair(child, depth + 1))
                            }
                        }
                    }
                } finally {
                    if (!keepNode) {
                        recycleNode(node)
                    }
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst().first)
            }
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

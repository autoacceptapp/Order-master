package com.example

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.repository.AppConfigRepository
import java.util.ArrayDeque
import java.util.Locale
import java.util.regex.Pattern

/**
 * Expert RideOfferParser for Order Master.
 *
 * Enforces:
 * 1. Card-Level Layout Isolation:
 *    - Identifies individual order card container nodes (ViewGroup / CardView) on the screen.
 *    - Does NOT dump all screen texts into a global list. Iterates through each container node separately
 *      so texts from Card A never mix with Card B.
 * 2. Structural Field Extraction per Card:
 *    - For each detected card container, extracts specific fields individually:
 *      * fareText: Extract ONLY the text node containing currency symbols (e.g., '₹', 'Rs', 'INR') or numeric fare patterns.
 *      * pickupText: Extract ONLY the text node containing pickup keywords or distances (e.g., "km", "Pickup").
 *      * dropText: Extract ONLY the text node containing drop keywords or drop distances.
 *      * acceptButton: Locate the specific clickable "Accept" node inside that particular card.
 * 3. Strict Data Cleanliness:
 *    - Returns strictly isolated and stored List<ParsedRideOffer>.
 *    - Prevents any string concatenation that merges different node values together before regex parsing.
 */
object RideOfferParser {

    private const val TAG = "RideOfferParser"

    // Currency pattern to identify pure fare nodes with Indian Rupee variants ('₹', '\u20B9', '\u20A8', 'Rs', 'INR')
    private val FARE_NODE_PATTERN = Pattern.compile(
        """(?:[₹\u20B9\u20A8]|Rs\.?|INR|\$)\s*(\d+(?:[.,]\d+)?)|(?:^|\s)(\d+(?:[.,]\d+)?)\s*(?:[₹\u20B9\u20A8]|Rs\.?|INR)""",
        Pattern.CASE_INSENSITIVE
    )

    // Standalone currency indicator covering all Rupee symbol variants
    private val CURRENCY_SYMBOL_PATTERN = Pattern.compile(
        """[₹\u20B9\u20A8$]|(?:\b(?:Rs\.?|INR)\b)""",
        Pattern.CASE_INSENSITIVE
    )

    // Distance pattern for isolated distance nodes (e.g., "1.2 km", "800 m", "2.8 km away")
    private val DISTANCE_PATTERN = Pattern.compile(
        """(\d+(?:[.,]\d+)?)\s*(km|m|kms|kilometer|meter)\b""",
        Pattern.CASE_INSENSITIVE
    )

    // Left-side mini-tab strip cutoff on Rapido's multi-order ("3 Orders") screen
    const val LEFT_SIDEBAR_WIDTH_RATIO = 0.22f

    // Pattern matching Rapido floating popup expansion chip ("See 1 more orders >", "View 3 more orders")
    val SEE_MORE_ORDERS_REGEX = Regex(
        """(?i)\b(?:see|view)\s+\d+\s+more\s+orders?\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Resolves effective screen width for sidebar exclusion using explicit screen width,
     * root window width, or system display metrics fallback.
     */
    fun resolveScreenWidthPx(root: AccessibilityNodeInfo?, explicitWidthPx: Int = 0): Int {
        if (explicitWidthPx > 0) return explicitWidthPx
        if (root != null) {
            val rootBounds = Rect()
            root.getBoundsInScreen(rootBounds)
            if (rootBounds.width() > 0) return rootBounds.width()
        }
        return runCatching { Resources.getSystem().displayMetrics.widthPixels }
            .getOrDefault(1080)
            .takeIf { it > 0 } ?: 1080
    }

    /**
     * Returns true when [bounds] sits inside the narrow left-side mini-tab strip
     * (`x < rapidoLeftSidebarWidthRatio`, default 22%, clamped 5%..45% of screen width)
     * on Rapido's multi-order list screen so mini-tabs are never parsed as cards
     * or merged into active order cards.
     */
    fun isLeftSidebarNode(bounds: Rect, screenWidthPx: Int): Boolean {
        if (bounds.isEmpty || screenWidthPx <= 0) return false
        val ratio = AppConfigRepository.effectiveRapidoLeftSidebarWidthRatio()
        val cutoffX = (screenWidthPx * ratio).toInt()
        if (cutoffX <= 0) return false
        val maxSidebarWidth = (screenWidthPx * (ratio + 0.08f).coerceAtMost(0.55f)).toInt()
        return bounds.right <= cutoffX ||
            (bounds.centerX() < cutoffX && bounds.width() < maxSidebarWidth)
    }

    fun isSeeMoreOrdersText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return false
        if (isRejectAction(trimmed) || RapidoAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(trimmed)) {
            return false
        }
        if (SEE_MORE_ORDERS_REGEX.containsMatchIn(trimmed)) return true
        val configuredKeywords = AppConfigRepository.effectiveRapidoSeeMoreOrdersKeywords()
        if (configuredKeywords.isEmpty()) return false
        if (configuredKeywords.all { kw -> trimmed.contains(kw, ignoreCase = true) }) {
            return true
        }
        return configuredKeywords.any { kw ->
            (kw.length >= 6 || kw.contains(' ')) && trimmed.contains(kw, ignoreCase = true)
        }
    }

    /**
     * Searches a window root for Rapido's "See N more orders >" expansion chip so the service
     * can optionally open the full order list when the top floating popup fails filters.
     */
    fun findSeeMoreOrdersNode(
        root: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): AccessibilityNodeInfo? {
        val effectiveWidth = resolveScreenWidthPx(root, screenWidthPx)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 100L

        try {
            while (queue.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) break
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (isLeftSidebarNode(bounds, effectiveWidth)) {
                    recycleNode(node)
                    continue
                }

                val text = (node.text?.toString() ?: "").trim()
                val desc = (node.contentDescription?.toString() ?: "").trim()
                if (isSeeMoreOrdersText(text) || isSeeMoreOrdersText(desc)) {
                    val target = if (node.isClickable && node.isEnabled) {
                        node
                    } else {
                        val clickableParent = findClickableParent(node)
                        if (clickableParent != null) {
                            recycleNode(node)
                            clickableParent
                        } else {
                            node
                        }
                    }
                    while (queue.isNotEmpty()) {
                        recycleNode(queue.removeFirst())
                    }
                    return target
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
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst())
            }
        }
        return null
    }

    /**
     * Parses all distinct order cards across the given candidate window roots.
     * Ensures strict card isolation and excludes the left-side mini-tab strip (`x < 22%` width).
     */
    fun parseCards(
        rootWindows: List<AccessibilityNodeInfo>,
        screenWidthPx: Int = 0
    ): List<ParsedRideOffer> {
        val parsedOffers = mutableListOf<ParsedRideOffer>()
        val seenCardKeys = mutableSetOf<String>()

        for (root in rootWindows) {
            val effectiveWidth = resolveScreenWidthPx(root, screenWidthPx)
            val cardContainers = findCardContainers(root, effectiveWidth)
            for (container in cardContainers) {
                try {
                    val offer = parseCardContainer(container, effectiveWidth)
                    if (offer != null) {
                        val pickupKey = String.format(Locale.US, "%.1f", offer.pickupDistanceKm ?: 0.0)
                        val dropKey = String.format(Locale.US, "%.1f", offer.dropDistanceKm ?: 0.0)
                        val deduplicationKey = "${offer.totalFare?.toInt()}_${pickupKey}_${dropKey}_${offer.bounds.top}_${offer.bounds.left}"
                        if (!seenCardKeys.contains(deduplicationKey)) {
                            seenCardKeys.add(deduplicationKey)
                            parsedOffers.add(offer)
                        }
                    }
                } finally {
                    recycleNode(container)
                }
            }
        }

        return parsedOffers
    }

    /**
     * Identifies individual order card container nodes on the screen.
     * Searches for ViewGroup/CardView containers that enclose individual ride offers
     * while ignoring Rapido's left-side mini-tab sidebar (`x < 22%` screen width).
     */
    fun findCardContainers(
        root: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): List<AccessibilityNodeInfo> {
        val effectiveWidth = resolveScreenWidthPx(root, screenWidthPx)
        val containers = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 200L

        try {
            while (queue.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    break
                }

                val current = queue.removeFirst()

                // Exclude narrow left-side mini-tab strip on multi-order ("3 Orders") screens
                val currentBounds = Rect()
                current.getBoundsInScreen(currentBounds)
                if (isLeftSidebarNode(currentBounds, effectiveWidth)) {
                    recycleNode(current)
                    continue
                }

                // Check if current node is an isolated order card container
                if (isOrderCardContainer(current, effectiveWidth)) {
                    containers.add(current)
                    // CRITICAL LAYOUT ISOLATION: Do not descend into children of this container
                    // to prevent sub-layouts from being falsely treated as separate cards.
                    continue
                }

                // Descend into children
                val childCount = current.childCount
                for (i in 0 until childCount) {
                    val child = current.getChild(i)
                    if (child != null) {
                        queue.add(child)
                    }
                }
                recycleNode(current)
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst())
            }
        }

        // Fallback: If no explicit card container tags were found, verify root only has exactly one accept action
        if (containers.isEmpty()) {
            val acceptCount = countAcceptNodesInSubtree(root, maxCount = 2, screenWidthPx = effectiveWidth)
            if (acceptCount == 1) {
                val acceptNode = findAcceptNodeInSubtree(root, effectiveWidth)
                val hasFare = containsFareInSubtree(root, effectiveWidth)
                try {
                    if (acceptNode != null && hasFare) {
                        @Suppress("DEPRECATION")
                        containers.add(AccessibilityNodeInfo.obtain(root))
                    }
                } finally {
                    if (acceptNode != null) {
                        recycleNode(acceptNode)
                    }
                }
            }
        }

        return containers
    }

    /**
     * Counts the number of accept buttons in a subtree up to maxCount (excluding left sidebar).
     */
    fun countAcceptNodesInSubtree(
        root: AccessibilityNodeInfo,
        maxCount: Int = 2,
        screenWidthPx: Int = 0
    ): Int {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        var count = 0
        val startTime = System.currentTimeMillis()
        val timeoutMs = 45L

        try {
            while (queue.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    break
                }
                val node = queue.removeFirst()
                try {
                    if (screenWidthPx > 0) {
                        val b = Rect()
                        node.getBoundsInScreen(b)
                        if (isLeftSidebarNode(b, screenWidthPx)) {
                            continue
                        }
                    }

                    val text = node.text?.toString() ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val viewId = node.viewIdResourceName ?: ""

                    val isRejectNode = isRapidoRejectId(viewId) || isRejectAction(text) || isRejectAction(desc)
                    if (!isRejectNode && (isRapidoAcceptId(viewId) || isAcceptAction(text) || isAcceptAction(desc))) {
                        count++
                        if (count >= maxCount) {
                            return count
                        }
                    }

                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    recycleNode(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst())
            }
        }
        return count
    }

    /**
     * Checks if this node directly contains child card view elements.
     */
    private fun hasChildCardViews(node: AccessibilityNodeInfo): Boolean {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val cClass = (child.className?.toString() ?: "").lowercase(Locale.ROOT)
            val cId = (child.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val isCard = cClass.contains("cardview") ||
                    cId.contains("order_card") ||
                    cId.contains("ride_card") ||
                    cId.contains("offer_item") ||
                    cId.contains("item_ride_offer")
            recycleNode(child)
            if (isCard) return true
        }
        return false
    }

    /**
     * Checks if a node qualifies as an individual order card container.
     * Enforces strict layout boundary isolation so containers of multiple cards are never merged
     * and left-side mini-tab strips (`x < 22%` width) are excluded.
     */
    fun isOrderCardContainer(
        node: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): Boolean {
        val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        val className = (node.className?.toString() ?: "").lowercase(Locale.ROOT)

        if (screenWidthPx > 0) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (isLeftSidebarNode(bounds, screenWidthPx)) {
                return false
            }
        }

        // Exclude aggregate collection / scroll containers
        if (className.contains("recyclerview") ||
            className.contains("listview") ||
            className.contains("gridview") ||
            className.contains("viewpager") ||
            className.contains("scrollview")
        ) {
            return false
        }

        // An aggregate layout holding multiple Accept buttons must NEVER be treated as a single card container
        val acceptCount = countAcceptNodesInSubtree(node, maxCount = 2, screenWidthPx = screenWidthPx)
        if (acceptCount > 1) {
            return false
        }

        // An outer container holding child card views must NEVER be treated as a single card container
        if (hasChildCardViews(node)) {
            return false
        }

        // 1. Explicit Rapido Order Card Container view IDs
        val cardIdKeywords = listOf(
            "order_card",
            "ride_card",
            "ride_request",
            "request_dialog",
            "incoming_order",
            "order_layout",
            "card_order",
            "bottom_sheet_order",
            "offer_item",
            "ride_item",
            "order_view",
            "item_ride_offer",
            "layout_order",
            "dialog_order",
            "offer_card_view"
        )
        if (cardIdKeywords.any { viewId.contains(it) }) {
            return true
        }

        // 2. ClassName is a CardView or MaterialCardView with children
        if ((className.contains("cardview") || className.contains("materialcardview")) && node.childCount > 0) {
            val hasAccept = findAcceptNodeInSubtree(node, screenWidthPx)
            if (hasAccept != null) {
                recycleNode(hasAccept)
                return true
            }
        }

        // 3. Isolated ViewGroup that directly bounds exactly one Accept button and contains a Fare symbol
        if (node.childCount >= 2 && acceptCount == 1) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 250 && bounds.height() in 100..1800) {
                if (containsFareInSubtree(node, screenWidthPx)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Extracts structural fields strictly from within the boundaries of a single card container.
     * Texts from other cards and left-sidebar mini-tabs are NEVER visited.
     */
    fun parseCardContainer(
        container: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): ParsedRideOffer? {
        val cardBounds = Rect()
        container.getBoundsInScreen(cardBounds)
        if (screenWidthPx > 0 && isLeftSidebarNode(cardBounds, screenWidthPx)) {
            return null
        }

        var fareText = ""
        var pickupText = ""
        var dropText = ""
        var pickupLocationText = ""
        var dropLocationText = ""
        var acceptButtonByViewId: AccessibilityNodeInfo? = null
        var acceptButtonByText: AccessibilityNodeInfo? = null

        // Collect all distinct text nodes strictly inside this card container
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(container))
        var isRootNode = true

        val candidateDistanceTexts = mutableListOf<String>()
        val allNodeTexts = mutableListOf<String>()

        val startTime = System.currentTimeMillis()
        val timeoutMs = 150L
        var fareFromStrikethroughView = false

        try {
            while (queue.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    break
                }

                val node = queue.removeFirst()
                val currentIsRoot = isRootNode
                isRootNode = false

                try {
                    // Strict layout boundary isolation: Never traverse into another distinct child card container
                    // or into the left-side mini-tab strip (`x < 22%` width).
                    if (!currentIsRoot) {
                        if (screenWidthPx > 0) {
                            val nodeBounds = Rect()
                            node.getBoundsInScreen(nodeBounds)
                            if (isLeftSidebarNode(nodeBounds, screenWidthPx)) {
                                continue
                            }
                        }
                        if (isOrderCardContainer(node, screenWidthPx)) {
                            continue
                        }
                    }

                    val text = (node.text?.toString() ?: "").trim()
                    val desc = (node.contentDescription?.toString() ?: "").trim()
                    val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
                    val effectiveText = if (text.isNotEmpty()) text else desc

                    // Skip informational tip-badge phrases (e.g., "Customer added ₹10.0 extra")
                    // so tips already included on the main fare line are never double-counted or treated as locations.
                    val isTipNoise = TextAnalysisEngine.isTipBadgeNoise(effectiveText)
                    if (effectiveText.isNotEmpty() && !isTipNoise) {
                        allNodeTexts.add(effectiveText)
                    }

                    // 1. Locate Accept Button strictly inside this card's bounds:
                    //    Priority 1: viewIdResourceName match against rapidoAcceptViewIds (works for all regional languages)
                    //    Priority 2: Localized/English text match against rapidoAcceptButtonKeywords
                    //    Always exclude any node matching rapidoRejectKeywords or reject view IDs.
                    val isRejectNode = isRapidoRejectId(viewId) || isRejectAction(text) || isRejectAction(desc)
                    if (!isTipNoise && !isRejectNode) {
                        val matchedByViewId = isRapidoAcceptId(viewId)
                        val matchedByText = !matchedByViewId && (isAcceptAction(text) || isAcceptAction(desc))
                        if ((matchedByViewId && acceptButtonByViewId == null) ||
                            (matchedByText && acceptButtonByViewId == null && acceptButtonByText == null)
                        ) {
                            val candidateAccept = resolveClickableAcceptWithinCard(node, cardBounds)
                            if (matchedByViewId) {
                                acceptButtonByViewId = candidateAccept
                                // Release any lower-priority text candidate now that an authoritative viewId matched
                                acceptButtonByText?.let { recycleNode(it) }
                                acceptButtonByText = null
                            } else {
                                acceptButtonByText = candidateAccept
                            }
                        }
                    }

                    // 2. Extract Fare Node (Currency symbol or fare pattern; ignores tip badge noise)
                    if (!isTipNoise && isFareNode(effectiveText, viewId)) {
                        val isLikelyOldOrStrike = viewId.contains("strike") ||
                            viewId.contains("old") ||
                            viewId.contains("original")
                        val isExplicitPrimaryFare = viewId.contains("boost") ||
                            viewId.contains("final") ||
                            viewId.contains("total") ||
                            effectiveText.contains("BOOST", ignoreCase = true) ||
                            effectiveText.contains("AUTO", ignoreCase = true) ||
                            effectiveText.contains("BIKE", ignoreCase = true) ||
                            effectiveText.contains("CAB", ignoreCase = true) ||
                            effectiveText.contains("PARCEL", ignoreCase = true)

                        if (fareText.isEmpty() || !matchesFareIndicator(fareText)) {
                            fareText = effectiveText
                            fareFromStrikethroughView = isLikelyOldOrStrike
                        } else if (effectiveText.startsWith("+") && !fareText.contains(effectiveText) && !fareText.contains("+")) {
                            // Complementary bonus/tip within the same card boundary (e.g., "₹55" + "+ ₹10")
                            fareText = "$fareText $effectiveText"
                        } else if (!fareText.startsWith("+") && !isLikelyOldOrStrike &&
                            (fareFromStrikethroughView || isExplicitPrimaryFare) &&
                            matchesFareIndicator(effectiveText)
                        ) {
                            fareText = effectiveText
                            fareFromStrikethroughView = false
                        }
                    }

                    // 3. Extract Pickup Node
                    if (!isTipNoise && pickupText.isEmpty() && isPickupNode(effectiveText, viewId)) {
                        pickupText = effectiveText
                    }

                    // 4. Extract Drop Node
                    if (!isTipNoise && dropText.isEmpty() && isDropNode(effectiveText, viewId)) {
                        dropText = effectiveText
                    }

                    // 5. Track standalone distance strings for positional assignment
                    if (!isTipNoise && matchesDistancePattern(effectiveText) && !isFareNode(effectiveText, viewId)) {
                        if (!candidateDistanceTexts.contains(effectiveText)) {
                            candidateDistanceTexts.add(effectiveText)
                        }
                    }

                    // 6. Extract Location Labels
                    if (!isTipNoise && isPickupLocationNode(effectiveText, viewId) && pickupLocationText.isEmpty()) {
                        pickupLocationText = cleanLocation(effectiveText)
                    }
                    if (!isTipNoise && isDropLocationNode(effectiveText, viewId) && dropLocationText.isEmpty()) {
                        dropLocationText = cleanLocation(effectiveText)
                    }

                    val childCount = node.childCount
                    for (i in 0 until childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                } finally {
                    recycleNode(node)
                }
            }
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst())
            }
        }

        val acceptButtonNode = acceptButtonByViewId ?: acceptButtonByText
        if (acceptButtonByViewId != null && acceptButtonByText != null) {
            recycleNode(acceptButtonByText)
        }

        // Positional fallback for distance if not explicitly labeled with "Pickup" or "Drop"
        if (pickupText.isEmpty() && candidateDistanceTexts.isNotEmpty()) {
            pickupText = candidateDistanceTexts[0]
        }
        if (dropText.isEmpty() && candidateDistanceTexts.size > 1) {
            dropText = candidateDistanceTexts[1]
        }

        // If no fareText found, card cannot be a valid ride offer
        if (fareText.isEmpty()) {
            acceptButtonNode?.let { recycleNode(it) }
            return null
        }

        // Extract numeric fare value strictly from fareText
        val (totalFare, breakdown) = extractFareValue(fareText)
        if (totalFare == null || !TextAnalysisEngine.isValidRapidoFare(totalFare)) {
            acceptButtonNode?.let { recycleNode(it) }
            return null
        }

        // Extract numeric pickup distance strictly from pickupText
        val pickupDistance = extractDistanceValue(pickupText)
        // Extract numeric drop distance strictly from dropText
        val dropDistance = extractDistanceValue(dropText)

        // At least one distance must be detected
        if (pickupDistance == null && dropDistance == null) {
            acceptButtonNode?.let { recycleNode(it) }
            return null
        }

        // Structured location extraction fallback from node tree order (e.g. Rapido cards without explicit viewId)
        if (pickupLocationText.isEmpty() || dropLocationText.isEmpty()) {
            val (structPickup, structDrop) = extractStructuredLocations(allNodeTexts)
            if (pickupLocationText.isEmpty() && structPickup.isNotEmpty()) {
                pickupLocationText = structPickup
            }
            if (dropLocationText.isEmpty() && structDrop.isNotEmpty()) {
                dropLocationText = structDrop
            }
        }
        if (pickupLocationText.isEmpty() || dropLocationText.isEmpty()) {
            val fromNodes = TextAnalysisEngine.parseTargetOfferFromNodes(allNodeTexts)
            if (fromNodes != null) {
                if (pickupLocationText.isEmpty() && fromNodes.pickupLocation.isNotEmpty()) {
                    pickupLocationText = fromNodes.pickupLocation
                }
                if (dropLocationText.isEmpty() && fromNodes.dropLocation.isNotEmpty()) {
                    dropLocationText = fromNodes.dropLocation
                }
            }
        }

        val targetOffer = TargetRideOffer(
            fare = totalFare,
            pickupDistance = pickupDistance ?: 0.0,
            pickupLocation = pickupLocationText,
            dropDistance = dropDistance ?: 0.0,
            dropLocation = dropLocationText
        )

        val uniqueCardId = "card_${totalFare.toInt()}_${String.format(Locale.US, "%.1f", pickupDistance ?: 0.0)}_${cardBounds.top}_${cardBounds.left}"

        return ParsedRideOffer(
            rawText = allNodeTexts.joinToString(" • "),
            totalFare = totalFare,
            fareBreakdown = breakdown,
            pickupDistanceKm = pickupDistance,
            dropDistanceKm = dropDistance,
            hasAcceptButton = acceptButtonNode != null,
            targetOffer = targetOffer,
            fareText = fareText,
            pickupText = pickupText,
            dropText = dropText,
            acceptButton = acceptButtonNode,
            cardId = uniqueCardId,
            bounds = cardBounds
        )
    }

    // =========================================================================================
    // FIELD IDENTIFICATION HELPERS
    // =========================================================================================

    private fun matchesFareIndicator(text: String): Boolean {
        if (text.isEmpty() || TextAnalysisEngine.isTipBadgeNoise(text)) return false
        if (CURRENCY_SYMBOL_PATTERN.matcher(text).find()) return true
        val remoteFareRegex = AppConfigRepository.effectiveRapidoFareRegex()
        return remoteFareRegex != null && remoteFareRegex.containsMatchIn(text)
    }

    private fun matchesDistancePattern(text: String): Boolean {
        if (text.isEmpty()) return false
        if (DISTANCE_PATTERN.matcher(text).find()) return true
        val remoteDistRegex = AppConfigRepository.effectiveRapidoDistanceRegex()
        return remoteDistRegex != null && remoteDistRegex.containsMatchIn(text)
    }

    fun isFareNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        if (TextAnalysisEngine.isTipBadgeNoise(text)) return false
        if (viewId.contains("fare") || viewId.contains("price") || viewId.contains("amount")) {
            return true
        }
        return matchesFareIndicator(text)
    }

    fun isPickupNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("drop") || lower.contains("destination")) return false
        if (viewId.contains("pickup_dist") || viewId.contains("pickup_distance")) return true
        return (lower.contains("pickup") || lower.contains("pick up") || lower.contains("pick-up") || lower.contains("away")) &&
                matchesDistancePattern(text)
    }

    fun isDropNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (viewId.contains("drop_dist") || viewId.contains("drop_distance")) return true
        return (lower.contains("drop") || lower.contains("drop off") || lower.contains("drop-off") || lower.contains("destination")) &&
                matchesDistancePattern(text)
    }

    fun isPickupLocationNode(text: String, viewId: String): Boolean {
        if (text.isEmpty() || TextAnalysisEngine.isTipBadgeNoise(text)) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("drop") || matchesDistancePattern(text) || matchesFareIndicator(text)) return false
        if (viewId.contains("pickup_loc") || viewId.contains("source_address") || viewId.contains("pickup_address")) return true
        return false
    }

    fun isDropLocationNode(text: String, viewId: String): Boolean {
        if (text.isEmpty() || TextAnalysisEngine.isTipBadgeNoise(text)) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("pickup") || matchesDistancePattern(text) || matchesFareIndicator(text)) return false
        if (viewId.contains("drop_loc") || viewId.contains("dest_address") || viewId.contains("drop_address")) return true
        return false
    }

    fun extractFareValue(fareText: String): Pair<Double?, List<Double>> {
        if (fareText.isEmpty() || TextAnalysisEngine.isTipBadgeNoise(fareText)) return null to emptyList()
        return TextAnalysisEngine.extractCurrencies(fareText)
    }

    fun extractDistanceValue(distanceText: String): Double? {
        if (distanceText.isEmpty()) return null
        val remoteDistRegex = AppConfigRepository.effectiveRapidoDistanceRegex()
        if (remoteDistRegex != null) {
            val remoteMatch = remoteDistRegex.find(distanceText)
            if (remoteMatch != null) {
                var numericVal: Double? = null
                for (i in 1 until remoteMatch.groups.size) {
                    val v = remoteMatch.groups[i]?.value?.replace(",", ".")?.toDoubleOrNull()
                    if (v != null) {
                        numericVal = v
                        break
                    }
                }
                if (numericVal == null) {
                    numericVal = Regex("""\d+(?:[.,]\d+)?""").find(remoteMatch.value)
                        ?.value?.replace(",", ".")?.toDoubleOrNull()
                }
                if (numericVal != null) {
                    val raw = remoteMatch.value.lowercase(Locale.ROOT)
                    return if (raw.contains("km")) {
                        numericVal
                    } else if (raw.contains("m")) {
                        numericVal / 1000.0
                    } else {
                        numericVal
                    }
                }
            }
        }

        val matcher = DISTANCE_PATTERN.matcher(distanceText)
        if (matcher.find()) {
            val numStr = matcher.group(1)?.replace(",", ".") ?: return null
            val unit = matcher.group(2)?.lowercase(Locale.ROOT) ?: "km"
            val rawNum = numStr.toDoubleOrNull() ?: return null
            return if (unit.startsWith("m") && !unit.startsWith("meter") && !unit.startsWith("km")) {
                rawNum / 1000.0
            } else if (unit.startsWith("meter")) {
                rawNum / 1000.0
            } else {
                rawNum
            }
        }
        return null
    }

    private fun cleanLocation(text: String): String {
        return TextAnalysisEngine.locationCleaner(text)
    }

    private fun resolveClickableAcceptWithinCard(
        node: AccessibilityNodeInfo,
        cardBounds: Rect
    ): AccessibilityNodeInfo {
        if (node.isClickable && node.isEnabled) {
            @Suppress("DEPRECATION")
            return AccessibilityNodeInfo.obtain(node)
        }
        val clickableParent = findClickableParent(node)
        if (clickableParent != null) {
            val parentBounds = Rect()
            clickableParent.getBoundsInScreen(parentBounds)
            val parentFitsCard = cardBounds.isEmpty || parentBounds.isEmpty ||
                (parentBounds.top >= cardBounds.top - 12 &&
                    parentBounds.bottom <= cardBounds.bottom + 12 &&
                    parentBounds.left >= cardBounds.left - 12 &&
                    parentBounds.right <= cardBounds.right + 12)
            if (parentFitsCard) {
                return clickableParent
            }
            recycleNode(clickableParent)
        }
        @Suppress("DEPRECATION")
        return AccessibilityNodeInfo.obtain(node)
    }

    private fun matchesWordOrPhrase(text: String, keyword: String): Boolean {
        val kw = keyword.trim()
        if (kw.isEmpty()) return false
        if (text.equals(kw, ignoreCase = true)) return true
        val escaped = Regex.escape(kw)
        val pattern = if (kw.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == ' ' }) {
            Regex("""(?i)(?:^|[\s/|,•\-])$escaped(?:$|[\s/|,•\-!])""")
        } else {
            Regex("""(?i)$escaped""")
        }
        return pattern.containsMatchIn(text)
    }

    fun isRejectAction(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        if (RapidoAccessibilityService.REJECT_REGEX.containsMatchIn(trimmed)) return true
        return AppConfigRepository.effectiveRapidoRejectKeywords().any { kw ->
            matchesWordOrPhrase(trimmed, kw)
        }
    }

    fun isRapidoRejectId(viewId: String): Boolean {
        if (viewId.isBlank()) return false
        return RapidoAccessibilityService.RAPIDO_REJECT_ID_REGEX.containsMatchIn(viewId)
    }

    fun isAcceptAction(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        if (isRejectAction(trimmed)) return false
        if (RapidoAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(trimmed)) return false
        if (RapidoAccessibilityService.ACCEPT_REGEX.containsMatchIn(trimmed)) return true
        return AppConfigRepository.effectiveRapidoAcceptKeywords().any { kw ->
            trimmed.equals(kw, ignoreCase = true)
        }
    }

    /**
     * Checks if [viewId] matches any remote `rapidoAcceptViewIds` entry (exact full ID or `:id/` suffix)
     * or built-in accept ID pattern, while strictly excluding reject IDs.
     */
    fun isRapidoAcceptId(viewId: String): Boolean {
        val trimmed = viewId.trim()
        if (trimmed.isEmpty()) return false
        if (isRapidoRejectId(trimmed)) return false
        val fullLower = trimmed.lowercase(Locale.ROOT)
        val suffixLower = fullLower.substringAfter(":id/", fullLower)
        val matchesConfigured = AppConfigRepository.effectiveRapidoAcceptViewIds().any { configured ->
            val cfgLower = configured.trim().lowercase(Locale.ROOT)
            val cfgSuffix = cfgLower.substringAfter(":id/", cfgLower)
            cfgLower.isNotEmpty() && (
                fullLower == cfgLower ||
                    suffixLower == cfgLower ||
                    suffixLower == cfgSuffix
                )
        }
        if (matchesConfigured) return true
        return RapidoAccessibilityService.RAPIDO_ACCEPT_ID_REGEX.containsMatchIn(trimmed)
    }

    private fun findAcceptNodeInSubtree(
        root: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 80L
        var textFallbackCandidate: AccessibilityNodeInfo? = null

        try {
            while (queue.isNotEmpty()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    break
                }
                val node = queue.removeFirst()
                if (screenWidthPx > 0) {
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    if (isLeftSidebarNode(b, screenWidthPx)) {
                        recycleNode(node)
                        continue
                    }
                }
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val viewId = node.viewIdResourceName ?: ""

                val isRejectNode = isRapidoRejectId(viewId) || isRejectAction(text) || isRejectAction(desc)
                if (!isRejectNode) {
                    // Priority 1: Authoritative viewIdResourceName match returns immediately
                    if (isRapidoAcceptId(viewId)) {
                        textFallbackCandidate?.let { recycleNode(it) }
                        textFallbackCandidate = null
                        return node
                    }
                    // Priority 2: Retain first text/desc match in case no viewId match exists in subtree
                    if (textFallbackCandidate == null && (isAcceptAction(text) || isAcceptAction(desc))) {
                        @Suppress("DEPRECATION")
                        textFallbackCandidate = AccessibilityNodeInfo.obtain(node)
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
        } finally {
            while (queue.isNotEmpty()) {
                recycleNode(queue.removeFirst())
            }
        }
        return textFallbackCandidate
    }

    private fun containsFareInSubtree(
        root: AccessibilityNodeInfo,
        screenWidthPx: Int = 0
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 80L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }
            val node = queue.removeFirst()
            if (screenWidthPx > 0) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (isLeftSidebarNode(b, screenWidthPx)) {
                    recycleNode(node)
                    continue
                }
            }
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)

            if (isFareNode(text, viewId) || isFareNode(desc, viewId)) {
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

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            if (current.isClickable && current.isEnabled) {
                return current
            }
            val next = current.parent
            recycleNode(current)
            current = next
            depth++
        }
        current?.let { recycleNode(it) }
        return null
    }

    /**
     * Extracts structured pickup and drop locations from node texts based on relative positions
     * to the detected distance nodes, perfectly handling Rapido card structures like:
     * ["Auto Boost", "₹45", "₹ 57 AUTO", "0.6 km", "City Centre", "Premji's Park...", "2.4 km", "Rajkot railway station", "Rajkot Junction...", "Accept"]
     */
    fun extractStructuredLocations(nodeTexts: List<String>): Pair<String, String> {
        var pickupLoc = ""
        var dropLoc = ""

        val distIndices = mutableListOf<Int>()
        for (i in nodeTexts.indices) {
            val text = nodeTexts[i].trim()
            if (matchesDistancePattern(text) && !isFareNode(text, "")) {
                distIndices.add(i)
            }
        }

        if (distIndices.isNotEmpty()) {
            val pickupDistIdx = distIndices[0]
            val pickupParts = mutableListOf<String>()
            var i = pickupDistIdx + 1
            while (i < nodeTexts.size && (distIndices.size < 2 || i < distIndices[1])) {
                val t = nodeTexts[i].trim()
                if (isValidLocationText(t)) {
                    pickupParts.add(t)
                }
                i++
            }
            if (pickupParts.isNotEmpty()) {
                pickupLoc = pickupParts.joinToString(" • ")
            }
        }

        if (distIndices.size >= 2) {
            val dropDistIdx = distIndices[1]
            val dropParts = mutableListOf<String>()
            var i = dropDistIdx + 1
            while (i < nodeTexts.size) {
                val t = nodeTexts[i].trim()
                if (isAcceptAction(t) || isFareNode(t, "") || matchesDistancePattern(t)) {
                    break
                }
                if (isValidLocationText(t)) {
                    dropParts.add(t)
                }
                i++
            }
            if (dropParts.isNotEmpty()) {
                dropLoc = dropParts.joinToString(" • ")
            }
        }

        return pickupLoc to dropLoc
    }

    private fun isValidLocationText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        if (TextAnalysisEngine.isTipBadgeNoise(trimmed)) return false
        if (isSeeMoreOrdersText(trimmed)) return false
        if (isAcceptAction(trimmed)) return false
        if (matchesFareIndicator(trimmed)) return false
        if (matchesDistancePattern(trimmed)) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        val noiseWords = setOf("accept", "decline", "reject", "order", "1 order", "go to", "auto boost", "auto", "bike", "orders")
        if (lower in noiseWords) return false
        return true
    }

    private fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val isRobolectricTest = BuildConfig.DEBUG && (
            android.os.Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true ||
                runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
        )
        if (isRobolectricTest || android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return
        }
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: IllegalStateException) {
        }
    }
}

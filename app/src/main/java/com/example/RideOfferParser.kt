package com.example

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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

    // Currency pattern to identify pure fare nodes
    private val FARE_NODE_PATTERN = Pattern.compile(
        """(?:₹|Rs\.?|INR|\$)\s*(\d+(?:[.,]\d+)?)|(?:^|\s)(\d+(?:[.,]\d+)?)\s*(?:₹|Rs\.?|INR)""",
        Pattern.CASE_INSENSITIVE
    )

    // Standalone currency indicator
    private val CURRENCY_SYMBOL_PATTERN = Pattern.compile("""[₹$]|(?:\b(?:Rs\.?|INR)\b)""", Pattern.CASE_INSENSITIVE)

    // Distance pattern for isolated distance nodes (e.g., "1.2 km", "800 m", "2.8 km away")
    private val DISTANCE_PATTERN = Pattern.compile(
        """(\d+(?:[.,]\d+)?)\s*(km|m|kms|kilometer|meter)\b""",
        Pattern.CASE_INSENSITIVE
    )

    /**
     * Parses all distinct order cards across the given candidate window roots.
     * Ensures strict card isolation so nodes from one card never contaminate another.
     */
    fun parseCards(rootWindows: List<AccessibilityNodeInfo>): List<ParsedRideOffer> {
        val parsedOffers = mutableListOf<ParsedRideOffer>()
        val seenCardKeys = mutableSetOf<String>()

        for (root in rootWindows) {
            val cardContainers = findCardContainers(root)
            for (container in cardContainers) {
                try {
                    val offer = parseCardContainer(container)
                    if (offer != null) {
                        val deduplicationKey = "${offer.totalFare?.toInt()}_${String.format(Locale.US, "%.1f", offer.pickupDistanceKm ?: 0.0)}"
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
     * Searches for ViewGroup/CardView containers that enclose individual ride offers.
     */
    fun findCardContainers(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val containers = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()

        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(root))

        val startTime = System.currentTimeMillis()
        val timeoutMs = 200L

        while (queue.isNotEmpty()) {
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                while (queue.isNotEmpty()) {
                    recycleNode(queue.removeFirst())
                }
                break
            }

            val current = queue.removeFirst()

            // Check if current node is an isolated order card container
            if (isOrderCardContainer(current)) {
                @Suppress("DEPRECATION")
                containers.add(AccessibilityNodeInfo.obtain(current))
                // CRITICAL LAYOUT ISOLATION: Do not descend into children of this container
                // to prevent sub-layouts from being falsely treated as separate cards.
                recycleNode(current)
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

        // Fallback: If no explicit card container tags were found, but the root itself contains
        // an active Accept button and a Fare node, the root window itself is the single card container!
        if (containers.isEmpty()) {
            val acceptNode = findAcceptNodeInSubtree(root)
            val hasFare = containsFareInSubtree(root)
            if (acceptNode != null && hasFare) {
                @Suppress("DEPRECATION")
                containers.add(AccessibilityNodeInfo.obtain(root))
            }
            if (acceptNode != null) {
                recycleNode(acceptNode)
            }
        }

        return containers
    }

    /**
     * Checks if a node qualifies as an individual order card container.
     */
    fun isOrderCardContainer(node: AccessibilityNodeInfo): Boolean {
        val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
        val className = (node.className?.toString() ?: "").lowercase(Locale.ROOT)

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
            val hasAccept = findAcceptNodeInSubtree(node)
            if (hasAccept != null) {
                recycleNode(hasAccept)
                return true
            }
        }

        // 3. Any ViewGroup that directly bounds an Accept button and contains a Fare symbol
        if (node.childCount >= 2) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 250 && bounds.height() in 100..1800) {
                val accept = findAcceptNodeInSubtree(node)
                if (accept != null) {
                    val hasFare = containsFareInSubtree(node)
                    recycleNode(accept)
                    if (hasFare) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Extracts structural fields strictly from within the boundaries of a single card container.
     * Texts from other cards are NEVER visited.
     */
    fun parseCardContainer(container: AccessibilityNodeInfo): ParsedRideOffer? {
        val cardBounds = Rect()
        container.getBoundsInScreen(cardBounds)

        var fareText = ""
        var pickupText = ""
        var dropText = ""
        var pickupLocationText = ""
        var dropLocationText = ""
        var acceptButtonNode: AccessibilityNodeInfo? = null

        // Collect all distinct text nodes strictly inside this card container
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        @Suppress("DEPRECATION")
        queue.add(AccessibilityNodeInfo.obtain(container))

        val candidateDistanceTexts = mutableListOf<String>()
        val allNodeTexts = mutableListOf<String>()

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

            val text = (node.text?.toString() ?: "").trim()
            val desc = (node.contentDescription?.toString() ?: "").trim()
            val viewId = (node.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val effectiveText = if (text.isNotEmpty()) text else desc

            if (effectiveText.isNotEmpty()) {
                allNodeTexts.add(effectiveText)
            }

            // 1. Locate Accept Button inside this card
            if (acceptButtonNode == null) {
                if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
                    if (node.isClickable && node.isEnabled) {
                        @Suppress("DEPRECATION")
                        acceptButtonNode = AccessibilityNodeInfo.obtain(node)
                    } else {
                        val clickableParent = findClickableParent(node)
                        if (clickableParent != null) {
                            acceptButtonNode = clickableParent
                        } else {
                            @Suppress("DEPRECATION")
                            acceptButtonNode = AccessibilityNodeInfo.obtain(node)
                        }
                    }
                }
            }

            // 2. Extract Fare Node (Currency symbol or fare pattern)
            if (fareText.isEmpty() || !CURRENCY_SYMBOL_PATTERN.matcher(fareText).find()) {
                if (isFareNode(effectiveText, viewId)) {
                    fareText = effectiveText
                }
            }

            // 3. Extract Pickup Node
            if (isPickupNode(effectiveText, viewId)) {
                pickupText = effectiveText
            }

            // 4. Extract Drop Node
            if (isDropNode(effectiveText, viewId)) {
                dropText = effectiveText
            }

            // 5. Track standalone distance strings for positional assignment
            if (DISTANCE_PATTERN.matcher(effectiveText).find() && !isFareNode(effectiveText, viewId)) {
                if (!candidateDistanceTexts.contains(effectiveText)) {
                    candidateDistanceTexts.add(effectiveText)
                }
            }

            // 6. Extract Location Labels
            if (isPickupLocationNode(effectiveText, viewId) && pickupLocationText.isEmpty()) {
                pickupLocationText = cleanLocation(effectiveText)
            }
            if (isDropLocationNode(effectiveText, viewId) && dropLocationText.isEmpty()) {
                dropLocationText = cleanLocation(effectiveText)
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

    fun isFareNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        if (viewId.contains("fare") || viewId.contains("price") || viewId.contains("amount")) {
            return true
        }
        return CURRENCY_SYMBOL_PATTERN.matcher(text).find()
    }

    fun isPickupNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("drop") || lower.contains("destination")) return false
        if (viewId.contains("pickup_dist") || viewId.contains("pickup_distance")) return true
        return (lower.contains("pickup") || lower.contains("pick up") || lower.contains("pick-up") || lower.contains("away")) &&
                DISTANCE_PATTERN.matcher(text).find()
    }

    fun isDropNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (viewId.contains("drop_dist") || viewId.contains("drop_distance") || viewId.contains("drop_location")) return true
        return (lower.contains("drop") || lower.contains("drop off") || lower.contains("drop-off") || lower.contains("destination")) &&
                DISTANCE_PATTERN.matcher(text).find()
    }

    fun isPickupLocationNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("drop") || DISTANCE_PATTERN.matcher(text).find() || CURRENCY_SYMBOL_PATTERN.matcher(text).find()) return false
        if (viewId.contains("pickup_loc") || viewId.contains("source_address") || viewId.contains("pickup_address")) return true
        return false
    }

    fun isDropLocationNode(text: String, viewId: String): Boolean {
        if (text.isEmpty()) return false
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("pickup") || DISTANCE_PATTERN.matcher(text).find() || CURRENCY_SYMBOL_PATTERN.matcher(text).find()) return false
        if (viewId.contains("drop_loc") || viewId.contains("dest_address") || viewId.contains("drop_address")) return true
        return false
    }

    fun extractFareValue(fareText: String): Pair<Double?, List<Double>> {
        if (fareText.isEmpty()) return null to emptyList()
        return TextAnalysisEngine.extractCurrencies(fareText)
    }

    fun extractDistanceValue(distanceText: String): Double? {
        if (distanceText.isEmpty()) return null
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

    private fun isAcceptAction(content: String): Boolean {
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

    private fun findAcceptNodeInSubtree(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
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
            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
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

    private fun containsFareInSubtree(root: AccessibilityNodeInfo): Boolean {
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

    private fun recycleNode(node: AccessibilityNodeInfo) {
        try {
            @Suppress("DEPRECATION")
            node.recycle()
        } catch (_: Exception) {}
    }
}

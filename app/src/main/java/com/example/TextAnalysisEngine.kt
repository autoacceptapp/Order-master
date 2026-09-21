package com.example

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/**
 * Structured model representing an isolated raw order card container
 * detected in the UI hierarchy with its bounded text nodes and screen coordinates.
 */
data class RawOrderCard(
    val id: String,
    val nodeTexts: List<String>,
    val bounds: Rect,
    val hasAcceptButton: Boolean = false,
    val acceptNode: AccessibilityNodeInfo? = null
)

/**
 * Structured model encapsulating the 5 target highlighted fields extracted strictly
 * from the active offer layout, ignoring full addresses and extra UI noise:
 * 1. Base Price Amount: (e.g., "₹95", "₹55") - currency number starting with ₹ or INR.
 * 2. Pickup Distance: Kilometer value next to the top dot (e.g., "2.8 km", "0.5 km").
 * 3. Pickup Main Location: Bold main landmark only (e.g., "Lalpari River", "City Centre").
 * 4. Drop Distance: Kilometer value next to the bottom arrow (e.g., "6.2 km", "3.4 km").
 * 5. Drop Main Location: Bold main landmark only (e.g., "Race Course", "Rail Nagar").
 */
data class TargetRideOffer(
    val fare: Double,
    val pickupDistance: Double,
    val pickupLocation: String,
    val dropDistance: Double,
    val dropLocation: String
)

/**
 * Structured representation of a fully parsed, isolated ride offer.
 * Encapsulates card boundaries, isolated fare/distance parameters,
 * optional location details, and a unique deduplication hash.
 */
data class RideOffer(
    val id: String,
    val rawCard: RawOrderCard,
    val totalFare: Double?,
    val fareBreakdown: List<Double>,
    val pickupDistanceKm: Double?,
    val dropDistanceKm: Double?,
    val location: String = "",
    val hasAcceptButton: Boolean = false,
    val acceptNode: AccessibilityNodeInfo? = null,
    val targetOffer: TargetRideOffer? = null
) {
    fun toParsedRideOffer(): ParsedRideOffer {
        return ParsedRideOffer(
            rawText = rawCard.nodeTexts.joinToString(" "),
            totalFare = totalFare,
            fareBreakdown = fareBreakdown,
            pickupDistanceKm = pickupDistanceKm,
            dropDistanceKm = dropDistanceKm,
            hasAcceptButton = hasAcceptButton,
            targetOffer = targetOffer
        )
    }
}

/**
 * Structured data class representing parsed ride offer details from screen text.
 */
data class ParsedRideOffer(
    val rawText: String,
    val totalFare: Double?,
    val fareBreakdown: List<Double>,
    val pickupDistanceKm: Double?,
    val dropDistanceKm: Double?,
    val hasAcceptButton: Boolean,
    val targetOffer: TargetRideOffer? = null
) {
    val baseFare: Double? get() = targetOffer?.fare ?: fareBreakdown.firstOrNull() ?: totalFare
    val pickupLocation: String get() = targetOffer?.pickupLocation ?: ""
    val dropLocation: String get() = targetOffer?.dropLocation ?: ""
}

/**
 * Structured evaluation result detailing whether the ride offer meets the captain's criteria.
 */
data class RideEvaluation(
    val offer: ParsedRideOffer,
    val isAutoAcceptEnabled: Boolean,
    val passesFare: Boolean,
    val passesPickupDistance: Boolean,
    val isAccepted: Boolean,
    val decisionReason: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val totalCurrency: Double? get() = offer.totalFare
    val distanceKm: Double? get() = offer.pickupDistanceKm ?: offer.dropDistanceKm
}

/**
 * Backward-compatible analysis result class for general text analysis.
 */
data class AnalysisResult(
    val rawText: String,
    val totalCurrency: Double?,
    val currencyBreakdown: List<Double>,
    val distanceKm: Double?,
    val hasAcceptButton: Boolean,
    val conditionsPassed: Boolean,
    val reason: String
)

/**
 * Dedicated parsing, UI tree grouping, and business logic engine for analyzing ride requests
 * (e.g. Rapido Captain, Uber Driver, Ola Captain overlay cards) and evaluating user decision criteria.
 *
 * Provides container-based node tree grouping to isolate simultaneous order cards, preventing
 * text cross-contamination between multiple offers on screen.
 */
object TextAnalysisEngine {

    private const val TAG = "TextAnalysisEngine"

    // Supported distance units: km, kms, kilometer(s), m, meter(s)
    private const val DISTANCE_UNIT_PATTERN = """(?:km|kms|kilometers?|meters?|m)"""

    // Target regex: Extracts currency numbers starting with ₹ or INR
    val fareRegex = Regex(
        """(?:[₹]|INR|Rs\.?)\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // Target regex: Matches patterns ending in km (First match = Pickup distance, Second match = Drop distance)
    val distanceRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*(?:km|kms|kilometers?)\b""",
        RegexOption.IGNORE_CASE
    )

    // Location split regex: Splits text by hyphen, dash, or newline to isolate only the bold primary landmark name
    val locationSplitRegex = Regex("""[\n\r\-–—|]""")

    // Regex for currency: matches ₹, Rs., Rs, $, €, £, INR followed by numeric values
    private val currencyRegex = Regex(
        """(?:[₹\$€£]|Rs\.?|INR)\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // Regex for plus component numbers (e.g. "+ ₹15" or "+ 20" following a fare)
    private val plusBonusRegex = Regex(
        """\+\s*(?:[₹\$€£]|Rs\.?|INR)?\s*(\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // General distance regex with boundary protection (prevents matching "10 am" or "15 min")
    private val generalDistanceRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*($DISTANCE_UNIT_PATTERN)(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE
    )

    // Optimized pickup regex:
    // Pattern 1: Label first (e.g., "Pickup: 0.5 km", "away 1.2km")
    private val pickupLabelFirstRegex = Regex(
        """\b(?:pickup|pick\s*up|away|to\s*rider)\b[^\d\r\n]{0,20}?(\d+(?:\.\d+)?)\s*($DISTANCE_UNIT_PATTERN)(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE
    )

    // Pattern 2: Distance first (e.g., "0.5 km pickup", "1.2 km away")
    private val pickupDistanceFirstRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*($DISTANCE_UNIT_PATTERN)(?![a-zA-Z])[^\d\r\n]{0,15}?\b(?:pickup|pick\s*up|away)\b""",
        RegexOption.IGNORE_CASE
    )

    // Optimized drop/trip regex:
    // Pattern 1: Label first (e.g., "Drop: 3.4 km", "Trip: 6.2 km")
    private val dropLabelFirstRegex = Regex(
        """\b(?:drop|dropoff|drop\s*off|destination|to\s*drop|trip|travel)\b[^\d\r\n]{0,20}?(\d+(?:\.\d+)?)\s*($DISTANCE_UNIT_PATTERN)(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE
    )

    // Pattern 2: Distance first (e.g., "3.4 km drop", "6.2 km trip")
    private val dropDistanceFirstRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*($DISTANCE_UNIT_PATTERN)(?![a-zA-Z])[^\d\r\n]{0,15}?\b(?:drop|dropoff|drop\s*off|destination|trip)\b""",
        RegexOption.IGNORE_CASE
    )

    // =========================================================================================
    // 1. NODE TREE GROUPING & CONTAINER ISOLATION LOGIC
    // =========================================================================================

    /**
     * Recursively traverses the AccessibilityNodeInfo tree to discover and isolate
     * distinct order cards (e.g. CardView, ViewGroup, bounded subtrees).
     *
     * Groups child text nodes by their parent card container ID and spatial boundaries,
     * isolating each offer and preventing cross-card text contamination when multiple
     * offers are displayed simultaneously.
     */
    fun extractOrderCards(root: AccessibilityNodeInfo?): List<RawOrderCard> {
        if (root == null) return emptyList()

        val cards = mutableListOf<RawOrderCard>()
        val startTime = System.currentTimeMillis()
        val timeoutMs = 180L

        // Pass 1: Collect all candidate accept nodes and their screen bounds
        val acceptNodes = mutableListOf<AccessibilityNodeInfo>()
        findAcceptNodesDfs(root, acceptNodes, startTime, timeoutMs)

        if (acceptNodes.size > 1) {
            // MULTIPLE ORDERS ON SCREEN:
            // For each accept button, find its highest enclosing container node
            // that does NOT contain any other accept button.
            val processedBounds = mutableListOf<Rect>()

            for (acceptNode in acceptNodes) {
                val container = findIsolatedCardContainer(acceptNode, acceptNodes) ?: acceptNode
                val bounds = Rect()
                container.getBoundsInScreen(bounds)

                // Validate container dimensions
                if (!bounds.isEmpty && bounds.width() > 40 && bounds.height() > 40) {
                    val isDuplicate = processedBounds.any { existing ->
                        (Math.abs(existing.top - bounds.top) < 25 && Math.abs(existing.bottom - bounds.bottom) < 25)
                    }

                    if (!isDuplicate) {
                        processedBounds.add(bounds)
                        val nodeTexts = extractTextNodesFromSubtree(container, startTime, timeoutMs)
                        val cardId = container.viewIdResourceName
                            ?: "card_${bounds.left}_${bounds.top}_${bounds.right}_${bounds.bottom}"

                        cards.add(
                            RawOrderCard(
                                id = cardId,
                                nodeTexts = nodeTexts,
                                bounds = bounds,
                                hasAcceptButton = true,
                                acceptNode = acceptNode
                            )
                        )
                    }
                }
            }

            if (cards.isNotEmpty()) {
                return cards
            }
        }

        // Pass 2: Fallback container search: Look for child containers with currency and distance
        val containerCards = findContainerCardsDfs(root, startTime, timeoutMs)
        if (containerCards.size > 1) {
            return containerCards
        }

        // Pass 3: Single card or standard screen fallback:
        // Whole window / root is treated as a single RawOrderCard
        val bounds = Rect()
        root.getBoundsInScreen(bounds)
        val allTexts = extractTextNodesFromSubtree(root, startTime, timeoutMs)
        val hasAccept = acceptNodes.isNotEmpty() || hasAcceptAction(allTexts.joinToString(" "))
        val primaryAccept = acceptNodes.firstOrNull()

        return listOf(
            RawOrderCard(
                id = root.viewIdResourceName ?: "card_${bounds.left}_${bounds.top}",
                nodeTexts = allTexts,
                bounds = bounds,
                hasAcceptButton = hasAccept,
                acceptNode = primaryAccept
            )
        )
    }

    /**
     * Walks up the parent chain of an accept button to find the highest container node
     * that encapsulates this offer's details without enclosing other accept buttons.
     */
    private fun findIsolatedCardContainer(
        acceptNode: AccessibilityNodeInfo,
        allAcceptNodes: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        val otherNodes = allAcceptNodes.filter { it !== acceptNode }
        var current: AccessibilityNodeInfo? = acceptNode.parent
        var bestContainer: AccessibilityNodeInfo? = acceptNode
        var depth = 0

        while (current != null && depth < 8) {
            // Check if current parent encloses any other accept button
            val enclosesOther = otherNodes.any { other ->
                isDescendantOf(other, current)
            }

            if (enclosesOther) {
                // Stop! The previous container was the highest isolated node for this card
                break
            }

            val bounds = Rect()
            current.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.height() > 50) {
                bestContainer = current
            }

            val parent = current.parent
            current = parent
            depth++
        }

        return bestContainer
    }

    /**
     * Checks whether a target node is a descendant of the candidate parent.
     */
    private fun isDescendantOf(target: AccessibilityNodeInfo, parent: AccessibilityNodeInfo?): Boolean {
        if (parent == null) return false
        var cur: AccessibilityNodeInfo? = target.parent
        var depth = 0
        while (cur != null && depth < 10) {
            if (cur == parent) return true
            cur = cur.parent
            depth++
        }
        return false
    }

    /**
     * DFS search collecting all accept action nodes in the hierarchy.
     */
    private fun findAcceptNodesDfs(
        node: AccessibilityNodeInfo?,
        outList: MutableList<AccessibilityNodeInfo>,
        startTime: Long,
        timeoutMs: Long
    ) {
        if (node == null) return
        if (System.currentTimeMillis() - startTime > timeoutMs) return

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        if (isAcceptAction(text) || isAcceptAction(desc) || isRapidoAcceptId(viewId)) {
            outList.add(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAcceptNodesDfs(child, outList, startTime, timeoutMs)
        }
    }

    /**
     * Traverses subtrees to detect multi-card structures (e.g. RecyclerView items, CardViews).
     */
    private fun findContainerCardsDfs(
        node: AccessibilityNodeInfo?,
        startTime: Long,
        timeoutMs: Long
    ): List<RawOrderCard> {
        if (node == null) return emptyList()
        val results = mutableListOf<RawOrderCard>()

        val childCount = node.childCount
        if (childCount >= 2) {
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                val childTexts = extractTextNodesFromSubtree(child, startTime, timeoutMs)
                val fullChildText = childTexts.joinToString(" ")
                val (fare, _) = extractCurrencies(fullChildText)
                val (pickup, drop) = extractRideDistances(fullChildText)

                if (fare != null && (pickup != null || drop != null)) {
                    val bounds = Rect()
                    child.getBoundsInScreen(bounds)
                    val hasAccept = hasAcceptAction(fullChildText)
                    results.add(
                        RawOrderCard(
                            id = child.viewIdResourceName ?: "container_card_${bounds.left}_${bounds.top}",
                            nodeTexts = childTexts,
                            bounds = bounds,
                            hasAcceptButton = hasAccept,
                            acceptNode = if (hasAccept) child else null
                        )
                    )
                }
            }
        }

        if (results.size >= 2) {
            return results
        }

        return emptyList()
    }

    /**
     * Extracts all distinct text and contentDescription tokens from a subtree in reading order.
     */
    fun extractTextNodesFromSubtree(
        node: AccessibilityNodeInfo?,
        startTime: Long = System.currentTimeMillis(),
        timeoutMs: Long = 180L
    ): List<String> {
        if (node == null) return emptyList()
        val textList = mutableListOf<String>()

        fun dfs(cur: AccessibilityNodeInfo?, depth: Int) {
            if (cur == null || depth > 30) return
            if (System.currentTimeMillis() - startTime > timeoutMs) return

            val t = cur.text?.toString()?.trim()
            if (!t.isNullOrEmpty()) {
                textList.add(t)
            }

            val d = cur.contentDescription?.toString()?.trim()
            if (!d.isNullOrEmpty() && !d.equals(t, ignoreCase = true)) {
                textList.add(d)
            }

            for (i in 0 until cur.childCount) {
                val child = cur.getChild(i) ?: continue
                dfs(child, depth + 1)
            }
        }

        dfs(node, 0)
        return textList
    }

    // =========================================================================================
    // 2. ORDER SEPARATION & UNIQUE PARSING
    // =========================================================================================

    /**
     * Parses an isolated RawOrderCard into a distinct RideOffer instance.
     *
     * Incomplete or malformed cards where price or pickup distance cannot be properly paired
     * are strictly ignored (returns null).
     * Assigns a unique hash/identifier to each detected offer using price + distance + location string.
     */
    fun parseOrderCard(card: RawOrderCard): RideOffer? {
        val fullText = card.nodeTexts.joinToString(" ")
        val targetOffer = parseTargetOfferFromNodes(card.nodeTexts) ?: parseTargetOffer(fullText)
        val (totalFare, breakdown) = extractCurrencies(fullText)
        val (pickupDist, dropDist) = extractRideDistances(fullText)
        val hasAccept = card.hasAcceptButton || hasAcceptAction(fullText)

        val targetPickup = targetOffer?.pickupDistance?.takeIf { it > 0.0 }
        val targetDrop = targetOffer?.dropDistance?.takeIf { it > 0.0 }
        val effectiveFare = totalFare ?: targetOffer?.fare?.takeIf { it > 0.0 }
        val effectivePickup = targetPickup ?: pickupDist
        val effectiveDrop = targetDrop ?: dropDist

        // Strict pairing: Card must have a valid non-zero fare AND a valid pickup or drop distance
        if (effectiveFare == null || effectiveFare <= 0.0) {
            Log.d(TAG, "parseOrderCard: Rejected incomplete card (Missing or invalid fare): $fullText")
            return null
        }

        if (effectivePickup == null && effectiveDrop == null) {
            Log.d(TAG, "parseOrderCard: Rejected incomplete card (Missing distance pairing): $fullText")
            return null
        }

        val location = targetOffer?.pickupLocation?.ifEmpty { null } ?: extractLocation(card.nodeTexts)
        val offerId = generateOfferId(effectiveFare, effectivePickup ?: effectiveDrop, location)

        return RideOffer(
            id = offerId,
            rawCard = card,
            totalFare = effectiveFare,
            fareBreakdown = breakdown.ifEmpty { targetOffer?.fare?.let { listOf(it) } ?: emptyList() },
            pickupDistanceKm = effectivePickup,
            dropDistanceKm = effectiveDrop,
            location = location,
            hasAcceptButton = hasAccept,
            acceptNode = card.acceptNode,
            targetOffer = targetOffer
        )
    }

    /**
     * Parses a list of RawOrderCards into distinct RideOffer instances,
     * automatically ignoring incomplete or malformed cards.
     */
    fun parseAllOrderCards(cards: List<RawOrderCard>): List<RideOffer> {
        return cards.mapNotNull { parseOrderCard(it) }
    }

    /**
     * Generates a stable unique hash/identifier using price + distance + location string
     * to avoid evaluating or announcing the same order twice.
     */
    fun generateOfferId(totalFare: Double?, distanceKm: Double?, location: String): String {
        val fareStr = totalFare?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"
        val distStr = distanceKm?.let { String.format(Locale.US, "%.1f", it) } ?: "0.0"
        val cleanLoc = location.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val raw = "offer_f${fareStr}_d${distStr}_l${cleanLoc}"
        val hash = (raw.hashCode().toLong() and 0xFFFFFFFFL).toString(16)
        return "offer_${hash}"
    }

    /**
     * Extracts potential location / landmark / street names from card text nodes,
     * filtering out currency amounts, distances, app titles, and functional UI keywords.
     */
    fun extractLocation(nodeTexts: List<String>): String {
        val brandOrActionRegex = Regex(
            """\b(?:rapido|captain|uber|ola|driver|rider|accept|confirm|select|reject|cancel|decline|order|ride|earnings|trip|fare|cash|online|bonus)\b""",
            RegexOption.IGNORE_CASE
        )

        for (text in nodeTexts) {
            val trimmed = text.trim()

            if (currencyRegex.containsMatchIn(trimmed) || generalDistanceRegex.containsMatchIn(trimmed)) {
                continue
            }

            if (brandOrActionRegex.containsMatchIn(trimmed)) {
                continue
            }

            if (trimmed.length in 3..60 && trimmed.any { it.isLetter() }) {
                return trimmed
            }
        }
        return ""
    }

    /**
     * Splits text by hyphen `-` or newline `\n` to isolate only the bold primary landmark name
     * (e.g., split "Lalpari River - 22-24-2..." -> keep "Lalpari River").
     * Filters out pincodes and trailing secondary address noise.
     */
    fun locationCleaner(raw: String): String {
        if (raw.isBlank()) return ""

        // 1. Split by hyphen, dash, newline, or pipe to isolate only the bold primary landmark name
        val firstSegment = raw.split(locationSplitRegex).firstOrNull()?.trim() ?: ""

        // 2. Remove 6-digit Indian PIN codes
        val withoutPin = firstSegment.replace(Regex("""\b\d{6}\b"""), "").trim()

        // 3. Clean trailing punctuation
        var cleaned = withoutPin.trim(',', ';', '.', '-', ' ')

        // 4. If secondary address was comma-separated and long, take first component before comma
        if (cleaned.contains(",") && cleaned.length > 25) {
            val firstPart = cleaned.split(",").first().trim()
            if (firstPart.length >= 2 && firstPart.any { it.isLetter() }) {
                cleaned = firstPart.trim(',', ';', '.', '-', ' ')
            }
        }

        return cleaned.replace(Regex("""\s+"""), " ").trim()
    }

    /**
     * Parses the 5 target fields directly from isolated UI text nodes.
     */
    fun parseTargetOfferFromNodes(nodes: List<String>): TargetRideOffer? {
        if (nodes.isEmpty()) return null

        // 1. Base Price Amount: Extracts currency numbers starting with ₹ or INR (first match ignores bonus tags)
        var baseFare = 0.0
        for (node in nodes) {
            val match = fareRegex.find(node)
            if (match != null) {
                baseFare = match.groups[1]?.value?.toDoubleOrNull() ?: 0.0
                if (baseFare > 0.0) break
            }
        }

        // 2. Distances: First match = Pickup distance, Second match = Drop distance
        val allDistanceValues = mutableListOf<Double>()
        for (node in nodes) {
            distanceRegex.findAll(node).forEach { match ->
                match.groups[1]?.value?.toDoubleOrNull()?.let { allDistanceValues.add(it) }
            }
        }

        val pickupDistance = allDistanceValues.getOrNull(0) ?: 0.0
        val dropDistance = allDistanceValues.getOrNull(1) ?: 0.0

        // 3. Locations: Find candidate location nodes by excluding fare, distance, and UI noise
        val brandOrNoiseRegex = Regex(
            """\b(?:rapido|captain|uber|ola|driver|rider|accept|confirm|select|reject|cancel|decline|order|ride|earnings|trip|fare|cash|online|bonus|swipe to accept|take ride)\b""",
            RegexOption.IGNORE_CASE
        )

        val brandOrHeaderWords = setOf(
            "rapido", "captain", "uber", "driver", "ola", "rider", "bike", "auto",
            "order", "ride", "new", "accepted", "cancelled", "earnings", "trip",
            "fare", "cash", "online", "bonus", "details", "request"
        )

        fun isBrandOrNoiseNode(s: String): Boolean {
            val words = s.lowercase(Locale.ROOT).split(Regex("""[\s_\-–—|:,.]+""")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return true
            return words.all { it in brandOrHeaderWords }
        }

        val labelPrefixRegex = Regex("""^(?i)(?:pickup|pick\s*up|drop|dropoff|drop\s*off|destination|to|from)[:\s-]+""")

        val locationCandidates = mutableListOf<String>()
        for (node in nodes) {
            val trimmed = node.trim()
            if (trimmed.isEmpty()) continue

            // Ignore pure currency or pure distance nodes
            if (fareRegex.containsMatchIn(trimmed) && !trimmed.contains("-") && !trimmed.contains("\n")) {
                continue
            }
            if (distanceRegex.containsMatchIn(trimmed) && trimmed.length < 18 && !trimmed.contains("-")) {
                continue
            }
            if (isBrandOrNoiseNode(trimmed) || isAcceptAction(trimmed)) {
                continue
            }

            // Strip prefix like "Pickup: " or "Drop: "
            val cleanedOfPrefix = trimmed.replace(labelPrefixRegex, "").trim()
            val cleanedLoc = locationCleaner(cleanedOfPrefix)

            if (cleanedLoc.length >= 2 && cleanedLoc.any { it.isLetter() } && !isBrandOrNoiseNode(cleanedLoc)) {
                locationCandidates.add(cleanedLoc)
            }
        }

        val pickupLocation = locationCandidates.getOrNull(0) ?: ""
        val dropLocation = locationCandidates.getOrNull(1) ?: ""

        if (baseFare <= 0.0 && pickupDistance <= 0.0) {
            return null
        }

        return TargetRideOffer(
            fare = baseFare,
            pickupDistance = pickupDistance,
            pickupLocation = pickupLocation,
            dropDistance = dropDistance,
            dropLocation = dropLocation
        )
    }

    /**
     * Parses the 5 target fields from raw text string.
     */
    fun parseTargetOffer(text: String): TargetRideOffer? {
        if (text.isBlank()) return null
        if (text.contains("\n")) {
            val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val parsed = parseTargetOfferFromNodes(lines)
            if (parsed != null) return parsed
        }

        // 1. Base Price Amount: first currency match starting with ₹ or INR
        val fareMatch = fareRegex.find(text)
        val baseFare = fareMatch?.groups?.get(1)?.value?.toDoubleOrNull() ?: 0.0

        // 2. Distances (First match = Pickup distance, Second match = Drop distance)
        val distMatches = distanceRegex.findAll(text).toList()
        val pickupDistance = distMatches.getOrNull(0)?.groups?.get(1)?.value?.toDoubleOrNull() ?: 0.0
        val dropDistance = distMatches.getOrNull(1)?.groups?.get(1)?.value?.toDoubleOrNull() ?: 0.0

        // 3. Locations
        var pickupLocation = ""
        var dropLocation = ""

        if (distMatches.size >= 2) {
            val match1 = distMatches[0]
            val match2 = distMatches[1]
            val between = text.substring(match1.range.last + 1, match2.range.first).trim()
            pickupLocation = locationCleaner(between.replace(Regex("""^(?i)(?:pickup|pick\s*up|drop|to|from)[:\s-]+"""), ""))

            val after = text.substring(match2.range.last + 1).trim()
            val afterCleaned = after.replace(Regex("""(?i)\b(?:accept|confirm|swipe to accept|take ride|go)\b.*"""), "").trim()
            dropLocation = locationCleaner(afterCleaned.replace(Regex("""^(?i)(?:drop|dropoff|destination|to)[:\s-]+"""), ""))
        } else if (distMatches.size == 1) {
            val match1 = distMatches[0]
            val after = text.substring(match1.range.last + 1).trim()
            val afterCleaned = after.replace(Regex("""(?i)\b(?:accept|confirm|swipe to accept|take ride|go)\b.*"""), "").trim()
            pickupLocation = locationCleaner(afterCleaned.replace(Regex("""^(?i)(?:pickup|pick\s*up)[:\s-]+"""), ""))
        }

        if (baseFare <= 0.0 && pickupDistance <= 0.0) {
            return null
        }

        return TargetRideOffer(
            fare = baseFare,
            pickupDistance = pickupDistance,
            pickupLocation = pickupLocation,
            dropDistance = dropDistance,
            dropLocation = dropLocation
        )
    }

    // =========================================================================================
    // 3. CURRENCY & DISTANCE EXTRACTION LOGIC
    // =========================================================================================

    /**
     * Extracts all currency amounts, correctly handling base fare + bonus tips/incentives
     * (e.g., "₹55", "₹56 + ₹13", "₹95 + ₹23", "Rs. 70 + Rs. 15").
     */
    fun extractCurrencies(text: String): Pair<Double?, List<Double>> {
        val breakdown = mutableListOf<Double>()

        // 1. Direct currency matches
        val directMatches = currencyRegex.findAll(text).toList()
        for (match in directMatches) {
            match.groups[1]?.value?.toDoubleOrNull()?.let { breakdown.add(it) }
        }

        // 2. Plus component bonuses
        if (breakdown.isNotEmpty()) {
            val plusMatches = plusBonusRegex.findAll(text).toList()
            for (pm in plusMatches) {
                val bonusVal = pm.groups[1]?.value?.toDoubleOrNull()
                if (bonusVal != null && !breakdown.contains(bonusVal)) {
                    breakdown.add(bonusVal)
                }
            }
        }

        return if (breakdown.isNotEmpty()) {
            val total = breakdown.sum()
            total to breakdown
        } else {
            null to emptyList()
        }
    }

    /**
     * Extracts pickup distance and drop/trip distance from raw screen text.
     * Accurately converts meters to kilometers (e.g. "500 m" -> 0.5 km).
     */
    fun extractRideDistances(text: String): Pair<Double?, Double?> {
        var pickup: Double? = null
        var drop: Double? = null

        // 1. Check for labeled pickup
        val pickupMatch = pickupLabelFirstRegex.find(text) ?: pickupDistanceFirstRegex.find(text)
        if (pickupMatch != null) {
            val rawNum = pickupMatch.groups[1]?.value?.toDoubleOrNull()
            val unit = pickupMatch.groups[2]?.value?.lowercase(Locale.ROOT) ?: "km"
            if (rawNum != null) {
                pickup = if (unit.startsWith("m")) rawNum / 1000.0 else rawNum
            }
        }

        // 2. Check for labeled drop/trip
        val dropMatch = dropLabelFirstRegex.find(text) ?: dropDistanceFirstRegex.find(text)
        if (dropMatch != null) {
            val rawNum = dropMatch.groups[1]?.value?.toDoubleOrNull()
            val unit = dropMatch.groups[2]?.value?.lowercase(Locale.ROOT) ?: "km"
            if (rawNum != null) {
                drop = if (unit.startsWith("m")) rawNum / 1000.0 else rawNum
            }
        }

        // 3. Fallback: Parse sequential distances if labels were not explicitly matched
        if (pickup == null || drop == null) {
            val allDistances = generalDistanceRegex.findAll(text).mapNotNull { match ->
                val num = match.groups[1]?.value?.toDoubleOrNull()
                val unit = match.groups[2]?.value?.lowercase(Locale.ROOT) ?: "km"
                if (num != null) {
                    if (unit.startsWith("m")) num / 1000.0 else num
                } else null
            }.toList()

            if (allDistances.isNotEmpty()) {
                if (pickup == null) pickup = allDistances.first()
                if (drop == null && allDistances.size > 1) {
                    drop = allDistances[1]
                }
            }
        }

        return pickup to drop
    }

    /**
     * Convenience method returning the primary distance found in text (in kilometers).
     */
    fun extractDistance(text: String): Double? {
        val (pickup, drop) = extractRideDistances(text)
        return pickup ?: drop
    }

    /**
     * Detects if the screen hierarchy includes an interactive "Accept" / "Go" action.
     */
    fun hasAcceptAction(text: String): Boolean {
        return text.contains("Accept", ignoreCase = true) ||
                text.contains("Accept Order", ignoreCase = true) ||
                text.contains("Accept Ride", ignoreCase = true) ||
                text.contains("Swipe to Accept", ignoreCase = true) ||
                text.contains("Go", ignoreCase = false) ||
                text.contains("Take Ride", ignoreCase = true) ||
                text.contains("Confirm Order", ignoreCase = true) ||
                text.contains("Confirm Ride", ignoreCase = true)
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

    /**
     * Parses the raw screen text into a structured ParsedRideOffer.
     */
    fun parse(text: String): ParsedRideOffer = parseRideOffer(text)

    fun parseRideOffer(text: String): ParsedRideOffer {
        val targetOffer = parseTargetOffer(text)
        val (totalFare, breakdown) = extractCurrencies(text)
        val (pickupDist, dropDist) = extractRideDistances(text)
        val hasAccept = hasAcceptAction(text)

        val finalTotalFare = totalFare ?: targetOffer?.fare
        val finalPickupDist = targetOffer?.pickupDistance ?: pickupDist
        val finalDropDist = if ((targetOffer?.dropDistance ?: 0.0) > 0.0) targetOffer?.dropDistance else dropDist

        return ParsedRideOffer(
            rawText = text,
            totalFare = finalTotalFare,
            fareBreakdown = breakdown.ifEmpty { targetOffer?.fare?.let { listOf(it) } ?: emptyList() },
            pickupDistanceKm = finalPickupDist,
            dropDistanceKm = finalDropDist,
            hasAcceptButton = hasAccept,
            targetOffer = targetOffer
        )
    }

    /**
     * Core business evaluation engine: evaluates ride offer parameters against user limits.
     *
     * Criteria:
     * 1. Auto-Accept must be enabled in settings.
     * 2. Accept action/button must be detected on screen.
     * 3. Total Fare must be >= User Min Fare limit AND <= User Max Fare limit (minFare <= offer.fare <= maxFare).
     * 4. Pickup Distance must be <= User Max Pickup Distance limit (Drop distance is ignored for accept decision).
     */
    fun evaluateRideOffer(
        offer: ParsedRideOffer,
        minFare: Float,
        maxFare: Float = 5000.0f,
        maxPickupDistance: Float,
        isAutoAcceptEnabled: Boolean
    ): RideEvaluation {
        val effectiveMaxPickup = maxPickupDistance.coerceIn(0.0f, 3.0f)

        if (!isAutoAcceptEnabled) {
            return RideEvaluation(
                offer = offer,
                isAutoAcceptEnabled = false,
                passesFare = false,
                passesPickupDistance = false,
                isAccepted = false,
                decisionReason = "Auto-Accept is DISABLED in settings"
            )
        }

        if (!offer.hasAcceptButton) {
            return RideEvaluation(
                offer = offer,
                isAutoAcceptEnabled = true,
                passesFare = false,
                passesPickupDistance = false,
                isAccepted = false,
                decisionReason = "No 'Accept' button detected on screen"
            )
        }

        val evalFare = offer.totalFare ?: offer.targetOffer?.fare
        if (evalFare == null) {
            return RideEvaluation(
                offer = offer,
                isAutoAcceptEnabled = true,
                passesFare = false,
                passesPickupDistance = false,
                isAccepted = false,
                decisionReason = "No fare amount detected in request"
            )
        }

        // Apply filters strictly using fare and pickupDistance (ignoring drop distance for accept decision)
        val pickupDist = offer.targetOffer?.pickupDistance?.takeIf { it > 0.0 } ?: offer.pickupDistanceKm

        val isAboveMinFare = evalFare >= minFare
        val isBelowMaxFare = evalFare <= maxFare
        val passesFare = isAboveMinFare && isBelowMaxFare
        val passesPickup = pickupDist != null && pickupDist <= effectiveMaxPickup

        val breakdownStr = if (offer.fareBreakdown.size > 1) {
            " (${offer.fareBreakdown.joinToString(" + ") { "₹$it" }})"
        } else ""

        val reason = when {
            passesFare && passesPickup -> {
                val dropStr = offer.dropDistanceKm?.let { ", Drop: ${String.format(Locale.US, "%.1f", it)}km" } ?: ""
                "MATCHED: Fare ₹${String.format(Locale.US, "%.1f", evalFare)}$breakdownStr is within ₹$minFare - ₹$maxFare and Pickup ${String.format(Locale.US, "%.1f", pickupDist!!)}km <= ${effectiveMaxPickup}km$dropStr"
            }
            !isAboveMinFare -> {
                "REJECTED: Fare ₹${String.format(Locale.US, "%.1f", evalFare)}$breakdownStr < Min Limit ₹$minFare"
            }
            !isBelowMaxFare -> {
                "REJECTED: Fare ₹${String.format(Locale.US, "%.1f", evalFare)}$breakdownStr > Max Limit ₹$maxFare"
            }
            pickupDist == null -> {
                "REJECTED: Pickup distance could not be determined"
            }
            else -> {
                "REJECTED: Pickup distance (${String.format(Locale.US, "%.1f", pickupDist)} km) exceeds set maximum limit (${String.format(Locale.US, "%.1f", effectiveMaxPickup)} km)"
            }
        }

        val isAccepted = passesFare && passesPickup

        return RideEvaluation(
            offer = offer,
            isAutoAcceptEnabled = true,
            passesFare = passesFare,
            passesPickupDistance = passesPickup,
            isAccepted = isAccepted,
            decisionReason = reason
        )
    }

    /**
     * Convenience overload accepting SettingsState directly.
     */
    fun evaluateRideOffer(
        offer: ParsedRideOffer,
        settings: SettingsState
    ): RideEvaluation {
        return evaluateRideOffer(
            offer = offer,
            minFare = settings.minFare,
            maxFare = settings.maxFare,
            maxPickupDistance = settings.maxPickupDistance,
            isAutoAcceptEnabled = settings.isAutoAcceptEnabled
        )
    }

    /**
     * Backward-compatible analyze method returning AnalysisResult.
     */
    fun analyze(text: String): AnalysisResult {
        val offer = parseRideOffer(text)
        val minThreshold = AppSettings.minCurrencyThreshold

        val conditionMet = offer.totalFare != null && offer.totalFare >= minThreshold
        val reason = if (conditionMet) "Condition Met" else "Threshold Not Met"

        return AnalysisResult(
            rawText = text,
            totalCurrency = offer.totalFare,
            currencyBreakdown = offer.fareBreakdown,
            distanceKm = offer.pickupDistanceKm ?: offer.dropDistanceKm,
            hasAcceptButton = offer.hasAcceptButton,
            conditionsPassed = conditionMet,
            reason = reason
        )
    }

    /**
     * Backward-compatible evaluate method.
     */
    fun evaluate(
        text: String,
        hasAcceptButton: Boolean,
        minFilter: Float,
        maxFilter: Float
    ): AnalysisResult {
        val offer = parseRideOffer(text).copy(hasAcceptButton = hasAcceptButton)
        val evaluation = evaluateRideOffer(
            offer = offer,
            minFare = minFilter,
            maxPickupDistance = maxFilter,
            isAutoAcceptEnabled = true
        )

        return AnalysisResult(
            rawText = text,
            totalCurrency = offer.totalFare,
            currencyBreakdown = offer.fareBreakdown,
            distanceKm = offer.pickupDistanceKm ?: offer.dropDistanceKm,
            hasAcceptButton = hasAcceptButton,
            conditionsPassed = evaluation.isAccepted,
            reason = evaluation.decisionReason
        )
    }
}

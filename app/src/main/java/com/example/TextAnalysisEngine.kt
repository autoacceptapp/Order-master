package com.example

import java.util.Locale

/**
 * Structured data class representing parsed ride offer details from screen text.
 */
data class ParsedRideOffer(
    val rawText: String,
    val totalFare: Double?,
    val fareBreakdown: List<Double>,
    val pickupDistanceKm: Double?,
    val dropDistanceKm: Double?,
    val hasAcceptButton: Boolean
)

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
 * Dedicated parsing and business logic engine for analyzing ride requests
 * (e.g. Rapido Captain, Uber Driver, Ola Captain overlay cards) and evaluating
 * user decision criteria.
 *
 * Regex patterns are strictly optimized with word boundaries and non-greedy matching
 * to prevent catastrophic backtracking and stack overflow errors.
 */
object TextAnalysisEngine {

    // Supported distance units: km, kms, kilometer(s), m, meter(s)
    private const val DISTANCE_UNIT_PATTERN = """(?:km|kms|kilometers?|meters?|m)"""

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

        // 2. If no direct matches, or if there are plus bonuses not captured above
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

        // 1. Check for labeled pickup (label first, or distance first)
        val pickupMatch = pickupLabelFirstRegex.find(text) ?: pickupDistanceFirstRegex.find(text)
        if (pickupMatch != null) {
            val rawNum = pickupMatch.groups[1]?.value?.toDoubleOrNull()
            val unit = pickupMatch.groups[2]?.value?.lowercase(Locale.ROOT) ?: "km"
            if (rawNum != null) {
                pickup = if (unit.startsWith("m")) rawNum / 1000.0 else rawNum
            }
        }

        // 2. Check for labeled drop/trip (label first, or distance first)
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
     * Detects if the screen hierarchy includes an "Accept" action.
     */
    fun hasAcceptAction(text: String): Boolean {
        return text.contains("Accept", ignoreCase = true) ||
                text.contains("Swipe to Accept", ignoreCase = true)
    }

    /**
     * Parses the raw screen text into a structured ParsedRideOffer.
     */
    fun parse(text: String): ParsedRideOffer = parseRideOffer(text)

    fun parseRideOffer(text: String): ParsedRideOffer {
        val (totalFare, breakdown) = extractCurrencies(text)
        val (pickupDist, dropDist) = extractRideDistances(text)
        val hasAccept = hasAcceptAction(text)

        return ParsedRideOffer(
            rawText = text,
            totalFare = totalFare,
            fareBreakdown = breakdown,
            pickupDistanceKm = pickupDist,
            dropDistanceKm = dropDist,
            hasAcceptButton = hasAccept
        )
    }

    /**
     * Core business evaluation engine: evaluates ride offer parameters against user limits.
     *
     * Criteria:
     * 1. Auto-Accept must be enabled in settings.
     * 2. Accept action/button must be detected on screen.
     * 3. Total Fare must be >= User Min Fare limit.
     * 4. Pickup Distance must be <= User Max Pickup Distance limit.
     */
    fun evaluateRideOffer(
        offer: ParsedRideOffer,
        minFare: Float,
        maxPickupDistance: Float,
        isAutoAcceptEnabled: Boolean
    ): RideEvaluation {
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

        if (offer.totalFare == null) {
            return RideEvaluation(
                offer = offer,
                isAutoAcceptEnabled = true,
                passesFare = false,
                passesPickupDistance = false,
                isAccepted = false,
                decisionReason = "No fare amount detected in request"
            )
        }

        val passesFare = offer.totalFare >= minFare
        val passesPickup = offer.pickupDistanceKm != null && offer.pickupDistanceKm <= maxPickupDistance

        val breakdownStr = if (offer.fareBreakdown.size > 1) {
            " (${offer.fareBreakdown.joinToString(" + ") { "₹$it" }})"
        } else ""

        val reason = when {
            passesFare && passesPickup -> {
                val dropStr = offer.dropDistanceKm?.let { ", Drop: ${String.format(Locale.US, "%.1f", it)}km" } ?: ""
                "MATCHED: Fare ₹${String.format(Locale.US, "%.1f", offer.totalFare)}$breakdownStr >= ₹$minFare and Pickup ${String.format(Locale.US, "%.1f", offer.pickupDistanceKm!!)}km <= ${maxPickupDistance}km$dropStr"
            }
            !passesFare -> {
                "REJECTED: Fare ₹${String.format(Locale.US, "%.1f", offer.totalFare)}$breakdownStr < Min Limit ₹$minFare"
            }
            offer.pickupDistanceKm == null -> {
                "REJECTED: Pickup distance could not be determined"
            }
            else -> {
                "REJECTED: Pickup distance ${String.format(Locale.US, "%.1f", offer.pickupDistanceKm)}km > Max Limit ${maxPickupDistance}km"
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

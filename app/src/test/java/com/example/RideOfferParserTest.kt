package com.example

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.repository.AppConfigRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RideOfferParserTest {

    @After
    fun tearDown() {
        AppConfigRepository.applyAppConfigFromMap(null, persistToCache = false)
        RapidoAccessibilityService.clearSanitizedUiSnapshotForTesting()
    }

    @Test
    fun `test field identification helpers`() {
        // Fare detection
        assertTrue(RideOfferParser.isFareNode("₹110", ""))
        assertTrue(RideOfferParser.isFareNode("Trip Fare: ₹55", ""))
        assertTrue(RideOfferParser.isFareNode("Rs. 120", ""))
        assertTrue(RideOfferParser.isFareNode("150", "com.rapido.rider:id/fare_amount"))
        assertFalse(RideOfferParser.isFareNode("Pickup 1.2 km", ""))

        // Pickup detection
        assertTrue(RideOfferParser.isPickupNode("Pickup 1.2 km away", ""))
        assertTrue(RideOfferParser.isPickupNode("0.8 km away", ""))
        assertTrue(RideOfferParser.isPickupNode("1.5 km", "com.rapido.rider:id/pickup_distance"))
        assertFalse(RideOfferParser.isPickupNode("Drop 4.5 km", ""))
        assertFalse(RideOfferParser.isPickupNode("₹120", ""))

        // Drop detection
        assertTrue(RideOfferParser.isDropNode("Drop 4.5 km", ""))
        assertTrue(RideOfferParser.isDropNode("Destination 6.0 km", ""))
        assertTrue(RideOfferParser.isDropNode("3.0 km", "com.rapido.rider:id/drop_distance"))
        assertFalse(RideOfferParser.isDropNode("Pickup 1.2 km", ""))
    }

    @Test
    fun `test fare and distance value extraction`() {
        val (fare1, _) = RideOfferParser.extractFareValue("₹145")
        assertEquals(145.0, fare1 ?: 0.0, 0.01)

        val (fare2, breakdown) = RideOfferParser.extractFareValue("₹56 + ₹13")
        assertEquals(69.0, fare2 ?: 0.0, 0.01)
        assertEquals(2, breakdown.size)

        val dist1 = RideOfferParser.extractDistanceValue("1.5 km")
        assertEquals(1.5, dist1 ?: 0.0, 0.01)

        val dist2 = RideOfferParser.extractDistanceValue("800 m")
        assertEquals(0.8, dist2 ?: 0.0, 0.01)

        val dist3 = RideOfferParser.extractDistanceValue("Pickup: 2.8 km away")
        assertEquals(2.8, dist3 ?: 0.0, 0.01)

        val distNull = RideOfferParser.extractDistanceValue("No distance here")
        assertNull(distNull)
    }

    @Test
    fun `test single card container parsing with strict field extraction`() {
        @Suppress("DEPRECATION")
        val card = AccessibilityNodeInfo.obtain()
        card.className = "androidx.cardview.widget.CardView"
        card.viewIdResourceName = "com.rapido.rider:id/order_card"
        card.setBoundsInScreen(Rect(50, 100, 1030, 600))

        @Suppress("DEPRECATION")
        val fareNode = AccessibilityNodeInfo.obtain()
        fareNode.text = "₹110"
        fareNode.viewIdResourceName = "com.rapido.rider:id/tv_fare"

        @Suppress("DEPRECATION")
        val pickupNode = AccessibilityNodeInfo.obtain()
        pickupNode.text = "Pickup 1.2 km"
        pickupNode.viewIdResourceName = "com.rapido.rider:id/tv_pickup_dist"

        @Suppress("DEPRECATION")
        val dropNode = AccessibilityNodeInfo.obtain()
        dropNode.text = "Drop 4.5 km"
        dropNode.viewIdResourceName = "com.rapido.rider:id/tv_drop_dist"

        @Suppress("DEPRECATION")
        val acceptBtn = AccessibilityNodeInfo.obtain()
        acceptBtn.text = "Accept"
        acceptBtn.isClickable = true
        acceptBtn.isEnabled = true
        acceptBtn.viewIdResourceName = "com.rapido.rider:id/btn_accept"

        org.robolectric.Shadows.shadowOf(card).addChild(fareNode)
        org.robolectric.Shadows.shadowOf(card).addChild(pickupNode)
        org.robolectric.Shadows.shadowOf(card).addChild(dropNode)
        org.robolectric.Shadows.shadowOf(card).addChild(acceptBtn)

        val parsedOffer = RideOfferParser.parseCardContainer(card)
        assertNotNull(parsedOffer)
        assertEquals("₹110", parsedOffer?.fareText)
        assertEquals("Pickup 1.2 km", parsedOffer?.pickupText)
        assertEquals("Drop 4.5 km", parsedOffer?.dropText)
        assertEquals(110.0, parsedOffer?.totalFare ?: 0.0, 0.01)
        assertEquals(1.2, parsedOffer?.pickupDistanceKm ?: 0.0, 0.01)
        assertEquals(4.5, parsedOffer?.dropDistanceKm ?: 0.0, 0.01)
        assertTrue(parsedOffer?.hasAcceptButton == true)
        assertNotNull(parsedOffer?.acceptButton)
    }

    @Test
    fun `test card level isolation prevents mixing between two cards`() {
        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain()

        // Card A: Fare ₹120, Pickup 1.5 km
        @Suppress("DEPRECATION")
        val cardA = AccessibilityNodeInfo.obtain()
        cardA.viewIdResourceName = "com.rapido.rider:id/order_card_container"
        cardA.setBoundsInScreen(Rect(50, 100, 1030, 500))

        @Suppress("DEPRECATION")
        val fareA = AccessibilityNodeInfo.obtain()
        fareA.text = "₹120"
        @Suppress("DEPRECATION")
        val pickupA = AccessibilityNodeInfo.obtain()
        pickupA.text = "Pickup 1.5 km away"
        @Suppress("DEPRECATION")
        val acceptA = AccessibilityNodeInfo.obtain()
        acceptA.text = "Accept"
        acceptA.isClickable = true
        acceptA.isEnabled = true

        org.robolectric.Shadows.shadowOf(cardA).addChild(fareA)
        org.robolectric.Shadows.shadowOf(cardA).addChild(pickupA)
        org.robolectric.Shadows.shadowOf(cardA).addChild(acceptA)

        // Card B: Fare ₹75, Pickup 3.2 km
        @Suppress("DEPRECATION")
        val cardB = AccessibilityNodeInfo.obtain()
        cardB.viewIdResourceName = "com.rapido.rider:id/order_card_container"
        cardB.setBoundsInScreen(Rect(50, 550, 1030, 950))

        @Suppress("DEPRECATION")
        val fareB = AccessibilityNodeInfo.obtain()
        fareB.text = "₹75"
        @Suppress("DEPRECATION")
        val pickupB = AccessibilityNodeInfo.obtain()
        pickupB.text = "Pickup 3.2 km away"
        @Suppress("DEPRECATION")
        val acceptB = AccessibilityNodeInfo.obtain()
        acceptB.text = "Accept"
        acceptB.isClickable = true
        acceptB.isEnabled = true

        org.robolectric.Shadows.shadowOf(cardB).addChild(fareB)
        org.robolectric.Shadows.shadowOf(cardB).addChild(pickupB)
        org.robolectric.Shadows.shadowOf(cardB).addChild(acceptB)

        org.robolectric.Shadows.shadowOf(root).addChild(cardA)
        org.robolectric.Shadows.shadowOf(root).addChild(cardB)

        val cards = RideOfferParser.parseCards(listOf(root))
        assertEquals(2, cards.size)

        val offerA = cards[0]
        val offerB = cards[1]

        // Strict layout isolation verification:
        // Card A must strictly have its own fare and pickup, and NOT have Card B's values
        assertEquals("₹120", offerA.fareText)
        assertEquals("Pickup 1.5 km away", offerA.pickupText)
        assertEquals(120.0, offerA.totalFare ?: 0.0, 0.01)
        assertEquals(1.5, offerA.pickupDistanceKm ?: 0.0, 0.01)
        assertFalse(offerA.fareText.contains("75"))
        assertFalse(offerA.pickupText.contains("3.2"))

        // Card B must strictly have its own fare and pickup, and NOT have Card A's values
        assertEquals("₹75", offerB.fareText)
        assertEquals("Pickup 3.2 km away", offerB.pickupText)
        assertEquals(75.0, offerB.totalFare ?: 0.0, 0.01)
        assertEquals(3.2, offerB.pickupDistanceKm ?: 0.0, 0.01)
        assertFalse(offerB.fareText.contains("120"))
        assertFalse(offerB.pickupText.contains("1.5"))
    }

    @Test
    fun `test Indian Rupee variants and prefix suffix extraction`() {
        val testCases = listOf(
            "₹95" to 95.0,
            "\u20B9120" to 120.0,
            "\u20A880" to 80.0,
            "Rs. 150" to 150.0,
            "Rs 200" to 200.0,
            "INR 350" to 350.0,
            "75 ₹" to 75.0,
            "110 Rs" to 110.0,
            "140 INR" to 140.0
        )

        for ((text, expected) in testCases) {
            assertTrue("Expected isFareNode to be true for '$text'", RideOfferParser.isFareNode(text, ""))
            val (fareVal, _) = RideOfferParser.extractFareValue(text)
            assertEquals("Expected fare value $expected for '$text'", expected, fareVal ?: 0.0, 0.01)

            val (engineFare, _) = TextAnalysisEngine.extractCurrencies(text)
            assertEquals("Expected TextAnalysisEngine fare $expected for '$text'", expected, engineFare ?: 0.0, 0.01)
        }
    }

    @Test
    fun `test RecyclerView container holding multiple distinct cards does not merge`() {
        @Suppress("DEPRECATION")
        val recyclerView = AccessibilityNodeInfo.obtain()
        recyclerView.className = "androidx.recyclerview.widget.RecyclerView"
        recyclerView.viewIdResourceName = "com.rapido.rider:id/orders_recycler_view"
        recyclerView.setBoundsInScreen(Rect(0, 0, 1080, 1920))

        // Card 1
        @Suppress("DEPRECATION")
        val card1 = AccessibilityNodeInfo.obtain()
        card1.className = "androidx.cardview.widget.CardView"
        card1.viewIdResourceName = "com.rapido.rider:id/order_card_1"
        card1.setBoundsInScreen(Rect(50, 100, 1030, 500))

        @Suppress("DEPRECATION")
        val fare1 = AccessibilityNodeInfo.obtain()
        fare1.text = "₹50"
        @Suppress("DEPRECATION")
        val pickup1 = AccessibilityNodeInfo.obtain()
        pickup1.text = "Pickup 1.0 km"
        @Suppress("DEPRECATION")
        val accept1 = AccessibilityNodeInfo.obtain()
        accept1.text = "Accept"
        accept1.isClickable = true
        accept1.isEnabled = true

        org.robolectric.Shadows.shadowOf(card1).addChild(fare1)
        org.robolectric.Shadows.shadowOf(card1).addChild(pickup1)
        org.robolectric.Shadows.shadowOf(card1).addChild(accept1)

        // Card 2
        @Suppress("DEPRECATION")
        val card2 = AccessibilityNodeInfo.obtain()
        card2.className = "androidx.cardview.widget.CardView"
        card2.viewIdResourceName = "com.rapido.rider:id/order_card_2"
        card2.setBoundsInScreen(Rect(50, 550, 1030, 950))

        @Suppress("DEPRECATION")
        val fare2 = AccessibilityNodeInfo.obtain()
        fare2.text = "₹120"
        @Suppress("DEPRECATION")
        val pickup2 = AccessibilityNodeInfo.obtain()
        pickup2.text = "Pickup 3.5 km"
        @Suppress("DEPRECATION")
        val accept2 = AccessibilityNodeInfo.obtain()
        accept2.text = "Accept"
        accept2.isClickable = true
        accept2.isEnabled = true

        org.robolectric.Shadows.shadowOf(card2).addChild(fare2)
        org.robolectric.Shadows.shadowOf(card2).addChild(pickup2)
        org.robolectric.Shadows.shadowOf(card2).addChild(accept2)

        org.robolectric.Shadows.shadowOf(recyclerView).addChild(card1)
        org.robolectric.Shadows.shadowOf(recyclerView).addChild(card2)

        // findCardContainers must find the 2 child cards, NOT treat the RecyclerView as a single card!
        val containers = RideOfferParser.findCardContainers(recyclerView)
        assertEquals(2, containers.size)

        val parsedCards = RideOfferParser.parseCards(listOf(recyclerView))
        assertEquals(2, parsedCards.size)

        assertEquals("₹50", parsedCards[0].fareText)
        assertEquals("Pickup 1.0 km", parsedCards[0].pickupText)
        assertEquals(50.0, parsedCards[0].totalFare ?: 0.0, 0.01)
        assertEquals(1.0, parsedCards[0].pickupDistanceKm ?: 0.0, 0.01)

        assertEquals("₹120", parsedCards[1].fareText)
        assertEquals("Pickup 3.5 km", parsedCards[1].pickupText)
        assertEquals(120.0, parsedCards[1].totalFare ?: 0.0, 0.01)
        assertEquals(3.5, parsedCards[1].pickupDistanceKm ?: 0.0, 0.01)
    }

    @Test
    fun `test accept regex and chat notification filter`() {
        val validAcceptActions = listOf(
            "Accept",
            "ACCEPT",
            "Accept Order",
            "Accept Ride",
            "Swipe to accept",
            "Take Ride",
            "Go",
            "स्वीकार करें",
            "Auto-Accept"
        )
        for (action in validAcceptActions) {
            assertTrue("Expected ACCEPT_REGEX match for '$action'", RapidoAccessibilityService.ACCEPT_REGEX.containsMatchIn(action))
            assertFalse("Expected CHAT_OR_NOTIFICATION_REGEX not to match '$action'", RapidoAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(action))
        }

        val chatAndNoiseMessages = listOf(
            "can you please accept bhaiya",
            "bhaiya ride jaldi karo please",
            "type a message",
            "new message from customer",
            "please hurry up sir",
            "order delivered successfully"
        )
        for (msg in chatAndNoiseMessages) {
            assertTrue("Expected CHAT_OR_NOTIFICATION_REGEX to match '$msg'", RapidoAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(msg))
        }
    }

    @Test
    fun `test boosted primary fare overrides strikethrough old fare inside card`() {
        @Suppress("DEPRECATION")
        val card = AccessibilityNodeInfo.obtain()
        card.className = "androidx.cardview.widget.CardView"
        card.viewIdResourceName = "com.rapido.rider:id/order_card"
        card.setBoundsInScreen(Rect(50, 100, 1030, 600))

        @Suppress("DEPRECATION")
        val oldFareNode = AccessibilityNodeInfo.obtain()
        oldFareNode.text = "₹45"
        oldFareNode.viewIdResourceName = "com.rapido.rider:id/tv_old_strike_fare"

        @Suppress("DEPRECATION")
        val boostedFareNode = AccessibilityNodeInfo.obtain()
        boostedFareNode.text = "₹ 57 AUTO"
        boostedFareNode.viewIdResourceName = "com.rapido.rider:id/tv_fare"

        @Suppress("DEPRECATION")
        val pickupNode = AccessibilityNodeInfo.obtain()
        pickupNode.text = "Pickup 1.1 km"

        @Suppress("DEPRECATION")
        val acceptBtn = AccessibilityNodeInfo.obtain()
        acceptBtn.text = "Accept"
        acceptBtn.isClickable = true
        acceptBtn.isEnabled = true

        org.robolectric.Shadows.shadowOf(card).addChild(oldFareNode)
        org.robolectric.Shadows.shadowOf(card).addChild(boostedFareNode)
        org.robolectric.Shadows.shadowOf(card).addChild(pickupNode)
        org.robolectric.Shadows.shadowOf(card).addChild(acceptBtn)

        val parsedOffer = RideOfferParser.parseCardContainer(card)
        assertNotNull(parsedOffer)
        assertEquals(57.0, parsedOffer?.totalFare ?: 0.0, 0.01)
    }

    @Test
    fun `test simultaneous cards with identical fare and pickup distance are NOT deduplicated`() {
        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain()

        fun buildCard(top: Int, bottom: Int, dropText: String): AccessibilityNodeInfo {
            @Suppress("DEPRECATION")
            val card = AccessibilityNodeInfo.obtain()
            card.className = "androidx.cardview.widget.CardView"
            card.viewIdResourceName = "com.rapido.rider:id/order_card"
            card.setBoundsInScreen(Rect(50, top, 1030, bottom))

            @Suppress("DEPRECATION")
            val fare = AccessibilityNodeInfo.obtain()
            fare.text = "₹95"
            @Suppress("DEPRECATION")
            val pickup = AccessibilityNodeInfo.obtain()
            pickup.text = "Pickup 1.2 km"
            @Suppress("DEPRECATION")
            val drop = AccessibilityNodeInfo.obtain()
            drop.text = dropText
            @Suppress("DEPRECATION")
            val accept = AccessibilityNodeInfo.obtain()
            accept.text = "Accept"
            accept.isClickable = true
            accept.isEnabled = true

            org.robolectric.Shadows.shadowOf(card).addChild(fare)
            org.robolectric.Shadows.shadowOf(card).addChild(pickup)
            org.robolectric.Shadows.shadowOf(card).addChild(drop)
            org.robolectric.Shadows.shadowOf(card).addChild(accept)
            return card
        }

        val card1 = buildCard(100, 450, "Drop 3.4 km")
        val card2 = buildCard(500, 850, "Drop 6.8 km")

        org.robolectric.Shadows.shadowOf(root).addChild(card1)
        org.robolectric.Shadows.shadowOf(root).addChild(card2)

        val offers = RideOfferParser.parseCards(listOf(root))
        assertEquals(2, offers.size)
        assertEquals(3.4, offers[0].dropDistanceKm ?: 0.0, 0.01)
        assertEquals(6.8, offers[1].dropDistanceKm ?: 0.0, 0.01)
    }

    @Test
    fun `test drop_location node does not overwrite extracted drop distance`() {
        assertFalse(RideOfferParser.isDropNode("Sector 14 Market, Near Bus Stand", "com.rapido.rider:id/drop_location"))
        assertTrue(RideOfferParser.isDropNode("4.2 km", "com.rapido.rider:id/drop_distance"))

        @Suppress("DEPRECATION")
        val card = AccessibilityNodeInfo.obtain()
        card.className = "androidx.cardview.widget.CardView"
        card.setBoundsInScreen(Rect(50, 100, 1030, 600))

        @Suppress("DEPRECATION")
        val fare = AccessibilityNodeInfo.obtain()
        fare.text = "₹140"
        @Suppress("DEPRECATION")
        val pickup = AccessibilityNodeInfo.obtain()
        pickup.text = "1.0 km"
        pickup.viewIdResourceName = "com.rapido.rider:id/pickup_distance"
        @Suppress("DEPRECATION")
        val dropDist = AccessibilityNodeInfo.obtain()
        dropDist.text = "5.5 km"
        dropDist.viewIdResourceName = "com.rapido.rider:id/drop_distance"
        @Suppress("DEPRECATION")
        val dropAddr = AccessibilityNodeInfo.obtain()
        dropAddr.text = "10 m from Main Gate, Sir C.V. Raman Road"
        dropAddr.viewIdResourceName = "com.rapido.rider:id/drop_location"
        @Suppress("DEPRECATION")
        val accept = AccessibilityNodeInfo.obtain()
        accept.text = "Accept"
        accept.isClickable = true
        accept.isEnabled = true

        org.robolectric.Shadows.shadowOf(card).addChild(fare)
        org.robolectric.Shadows.shadowOf(card).addChild(pickup)
        org.robolectric.Shadows.shadowOf(card).addChild(dropDist)
        org.robolectric.Shadows.shadowOf(card).addChild(dropAddr)
        org.robolectric.Shadows.shadowOf(card).addChild(accept)

        val parsed = RideOfferParser.parseCardContainer(card)
        assertNotNull(parsed)
        assertEquals("5.5 km", parsed?.dropText)
        assertEquals(5.5, parsed?.dropDistanceKm ?: 0.0, 0.01)
    }

    @Test
    fun `test offer events containing Sir or Bhai in location pass prefilter while pure chat drops`() {
        val service = RapidoAccessibilityService()

        @Suppress("DEPRECATION")
        val offerEvent = android.view.accessibility.AccessibilityEvent.obtain(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        offerEvent.packageName = RapidoAccessibilityService.TARGET_PACKAGE
        offerEvent.className = "android.widget.FrameLayout"
        offerEvent.text.add("₹120 • 1.2 km • Pickup Sir C.V. Raman Nagar, Drop Bhai Parmanand Colony!")

        assertTrue(
            "Offer event with fare/km signals must NOT be dropped when location contains Sir/Bhai/!",
            service.isPotentialOfferEvent(offerEvent)
        )

        @Suppress("DEPRECATION")
        val chatEvent = android.view.accessibility.AccessibilityEvent.obtain(
            android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
        chatEvent.packageName = RapidoAccessibilityService.TARGET_PACKAGE
        chatEvent.className = "android.widget.FrameLayout"
        chatEvent.text.add("Bhaiya jaldi aao please sir?")

        assertFalse(
            "Pure chat notification without offer signal must still be dropped",
            service.isPotentialOfferEvent(chatEvent)
        )
    }

    @Test
    fun `test base plus tip fare summing ignores customer added extra badge without double counting`() {
        // Direct inline base + tip expressions
        val (sum1, parts1) = RideOfferParser.extractFareValue("₹55 + ₹10")
        assertEquals(65.0, sum1 ?: 0.0, 0.01)
        assertEquals(listOf(55.0, 10.0), parts1)

        val (sum2, parts2) = RideOfferParser.extractFareValue("₹56 + ₹13")
        assertEquals(69.0, sum2 ?: 0.0, 0.01)
        assertEquals(listOf(56.0, 13.0), parts2)

        // Tip badge noise must NEVER be treated as a fare node or extract a fare value
        assertTrue(TextAnalysisEngine.isTipBadgeNoise("Customer added ₹10.0 extra"))
        assertFalse(RideOfferParser.isFareNode("Customer added ₹10.0 extra", ""))
        assertNull(RideOfferParser.extractFareValue("Customer added ₹10.0 extra").first)

        // TextAnalysisEngine combined text must sum ₹55 + ₹10 = ₹65 and NOT double count ₹10.0 from badge
        val (engineFare, _) = TextAnalysisEngine.extractCurrencies("₹55 + ₹10  Customer added ₹10.0 extra")
        assertEquals(65.0, engineFare ?: 0.0, 0.01)

        // Full card containing both "₹55 + ₹10" AND "Customer added ₹10.0 extra" badge
        @Suppress("DEPRECATION")
        val card = AccessibilityNodeInfo.obtain()
        card.className = "androidx.cardview.widget.CardView"
        card.viewIdResourceName = "com.rapido.rider:id/order_card"
        card.setBoundsInScreen(Rect(240, 120, 1040, 620))

        @Suppress("DEPRECATION")
        val fareNode = AccessibilityNodeInfo.obtain()
        fareNode.text = "₹55 + ₹10"
        fareNode.setBoundsInScreen(Rect(260, 140, 560, 210))

        @Suppress("DEPRECATION")
        val tipBadgeNode = AccessibilityNodeInfo.obtain()
        tipBadgeNode.text = "Customer added ₹10.0 extra"
        tipBadgeNode.setBoundsInScreen(Rect(260, 215, 760, 260))

        @Suppress("DEPRECATION")
        val pickupNode = AccessibilityNodeInfo.obtain()
        pickupNode.text = "Pickup 0.9 km"
        pickupNode.setBoundsInScreen(Rect(260, 270, 560, 310))

        @Suppress("DEPRECATION")
        val dropNode = AccessibilityNodeInfo.obtain()
        dropNode.text = "Drop 3.8 km"
        dropNode.setBoundsInScreen(Rect(260, 320, 560, 360))

        @Suppress("DEPRECATION")
        val dropLocNode = AccessibilityNodeInfo.obtain()
        dropLocNode.text = "Koramangala 4th Block"
        dropLocNode.viewIdResourceName = "com.rapido.rider:id/drop_location"
        dropLocNode.setBoundsInScreen(Rect(260, 370, 880, 420))

        @Suppress("DEPRECATION")
        val acceptBtn = AccessibilityNodeInfo.obtain()
        acceptBtn.text = "Accept"
        acceptBtn.isClickable = true
        acceptBtn.isEnabled = true
        acceptBtn.setBoundsInScreen(Rect(280, 500, 1000, 600))

        org.robolectric.Shadows.shadowOf(card).addChild(fareNode)
        org.robolectric.Shadows.shadowOf(card).addChild(tipBadgeNode)
        org.robolectric.Shadows.shadowOf(card).addChild(pickupNode)
        org.robolectric.Shadows.shadowOf(card).addChild(dropNode)
        org.robolectric.Shadows.shadowOf(card).addChild(dropLocNode)
        org.robolectric.Shadows.shadowOf(card).addChild(acceptBtn)

        val parsed = RideOfferParser.parseCardContainer(card, screenWidthPx = 1080)
        assertNotNull(parsed)
        assertEquals(65.0, parsed?.totalFare ?: 0.0, 0.01)
        assertEquals(0.9, parsed?.pickupDistanceKm ?: 0.0, 0.01)
        assertEquals(3.8, parsed?.dropDistanceKm ?: 0.0, 0.01)
        assertEquals("Koramangala 4th Block", parsed?.dropLocation)
        assertFalse(parsed?.dropLocation?.contains("Customer added", ignoreCase = true) == true)

        // Split sibling nodes: "₹55" and "+ ₹10" alongside tip badge -> strictly 65.0
        val splitOffer = TextAnalysisEngine.parseTargetOfferFromNodes(
            listOf("₹55", "+ ₹10", "Customer added ₹10.0 extra", "Pickup 1.2 km", "Drop 4.0 km", "Accept")
        )
        assertNotNull(splitOffer)
        assertEquals(65.0, splitOffer?.fare ?: 0.0, 0.01)
    }

    @Test
    fun `test 3 orders screen ignores left mini-tab sidebar and isolates each card accept button`() {
        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain()
        root.setBoundsInScreen(Rect(0, 0, 1080, 2400))

        // Narrow left-side mini-tab strip (x = 0..190 < 22% of 1080px = 237px)
        @Suppress("DEPRECATION")
        val leftSidebar = AccessibilityNodeInfo.obtain()
        leftSidebar.className = "android.widget.LinearLayout"
        leftSidebar.viewIdResourceName = "com.rapido.rider:id/order_tabs_strip"
        leftSidebar.setBoundsInScreen(Rect(0, 150, 190, 1100))

        listOf("₹42" to 200, "₹80" to 420, "₹65" to 640).forEach { (tabPrice, topY) ->
            @Suppress("DEPRECATION")
            val tabItem = AccessibilityNodeInfo.obtain()
            tabItem.text = tabPrice
            tabItem.isClickable = true
            tabItem.setBoundsInScreen(Rect(12, topY, 180, topY + 140))
            org.robolectric.Shadows.shadowOf(leftSidebar).addChild(tabItem)
        }
        org.robolectric.Shadows.shadowOf(root).addChild(leftSidebar)

        // Right-side RecyclerView with 3 isolated order cards
        @Suppress("DEPRECATION")
        val listContainer = AccessibilityNodeInfo.obtain()
        listContainer.className = "androidx.recyclerview.widget.RecyclerView"
        listContainer.setBoundsInScreen(Rect(220, 150, 1060, 2200))

        fun buildOrderCard(
            topY: Int,
            fareStr: String,
            tipBadge: String?,
            pickupStr: String,
            dropStr: String,
            dropLocality: String
        ): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo> {
            @Suppress("DEPRECATION")
            val card = AccessibilityNodeInfo.obtain()
            card.className = "androidx.cardview.widget.CardView"
            card.viewIdResourceName = "com.rapido.rider:id/order_card"
            card.setBoundsInScreen(Rect(240, topY, 1040, topY + 520))

            @Suppress("DEPRECATION")
            val fare = AccessibilityNodeInfo.obtain()
            fare.text = fareStr
            fare.setBoundsInScreen(Rect(260, topY + 20, 560, topY + 80))

            @Suppress("DEPRECATION")
            val pickup = AccessibilityNodeInfo.obtain()
            pickup.text = pickupStr
            pickup.setBoundsInScreen(Rect(260, topY + 100, 560, topY + 150))

            @Suppress("DEPRECATION")
            val drop = AccessibilityNodeInfo.obtain()
            drop.text = dropStr
            drop.setBoundsInScreen(Rect(260, topY + 160, 560, topY + 210))

            @Suppress("DEPRECATION")
            val dropLoc = AccessibilityNodeInfo.obtain()
            dropLoc.text = dropLocality
            dropLoc.viewIdResourceName = "com.rapido.rider:id/drop_location"
            dropLoc.setBoundsInScreen(Rect(260, topY + 220, 960, topY + 280))

            @Suppress("DEPRECATION")
            val accept = AccessibilityNodeInfo.obtain()
            accept.text = "Accept"
            accept.isClickable = true
            accept.isEnabled = true
            accept.setBoundsInScreen(Rect(270, topY + 390, 1010, topY + 490))

            org.robolectric.Shadows.shadowOf(card).addChild(fare)
            if (tipBadge != null) {
                @Suppress("DEPRECATION")
                val badge = AccessibilityNodeInfo.obtain()
                badge.text = tipBadge
                badge.setBoundsInScreen(Rect(570, topY + 20, 1000, topY + 80))
                org.robolectric.Shadows.shadowOf(card).addChild(badge)
            }
            org.robolectric.Shadows.shadowOf(card).addChild(pickup)
            org.robolectric.Shadows.shadowOf(card).addChild(drop)
            org.robolectric.Shadows.shadowOf(card).addChild(dropLoc)
            org.robolectric.Shadows.shadowOf(card).addChild(accept)
            return card to accept
        }

        val (card1, accept1) = buildOrderCard(160, "₹42", null, "Pickup 0.8 km", "Drop 2.1 km", "BTM Layout")
        val (card2, accept2) = buildOrderCard(720, "₹80", null, "Pickup 1.1 km", "Drop 5.0 km", "Kempegowda Airport Road")
        val (card3, accept3) = buildOrderCard(
            1280,
            "₹55 + ₹10",
            "Customer added ₹10.0 extra",
            "Pickup 1.0 km",
            "Drop 3.2 km",
            "Indiranagar 100 Feet Road"
        )

        org.robolectric.Shadows.shadowOf(listContainer).addChild(card1)
        org.robolectric.Shadows.shadowOf(listContainer).addChild(card2)
        org.robolectric.Shadows.shadowOf(listContainer).addChild(card3)
        org.robolectric.Shadows.shadowOf(root).addChild(listContainer)

        val offers = RideOfferParser.parseCards(listOf(root), screenWidthPx = 1080)
        assertEquals("Left mini-tab sidebar must be ignored leaving strictly 3 cards", 3, offers.size)

        assertEquals(42.0, offers[0].totalFare ?: 0.0, 0.01)
        assertEquals(80.0, offers[1].totalFare ?: 0.0, 0.01)
        assertEquals("Kempegowda Airport Road", offers[1].dropLocation)
        assertEquals(65.0, offers[2].totalFare ?: 0.0, 0.01)
        assertEquals("Indiranagar 100 Feet Road", offers[2].dropLocation)

        // Simulate filter evaluation: minFare=60, blacklisted drop="airport"
        val blacklistedDrop = "airport"
        val matching = offers.filter { offer ->
            val fareOk = (offer.totalFare ?: 0.0) >= 60.0
            val dropOk = !offer.dropLocation.contains(blacklistedDrop, ignoreCase = true)
            fareOk && dropOk
        }
        assertEquals("Only Card 3 should match filters", 1, matching.size)
        val matchedCard = matching.first()
        assertEquals(65.0, matchedCard.totalFare ?: 0.0, 0.01)

        // Verify accept button belongs strictly to Card 3's bounds (NOT Card 1 or Card 2)
        val matchedAcceptBounds = Rect()
        matchedCard.acceptButton?.getBoundsInScreen(matchedAcceptBounds)
        val card3Bounds = Rect()
        card3.getBoundsInScreen(card3Bounds)
        val accept3Bounds = Rect()
        accept3.getBoundsInScreen(accept3Bounds)
        val accept1Bounds = Rect()
        accept1.getBoundsInScreen(accept1Bounds)
        val accept2Bounds = Rect()
        accept2.getBoundsInScreen(accept2Bounds)

        assertEquals(accept3Bounds, matchedAcceptBounds)
        assertTrue("Matched Accept button must reside inside Card 3 bounds", card3Bounds.contains(matchedAcceptBounds))
        assertFalse(matchedAcceptBounds == accept1Bounds)
        assertFalse(matchedAcceptBounds == accept2Bounds)
    }

    @Test
    fun `test see more orders node detection for floating popup expansion`() {
        assertTrue(RideOfferParser.isSeeMoreOrdersText("See 1 more orders >"))
        assertTrue(RideOfferParser.isSeeMoreOrdersText("See 2 more order"))
        assertTrue(RideOfferParser.isSeeMoreOrdersText("View 3 more orders"))
        assertFalse(RideOfferParser.isSeeMoreOrdersText("Accept Order"))

        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain()
        root.setBoundsInScreen(Rect(0, 0, 1080, 2400))

        @Suppress("DEPRECATION")
        val expandBtn = AccessibilityNodeInfo.obtain()
        expandBtn.text = "See 1 more orders >"
        expandBtn.isClickable = true
        expandBtn.isEnabled = true
        expandBtn.setBoundsInScreen(Rect(260, 1800, 820, 1900))
        org.robolectric.Shadows.shadowOf(root).addChild(expandBtn)

        val found = RideOfferParser.findSeeMoreOrdersNode(root, screenWidthPx = 1080)
        assertNotNull("Expected to find clickable 'See 1 more orders >' node", found)
        assertEquals("See 1 more orders >", found?.text?.toString())
    }

    @Test
    fun `test remote rapido regex and accept keywords work and malformed regex falls back safely`() {
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoAcceptButtonKeywords" to listOf("Tap To Grab", "Quick Accept"),
                "rapidoFareRegex" to """PAYOUT\s*:\s*(\d+(?:\.\d{1,2})?)""",
                "rapidoDistanceRegex" to """DIST\s*:\s*(\d+(?:\.\d{1,2})?)\s*(km|m)"""
            )
        )

        val (customFare, _) = RideOfferParser.extractFareValue("PAYOUT: 92")
        assertEquals(92.0, customFare ?: 0.0, 0.01)
        val customDist = RideOfferParser.extractDistanceValue("DIST: 3.4 km")
        assertEquals(3.4, customDist ?: 0.0, 0.01)
        assertTrue(TextAnalysisEngine.hasAcceptAction("Tap To Grab"))

        // Malformed remote regex must fall back seamlessly to built-in ₹ and km parsing
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoFareRegex" to "[broken((",
                "rapidoDistanceRegex" to "((invalid"
            )
        )
        val (fallbackFare, _) = RideOfferParser.extractFareValue("₹55 + ₹10")
        assertEquals(65.0, fallbackFare ?: 0.0, 0.01)
        val fallbackDist = RideOfferParser.extractDistanceValue("2.5 km")
        assertEquals(2.5, fallbackDist ?: 0.0, 0.01)
    }

    @Test
    fun `test viewIdResourceName-first matching accepts regional Gujarati Hindi Tamil buttons and excludes reject buttons`() {
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoAcceptViewIds" to "btn_accept,btn_accept_order,accept_button",
                "rapidoRejectKeywords" to "Reject,Decline,Skip,Pass,Dismiss"
            ),
            persistToCache = false
        )

        // Reject keywords must NEVER be misidentified as Accept actions
        listOf("Reject", "Decline", "Skip", "Pass", "Dismiss", "Skip Ride").forEach { rejectLabel ->
            assertTrue("Expected isRejectAction=true for '$rejectLabel'", RideOfferParser.isRejectAction(rejectLabel))
            assertFalse("Expected isAcceptAction=false for '$rejectLabel'", RideOfferParser.isAcceptAction(rejectLabel))
            assertFalse("Expected TextAnalysisEngine.hasAcceptAction=false for '$rejectLabel'", TextAnalysisEngine.hasAcceptAction(rejectLabel))
        }

        // Regional labels (Gujarati, Hindi, Tamil) backed by com.rapido.rider:id/btn_accept_order
        // must match strictly by viewIdResourceName while skipping Reject/Dismiss buttons placed before them.
        listOf("સ્વીકારો", "स्वीकार करें", "ஏற்றுக்கொள்").forEach { localizedAcceptLabel ->
            @Suppress("DEPRECATION")
            val card = AccessibilityNodeInfo.obtain()
            card.className = "androidx.cardview.widget.CardView"
            card.viewIdResourceName = "com.rapido.rider:id/order_card"
            card.setBoundsInScreen(Rect(240, 120, 1040, 620))

            @Suppress("DEPRECATION")
            val fare = AccessibilityNodeInfo.obtain()
            fare.text = "₹78"
            fare.setBoundsInScreen(Rect(260, 140, 500, 200))

            @Suppress("DEPRECATION")
            val pickup = AccessibilityNodeInfo.obtain()
            pickup.text = "Pickup 1.1 km"
            pickup.setBoundsInScreen(Rect(260, 210, 560, 260))

            // Reject button placed FIRST in tree with custom remote reject keyword "Dismiss"
            @Suppress("DEPRECATION")
            val rejectBtn = AccessibilityNodeInfo.obtain()
            rejectBtn.text = "Dismiss"
            rejectBtn.viewIdResourceName = "com.rapido.rider:id/btn_reject_order"
            rejectBtn.isClickable = true
            rejectBtn.isEnabled = true
            rejectBtn.setBoundsInScreen(Rect(260, 500, 520, 590))

            // Localized Accept button backed by authoritative viewIdResourceName
            @Suppress("DEPRECATION")
            val acceptBtn = AccessibilityNodeInfo.obtain()
            acceptBtn.text = localizedAcceptLabel
            acceptBtn.viewIdResourceName = "com.rapido.rider:id/btn_accept_order"
            acceptBtn.isClickable = true
            acceptBtn.isEnabled = true
            acceptBtn.setBoundsInScreen(Rect(550, 500, 1010, 590))

            org.robolectric.Shadows.shadowOf(card).addChild(fare)
            org.robolectric.Shadows.shadowOf(card).addChild(pickup)
            org.robolectric.Shadows.shadowOf(card).addChild(rejectBtn)
            org.robolectric.Shadows.shadowOf(card).addChild(acceptBtn)

            val parsed = RideOfferParser.parseCardContainer(card, screenWidthPx = 1080)
            assertNotNull("Card with localized '$localizedAcceptLabel' should parse", parsed)
            assertTrue("hasAcceptButton must be true for '$localizedAcceptLabel'", parsed?.hasAcceptButton == true)
            assertEquals(
                "Matched Accept button must be btn_accept_order (NOT btn_reject_order)",
                "com.rapido.rider:id/btn_accept_order",
                parsed?.acceptButton?.viewIdResourceName
            )
            assertEquals(localizedAcceptLabel, parsed?.acceptButton?.text?.toString())
        }
    }

    @Test
    fun `test dynamic rapidoLeftSidebarWidthRatio and rapidoSeeMoreOrdersKeywords`() {
        // With wider sidebar ratio (0.35 on 1080px -> cutoff 378px), a strip at x=0..340 is excluded
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoLeftSidebarWidthRatio" to 0.35,
                "rapidoSeeMoreOrdersKeywords" to "और ऑर्डर,extra rides"
            ),
            persistToCache = false
        )
        assertTrue(RideOfferParser.isLeftSidebarNode(Rect(0, 150, 340, 800), screenWidthPx = 1080))
        assertFalse(RideOfferParser.isLeftSidebarNode(Rect(400, 150, 1040, 800), screenWidthPx = 1080))
        assertTrue(RideOfferParser.isSeeMoreOrdersText("2 और ऑर्डर देखें"))
        assertTrue(RideOfferParser.isSeeMoreOrdersText("Tap for extra rides"))

        // Lowering sidebar ratio to 0.15 (cutoff 162px) includes x=180..340 as normal card area
        AppConfigRepository.applyAppConfigFromMap(
            mapOf("rapidoLeftSidebarWidthRatio" to 0.15),
            persistToCache = false
        )
        assertFalse(RideOfferParser.isLeftSidebarNode(Rect(180, 150, 340, 800), screenWidthPx = 1080))
        assertTrue(RideOfferParser.isLeftSidebarNode(Rect(0, 150, 150, 800), screenWidthPx = 1080))
    }

    @Test
    fun `test Report UI Issue sanitizer preserves View IDs bounds and button labels while redacting PII and addresses`() {
        @Suppress("DEPRECATION")
        val root = AccessibilityNodeInfo.obtain()
        root.className = "android.widget.FrameLayout"
        root.viewIdResourceName = "com.rapido.rider:id/order_card"
        root.setBoundsInScreen(Rect(240, 120, 1040, 620))

        @Suppress("DEPRECATION")
        val fareNode = AccessibilityNodeInfo.obtain()
        fareNode.className = "android.widget.TextView"
        fareNode.viewIdResourceName = "com.rapido.rider:id/tv_fare"
        fareNode.text = "₹55 + ₹10"
        fareNode.setBoundsInScreen(Rect(260, 140, 560, 200))

        @Suppress("DEPRECATION")
        val tipBadgeNode = AccessibilityNodeInfo.obtain()
        tipBadgeNode.className = "android.widget.TextView"
        tipBadgeNode.text = "Customer added ₹10.0 extra"
        tipBadgeNode.setBoundsInScreen(Rect(260, 205, 760, 245))

        @Suppress("DEPRECATION")
        val pickupAddrNode = AccessibilityNodeInfo.obtain()
        pickupAddrNode.className = "android.widget.TextView"
        pickupAddrNode.viewIdResourceName = "com.rapido.rider:id/pickup_location"
        pickupAddrNode.text = "Flat 4B, 22-24-2 Shree Ram Society, MG Road, Bengaluru 560001"
        pickupAddrNode.setBoundsInScreen(Rect(260, 260, 980, 320))

        @Suppress("DEPRECATION")
        val dropAddrNode = AccessibilityNodeInfo.obtain()
        dropAddrNode.className = "android.widget.TextView"
        dropAddrNode.viewIdResourceName = "com.rapido.rider:id/drop_location"
        dropAddrNode.text = "Koramangala 4th Block, Near Sony Signal"
        dropAddrNode.setBoundsInScreen(Rect(260, 330, 980, 390))

        @Suppress("DEPRECATION")
        val passengerNode = AccessibilityNodeInfo.obtain()
        passengerNode.className = "android.widget.TextView"
        passengerNode.viewIdResourceName = "com.rapido.rider:id/customer_name"
        passengerNode.text = "Rahul Sharma +91 9876543210"
        passengerNode.setBoundsInScreen(Rect(260, 400, 820, 445))

        @Suppress("DEPRECATION")
        val freeTextAddressNode = AccessibilityNodeInfo.obtain()
        freeTextAddressNode.className = "android.widget.TextView"
        freeTextAddressNode.text = "14, Indiranagar 100 Feet Road, 560038"
        freeTextAddressNode.isClickable = true
        freeTextAddressNode.setBoundsInScreen(Rect(260, 450, 960, 490))

        @Suppress("DEPRECATION")
        val rejectBtn = AccessibilityNodeInfo.obtain()
        rejectBtn.className = "android.widget.Button"
        rejectBtn.viewIdResourceName = "com.rapido.rider:id/btn_reject_order"
        rejectBtn.text = "Skip"
        rejectBtn.isClickable = true
        rejectBtn.isEnabled = true
        rejectBtn.setBoundsInScreen(Rect(260, 510, 520, 600))

        @Suppress("DEPRECATION")
        val acceptBtn = AccessibilityNodeInfo.obtain()
        acceptBtn.className = "android.widget.Button"
        acceptBtn.viewIdResourceName = "com.rapido.rider:id/btn_accept_order"
        acceptBtn.text = "સ્વીકારો"
        acceptBtn.isClickable = true
        acceptBtn.isEnabled = true
        acceptBtn.setBoundsInScreen(Rect(550, 510, 1010, 600))

        org.robolectric.Shadows.shadowOf(root).addChild(fareNode)
        org.robolectric.Shadows.shadowOf(root).addChild(tipBadgeNode)
        org.robolectric.Shadows.shadowOf(root).addChild(pickupAddrNode)
        org.robolectric.Shadows.shadowOf(root).addChild(dropAddrNode)
        org.robolectric.Shadows.shadowOf(root).addChild(passengerNode)
        org.robolectric.Shadows.shadowOf(root).addChild(freeTextAddressNode)
        org.robolectric.Shadows.shadowOf(root).addChild(rejectBtn)
        org.robolectric.Shadows.shadowOf(root).addChild(acceptBtn)

        val snapshotJson = RapidoAccessibilityService.captureSanitizedUiSnapshot(
            rootWindows = listOf(root),
            packageName = "com.rapido.rider",
            screenWidthPx = 1080,
            screenHeightPx = 2400
        )

        // Structural metadata, View IDs, bounds, and action button labels MUST be preserved
        assertTrue(snapshotJson.contains("com.rapido.rider:id/order_card"))
        assertTrue(snapshotJson.contains("com.rapido.rider:id/btn_accept_order"))
        assertTrue(snapshotJson.contains("com.rapido.rider:id/btn_reject_order"))
        assertTrue(snapshotJson.contains("240,120,1040,620"))
        assertTrue(snapshotJson.contains("550,510,1010,600"))
        assertTrue(snapshotJson.contains("સ્વીકારો"))
        assertTrue(snapshotJson.contains("Skip"))
        assertTrue(snapshotJson.contains("[FARE_WITH_TIP]"))
        assertTrue(snapshotJson.contains("[TIP_BADGE]"))
        assertTrue(snapshotJson.contains("[REDACTED_LOCATION]"))
        assertTrue(snapshotJson.contains("[REDACTED_CONTACT]"))

        // All passenger addresses, street names, landmarks, customer names, phone numbers, and pincodes MUST be redacted
        listOf(
            "Shree Ram",
            "MG Road",
            "560001",
            "Koramangala",
            "Sony Signal",
            "Rahul",
            "Sharma",
            "9876543210",
            "Indiranagar",
            "560038",
            "₹55",
            "₹10"
        ).forEach { sensitive ->
            assertFalse("Sanitized snapshot leaked sensitive token '$sensitive'", snapshotJson.contains(sensitive))
        }

        // Clipboard helper returns live snapshot when present, and safe fallback when cleared
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val (livePayload, hasLive) = RapidoAccessibilityService.buildDiagnosticClipboardPayload(context)
        assertTrue(hasLive)
        assertEquals(snapshotJson, livePayload)

        RapidoAccessibilityService.clearSanitizedUiSnapshotForTesting()
        val (fallbackPayload, hasLiveAfterClear) = RapidoAccessibilityService.buildDiagnosticClipboardPayload(context)
        assertFalse(hasLiveAfterClear)
        assertTrue(fallbackPayload.contains("NO_RAPIDO_SCREEN_CAPTURED_YET"))
    }
}





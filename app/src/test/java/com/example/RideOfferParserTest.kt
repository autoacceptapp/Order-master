package com.example

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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
            assertTrue("Expected ACCEPT_REGEX match for '$action'", MyAccessibilityService.ACCEPT_REGEX.containsMatchIn(action))
            assertFalse("Expected CHAT_OR_NOTIFICATION_REGEX not to match '$action'", MyAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(action))
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
            assertTrue("Expected CHAT_OR_NOTIFICATION_REGEX to match '$msg'", MyAccessibilityService.CHAT_OR_NOTIFICATION_REGEX.containsMatchIn(msg))
        }
    }
}


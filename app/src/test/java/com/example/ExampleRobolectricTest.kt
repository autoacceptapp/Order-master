package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Smart Text Analyzer", appName)

        val filterTitle = context.getString(R.string.filter_title)
        assertEquals("Minimum Value Filter (₹ / $)", filterTitle)

        val filterDesc = context.getString(R.string.filter_desc)
        assertTrue(filterDesc.contains("₹"))
    }

    @Test
    fun `test app settings preferences and reactive state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Default values
        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(60.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(5000.0f, AppSettings.getMaxFare(context), 0.01f)
        assertEquals(3.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Update values
        AppSettings.setAutoAcceptEnabled(context, false)
        assertFalse(AppSettings.isAutoAcceptEnabled(context))

        AppSettings.setMinFare(context, 75.0f)
        assertEquals(75.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(75.0f, AppSettings.getMinPrice(context), 0.01f)

        AppSettings.setMaxFare(context, 800.0f)
        assertEquals(800.0f, AppSettings.getMaxFare(context), 0.01f)
        assertEquals(800.0f, AppSettings.getMaxPrice(context), 0.01f)

        AppSettings.setMaxPickupDistance(context, 2.5f)
        assertEquals(2.5f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Enforce 0.0 to 3.0 km clamp
        AppSettings.setMaxPickupDistance(context, 7.5f)
        assertEquals(3.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        AppSettings.setMaxPickupDistance(context, -1.0f)
        assertEquals(0.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Reset for subsequent tests
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setMinFare(context, 60.0f)
        AppSettings.setMaxFare(context, 5000.0f)
        AppSettings.setMaxPickupDistance(context, 3.0f)
    }

    @Test
    fun `test base fare plus bonus tip calculation`() {
        // ₹55 single
        val (fare1, list1) = TextAnalysisEngine.extractCurrencies("Trip Fare: ₹55")
        assertEquals(55.0, fare1 ?: 0.0, 0.001)
        assertEquals(listOf(55.0), list1)

        // ₹56 + ₹13 = ₹69
        val (fare2, list2) = TextAnalysisEngine.extractCurrencies("Rapido Captain: ₹56 + ₹13 [Accept]")
        assertEquals(69.0, fare2 ?: 0.0, 0.001)
        assertEquals(listOf(56.0, 13.0), list2)

        // ₹95 + ₹23 = ₹118
        val (fare3, list3) = TextAnalysisEngine.extractCurrencies("New Offer ₹95 + ₹23 bonus")
        assertEquals(118.0, fare3 ?: 0.0, 0.001)
        assertEquals(listOf(95.0, 23.0), list3)

        // Alternative notation: Rs. and $
        val (fare4, _) = TextAnalysisEngine.extractCurrencies("Rs. 120 + Rs 30")
        assertEquals(150.0, fare4 ?: 0.0, 0.001)

        val (fare5, _) = TextAnalysisEngine.extractCurrencies("$45 + $15")
        assertEquals(60.0, fare5 ?: 0.0, 0.001)
    }

    @Test
    fun `test pickup distance and drop distance extraction`() {
        // Labeled pickup and drop: "0.5 km pickup", "3.4 km drop"
        val (pickup1, drop1) = TextAnalysisEngine.extractRideDistances("Pickup: 0.5 km • Drop: 3.4 km")
        assertNotNull(pickup1)
        assertNotNull(drop1)
        assertEquals(0.5, pickup1 ?: 0.0, 0.001)
        assertEquals(3.4, drop1 ?: 0.0, 0.001)

        // "2.8 km" pickup and "6.2 km" drop
        val (pickup2, drop2) = TextAnalysisEngine.extractRideDistances("Pickup: 2.8 km • Drop: 6.2 km")
        assertEquals(2.8, pickup2 ?: 0.0, 0.001)
        assertEquals(6.2, drop2 ?: 0.0, 0.001)

        // Conversion from meters
        val (pickupMeters, _) = TextAnalysisEngine.extractRideDistances("Pickup: 800 m away")
        assertEquals(0.8, pickupMeters ?: 0.0, 0.001)

        // Sequential distances without labels: 1.2 km and 5.0 km
        val (pickupSeq, dropSeq) = TextAnalysisEngine.extractRideDistances("Offer: 1.2 km ... 5.0 km")
        assertEquals(1.2, pickupSeq ?: 0.0, 0.001)
        assertEquals(5.0, dropSeq ?: 0.0, 0.001)
    }

    @Test
    fun `test ride offer parsing and business decision evaluation`() {
        val minFare = 60.0f
        val maxPickup = 3.0f

        // Case 1: Match accepted (₹56 + ₹13 = ₹69 >= ₹60, pickup 1.2 km <= 3.0 km, has Accept)
        val offerPass = TextAnalysisEngine.parseRideOffer("Rapido Ride: ₹56 + ₹13 • Pickup 1.2 km • Drop 4.5 km [Accept]")
        val evalPass = TextAnalysisEngine.evaluateRideOffer(
            offer = offerPass,
            minFare = minFare,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = true
        )
        assertTrue(evalPass.isAccepted)
        assertTrue(evalPass.passesFare)
        assertTrue(evalPass.passesPickupDistance)
        assertEquals(69.0, evalPass.offer.totalFare ?: 0.0, 0.001)
        assertEquals(1.2, evalPass.offer.pickupDistanceKm ?: 0.0, 0.001)
        assertTrue(evalPass.decisionReason.contains("MATCHED"))

        // Case 2: Rejected because fare is below minimum (₹55 < ₹60)
        val offerLowFare = TextAnalysisEngine.parseRideOffer("Ride: ₹55 • Pickup 1.0 km • Drop 3.0 km [Accept]")
        val evalLowFare = TextAnalysisEngine.evaluateRideOffer(
            offer = offerLowFare,
            minFare = minFare,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = true
        )
        assertFalse(evalLowFare.isAccepted)
        assertFalse(evalLowFare.passesFare)
        assertTrue(evalLowFare.decisionReason.contains("REJECTED"))

        // Case 3: Rejected because pickup is too far (4.5 km > 3.0 km) even if fare is high (₹95 + ₹23 = ₹118)
        val offerFarPickup = TextAnalysisEngine.parseRideOffer("Ride: ₹95 + ₹23 • Pickup 4.5 km • Drop 10.0 km [Accept]")
        val evalFarPickup = TextAnalysisEngine.evaluateRideOffer(
            offer = offerFarPickup,
            minFare = minFare,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = true
        )
        assertFalse(evalFarPickup.isAccepted)
        assertTrue(evalFarPickup.passesFare)
        assertFalse(evalFarPickup.passesPickupDistance)
        assertTrue(evalFarPickup.decisionReason.contains("Pickup distance (4.5 km) exceeds set maximum limit (3.0 km)"))

        // Case 4: Rejected if master switch is disabled
        val evalDisabled = TextAnalysisEngine.evaluateRideOffer(
            offer = offerPass,
            minFare = minFare,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = false
        )
        assertFalse(evalDisabled.isAccepted)
        assertTrue(evalDisabled.decisionReason.contains("DISABLED"))

        // Case 5: Rejected if no Accept button is present
        val offerNoAccept = TextAnalysisEngine.parseRideOffer("Ride: ₹95 + ₹23 • Pickup 1.5 km (Viewing details)")
        val evalNoAccept = TextAnalysisEngine.evaluateRideOffer(
            offer = offerNoAccept,
            minFare = minFare,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = true
        )
        assertFalse(evalNoAccept.isAccepted)
        assertTrue(evalNoAccept.decisionReason.contains("No 'Accept' button"))

        // Case 6: Rejected because fare exceeds maximum price (₹350 > ₹250)
        val offerHighFare = TextAnalysisEngine.parseRideOffer("Ride: ₹350 • Pickup 1.5 km • Drop 18.0 km [Accept]")
        val evalHighFare = TextAnalysisEngine.evaluateRideOffer(
            offer = offerHighFare,
            minFare = 50.0f,
            maxFare = 250.0f,
            maxPickupDistance = maxPickup,
            isAutoAcceptEnabled = true
        )
        assertFalse(evalHighFare.isAccepted)
        assertFalse(evalHighFare.passesFare)
        assertTrue(evalHighFare.decisionReason.contains("> Max Limit ₹250"))

        // Case 7: Evaluated with SettingsState containing custom minFare and maxFare
        val settingsState = SettingsState(
            minFare = 50.0f,
            maxFare = 200.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true
        )
        val evalSettingsState = TextAnalysisEngine.evaluateRideOffer(
            offer = offerPass, // ₹69
            settings = settingsState
        )
        assertTrue(evalSettingsState.isAccepted)
        assertTrue(evalSettingsState.passesFare)
    }

    @Test
    fun `test backward compatible methods`() {
        val result = TextAnalysisEngine.analyze("Offer: ₹150 + ₹50")
        assertEquals(200.0, result.totalCurrency ?: 0.0, 0.001)
        assertTrue(result.conditionsPassed)

        val passText = "Order ₹120 + ₹80 • Distance 2.2 km"
        val evalResult = TextAnalysisEngine.evaluate(
            text = passText,
            hasAcceptButton = true,
            minFilter = 150.0f,
            maxFilter = 3.0f
        )
        assertTrue(evalResult.conditionsPassed)
        assertEquals(200.0, evalResult.totalCurrency ?: 0.0, 0.001)
        assertEquals(2.2, evalResult.distanceKm ?: 0.0, 0.001)
    }

    @Test
    fun `test permission status list and permission utils`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionList = PermissionUtils.getPermissionStatusList(context)
        assertNotNull(permissionList)
        assertTrue(permissionList.isNotEmpty())

        val criticalItems = permissionList.filter { it.isCritical }
        assertEquals(3, criticalItems.size) // Accessibility, Overlay, Battery
        assertTrue(criticalItems.any { it.id == "accessibility" })
        assertTrue(criticalItems.any { it.id == "overlay" })
        assertTrue(criticalItems.any { it.id == "battery" })

        // Check helper checkers do not crash
        assertNotNull(PermissionUtils.areNotificationsEnabled(context))
        assertNotNull(PermissionUtils.canScheduleExactAlarms(context))
    }

    @Test
    fun `test accessibility service target package constant`() {
        assertEquals("com.rapido.rider", MyAccessibilityService.TARGET_PACKAGE)
    }

    @Test
    fun `test voice announcer app settings and state persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Check defaults
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("en", AppSettings.getVoiceLanguage(context))

        // Update settings
        AppSettings.setVoiceAnnouncerEnabled(context, false)
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.settingsState.value.isVoiceAnnouncerEnabled)

        AppSettings.setVoiceLanguage(context, "hi")
        assertEquals("hi", AppSettings.getVoiceLanguage(context))
        assertEquals("hi", AppSettings.settingsState.value.voiceLanguage)

        // Reset
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        AppSettings.setVoiceLanguage(context, "en")
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("en", AppSettings.getVoiceLanguage(context))
    }

    @Test
    fun `test strict accept action detection in accessibility service`() {
        val service = MyAccessibilityService()

        // Valid actionable strings
        assertTrue(service.isAcceptAction("Accept"))
        assertTrue(service.isAcceptAction("Auto Accept"))
        assertTrue(service.isAcceptAction("Confirm"))
        assertTrue(service.isAcceptAction("Select"))
        assertTrue(service.isAcceptAction("Confirm Order"))
        assertTrue(service.isAcceptAction("Confirm Ride"))
        assertTrue(service.isAcceptAction("Accept Order"))
        assertTrue(service.isAcceptAction("Accept Ride"))
        assertTrue(service.isAcceptAction("Go"))
        assertTrue(service.isAcceptAction("Swipe to Accept"))
        assertTrue(service.isAcceptAction("Take Ride"))
        assertTrue(service.isAcceptAction("  accept  "))
        assertTrue(service.isAcceptAction("  auto accept  "))
        assertTrue(service.isAcceptAction("  CONFIRM  "))

        // Static or irrelevant strings that should NOT trigger evaluation
        assertFalse(service.isAcceptAction("Total Earnings: ₹1,500"))
        assertFalse(service.isAcceptAction("Weekly History 24.5 km"))
        assertFalse(service.isAcceptAction("Profile Settings"))
        assertFalse(service.isAcceptAction("Good Morning"))
        assertFalse(service.isAcceptAction("Gold Captain"))
        assertFalse(service.isAcceptAction(""))
    }

    @Test
    fun `test extractAllText and findAndClickAcceptButton null safety`() {
        val service = MyAccessibilityService()

        // Null node returns empty string safely
        val text = service.extractAllText(null)
        assertEquals("", text)

        // findAndClickAcceptButton and executeAssistiveClick with null root safely execute without exception
        val clicked = service.findAndClickAcceptButton(null)
        assertFalse(clicked)

        val assistiveClicked = service.executeAssistiveClick(null)
        assertFalse(assistiveClicked)
    }

    @Test
    fun `test GitHubUpdateManager version parsing and comparison`() {
        // Tag with build number pattern e.g. debug-apk-build-7-1
        assertTrue(GitHubUpdateManager.isVersionNewer("debug-apk-build-7-1", "1.0", 1))
        assertEquals(listOf(7, 1), GitHubUpdateManager.extractVersionNumbers("debug-apk-build-7-1"))

        // Standard SemVer tags
        assertTrue(GitHubUpdateManager.isVersionNewer("v1.2.0", "1.0", 1))
        assertTrue(GitHubUpdateManager.isVersionNewer("2.0", "1.0", 1))
        assertTrue(GitHubUpdateManager.isVersionNewer("v2.1.5", "1.0", 1))
        assertEquals(listOf(1, 2, 0), GitHubUpdateManager.extractVersionNumbers("v1.2.0"))

        // Same version or older
        assertFalse(GitHubUpdateManager.isVersionNewer("1.0", "1.0", 1))
        assertFalse(GitHubUpdateManager.isVersionNewer("v1.0", "1.0", 1))
        assertFalse(GitHubUpdateManager.isVersionNewer("v0.9.0", "1.0", 1))
    }

    @Test
    fun `test GitHubUpdateManager SharedPreferences skip and cache handling`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initially no skipped version
        assertEquals("", GitHubUpdateManager.getSkippedVersion(context))

        // Skip a version
        GitHubUpdateManager.skipVersion(context, "debug-apk-build-7-1")
        assertEquals("debug-apk-build-7-1", GitHubUpdateManager.getSkippedVersion(context))

        // Clear skipped version
        GitHubUpdateManager.clearSkippedVersion(context)
        assertEquals("", GitHubUpdateManager.getSkippedVersion(context))
    }

    @Test
    fun `test UpdateNotificationManager channel registration does not throw`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UpdateNotificationManager.createNotificationChannel(context)
        // Verify channel creation succeeded without exceptions
        assertTrue(UpdateNotificationManager.CHANNEL_ID == "APP_UPDATE_CHANNEL")
    }

    @Test
    fun `test RawOrderCard and isolated RideOffer parsing`() {
        // Card 1: Valid offer
        val card1 = RawOrderCard(
            id = "card_1",
            nodeTexts = listOf("Rapido Captain", "₹120", "Pickup: 1.5 km", "Koramangala 4th Block", "Accept"),
            bounds = android.graphics.Rect(0, 100, 1080, 500),
            hasAcceptButton = true
        )

        val offer1 = TextAnalysisEngine.parseOrderCard(card1)
        assertNotNull(offer1)
        assertEquals(120.0, offer1?.totalFare ?: 0.0, 0.001)
        assertEquals(1.5, offer1?.pickupDistanceKm ?: 0.0, 0.001)
        assertTrue(offer1?.hasAcceptButton == true)
        assertTrue(offer1?.id?.startsWith("offer_") == true)
        assertTrue(offer1?.location?.contains("Koramangala", ignoreCase = true) == true)

        // Card 2: Valid offer with bonus tip
        val card2 = RawOrderCard(
            id = "card_2",
            nodeTexts = listOf("New Ride", "₹56 + ₹14", "1.2 km away", "Indiranagar", "Accept Ride"),
            bounds = android.graphics.Rect(0, 550, 1080, 950),
            hasAcceptButton = true
        )

        val offer2 = TextAnalysisEngine.parseOrderCard(card2)
        assertNotNull(offer2)
        assertEquals(70.0, offer2?.totalFare ?: 0.0, 0.001)
        assertEquals(1.2, offer2?.pickupDistanceKm ?: 0.0, 0.001)
        assertEquals(listOf(56.0, 14.0), offer2?.fareBreakdown)

        // Verify no text cross-contamination between card1 and card2
        assertFalse(offer1?.rawCard?.nodeTexts?.contains("Indiranagar") == true)
        assertFalse(offer2?.rawCard?.nodeTexts?.contains("Koramangala 4th Block") == true)
    }

    @Test
    fun `test incomplete or malformed cards are ignored`() {
        // Missing fare (only distance and button)
        val missingFareCard = RawOrderCard(
            id = "malformed_1",
            nodeTexts = listOf("Pickup: 2.0 km", "HSR Layout", "Accept"),
            bounds = android.graphics.Rect(0, 0, 100, 100)
        )
        val result1 = TextAnalysisEngine.parseOrderCard(missingFareCard)
        assertEquals(null, result1)

        // Missing distance (only fare and button)
        val missingDistCard = RawOrderCard(
            id = "malformed_2",
            nodeTexts = listOf("₹85", "Whitefield", "Accept"),
            bounds = android.graphics.Rect(0, 0, 100, 100)
        )
        val result2 = TextAnalysisEngine.parseOrderCard(missingDistCard)
        assertEquals(null, result2)

        // Empty card
        val emptyCard = RawOrderCard(
            id = "empty",
            nodeTexts = emptyList(),
            bounds = android.graphics.Rect()
        )
        val result3 = TextAnalysisEngine.parseOrderCard(emptyCard)
        assertEquals(null, result3)
    }

    @Test
    fun `test unique offer id hash generation`() {
        val hash1 = TextAnalysisEngine.generateOfferId(120.0, 1.5, "Koramangala")
        val hash2 = TextAnalysisEngine.generateOfferId(120.0, 1.5, "Koramangala")
        val hash3 = TextAnalysisEngine.generateOfferId(120.0, 1.5, "Indiranagar")
        val hash4 = TextAnalysisEngine.generateOfferId(40.0, 4.0, "Koramangala")

        // Identical parameters produce the exact same hash
        assertEquals(hash1, hash2)

        // Different location produces distinct hash
        assertFalse(hash1 == hash3)

        // Different fare/distance produces distinct hash
        assertFalse(hash1 == hash4)
    }

    @Test
    fun `test multi-order evaluation logic with isolated cards`() {
        val validCard = RawOrderCard(
            id = "card_valid",
            nodeTexts = listOf("₹120", "Pickup: 1.5 km", "Koramangala", "Accept"),
            bounds = android.graphics.Rect(0, 100, 1080, 500),
            hasAcceptButton = true
        )
        val invalidCard = RawOrderCard(
            id = "card_invalid",
            nodeTexts = listOf("₹40", "Pickup: 4.0 km", "HSR Layout", "Accept"),
            bounds = android.graphics.Rect(0, 550, 1080, 950),
            hasAcceptButton = true
        )

        val offers = TextAnalysisEngine.parseAllOrderCards(listOf(validCard, invalidCard))
        assertEquals(2, offers.size)

        val eval1 = TextAnalysisEngine.evaluateRideOffer(
            offer = offers[0].toParsedRideOffer(),
            minFare = 60.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true
        )
        assertTrue(eval1.isAccepted)
        assertTrue(eval1.decisionReason.contains("MATCHED"))

        val eval2 = TextAnalysisEngine.evaluateRideOffer(
            offer = offers[1].toParsedRideOffer(),
            minFare = 60.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true
        )
        assertFalse(eval2.isAccepted)
        assertTrue(eval2.decisionReason.contains("REJECTED"))
    }

    @Test
    fun `test locationCleaner extracts primary landmark and strips addresses and pincodes`() {
        val loc1 = TextAnalysisEngine.locationCleaner("Lalpari River - 22-24-2, Shree Ram Society, 360003")
        assertEquals("Lalpari River", loc1)

        val loc2 = TextAnalysisEngine.locationCleaner("City Centre - Shop 14, Main Road")
        assertEquals("City Centre", loc2)

        val loc3 = TextAnalysisEngine.locationCleaner("Race Course\nNear Jubilee Garden, 360001")
        assertEquals("Race Course", loc3)

        val loc4 = TextAnalysisEngine.locationCleaner("Rail Nagar - Street 2")
        assertEquals("Rail Nagar", loc4)
    }

    @Test
    fun `test targeted 5-field extraction from Rapido Captain card nodes`() {
        val nodeTexts = listOf(
            "Rapido Captain",
            "₹95",
            "+ ₹23",
            "2.8 km",
            "Lalpari River - 22-24-2, Shree Ram Society",
            "6.2 km",
            "Race Course - Near Jubilee Garden",
            "Accept"
        )

        val target = TextAnalysisEngine.parseTargetOfferFromNodes(nodeTexts)
        assertNotNull(target)
        assertEquals(95.0, target!!.fare, 0.001)
        assertEquals(2.8, target.pickupDistance, 0.001)
        assertEquals("Lalpari River", target.pickupLocation)
        assertEquals(6.2, target.dropDistance, 0.001)
        assertEquals("Race Course", target.dropLocation)
    }

    @Test
    fun `test targeted 5-field extraction from flat text string`() {
        val rawText = "₹55 0.5 km City Centre - Shop 14, Main Road 3.4 km Rail Nagar - Street 2 Accept"
        val target = TextAnalysisEngine.parseTargetOffer(rawText)
        assertNotNull(target)
        assertEquals(55.0, target!!.fare, 0.001)
        assertEquals(0.5, target.pickupDistance, 0.001)
        assertEquals("City Centre", target.pickupLocation)
        assertEquals(3.4, target.dropDistance, 0.001)
        assertEquals("Rail Nagar", target.dropLocation)
    }

    @Test
    fun `test natural spoken Hindi Hinglish TTS announcement format`() {
        val target = TargetRideOffer(
            fare = 95.0,
            pickupDistance = 2.8,
            pickupLocation = "Lalpari River",
            dropDistance = 6.2,
            dropLocation = "Race Course"
        )

        val service = MyAccessibilityService()
        val speech = service.formatCleanVoiceAnnouncement(target)
        assertEquals(
            "Kiraya 95 rupaye. Pickup 2.8 kilometer Lalpari River. Drop 6.2 kilometer Race Course.",
            speech
        )
    }

    @Test
    fun `test evaluation uses strictly fare and pickup distance ignoring drop distance`() {
        val offerWithLongDrop = ParsedRideOffer(
            rawText = "₹95 2.5 km Lalpari River 25.0 km Distant Town Accept",
            totalFare = 95.0,
            fareBreakdown = listOf(95.0),
            pickupDistanceKm = 2.5,
            dropDistanceKm = 25.0,
            hasAcceptButton = true,
            targetOffer = TargetRideOffer(
                fare = 95.0,
                pickupDistance = 2.5,
                pickupLocation = "Lalpari River",
                dropDistance = 25.0,
                dropLocation = "Distant Town"
            )
        )

        val eval = TextAnalysisEngine.evaluateRideOffer(
            offer = offerWithLongDrop,
            minFare = 60.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true
        )

        // Pickup is 2.5 km <= 3.0 km max limit, fare 95 >= 60 -> Must be ACCEPTED even though drop is 25 km
        assertTrue(eval.isAccepted)
        assertTrue(eval.passesPickupDistance)
        assertTrue(eval.passesFare)
    }
}

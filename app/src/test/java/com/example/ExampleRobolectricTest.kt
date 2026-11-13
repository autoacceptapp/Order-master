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
import org.robolectric.shadows.ShadowToast
import kotlinx.coroutines.test.runTest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Order Master", appName)

        val filterTitle = context.getString(R.string.filter_title)
        assertEquals("Minimum Value Filter (₹ / $)", filterTitle)

        val filterDesc = context.getString(R.string.filter_desc)
        assertTrue(filterDesc.contains("₹"))
    }

    @Test
    fun `test app settings preferences and reactive state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // Restricted safe default values before Firestore provisioning
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(AppSettings.RESTRICTED_MIN_FARE, AppSettings.getMinFare(context), 0.01f)
        assertEquals(AppSettings.DEFAULT_MAX_FARE, AppSettings.getMaxFare(context), 0.01f)
        assertEquals(AppSettings.RESTRICTED_MAX_PICKUP_DISTANCE, AppSettings.getMaxPickupDistance(context), 0.01f)

        // While filters are locked in Free Mode, filter setters stay blocked
        AppSettings.setMinFare(context, 75.0f)
        assertEquals(AppSettings.RESTRICTED_MIN_FARE, AppSettings.getMinFare(context), 0.01f)
        assertEquals("Unlock Minimum Fare Filter with Points or Pro Pass", ShadowToast.getTextOfLatestToast())

        // Authoritative Firestore snapshot drives RAM StateFlows & unlock state
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.MAX_FARE to futureExpiry,
                FeatureIds.MAX_PICKUP_DISTANCE to futureExpiry
            )
        )
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "autoAcceptEnabled" to true,
                "minFare" to 75.0,
                "maxFare" to 800.0,
                "maxPickupDistance" to 2.5
            )
        )
        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(75.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(75.0f, AppSettings.getMinPrice(context), 0.01f)
        assertEquals(800.0f, AppSettings.getMaxFare(context), 0.01f)
        assertEquals(800.0f, AppSettings.getMaxPrice(context), 0.01f)
        assertEquals(2.5f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Now that filters are unlocked, user CAN manually adjust thresholds & auto-accept toggle
        AppSettings.setMinFare(context, 120.0f)
        AppSettings.setMaxPickupDistance(context, 1.8f)
        assertEquals(120.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(1.8f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Enforce 0.0 to 3.0 km clamp on pickup distance
        AppSettings.setMaxPickupDistance(context, 7.5f)
        assertEquals(3.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        AppSettings.setMaxPickupDistance(context, -1.0f)
        assertEquals(0.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Reset to restricted defaults
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
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
        assertTrue(evalFarPickup.decisionReason.contains("Pickup distance") && evalFarPickup.decisionReason.contains("exceeds"))

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

        // OEM autostart and Chinese ROM helpers
        assertNotNull(PermissionUtils.isChineseRomDevice())
        assertNotNull(PermissionUtils.getDeviceBrandName())
        assertTrue(PermissionUtils.openOemAutoStartSettings(context))
    }

    @Test
    fun `test notification channel ids and foreground service requirements`() {
        assertEquals("order_master_overlay_fgs_channel", FloatingOverlayService.CHANNEL_ID)
        assertEquals("APP_UPDATE_CHANNEL", UpdateNotificationManager.CHANNEL_ID)
    }

    @Test
    fun `test accessibility service target package constant`() {
        assertEquals("com.rapido.rider", MyAccessibilityService.TARGET_PACKAGE)
    }

    @Test
    fun `test voice announcer app settings and state persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // Check restricted defaults (voice announcer off & locked initially)
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("en", AppSettings.getVoiceLanguage(context))

        // While TTS_VOICE is locked, toggle remains blocked
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.settingsState.value.isVoiceAnnouncerEnabled)
        assertEquals("Unlock Voice Announcements with Points or Pro Pass", ShadowToast.getTextOfLatestToast())

        // Once TTS_VOICE is unlocked (Points or Admin -1L), user CAN toggle voice announcements ON/OFF
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(unlocked = mapOf(FeatureIds.TTS_VOICE to futureExpiry))
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertTrue(AppSettings.settingsState.value.isVoiceAnnouncerEnabled)

        // Local UI preference (language) remains locally configurable
        AppSettings.setVoiceLanguage(context, "hi")
        assertEquals("hi", AppSettings.getVoiceLanguage(context))
        assertEquals("hi", AppSettings.settingsState.value.voiceLanguage)

        // Reset
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        AppSettings.setVoiceLanguage(context, "en")
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
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

        // Multi-language Hindi native script support
        assertTrue(service.isAcceptAction("स्वीकार"))
        assertTrue(service.isAcceptAction("स्वीकार करें"))
        assertTrue(service.isAcceptAction("राइड स्वीकार करें"))
        assertTrue(service.isAcceptAction("ऑर्डर स्वीकार करें"))
        assertTrue(service.isAcceptAction("कन्फर्म करें"))

        // Multi-language Hindi transliteration (Hinglish) support
        assertTrue(service.isAcceptAction("sweekar"))
        assertTrue(service.isAcceptAction("sweekarein"))
        assertTrue(service.isAcceptAction("accept karein"))
        assertTrue(service.isAcceptAction("confirm karein"))

        // Resilient target app ID regex checks
        assertTrue(service.isRapidoAcceptId("btn_accept"))
        assertTrue(service.isRapidoAcceptId("accept_order"))
        assertTrue(service.isRapidoAcceptId("btn_confirm"))
        assertTrue(service.isRapidoAcceptId("auto_accept"))
        assertTrue(service.isRapidoAcceptId("cta_accept"))
        assertTrue(service.isRapidoAcceptId("com.rapido.rider:id/btn_accept"))
        assertFalse(service.isRapidoAcceptId("btn_reject"))

        // Static or irrelevant strings that should NOT trigger evaluation
        assertFalse(service.isAcceptAction("Total Earnings: ₹1,500"))
        assertFalse(service.isAcceptAction("Weekly History 24.5 km"))
        assertFalse(service.isAcceptAction("Profile Settings"))
        assertFalse(service.isAcceptAction("Good Morning"))
        assertFalse(service.isAcceptAction("Gold Captain"))
        assertFalse(service.isAcceptAction(""))
    }

    @Test
    fun `test extractAllText and findAndClickAcceptButton null safety`() = runTest {
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
    fun `test accessibility service traversal limits strictly under 50ms and bounded depth`() {
        // Acceptance criteria: Max traversal time in AccessibilityService strictly under 50ms
        assertTrue(MyAccessibilityService.MAX_TRAVERSAL_TIMEOUT_MS < 50L)
        assertEquals(45L, MyAccessibilityService.MAX_TRAVERSAL_TIMEOUT_MS)
        assertTrue(MyAccessibilityService.MAX_TRAVERSAL_DEPTH <= 15)
        assertEquals(12, MyAccessibilityService.MAX_TRAVERSAL_DEPTH)

        val service = MyAccessibilityService()
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
        val bounds = android.graphics.Rect(0, 0, 1080, 500)

        // Warm up JIT/classloading to prevent container scheduling jitter
        service.findAcceptNodeInBounds(node, bounds)

        // Traversal returns safely without throwing exceptions or exceeding timeout
        val startTime = System.currentTimeMillis()
        val acceptNode = service.findAcceptNodeInBounds(node, bounds)
        val rejectNode = service.findRejectNodeInBounds(node, bounds)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Elapsed traversal time ($elapsed ms) should be fast and bounded", elapsed < 200L)
        // Null or unmatched node safely returns null
        org.junit.Assert.assertNull(acceptNode)
        org.junit.Assert.assertNull(rejectNode)
    }

    @Test
    fun `test floating overlay service rotation configuration change safe when detached`() {
        val service = FloatingOverlayService()
        val config = android.content.res.Configuration()
        // Calling onConfigurationChanged when view is detached must not crash with BadTokenException or IllegalArgumentException
        service.onConfigurationChanged(config)
    }

    @Test
    fun `test TTSManager debounce and duplicate cooldown mechanism`() {
        val key = "fare_110_dist_2.3"
        // First announcement allowed
        assertTrue(TTSManager.shouldAnnounceOffer(key))

        // Immediate subsequent announcements for the exact same offer are strictly suppressed (cooldown active)
        assertFalse(TTSManager.shouldAnnounceOffer(key))
        assertFalse(TTSManager.shouldAnnounceOffer(key))

        // Different offer is permitted
        val differentKey = "fare_180_dist_1.2"
        assertTrue(TTSManager.shouldAnnounceOffer(differentKey))

        // Verify cooldown state
        assertTrue(TTSManager.isOfferInCooldown(key))
        assertTrue(TTSManager.isOfferInCooldown(differentKey))
        assertFalse(TTSManager.isOfferInCooldown("fare_999_dist_9.9"))
    }

    @Test
    fun `test reject action detection in accessibility service`() {
        val service = MyAccessibilityService()

        assertTrue(service.isRejectAction("Reject"))
        assertTrue(service.isRejectAction("Decline"))
        assertTrue(service.isRejectAction("Skip"))
        assertTrue(service.isRejectAction("Cancel"))
        assertTrue(service.isRejectAction("Pass"))
        assertTrue(service.isRejectAction("Reject Order"))
        assertTrue(service.isRejectAction("Decline Ride"))
        assertTrue(service.isRejectAction("अस्वीकार"))
        assertTrue(service.isRejectAction("छोड़ें"))
        assertTrue(service.isRejectAction("रद्द करें"))
        assertTrue(service.isRejectAction("asveekar"))
        assertTrue(service.isRejectAction("asweekar"))
        assertTrue(service.isRejectAction("chhodein"))

        assertTrue(service.isRapidoRejectId("btn_reject"))
        assertTrue(service.isRapidoRejectId("reject_order"))
        assertTrue(service.isRapidoRejectId("btn_decline"))
        assertTrue(service.isRapidoRejectId("btn_skip"))
        assertTrue(service.isRapidoRejectId("btn_cancel"))
        assertFalse(service.isRapidoRejectId("btn_accept"))

        assertFalse(service.isRejectAction("Accept"))
        assertFalse(service.isRejectAction("Weekly Earnings"))
        assertFalse(service.isRejectAction(""))
    }

    @Test
    fun `test explicit ride request container id detection`() {
        assertTrue(TextAnalysisEngine.isExplicitRideRequest("com.rapido.rider:id/order_card"))
        assertTrue(TextAnalysisEngine.isExplicitRideRequest("com.rapido.rider:id/ride_request_dialog"))
        assertTrue(TextAnalysisEngine.isExplicitRideRequest("bottom_sheet_order_layout"))
        assertTrue(TextAnalysisEngine.isExplicitRideRequest("incoming_order_notification"))

        assertFalse(TextAnalysisEngine.isExplicitRideRequest("home_dashboard_earnings"))
        assertFalse(TextAnalysisEngine.isExplicitRideRequest("bubble_floating_view"))
        assertFalse(TextAnalysisEngine.isExplicitRideRequest(null))
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

        // Semantic versioning comparisons (e.g. 1.0.10 > 1.0.9)
        assertTrue(GitHubUpdateManager.isVersionNewer("1.0.10", "1.0.9"))
        assertFalse(GitHubUpdateManager.isVersionNewer("1.0.9", "1.0.10"))
        assertTrue(GitHubUpdateManager.isVersionNewer("1.0.0.1", "1.0.0"))
        assertFalse(GitHubUpdateManager.isVersionNewer("1.0.0", "1.0.0.1"))
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

    @Test
    fun `test per-km rate filter rejects low-rate rides and accepts high-rate rides`() {
        // Offer: ₹100 fare, 2.0 km pickup, 18.0 km drop -> total 20.0 km -> 5.0 ₹/km
        val lowRateOffer = ParsedRideOffer(
            rawText = "Ride: ₹100 • 2.0 km pickup • 18.0 km drop [Accept]",
            totalFare = 100.0,
            pickupDistanceKm = 2.0,
            dropDistanceKm = 18.0,
            hasAcceptButton = true
        )

        // Evaluate with minPerKmRate = 10.0f
        val evalRejected = TextAnalysisEngine.evaluateRideOffer(
            offer = lowRateOffer,
            minFare = 50.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            minPerKmRate = 10.0f,
            isMinPerKmRateEnabled = true
        )
        assertFalse(evalRejected.isAccepted)
        assertTrue(evalRejected.decisionReason.contains("low Per-Km rate") || evalRejected.decisionReason.contains("5.0/km"))

        // Offer: ₹150 fare, 1.0 km pickup, 4.0 km drop -> total 5.0 km -> 30.0 ₹/km
        val highRateOffer = ParsedRideOffer(
            rawText = "Ride: ₹150 • 1.0 km pickup • 4.0 km drop [Accept]",
            totalFare = 150.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = 4.0,
            hasAcceptButton = true
        )
        val evalAccepted = TextAnalysisEngine.evaluateRideOffer(
            offer = highRateOffer,
            minFare = 50.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            minPerKmRate = 10.0f,
            isMinPerKmRateEnabled = true
        )
        assertTrue(evalAccepted.isAccepted)
    }

    @Test
    fun `test destination blacklist filter rejects offers with blacklisted locations`() {
        val blockedOffer = ParsedRideOffer(
            rawText = "Ride: ₹180 • 1.0 km pickup • 5.0 km drop • Destination: Airport Terminal 2 [Accept]",
            totalFare = 180.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = 5.0,
            hasAcceptButton = true,
            targetOffer = TargetRideOffer(
                fare = 180.0,
                pickupDistance = 1.0,
                pickupLocation = "City Center",
                dropDistance = 5.0,
                dropLocation = "Airport Terminal 2"
            )
        )

        val evalBlocked = TextAnalysisEngine.evaluateRideOffer(
            offer = blockedOffer,
            minFare = 50.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            blockedLocations = listOf("Airport", "Toll Road"),
            isDestinationBlacklistEnabled = true
        )
        assertFalse(evalBlocked.isAccepted)
        assertTrue(evalBlocked.decisionReason.contains("Blacklisted Area") && evalBlocked.decisionReason.contains("Airport"))

        // Offer to non-blacklisted area
        val allowedOffer = ParsedRideOffer(
            rawText = "Ride: ₹180 • 1.0 km pickup • 5.0 km drop • Destination: MG Road [Accept]",
            totalFare = 180.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = 5.0,
            hasAcceptButton = true,
            targetOffer = TargetRideOffer(
                fare = 180.0,
                pickupDistance = 1.0,
                pickupLocation = "City Center",
                dropDistance = 5.0,
                dropLocation = "MG Road"
            )
        )
        val evalAllowed = TextAnalysisEngine.evaluateRideOffer(
            offer = allowedOffer,
            minFare = 50.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            blockedLocations = listOf("Airport", "Toll Road"),
            isDestinationBlacklistEnabled = true
        )
        assertTrue(evalAllowed.isAccepted)
    }

    @Test
    fun `test app settings rate per km and destination blacklist persistence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // While Per-Km Rate and Blacklist are locked in Free Mode, setters must stay blocked
        AppSettings.setMinPerKmRateEnabled(context, true)
        assertFalse(AppSettings.isMinPerKmRateEnabled(context))
        assertEquals("Unlock Minimum Per-KM Filter with Points or Pro Pass", ShadowToast.getTextOfLatestToast())

        AppSettings.setMinPerKmRate(context, 14.0f)
        assertEquals(AppSettings.DEFAULT_MIN_PER_KM_RATE, AppSettings.getMinPerKmRate(context), 0.01f)

        AppSettings.setDestinationBlacklistEnabled(context, true)
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        AppSettings.setBlockedLocations(context, listOf("Airport", "Toll"))
        assertTrue(AppSettings.getBlockedLocations(context).isEmpty())
        AppSettings.addBlockedLocation(context, "Slum Area")
        assertTrue(AppSettings.getBlockedLocations(context).isEmpty())
        AppSettings.removeBlockedLocation(context, "Airport")
        assertTrue(AppSettings.getBlockedLocations(context).isEmpty())

        // Once unlocked (Points or Admin -1L), user CAN set Per-Km Rate and Destination Blacklist manually
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_PER_KM_RATE to futureExpiry,
                FeatureIds.DESTINATION_BLACKLIST to futureExpiry
            )
        )
        AppSettings.setMinPerKmRateEnabled(context, true)
        AppSettings.setMinPerKmRate(context, 14.0f)
        AppSettings.setDestinationBlacklistEnabled(context, true)
        AppSettings.setBlockedLocations(context, listOf("Airport", "Toll"))
        AppSettings.addBlockedLocation(context, "Slum Area")

        assertTrue(AppSettings.isMinPerKmRateEnabled(context))
        assertEquals(14.0f, AppSettings.getMinPerKmRate(context), 0.01f)
        assertEquals(14.0f, AppSettings.settingsState.value.minPerKmRate, 0.01f)
        assertTrue(AppSettings.isDestinationBlacklistEnabled(context))
        assertTrue(AppSettings.getBlockedLocations(context).containsAll(listOf("Airport", "Toll", "Slum Area")))

        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun `test zero local storage for auto accept master toggle and business rules`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(AppSettings.PREFS_NAME, Context.MODE_PRIVATE)

        // Seed legacy business keys and confirm init() purges them from SharedPreferences
        prefs.edit()
            .putBoolean("key_auto_accept_enabled", true)
            .putFloat("key_min_fare", 99.0f)
            .putBoolean("key_floating_overlay_enabled", true)
            .apply()
        AppSettings.init(context)

        // Ensure RAM updates NEVER write business keys to SharedPreferences
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setMinFare(context, 120.0f)
        AppSettings.setMaxPickupDistance(context, 2.2f)
        AppSettings.setFloatingOverlayEnabled(context, true)
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        AppSettings.setBlockedLocations(context, listOf("Airport"))

        val forbiddenKeys = listOf(
            "key_auto_accept_enabled",
            "key_service_enabled",
            "key_min_fare",
            "key_min_fare_enabled",
            "key_max_fare",
            "key_max_pickup_distance",
            "key_blocked_locations",
            "key_floating_overlay_enabled",
            "key_voice_announcer_enabled",
            "key_auto_click_enabled"
        )
        for (key in forbiddenKeys) {
            assertFalse("Forbidden business key persisted in SharedPreferences: $key", prefs.contains(key))
        }
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun `test first launch language selection and regional TTS synchronization`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Verify 10 supported Indian languages
        assertEquals(10, SUPPORTED_LANGUAGES.size)
        val expectedCodes = listOf("en", "hi", "gu", "mr", "bn", "ta", "te", "kn", "ml", "pa")
        assertTrue(SUPPORTED_LANGUAGES.map { it.code }.containsAll(expectedCodes))

        // First launch selection
        AppSettings.setAppLanguage(context, "gu")
        assertTrue(AppSettings.isLanguageSelected(context))
        assertEquals("gu", AppSettings.getAppLanguage(context))
        assertEquals("gu", AppSettings.getVoiceLanguage(context))
        assertEquals("gu", AppSettings.settingsState.value.voiceLanguage)

        // Gujarati TTS Announcement test
        val target = TargetRideOffer(
            fare = 85.0,
            pickupDistance = 1.2,
            dropDistance = 6.2,
            pickupLocation = "Station",
            dropLocation = "Market"
        )
        val guAnnouncement = TTSManager.formatCleanVoiceAnnouncement(target, "gu")
        assertTrue(guAnnouncement.contains("ભાડું 85 રૂપિયા"))
        assertTrue(guAnnouncement.contains("પીકઅપ 1.2 કિલોમીટર Station"))
        assertTrue(guAnnouncement.contains("ડ્રોપ 6.2 કિલોમીટર Market"))

        // Hindi / Hinglish TTS Announcement test
        val hiAnnouncement = TTSManager.formatCleanVoiceAnnouncement(target, "hi")
        assertTrue(hiAnnouncement.contains("Kiraya 85 rupaye"))
        assertTrue(hiAnnouncement.contains("Pickup 1.2 kilometer Station"))
        assertTrue(hiAnnouncement.contains("Drop 6.2 kilometer Market"))
    }

    @Test
    fun `test onboarding completion state flow and TTS controls`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // Initially false
        assertFalse(AppSettings.isOnboardingCompleted(context))
        assertFalse(AppSettings.isOnboardingCompletedFlow.value)

        // Complete onboarding
        AppSettings.setOnboardingCompleted(context, true)
        assertTrue(AppSettings.isOnboardingCompleted(context))
        assertTrue(AppSettings.isOnboardingCompletedFlow.value)

        // Re-read via init
        AppSettings.init(context)
        assertTrue(AppSettings.isOnboardingCompleted(context))
        assertTrue(AppSettings.isOnboardingCompletedFlow.value)

        // Verify TTSManager stop and flush speak handle gracefully without throwing
        TTSManager.stop()
        TTSManager.speak(context, "Welcome to Order Master", flush = true)
        TTSManager.stop()
    }

    @Test
    fun `test all 5 fields announced cleanly and full details recorded in order history`() {
        val target = TargetRideOffer(
            fare = 45.0,
            pickupDistance = 0.6,
            pickupLocation = "City Centre • Premji's Park, 9, Subhash Road, opp. Aalap-A, Limda Chowk, Panchnath Plot, Sadar, 360001",
            dropDistance = 2.4,
            dropLocation = "Rajkot railway station • Rajkot Junction, Railway Station Road, Junction Plot, Rajkot, Gujarat, India"
        )

        // Test Voice announcement announces all 5 fields
        val hiSpeech = TTSManager.formatCleanVoiceAnnouncement(target, "hi")
        assertEquals(
            "Kiraya 45 rupaye. Pickup 0.6 kilometer City Centre. Drop 2.4 kilometer Rajkot railway station.",
            hiSpeech
        )

        val guSpeech = TTSManager.formatCleanVoiceAnnouncement(target, "gu")
        assertTrue(guSpeech.contains("ભાડું 45 રૂપિયા"))
        assertTrue(guSpeech.contains("પીકઅપ 0.6 કિલોમીટર City Centre"))
        assertTrue(guSpeech.contains("ડ્રોપ 2.4 કિલોમીટર Rajkot railway station"))

        val enSpeech = TTSManager.formatCleanVoiceAnnouncement(target, "en")
        assertEquals(
            "Fare 45 rupees. Pickup 0.6 km City Centre. Drop 2.4 km Rajkot railway station.",
            enSpeech
        )

        // Test structured locations extraction on raw text nodes from screenshot
        val nodeTexts = listOf(
            "Auto Boost",
            "₹45 1",
            "₹57 AUTO",
            "0.6 km 2",
            "City Centre 3",
            "Premji's Park, 9, Subhash Road, opp. Aalap-A, Limda Chowk, Panchnath Plot, Sadar, 360001",
            "2.4 km 4",
            "Rajkot railway station 5",
            "Rajkot Junction, Railway Station Road, Junction Plot, Rajkot, Gujarat, India",
            "Accept"
        )
        val (pLoc, dLoc) = RideOfferParser.extractStructuredLocations(nodeTexts)
        assertTrue(pLoc.contains("City Centre"))
        assertTrue(pLoc.contains("Premji's Park"))
        assertTrue(dLoc.contains("Rajkot railway station"))
        assertTrue(dLoc.contains("Rajkot Junction"))
    }

    @Test
    fun `test wait-for-success state machine regexes and states`() {
        // AcceptanceState enum presence
        assertEquals(3, MyAccessibilityService.AcceptanceState.values().size)
        assertNotNull(MyAccessibilityService.AcceptanceState.valueOf("CONFIRMED_ACCEPTED"))
        assertNotNull(MyAccessibilityService.AcceptanceState.valueOf("REJECTED_OR_TAKEN"))
        assertNotNull(MyAccessibilityService.AcceptanceState.valueOf("STALLED_OR_TIMED_OUT"))

        // Rejection indicators regex
        val rejectionPhrases = listOf(
            "Ride taken by another captain",
            "This offer was already booked",
            "Offer has expired",
            "Ride missed",
            "Ride cancelled by user",
            "Booking failed, please try again"
        )
        for (phrase in rejectionPhrases) {
            assertTrue("Expected rejection match for: $phrase", MyAccessibilityService.REJECTION_INDICATORS_REGEX.containsMatchIn(phrase))
        }

        // Active trip transition regex
        val activeTripPhrases = listOf(
            "Navigate to pickup point",
            "Drop-off at Rajkot Station",
            "Arrived at passenger location",
            "Start ride after boarding",
            "Pick up customer",
            "Ask customer to enter OTP 4821",
            "Call customer directly"
        )
        for (phrase in activeTripPhrases) {
            assertTrue("Expected active trip match for: $phrase", MyAccessibilityService.ACTIVE_TRIP_INDICATORS_REGEX.containsMatchIn(phrase))
        }

        // Negative check: standard offer text should NOT match rejection or active trip indicators
        val standardOffer = "₹45 0.6 km City Centre Premji Park 2.4 km Rajkot railway station Accept"
        assertFalse(MyAccessibilityService.REJECTION_INDICATORS_REGEX.containsMatchIn(standardOffer))
        assertFalse(MyAccessibilityService.ACTIVE_TRIP_INDICATORS_REGEX.containsMatchIn(standardOffer))
    }

    @Test
    fun `test zero trust thin client firestore snapshot sync signout reset and evaluation latency`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // 1. Missing Firestore doc falls back to strict safe restricted defaults
        AppSettings.applyFirestoreSnapshotForTest(context, null)
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(0.0f, AppSettings.minFare.value, 0.01f)
        assertEquals(0.0f, AppSettings.maxPickupDistanceFlow.value, 0.01f)
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals(null, AppSettings.activePassTypeFlow.value)
        assertEquals(0L, AppSettings.passExpiryTimestampFlow.value)
        assertTrue(AppSettings.unlockedFeaturesFlow.value.isEmpty())

        // 2. Push Firestore users/{uid} snapshot fields into RAM StateFlows
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.FARE_FILTERS to futureExpiry,
                FeatureIds.DISTANCE_FILTERS to futureExpiry,
                FeatureIds.FLOATING_OVERLAY to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry,
                FeatureIds.DESTINATION_BLACKLIST to futureExpiry
            )
        )
        val snapshotData = mapOf<String, Any?>(
            "autoAcceptEnabled" to true,
            "minFare" to 85.0,
            "maxPickupDistance" to 2.0,
            "isFloatingOverlayEnabled" to true,
            "isVoiceAnnouncerEnabled" to true,
            "activePassType" to "DAILY_PASS",
            "passExpiryTimestamp" to futureExpiry,
            "unlocked_features" to mapOf(
                FeatureIds.FARE_FILTERS to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry
            ),
            "blockedLocations" to listOf("Airport", "Highway Toll")
        )
        AppSettings.applyFirestoreSnapshotForTest(context, snapshotData)

        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(85.0f, AppSettings.minFare.value, 0.01f)
        assertEquals(2.0f, AppSettings.maxPickupDistanceFlow.value, 0.01f)
        assertTrue(AppSettings.isFloatingOverlayEnabled(context))
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("DAILY_PASS", AppSettings.activePassTypeFlow.value)
        assertEquals(futureExpiry, AppSettings.passExpiryTimestampFlow.value)
        assertTrue(AppSettings.isPassActiveFlow.value)
        assertEquals(futureExpiry, AppSettings.unlockedFeaturesFlow.value[FeatureIds.FARE_FILTERS])
        assertEquals(listOf("Airport", "Highway Toll"), AppSettings.getBlockedLocations(context))

        // 3. Verify synchronous order evaluation from RAM completes well under 300ms
        val offer = ParsedRideOffer(
            rawText = "₹120 • 1.2km away • Station to City Centre",
            totalFare = 120.0,
            fareBreakdown = listOf(120.0),
            pickupDistanceKm = 1.2,
            dropDistanceKm = 4.0,
            dropText = "City Centre",
            hasAcceptButton = true
        )
        val startNs = System.nanoTime()
        val evaluation = TextAnalysisEngine.evaluateRideOffer(offer, AppSettings.settingsState.value)
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue(evaluation.isAccepted)
        assertTrue("Expected synchronous RAM evaluation <300ms, got ${elapsedMs}ms", elapsedMs < 300L)

        // 4. Sign-out resets all business StateFlows to strict restricted defaults
        AppSettings.signOut(context)
        LicenseManager.resetStateForTesting(context)
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
        assertEquals(0.0f, AppSettings.minFare.value, 0.01f)
        assertEquals(0.0f, AppSettings.maxPickupDistanceFlow.value, 0.01f)
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals(null, AppSettings.activePassTypeFlow.value)
        assertEquals(0L, AppSettings.passExpiryTimestampFlow.value)
        assertTrue(AppSettings.unlockedFeaturesFlow.value.isEmpty())
    }
}




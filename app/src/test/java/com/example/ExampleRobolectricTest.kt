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
        assertEquals(3.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Update values
        AppSettings.setAutoAcceptEnabled(context, false)
        assertFalse(AppSettings.isAutoAcceptEnabled(context))

        AppSettings.setMinFare(context, 75.0f)
        assertEquals(75.0f, AppSettings.getMinFare(context), 0.01f)

        AppSettings.setMaxPickupDistance(context, 2.5f)
        assertEquals(2.5f, AppSettings.getMaxPickupDistance(context), 0.01f)

        // Reset for subsequent tests
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setMinFare(context, 60.0f)
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
        assertTrue(evalFarPickup.decisionReason.contains("REJECTED"))

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
    }

    @Test
    fun `test backward compatible methods`() {
        val result = TextAnalysisEngine.analyze("Offer: ₹150 + ₹50")
        assertEquals(200.0, result.totalCurrency ?: 0.0, 0.001)
        assertTrue(result.conditionsPassed)

        val passText = "Order ₹120 + ₹80 • Distance 4.2 km"
        val evalResult = TextAnalysisEngine.evaluate(
            text = passText,
            hasAcceptButton = true,
            minFilter = 150.0f,
            maxFilter = 8.0f
        )
        assertTrue(evalResult.conditionsPassed)
        assertEquals(200.0, evalResult.totalCurrency ?: 0.0, 0.001)
        assertEquals(4.2, evalResult.distanceKm ?: 0.0, 0.001)
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
        assertTrue(service.isAcceptAction("Accept Order"))
        assertTrue(service.isAcceptAction("Accept Ride"))
        assertTrue(service.isAcceptAction("Go"))
        assertTrue(service.isAcceptAction("Swipe to Accept"))
        assertTrue(service.isAcceptAction("Take Ride"))
        assertTrue(service.isAcceptAction("  accept  "))

        // Static or irrelevant strings that should NOT trigger evaluation
        assertFalse(service.isAcceptAction("Total Earnings: ₹1,500"))
        assertFalse(service.isAcceptAction("Weekly History 24.5 km"))
        assertFalse(service.isAcceptAction("Profile Settings"))
        assertFalse(service.isAcceptAction("Good Morning"))
        assertFalse(service.isAcceptAction("Gold Captain"))
        assertFalse(service.isAcceptAction(""))
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FareFilterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings.init(context)
        AppSettings.resetBusinessStateForTest(context)
    }

    @After
    fun tearDown() {
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun testBaselineConstants() {
        assertEquals(20f, AppSettings.MIN_FARE_RANGE_START, 0.01f)
        assertEquals(500f, AppSettings.MIN_FARE_RANGE_END, 0.01f)
        assertEquals(50f, AppSettings.DEFAULT_MIN_FARE, 0.01f)

        assertEquals(100f, AppSettings.MAX_FARE_RANGE_START, 0.01f)
        assertEquals(2000f, AppSettings.MAX_FARE_RANGE_END, 0.01f)
        assertEquals(500f, AppSettings.DEFAULT_MAX_FARE, 0.01f)
    }

    @Test
    fun testReactiveStateFlows() {
        // Client business setters must be strictly blocked and never mutate RAM StateFlows
        AppSettings.setMinFare(context, 75f)
        assertEquals(AppSettings.RESTRICTED_MIN_FARE, AppSettings.minFare.value, 0.01f)
        assertEquals(AppSettings.RESTRICTED_MIN_FARE, AppSettings.settingsState.value.minFare, 0.01f)
        assertEquals("Settings are managed by Admin app", ShadowToast.getTextOfLatestToast())

        AppSettings.setMinFareEnabled(context, true)
        assertFalse(AppSettings.isMinFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMinFareEnabled)

        AppSettings.setMaxFare(context, 800f)
        assertEquals(AppSettings.DEFAULT_MAX_FARE, AppSettings.maxFare.value, 0.01f)
        assertEquals(AppSettings.DEFAULT_MAX_FARE, AppSettings.settingsState.value.maxFare, 0.01f)

        AppSettings.setMaxFareEnabled(context, true)
        assertFalse(AppSettings.isMaxFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMaxFareEnabled)

        // Firestore snapshot listener pushes Admin-managed values directly into RAM StateFlows
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "minFare" to 75.0,
                "isMinFareEnabled" to true,
                "maxFare" to 800.0,
                "isMaxFareEnabled" to true
            )
        )
        assertEquals(75f, AppSettings.minFare.value, 0.01f)
        assertEquals(75f, AppSettings.settingsState.value.minFare, 0.01f)
        assertTrue(AppSettings.isMinFareEnabled.value)
        assertTrue(AppSettings.settingsState.value.isMinFareEnabled)
        assertEquals(800f, AppSettings.maxFare.value, 0.01f)
        assertEquals(800f, AppSettings.settingsState.value.maxFare, 0.01f)
        assertTrue(AppSettings.isMaxFareEnabled.value)
        assertTrue(AppSettings.settingsState.value.isMaxFareEnabled)

        // Disabling via Firestore snapshot updates RAM flows immediately
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "minFare" to 75.0,
                "isMinFareEnabled" to false,
                "maxFare" to 800.0,
                "isMaxFareEnabled" to false
            )
        )
        assertFalse(AppSettings.isMinFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMinFareEnabled)
        assertFalse(AppSettings.isMaxFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMaxFareEnabled)
    }

    @Test
    fun testValidationFunctions() {
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isMinFareEnabled" to true,
                "minFare" to 120.0,
                "isMaxFareEnabled" to true,
                "maxFare" to 400.0
            )
        )

        // Min fare valid within bounds and <= max
        assertTrue(AppSettings.isValidMinFare(100f, 400f))
        assertFalse(AppSettings.isValidMinFare(450f, 400f)) // Exceeds max fare
        assertFalse(AppSettings.isValidMinFare(10f, 400f))  // Below baseline min range (20f)
        assertFalse(AppSettings.isValidMinFare(600f, 400f)) // Exceeds baseline max range (500f)

        // Max fare valid within bounds and >= min
        assertTrue(AppSettings.isValidMaxFare(300f, 120f))
        assertFalse(AppSettings.isValidMaxFare(80f, 120f))  // Below min fare
        assertFalse(AppSettings.isValidMaxFare(50f, 120f))  // Below baseline range (100f)
        assertFalse(AppSettings.isValidMaxFare(2500f, 120f)) // Exceeds baseline range (2000f)

        // Clamp validation
        val clampedMin = AppSettings.validateMinFare(450f, 400f)
        assertEquals(400f, clampedMin, 0.01f)

        val clampedMax = AppSettings.validateMaxFare(100f, 200f)
        assertEquals(200f, clampedMax, 0.01f)
    }

    @Test
    fun testRapidSuccessiveRecordEvaluation() = kotlinx.coroutines.runBlocking {
        AppSettings.clearEvaluations()
        val initialCount = AppSettings.getRecentEvaluations().size

        val threads = (1..10).map { i ->
            Thread {
                val offer = ParsedRideOffer(
                    totalFare = 50.0 + i,
                    pickupDistanceKm = 1.0 + (i * 0.1),
                    dropDistanceKm = 5.0 + (i * 0.2),
                    pickupText = "Pickup $i km",
                    dropText = "Drop 5 km",
                    fareText = "₹${50 + i}",
                    hasAcceptButton = true
                )
                val evaluation = RideEvaluation(
                    offer = offer,
                    isAutoAcceptEnabled = true,
                    passesFare = true,
                    passesPickupDistance = true,
                    isAccepted = true,
                    decisionReason = "Test evaluation $i"
                )
                AppSettings.recordEvaluation(context, evaluation)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        // Allow async mutex worker to record all evaluations
        kotlinx.coroutines.delay(1000)

        val finalCount = AppSettings.getRecentEvaluations().size
        assertEquals(initialCount + 10, finalCount)
    }
}


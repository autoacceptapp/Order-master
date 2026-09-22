package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FareFilterTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings.init(context)
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
        AppSettings.setMinFare(context, 75f)
        assertEquals(75f, AppSettings.minFare.value, 0.01f)
        assertEquals(75f, AppSettings.settingsState.value.minFare, 0.01f)

        AppSettings.setMinFareEnabled(context, false)
        assertFalse(AppSettings.isMinFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMinFareEnabled)

        AppSettings.setMinFareEnabled(context, true)
        assertTrue(AppSettings.isMinFareEnabled.value)
        assertTrue(AppSettings.settingsState.value.isMinFareEnabled)

        AppSettings.setMaxFare(context, 800f)
        assertEquals(800f, AppSettings.maxFare.value, 0.01f)
        assertEquals(800f, AppSettings.settingsState.value.maxFare, 0.01f)

        AppSettings.setMaxFareEnabled(context, false)
        assertFalse(AppSettings.isMaxFareEnabled.value)
        assertFalse(AppSettings.settingsState.value.isMaxFareEnabled)

        AppSettings.setMaxFareEnabled(context, true)
        assertTrue(AppSettings.isMaxFareEnabled.value)
        assertTrue(AppSettings.settingsState.value.isMaxFareEnabled)
    }

    @Test
    fun testValidationFunctions() {
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxFareEnabled(context, true)
        AppSettings.setMaxFare(context, 400f)

        // Min fare valid within bounds and <= max
        assertTrue(AppSettings.isValidMinFare(100f, 400f))
        assertFalse(AppSettings.isValidMinFare(450f, 400f)) // Exceeds max fare
        assertFalse(AppSettings.isValidMinFare(10f, 400f))  // Below baseline min range (20f)
        assertFalse(AppSettings.isValidMinFare(600f, 400f)) // Exceeds baseline max range (500f)

        // Max fare valid within bounds and >= min
        AppSettings.setMinFare(context, 120f)
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
}

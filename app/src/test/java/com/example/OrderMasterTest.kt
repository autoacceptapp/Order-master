package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OrderMasterTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AppSettings.init(context)
        database = AppDatabase.getDatabase(context)
    }

    @After
    fun tearDown() {
        // AppDatabase singleton lifecycle handled by Room
    }

    @Test
    fun testModularFilterSettings() {
        // Test individual switches
        AppSettings.setMinFareEnabled(context, false)
        assertFalse(AppSettings.isMinFareEnabled(context))
        AppSettings.setMinFareEnabled(context, true)
        assertTrue(AppSettings.isMinFareEnabled(context))

        AppSettings.setMaxFareEnabled(context, false)
        assertFalse(AppSettings.isMaxFareEnabled(context))

        AppSettings.setMaxPickupDistanceEnabled(context, false)
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))

        AppSettings.setMaxDropDistanceEnabled(context, true)
        assertTrue(AppSettings.isMaxDropDistanceEnabled(context))
        AppSettings.setMaxDropDistance(context, 12.0f)
        assertEquals(12.0f, AppSettings.getMaxDropDistance(context), 0.01f)
    }

    @Test
    fun testQuickPresets() {
        AppSettings.applyPreset(context, "peak")
        assertEquals(80.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(2.0f, AppSettings.getMaxPickupDistance(context), 0.01f)

        AppSettings.applyPreset(context, "short")
        assertEquals(40.0f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(150.0f, AppSettings.getMaxFare(context), 0.01f)
        assertEquals(1.5f, AppSettings.getMaxPickupDistance(context), 0.01f)
        assertTrue(AppSettings.isMaxDropDistanceEnabled(context))
        assertEquals(5.0f, AppSettings.getMaxDropDistance(context), 0.01f)

        AppSettings.applyPreset(context, "default")
        assertEquals(AppSettings.DEFAULT_MIN_FARE, AppSettings.getMinFare(context), 0.01f)
        assertEquals(AppSettings.DEFAULT_MAX_PICKUP_DISTANCE, AppSettings.getMaxPickupDistance(context), 0.01f)
    }

    @Test
    fun testTextAnalysisEngineWithModularFilters() {
        val offer = ParsedRideOffer(
            rawText = "₹120 • 1.5km away • Station to Airport",
            totalFare = 120.0,
            fareBreakdown = listOf(120.0),
            pickupDistanceKm = 1.5,
            dropDistanceKm = 8.0,
            hasAcceptButton = true
        )

        // Matching all active criteria
        val eval = TextAnalysisEngine.evaluateRideOffer(
            offer = offer,
            minFare = 60.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 10.0f
        )
        assertTrue(eval.isAccepted)
        assertTrue(eval.decisionReason.contains("MATCHED"))

        // Exceeding drop distance
        val evalDropExceeded = TextAnalysisEngine.evaluateRideOffer(
            offer = offer,
            minFare = 60.0f,
            maxFare = 5000.0f,
            maxPickupDistance = 3.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 5.0f
        )
        assertFalse(evalDropExceeded.isAccepted)
        assertTrue(evalDropExceeded.decisionReason.contains("Drop distance"))
    }

    @Test
    fun testRoomDatabaseOrderPersistence() = runBlocking {
        val dao = database.orderDao()
        val initialCount = dao.getAllOrders().first().size

        val testOrder = OrderEntity(
            timestamp = System.currentTimeMillis(),
            fare = 250.0,
            pickupDistanceKm = 1.2,
            dropDistanceKm = 14.5,
            pickupLocation = "Test Station",
            dropLocation = "Test Airport",
            isAccepted = true,
            decisionReason = "MATCHED: Valid fare and distance",
            rawText = "₹250 Test Station to Test Airport"
        )

        dao.insertOrder(testOrder)
        val orders = dao.getAllOrders().first()
        assertTrue(orders.size > initialCount)

        val inserted = orders.firstOrNull { it.pickupLocation == "Test Station" }
        assertNotNull(inserted)
        assertEquals(250.0, inserted!!.fare, 0.01)
        assertTrue(inserted.isAccepted)
    }
}

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

    @Test
    fun testSemanticVersioningComparison() {
        // Direct SemVer checks
        assertTrue(isUpdateAvailable(currentVersion = "1.0.2", latestVersion = "1.1.0"))
        assertTrue(isUpdateAvailable(currentVersion = "1.0.2", latestVersion = "1.0.3"))
        assertTrue(isUpdateAvailable(currentVersion = "1.0.2", latestVersion = "2.0.0"))

        // Exact match -> false
        assertFalse(isUpdateAvailable(currentVersion = "1.0.2", latestVersion = "1.0.2"))
        assertFalse(isUpdateAvailable(currentVersion = "v1.0.2", latestVersion = "1.0.2"))
        assertFalse(isUpdateAvailable(currentVersion = "1.0.2", latestVersion = "v1.0.2"))

        // Server is older (downgrade / lag) -> false
        assertFalse(isUpdateAvailable(currentVersion = "1.1.0", latestVersion = "1.0.2"))
        assertFalse(isUpdateAvailable(currentVersion = "2.0.0", latestVersion = "1.9.9"))

        // Handling 'v' prefix and release tags
        assertTrue(isUpdateAvailable(currentVersion = "v1.0.0", latestVersion = "v1.0.1"))
        assertTrue(isUpdateAvailable(currentVersion = "1.0.0", latestVersion = "release-1.0.5"))

        // Pre-release versions
        // 1.0.0 stable is newer than 1.0.0-rc.1
        assertFalse(isUpdateAvailable(currentVersion = "1.0.0", latestVersion = "1.0.0-rc.1"))
        // 1.0.1-rc.1 is newer than 1.0.0
        assertTrue(isUpdateAvailable(currentVersion = "1.0.0", latestVersion = "1.0.1-rc.1"))
    }

    @Test
    fun testRapidoFareValidationAndGarbageFiltering() {
        // Valid Rapido fares (₹20 to ₹2000)
        assertTrue(TextAnalysisEngine.isValidRapidoFare(20.0))
        assertTrue(TextAnalysisEngine.isValidRapidoFare(55.0))
        assertTrue(TextAnalysisEngine.isValidRapidoFare(250.0))
        assertTrue(TextAnalysisEngine.isValidRapidoFare(2000.0))

        // Invalid garbage numbers and below-minimum values
        assertFalse(TextAnalysisEngine.isValidRapidoFare(19.0))
        assertFalse(TextAnalysisEngine.isValidRapidoFare(0.0))
        assertFalse(TextAnalysisEngine.isValidRapidoFare(37725.0)) // Garbage order ID or counter
        assertFalse(TextAnalysisEngine.isValidRapidoFare(99999.0))
        assertFalse(TextAnalysisEngine.isValidRapidoFare(null))

        // Currency extraction ignoring ₹37725 and timestamps (12:30)
        val (totalGarbage, _) = TextAnalysisEngine.extractCurrencies("Order #37725 at 12:30 PM with ₹37725")
        assertEquals(null, totalGarbage)

        // Currency extraction with valid fare
        val (validTotal, breakdown) = TextAnalysisEngine.extractCurrencies("New Order • 12:30 PM • ₹145 • 1.2 km away")
        assertEquals(145.0, validTotal ?: 0.0, 0.01)
        assertEquals(1, breakdown.size)
        assertEquals(145.0, breakdown[0], 0.01)

        // Parse target offer ignoring garbage numbers and timestamps
        val parsedGarbage = TextAnalysisEngine.parseTargetOffer("Time 12:30 PM • ID ₹37725 • Accept")
        // Fare should not be 37725
        val extractedFare = parsedGarbage?.fare ?: 0.0
        assertEquals(0.0, extractedFare, 0.01)
    }
}

package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.OrderEntity
import com.example.data.OrderRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformHardeningTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        LicenseManager.resetStateForTesting(context)
        AppSettings.init(context)
        AppSettings.resetBusinessStateForTest(context)
        ApkDownloader.cancelDownload(context)
    }

    @After
    fun tearDown() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        ApkDownloader.cancelDownload(context)
    }

    @Test
    fun `overlay drag bounds subtract top status bar and bottom gesture insets`() {
        val bounds = FloatingOverlayService.computeOverlayDragBounds(
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            insetLeftPx = 0,
            insetTopPx = 128,
            insetRightPx = 0,
            insetBottomPx = 144,
            buttonSizePx = 140
        )

        assertEquals(0, bounds.minX)
        assertEquals(128, bounds.minY)
        assertEquals(940, bounds.maxX)
        assertEquals(2116, bounds.maxY)

        // Landscape cutouts / nav bar on left & right are also respected
        val landscapeBounds = FloatingOverlayService.computeOverlayDragBounds(
            screenWidthPx = 2400,
            screenHeightPx = 1080,
            insetLeftPx = 96,
            insetTopPx = 64,
            insetRightPx = 48,
            insetBottomPx = 72,
            buttonSizePx = 140
        )
        assertEquals(96, landscapeBounds.minX)
        assertEquals(64, landscapeBounds.minY)
        assertEquals(2212, landscapeBounds.maxX)
        assertEquals(868, landscapeBounds.maxY)
    }

    @Test
    fun `floating overlay toggleMode shows lock toast and refuses dead state when TTS_VOICE is locked`() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry
            )
        )
        AppSettings.setAutoClickEnabled(context, true)
        assertTrue(AppSettings.isAutoClickEnabled(context))
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE))

        val service = Robolectric.buildService(FloatingOverlayService::class.java).get()
        service.toggleMode()

        assertEquals(
            "Voice Announcements locked \u2014 unlock in Store",
            ShadowToast.getTextOfLatestToast()
        )
        assertTrue(
            "Auto-Click must remain ON instead of silently entering a dead muted VOICE state",
            AppSettings.isAutoClickEnabled(context)
        )
    }

    @Test
    fun `floating overlay toggleMode switches to VOICE smoothly when TTS_VOICE is unlocked`() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry
            )
        )
        AppSettings.setAutoClickEnabled(context, true)
        AppSettings.setVoiceAnnouncerEnabled(context, false)

        val service = Robolectric.buildService(FloatingOverlayService::class.java).get()
        service.toggleMode()

        assertFalse(AppSettings.isAutoClickEnabled(context))
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals(
            "Voice-Only Mode Active (TTS Spoken Alerts Only)",
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun `download complete validation ignores unrequested ids even on cold start`() {
        // Fresh state: activeDownloadId == -1L and SharedPreferences empty
        assertFalse(ApkDownloader.isExpectedDownloadId(context, -1L))
        assertFalse(ApkDownloader.isExpectedDownloadId(context, 0L))
        assertFalse(ApkDownloader.isExpectedDownloadId(context, 9999L))

        // Spoofed/external broadcast must be ignored without changing DownloadState
        val receiver = DownloadCompleteReceiver()
        val spoofedIntent = Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE).apply {
            putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 4242L)
        }
        receiver.onReceive(context, spoofedIntent)
        assertTrue(ApkDownloader.downloadState.value is DownloadState.Idle)

        // When a legitimate active_download_id is persisted, only that exact ID matches
        context.getSharedPreferences(ApkDownloader.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(ApkDownloader.KEY_ACTIVE_DOWNLOAD_ID, 777L)
            .apply()

        assertTrue(ApkDownloader.isExpectedDownloadId(context, 777L))
        assertFalse(ApkDownloader.isExpectedDownloadId(context, 778L))
    }

    @Test
    fun `room order dao prunes entries older than 30 days while retaining recent records`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.orderDao()
            val nowMs = 1_800_000_000_000L
            val dayMs = 86_400_000L

            dao.insertOrder(
                OrderEntity(
                    timestamp = nowMs - (35L * dayMs),
                    fare = 120.0,
                    pickupDistanceKm = 1.0,
                    dropDistanceKm = 5.0,
                    pickupLocation = "Old Pickup",
                    dropLocation = "Old Drop",
                    isAccepted = true,
                    decisionReason = "Accepted",
                    rawText = "old"
                )
            )
            dao.insertOrder(
                OrderEntity(
                    timestamp = nowMs - (10L * dayMs),
                    fare = 180.0,
                    pickupDistanceKm = 0.8,
                    dropDistanceKm = 6.2,
                    pickupLocation = "Recent Pickup",
                    dropLocation = "Recent Drop",
                    isAccepted = true,
                    decisionReason = "Accepted",
                    rawText = "recent"
                )
            )

            val deletedCount = dao.pruneOrdersOlderThan(nowMs - OrderRepository.RETENTION_PERIOD_MS)
            assertEquals(1, deletedCount)

            val remaining = dao.getAllOrders().first()
            assertEquals(1, remaining.size)
            assertEquals("Recent Pickup", remaining.first().pickupLocation)
        } finally {
            db.close()
        }
    }

    @Test
    fun `openOemAutoStartSettings never throws across OEM manufacturers`() {
        val oems = listOf("Xiaomi", "Vivo", "Oppo", "Realme", "OnePlus", "Samsung", "Google")
        val originalManufacturer = Build.MANUFACTURER
        val originalBrand = Build.BRAND
        try {
            for (oem in oems) {
                ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", oem)
                ReflectionHelpers.setStaticField(Build::class.java, "BRAND", oem)
                assertTrue("Expected safe fallback true for $oem", PermissionUtils.openOemAutoStartSettings(context))
            }
        } finally {
            ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
            ReflectionHelpers.setStaticField(Build::class.java, "BRAND", originalBrand)
        }
    }

    @Test
    fun `github update manager fallback url selects release or debug apk by build type`() {
        assertEquals("app-debug.apk", GitHubUpdateManager.resolveFallbackApkFileName(isDebugBuild = true))
        assertEquals("app-release.apk", GitHubUpdateManager.resolveFallbackApkFileName(isDebugBuild = false))

        assertTrue(
            GitHubUpdateManager.resolveFallbackDownloadUrl("v1.2.3", isDebugBuild = false)
                .endsWith("/releases/download/v1.2.3/app-release.apk")
        )
        assertTrue(
            GitHubUpdateManager.resolveFallbackDownloadUrl("v1.2.3", isDebugBuild = true)
                .endsWith("/releases/download/v1.2.3/app-debug.apk")
        )
    }

    @Test
    fun `utf8 hindi and rupee string resources render intact`() {
        assertEquals("Order Master", context.getString(R.string.app_name))
        assertEquals("Hindi (\u0939\u093F\u0928\u094D\u0926\u0940)", context.getString(R.string.language_hindi))
        assertEquals("\u20B999", context.getString(R.string.payment_price_format, 99))
    }
}

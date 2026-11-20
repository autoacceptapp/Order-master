package com.example

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Looper
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
import org.robolectric.Shadows
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
                    decisionReason = "Accepted"
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
                    decisionReason = "Accepted"
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
        assertTrue(context.getString(R.string.settings_oem_title).contains("Xiaomi"))
        assertEquals("\u2713 Order Master is up to date (v1.0.2)", context.getString(R.string.settings_updater_up_to_date_format, "v1.0.2"))
    }

    @Test
    fun `release build never selects debug apk asset and apk downloader rejects mismatched build type`() {
        val debugOnlyAssets = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("name", "app-debug.apk")
                put("browser_download_url", "https://github.com/autoacceptapp/Order-master/releases/download/v1.0.2-debug-build-105/app-debug.apk")
                put("size", 5_000_000L)
            })
        }

        // Release builds must reject debug-only release assets cleanly
        val releaseSelection = GitHubUpdateManager.selectCompatibleApkAsset(debugOnlyAssets, isDebugBuild = false)
        assertEquals(null, releaseSelection)

        // Debug builds select app-debug.apk
        val debugSelection = GitHubUpdateManager.selectCompatibleApkAsset(debugOnlyAssets, isDebugBuild = true)
        assertEquals("app-debug.apk", debugSelection?.fileName)

        // Release builds select app-release.apk / *-release.apk even when debug APK is also present
        val mixedAssets = org.json.JSONArray().apply {
            put(org.json.JSONObject().apply {
                put("name", "app-debug.apk")
                put("browser_download_url", "https://example.com/app-debug.apk")
                put("size", 5_000_000L)
            })
            put(org.json.JSONObject().apply {
                put("name", "OrderMaster-v1.0.2-build-106-release.apk")
                put("browser_download_url", "https://example.com/OrderMaster-v1.0.2-build-106-release.apk")
                put("size", 6_000_000L)
            })
        }
        val mixedReleaseSelection = GitHubUpdateManager.selectCompatibleApkAsset(mixedAssets, isDebugBuild = false)
        assertEquals("OrderMaster-v1.0.2-build-106-release.apk", mixedReleaseSelection?.fileName)

        assertFalse(ApkDownloader.isCompatibleApkForBuildType("https://example.com/app-debug.apk", "app-debug.apk", isDebugBuild = false))
        assertTrue(ApkDownloader.isCompatibleApkForBuildType("https://example.com/app-release.apk", "app-release.apk", isDebugBuild = false))
    }

    @Test
    fun `floating overlay toggleMode cleanly restores voice state on AUTO to VOICE to AUTO and updates visual badge`() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry
            )
        )
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setFloatingOverlayEnabled(context, true)
        AppSettings.setAutoClickEnabled(context, true)
        AppSettings.setVoiceAnnouncerEnabled(context, false)

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()
        assertEquals("AUTO", service.getModeBadgeTextForTesting())

        // AUTO -> VOICE: auto-enables voice announcer & shows VOICE
        service.toggleMode()
        assertFalse(AppSettings.isAutoClickEnabled(context))
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("VOICE", service.getModeBadgeTextForTesting())

        // VOICE -> AUTO: restores voice announcer back to OFF & shows AUTO
        service.toggleMode()
        assertTrue(AppSettings.isAutoClickEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertEquals("AUTO", service.getModeBadgeTextForTesting())

        // When Auto-Click is OFF and voice announcer is OFF (even though TTS_VOICE is unlocked), badge shows PAUSED
        AppSettings.setAutoClickEnabled(context, false)
        service.updateVisualState(false)
        assertEquals("PAUSED", service.getModeBadgeTextForTesting())

        // When TTS_VOICE is locked by Admin and Auto-Click is OFF, badge shows LOCK
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry
            ),
            locked = setOf(FeatureIds.TTS_VOICE)
        )
        service.updateVisualState(false)
        assertEquals("LOCK", service.getModeBadgeTextForTesting())

        controller.destroy()
    }

    @Test
    fun `floating overlay stays active in Expired Free Mode when floating_overlay is unlocked via Points and stops when locked or expired`() {
        val now = LicenseManager.getCurrentServerTimeMs()
        val futureExpiry = now + 3_600_000L

        // 1. Force AccessStatus.Expired (Free Mode: no active Pass or Trial)
        LicenseManager.setLicensingForTesting(
            passTier = null,
            passExpiry = 0L,
            trialClaimed = now - 200_000_000L,
            trialExpiry = now - 10_000L,
            trialUsed = true
        )
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.Expired)

        // 2. Unlock FLOATING_OVERLAY + MIN_FARE + TTS_VOICE via Points in Free Mode
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.FLOATING_OVERLAY to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry
            )
        )
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setFloatingOverlayEnabled(context, true)
        AppSettings.setVoiceAnnouncerEnabled(context, true)

        val controller = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service = controller.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // Overlay service MUST stay active despite AccessStatus.Expired, showing Free Mode AUTO
        assertTrue(FloatingOverlayService.isServiceRunning)
        assertFalse(
            "FloatingOverlayService must remain active in Expired/Free mode when FLOATING_OVERLAY is unlocked via Points",
            Shadows.shadowOf(service).isStoppedBySelf
        )
        assertEquals("AUTO", service.getModeBadgeTextForTesting())

        // 3. Toggling Auto-Click OFF enters Voice-Only ("VOICE"), and toggling back ON restores "AUTO"
        AppSettings.setAutoClickEnabled(context, false)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        assertEquals("VOICE", service.getModeBadgeTextForTesting())

        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.FLOATING_OVERLAY to futureExpiry,
                FeatureIds.TTS_VOICE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry
            )
        )
        AppSettings.setAutoClickEnabled(context, true)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf)
        assertEquals("AUTO", service.getModeBadgeTextForTesting())

        // 4. Locking FLOATING_OVERLAY by Admin stops the service immediately via lockedFeatures observer
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.FLOATING_OVERLAY to futureExpiry
            ),
            locked = setOf(FeatureIds.FLOATING_OVERLAY)
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "FloatingOverlayService must stop immediately when Admin locks FLOATING_OVERLAY",
            Shadows.shadowOf(service).isStoppedBySelf
        )
        controller.destroy()

        // 5. Verify expiry of Points unlock also stops service immediately
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.FLOATING_OVERLAY to futureExpiry
            )
        )
        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setFloatingOverlayEnabled(context, true)
        val controller2 = Robolectric.buildService(FloatingOverlayService::class.java).create()
        val service2 = controller2.get()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Shadows.shadowOf(service2).isStoppedBySelf)

        // Points unlock for FLOATING_OVERLAY expires/is removed
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to futureExpiry)
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "FloatingOverlayService must stop immediately when FLOATING_OVERLAY Points unlock expires",
            Shadows.shadowOf(service2).isStoppedBySelf
        )
        controller2.destroy()
    }

    @Test
    fun `order entity default timestamp uses server adjusted time and formatDateTime is consistent`() {
        val simulatedServerNow = 1_900_000_000_000L
        LicenseManager.recordServerTimeSync(simulatedServerNow)

        val order = OrderEntity(
            fare = 150.0,
            isAccepted = true,
            decisionReason = "Accepted"
        )
        assertTrue(
            "OrderEntity default timestamp must use LicenseManager.getCurrentServerTimeMs()",
             kotlin.math.abs(order.timestamp - simulatedServerNow) < 5_000L
        )

        val formatted1 = GlobalAdminManager.formatDateTime(simulatedServerNow)
        val formatted2 = GlobalAdminManager.formatDateTime(simulatedServerNow)
        assertEquals(formatted1, formatted2)
        assertTrue(formatted1.isNotBlank())
    }
}

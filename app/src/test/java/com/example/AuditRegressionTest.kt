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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuditRegressionTest {

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
    fun `hasAcceptAction enforces word boundary around Go and rejects substring false positives`() {
        assertFalse(TextAnalysisEngine.hasAcceptAction("Google Maps navigation to drop"))
        assertFalse(TextAnalysisEngine.hasAcceptAction("Going to pickup 1.2 km"))
        assertFalse(TextAnalysisEngine.hasAcceptAction("Cargo delivery ₹120"))

        assertTrue(TextAnalysisEngine.hasAcceptAction("Go"))
        assertTrue(TextAnalysisEngine.hasAcceptAction("Tap Go to start trip"))
        assertTrue(TextAnalysisEngine.hasAcceptAction("Accept Ride ₹85"))
    }

    @Test
    fun `evaluateRideOffer rejects ride when maxDropDistance is enabled and dropDistanceKm is null`() {
        val offerWithoutDrop = ParsedRideOffer(
            rawText = "₹95 Pickup 1.0 km [Accept]",
            totalFare = 95.0,
            pickupDistanceKm = 1.0,
            dropDistanceKm = null,
            hasAcceptButton = true
        )

        val evaluation = TextAnalysisEngine.evaluateRideOffer(
            offer = offerWithoutDrop,
            minFare = 30.0f,
            maxFare = 500.0f,
            maxPickupDistance = 5.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 8.0f
        )

        assertFalse(evaluation.isAccepted)
        assertTrue(
            "Expected missing drop distance rejection message, got: ${evaluation.decisionReason}",
            evaluation.decisionReason.contains("Drop distance could not be determined")
        )

        val offerWithValidDrop = offerWithoutDrop.copy(dropDistanceKm = 4.5)
        val validEval = TextAnalysisEngine.evaluateRideOffer(
            offer = offerWithValidDrop,
            minFare = 30.0f,
            maxFare = 500.0f,
            maxPickupDistance = 5.0f,
            isAutoAcceptEnabled = true,
            isMinFareEnabled = true,
            isMaxFareEnabled = true,
            isMaxPickupDistanceEnabled = true,
            isMaxDropDistanceEnabled = true,
            maxDropDistance = 8.0f
        )
        assertTrue(validEval.isAccepted)
    }

    @Test
    fun `apk downloader handleDownloadRemoved clears saved tracking and emits Error state`() {
        context.getSharedPreferences("apk_downloader_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("active_download_id", 888L)
            .putString("download_file_name", "app-release.apk")
            .commit()
        assertTrue(ApkDownloader.isExpectedDownloadId(context, 888L))

        ApkDownloader.handleDownloadRemoved(context)

        assertFalse(ApkDownloader.isExpectedDownloadId(context, 888L))
        val state = ApkDownloader.downloadState.value
        assertTrue("Expected DownloadState.Error after removal, got $state", state is DownloadState.Error)
        assertEquals("Download cancelled or removed.", (state as DownloadState.Error).message)
    }

    @Test
    fun `remove ads pass does not grant pro pass access in LicenseManager or PassManager`() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.REMOVE_ADS,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )

        LicenseManager.refreshAccessStatus(context)

        assertFalse(
            "REMOVE_ADS tier must never activate PassActive Pro state",
            LicenseManager.accessStatus.value is AccessStatus.PassActive
        )
        assertFalse(
            "REMOVE_ADS tier must not grant Pro access",
            LicenseManager.isAccessGranted()
        )
        assertFalse(
            "REMOVE_ADS tier must not mark PassManager active",
            PassManager.isPassActive(context)
        )
        assertTrue(
            "REMOVE_ADS tier must still remove ads",
            LicenseManager.isAdsRemoved()
        )
    }

    @Test
    fun `merged manifest strips privacy sandbox adservices permissions to prevent MEASUREMENT_SERVICE onNullBinding`() {
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS
        )
        val requested = packageInfo.requestedPermissions?.toSet() ?: emptySet()
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_ATTRIBUTION"))
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_AD_ID"))
        assertFalse(requested.contains("android.permission.ACCESS_ADSERVICES_TOPICS"))
    }
}

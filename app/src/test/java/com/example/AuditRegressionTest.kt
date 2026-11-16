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

    @Test
    fun `tutorial steps and voice demo labels are complete in Hindi and English with intact UTF-8 text`() {
        val hindiSteps = com.example.ui.components.getTutorialSteps(context, isHindi = true)
        val englishSteps = com.example.ui.components.getTutorialSteps(context, isHindi = false)

        assertEquals(7, hindiSteps.size)
        assertEquals(7, englishSteps.size)

        val devanagariRegex = Regex("[\\u0900-\\u097F]+")
        hindiSteps.forEachIndexed { index, step ->
            assertTrue("Hindi step ${index + 1} badge blank", step.badge.isNotBlank())
            assertTrue("Hindi step ${index + 1} title blank", step.title.isNotBlank())
            assertTrue("Hindi step ${index + 1} shortDesc blank", step.shortDesc.isNotBlank())
            assertTrue("Hindi step ${index + 1} detailExplainer blank", step.detailExplainer.isNotBlank())
            assertTrue("Hindi step ${index + 1} recommendedSetting blank", step.recommendedSetting.isNotBlank())
            assertTrue("Hindi step ${index + 1} speechText blank", step.speechText.isNotBlank())
            assertTrue(
                "Hindi step ${index + 1} title missing Devanagari text: ${step.title}",
                devanagariRegex.containsMatchIn(step.title)
            )
            assertFalse("Replacement char found in Hindi step ${index + 1}", step.detailExplainer.contains('\uFFFD'))
            assertFalse("Question-mark placeholder found in Hindi step ${index + 1}", step.detailExplainer.contains("??"))
        }

        assertTrue("Hindi step 3 must contain Rupee symbol", hindiSteps[2].detailExplainer.contains('\u20B9'))
        assertTrue("Hindi step 4 must contain Rupee symbol", hindiSteps[3].detailExplainer.contains('\u20B9'))

        englishSteps.forEachIndexed { index, step ->
            assertTrue("English step ${index + 1} title blank", step.title.isNotBlank())
            assertTrue("English step ${index + 1} detailExplainer blank", step.detailExplainer.isNotBlank())
            assertTrue("English step ${index + 1} speechText blank", step.speechText.isNotBlank())
            assertFalse("Replacement char found in English step ${index + 1}", step.detailExplainer.contains('\uFFFD'))
        }

        val hindiVoiceLabels = com.example.ui.components.getVoiceDemoFieldLabels(isHindi = true)
        val englishVoiceLabels = com.example.ui.components.getVoiceDemoFieldLabels(isHindi = false)
        assertEquals(5, hindiVoiceLabels.size)
        assertEquals(5, englishVoiceLabels.size)
        assertEquals("1. \u0915\u093F\u0930\u093E\u092F\u093E (Fare):", hindiVoiceLabels[0])
        assertEquals("1. Fare:", englishVoiceLabels[0])
        hindiVoiceLabels.forEach { label ->
            assertTrue(label.isNotBlank())
            assertTrue("Hindi voice label missing Devanagari: $label", devanagariRegex.containsMatchIn(label))
        }
    }

    @Test
    fun `fare range presets stay within slider bounds and match clamped settings state`() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.MAX_FARE to futureExpiry
            )
        )
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxFareEnabled(context, true)

        assertTrue(AppSettings.FARE_RANGE_PRESETS.isNotEmpty())
        AppSettings.FARE_RANGE_PRESETS.forEach { (label, rawMin, rawMax) ->
            assertTrue("Preset label must contain Rupee symbol: $label", label.contains('\u20B9'))
            assertTrue(
                "Preset min $rawMin outside MIN_FARE range",
                rawMin in AppSettings.MIN_FARE_RANGE_START..AppSettings.MIN_FARE_RANGE_END
            )
            assertTrue(
                "Preset max $rawMax outside MAX_FARE range",
                rawMax in AppSettings.MAX_FARE_RANGE_START..AppSettings.MAX_FARE_RANGE_END
            )
            assertTrue("Preset min $rawMin must be <= max $rawMax", rawMin <= rawMax)

            if (rawMin > AppSettings.settingsState.value.maxFare) {
                AppSettings.setMaxFare(context, rawMax)
                AppSettings.setMinFare(context, rawMin)
            } else {
                AppSettings.setMinFare(context, rawMin)
                AppSettings.setMaxFare(context, rawMax)
            }

            val updated = AppSettings.settingsState.value
            assertEquals("Min fare mismatch for preset $label", rawMin, updated.minFare, 0.01f)
            assertEquals("Max fare mismatch for preset $label", rawMax, updated.maxFare, 0.01f)
        }
    }

    @Test
    fun `action queue pass payloads omit client-forged prices or durations`() {
        val purchasePayload = LicenseManager.buildPurchasePassPayload(PassTier.WEEKLY)
        assertEquals(setOf("passTier"), purchasePayload.keys)
        assertEquals(PassTier.WEEKLY.id, purchasePayload["passTier"])
        assertFalse(purchasePayload.containsKey("pointsCost"))
        assertFalse(purchasePayload.containsKey("durationDays"))
        assertFalse(purchasePayload.containsKey("durationMs"))

        val paymentPayload = LicenseManager.buildActivatePassPaymentPayload(PassTier.MONTHLY, "123456789012")
        assertEquals(setOf("passTier", "utr"), paymentPayload.keys)
        assertEquals(PassTier.MONTHLY.id, paymentPayload["passTier"])
        assertEquals("123456789012", paymentPayload["utr"])
        assertFalse(paymentPayload.containsKey("amount"))
        assertFalse(paymentPayload.containsKey("durationDays"))
        assertFalse(paymentPayload.containsKey("durationMs"))
        assertFalse(paymentPayload.containsKey("transactionId"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `pass manager isPassActiveFlow is strictly read-only from LicenseManager accessStatus`() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L

        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()
        assertFalse(PassManager.isPassActiveFlow.value)

        // Calling legacy setPassStatus(true) must remain ignored
        PassManager.setPassStatus(context, true)
        assertFalse(PassManager.isPassActiveFlow.value)
        assertFalse(PassManager.isPassActive(context))

        // Active Trial -> true
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = null,
            passExpiry = 0L,
            trialClaimed = System.currentTimeMillis() - 10_000L,
            trialExpiry = futureExpiry,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.TrialActive)
        assertTrue(PassManager.isPassActiveFlow.value)

        // Expired Trial + expired Pass -> false
        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()
        assertFalse(PassManager.isPassActiveFlow.value)

        // Remove Ads Pass alone -> false
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.REMOVE_ADS,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertFalse(PassManager.isPassActiveFlow.value)

        // Pro Pass (Weekly) -> true
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.WEEKLY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertTrue(PassManager.isPassActiveFlow.value)
    }

    @Test
    fun `pending referral invite code persists across cold restarts and clears cleanly`() {
        ReferralManager.clearPendingInviteCode(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)

        // Capture invite code from deep link
        ReferralManager.setPendingInviteCode(context, " om78xy90 ")
        assertEquals("OM78XY90", ReferralManager.pendingInviteCode.value)

        // Simulate cold process restart: wipe RAM flow and restore via init()
        ReferralManager.pendingInviteCode.value = ""
        ReferralManager.init(context)
        assertEquals("OM78XY90", ReferralManager.pendingInviteCode.value)

        // Redemption / explicit clear removes both RAM state and SharedPreferences
        ReferralManager.clearPendingInviteCode(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)
        ReferralManager.init(context)
        assertEquals("", ReferralManager.pendingInviteCode.value)
    }

    @Test
    fun `order repository pruneExpiredOrdersIfNeeded removes orders older than 30 days`() = kotlinx.coroutines.test.runTest {
        val db = com.example.data.AppDatabase.getDatabase(context)
        val dao = db.orderDao()
        dao.clearAll()

        val nowMs = 1_800_000_000_000L
        val dayMs = 86_400_000L
        dao.insertOrder(
            com.example.data.OrderEntity(
                timestamp = nowMs - (35L * dayMs),
                fare = 90.0,
                pickupDistanceKm = 1.1,
                dropDistanceKm = 4.0,
                pickupLocation = "Expired Pickup",
                dropLocation = "Expired Drop",
                isAccepted = false,
                decisionReason = "Rejected",
                rawText = "expired"
            )
        )
        dao.insertOrder(
            com.example.data.OrderEntity(
                timestamp = nowMs - (5L * dayMs),
                fare = 160.0,
                pickupDistanceKm = 0.7,
                dropDistanceKm = 5.5,
                pickupLocation = "Fresh Pickup",
                dropLocation = "Fresh Drop",
                isAccepted = true,
                decisionReason = "Accepted",
                rawText = "fresh"
            )
        )

        val repo = com.example.data.OrderRepository(context)
        val deleted = repo.pruneExpiredOrdersIfNeeded(nowMs)
        assertEquals(1, deleted)
        assertEquals(1, repo.getCount())
        // Subsequent call in the same session is throttled
        assertEquals(0, repo.pruneExpiredOrdersIfNeeded(nowMs))

        dao.clearAll()
    }
}


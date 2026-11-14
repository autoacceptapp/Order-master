package com.example

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.data.PaymentVerificationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SecurityHardeningTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        LicenseManager.resetStateForTesting(context)
    }

    @Test
    fun tamperedLicenseCacheHmacIsRejectedAndWipedImmediately() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )

        // Valid signed cache reads properly before tampering
        val cleanCached = LicenseManager.getCachedLicensingData(context)
        assertEquals(PassTier.MONTHLY, cleanCached.passTier)

        // Root attacker edits cachedPassExpiry directly without valid hardware HMAC
        val prefs = context.getSharedPreferences(LicenseManager.LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(LicenseManager.KEY_CACHED_PASS_EXPIRY, futureExpiry + 9_999_999L).commit()

        val tamperedRead = LicenseManager.getCachedLicensingData(context)
        assertNull("Tampered cache tier must be rejected", tamperedRead.passTier)
        assertEquals("Tampered cache expiry must return 0L", 0L, tamperedRead.passExpiry)

        // Fast UI boot must remain locked / loading, NOT PassActive
        LicenseManager.refreshAccessStatus(context)
        assertFalse(
            "Tampered cache must never grant PassActive",
            LicenseManager.accessStatus.value is AccessStatus.PassActive
        )
    }

    @Test
    fun forgedCacheWithoutHmacOrSyncMetadataIsRejected() {
        val prefs = context.getSharedPreferences(LicenseManager.LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(LicenseManager.KEY_CACHED_PASS_TIER, PassTier.WEEKLY.id)
            .putLong(LicenseManager.KEY_CACHED_PASS_EXPIRY, System.currentTimeMillis() + 600_000_000L)
            .commit()

        val cached = LicenseManager.getCachedLicensingData(context)
        assertNull(cached.passTier)
        assertEquals(0L, cached.passExpiry)
    }

    @Test
    fun preLaunchAndRuntimeClockSkewAreDetected() {
        val nowWall = System.currentTimeMillis()
        // Record authoritative server time baseline 2 hours in the future relative to wound-back local clock
        val forgedLocalWallInPast = nowWall - (2L * 3600L * 1000L)
        LicenseManager.recordServerTimeSync(nowWall + (2L * 3600L * 1000L))

        assertTrue(
            "Significant mismatch between local wall clock and authoritative server UTC must trigger tampering",
            LicenseManager.isClockTamperingDetected(context)
        )
        assertFalse("Access must be denied when clock tampering is active", LicenseManager.isAccessGranted())

        // Verify runtime rollback detection via persisted timestamps
        LicenseManager.resetStateForTesting(context)
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = nowWall + 86_400_000L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )
        // Simulate server sync was recorded when wall clock was ahead (device clock wound backward by 10 mins)
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = SystemClock.elapsedRealtime(),
            wallClockMs = nowWall + 600_000L
        )
        assertTrue(
            "Winding device clock backwards must trigger clock tampering",
            LicenseManager.isClockTamperingDetected(context)
        )
        assertTrue(
            "Forged past clock value shouldn't break cleanup",
            forgedLocalWallInPast < nowWall
        )
    }

    @Test
    fun addPointsDirectGrantIsDisabledInZeroTrustClient() = runTest {
        val result = LicenseManager.addPoints(5000)
        assertTrue("Client addPoints must strictly fail", result.isFailure)
    }

    @Test
    fun deviceHardwareIdOverridesSpoofedSharedPreferencesCache() {
        val legitimateHwId = DeviceUtils.getDeviceHardwareId(context)
        assertTrue(legitimateHwId.startsWith("HW-"))

        // Tamper directly with DEVICE_SECURITY_PREFS
        val prefs = context.getSharedPreferences("device_security_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("cached_device_hw_id", "HW-SPOOFED9999999999").commit()

        // Clear in-memory field reflection check via second call / recompute on HardwareIdManager
        val field = DeviceUtils::class.java.getDeclaredField("cachedHardwareId")
        field.isAccessible = true
        field.set(DeviceUtils, null)

        val verifiedHwId = DeviceUtils.getDeviceHardwareId(context)
        assertEquals(
            "DeviceUtils must recompute deterministic hardware fingerprint and ignore spoofed cache",
            legitimateHwId,
            verifiedHwId
        )
    }

    @Test
    fun fastUiBootDoesNotSetAuthoritativeHasLoadedLicensingFlag() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )
        assertFalse(LicenseManager.hasLoadedLicensing)
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertFalse(
            "Cache-backed Fast UI Boot must NOT masquerade as authoritative Firestore load",
            LicenseManager.hasLoadedLicensing
        )
    }

    @Test
    fun adminExplicitLockOverridesProPassAndLocalFilterXmlTampering() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(LicenseManager.isAccessGranted())

        // Attacker manually writes enabled=true in local USER_FILTER_PREFS XML for MIN_FARE
        val filterPrefs = context.getSharedPreferences(AppSettings.USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
        filterPrefs.edit()
            .putBoolean("user_min_fare_enabled", true)
            .putFloat("user_min_fare", 150f)
            .putBoolean("user_auto_click_enabled", true)
            .commit()

        // Admin explicitly locks MIN_FARE and AUTO_CLICK via Firestore
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to -1L, FeatureIds.AUTO_CLICK to -1L),
            locked = setOf(FeatureIds.MIN_FARE, FeatureIds.AUTO_CLICK)
        )
        AppSettings.init(context)

        assertTrue(LicenseManager.isFeatureLockedByAdmin(FeatureIds.MIN_FARE))
        assertFalse("Admin lock must override Pro Pass!", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertFalse("Locked filter must NEVER report enabled even if local XML was edited!", AppSettings.isMinFareEnabled(context))
        assertFalse("Admin lock on AUTO_CLICK must block auto-accept capability!", LicenseManager.hasAnyAutoAcceptCapability())
        assertFalse(AppSettings.isAutoClickEnabled(context))
    }

    @Test
    fun freeModeGranularFilterUnlockEnablesOnlyChosenFiltersAndAutoAcceptCapability() {
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        assertFalse(LicenseManager.isAccessGranted())
        assertFalse(LicenseManager.hasAnyAutoAcceptCapability())

        // User unlocks ONLY MIN_FARE and DESTINATION_BLACKLIST in Free Mode
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.DESTINATION_BLACKLIST to futureExpiry
            )
        )

        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertFalse("Buying MIN_FARE must NOT unlock MAX_FARE", LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE))
        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST))
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))
        assertTrue("Unlocking >= 1 filter in Free Mode must permit standard-speed Auto-Accept", LicenseManager.hasAnyAutoAcceptCapability())
    }

    @Test
    fun proPassesAndTrialKeepAdsEnabledUnlessRemoveAdsPassIsActive() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L

        // 1. Daily, Weekly, and Monthly Passes unlock Pro features, NOT ad removal
        listOf(PassTier.DAILY, PassTier.WEEKLY, PassTier.MONTHLY).forEach { tier ->
            LicenseManager.resetStateForTesting(context)
            LicenseManager.saveAuthoritativeLicensingCache(
                context = context,
                passTier = tier,
                passExpiry = futureExpiry,
                trialClaimed = 0L,
                trialExpiry = 0L,
                trialUsed = false
            )
            LicenseManager.refreshAccessStatus(context)

            assertTrue("${tier.title} must grant Pro access", LicenseManager.isAccessGranted())
            assertTrue("${tier.title} must unlock MIN_FARE", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
            assertFalse("${tier.title} MUST still show ads", LicenseManager.isAdsRemoved())
        }

        // 2. Active 2-Day Trial also keeps ads enabled
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
        assertTrue("Trial must grant Pro access", LicenseManager.isAccessGranted())
        assertFalse("Trial MUST still show ads", LicenseManager.isAdsRemoved())
    }

    @Test
    fun removeAdsPassStrictlyRemovesAdsOnlyWithoutUnlockingFeaturesOrProMode() {
        val futureExpiry = System.currentTimeMillis() + 28L * 86_400_000L

        // Case A: Remove Ads stored as removeAdsExpiry timestamp
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = null,
            passExpiry = 0L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true,
            removeAdsExpiry = futureExpiry
        )
        LicenseManager.refreshAccessStatus(context)

        assertTrue("Remove Ads Pass must hide ads", LicenseManager.isAdsRemoved())
        assertFalse("Remove Ads Pass must NEVER grant Pro access", LicenseManager.isAccessGranted())
        assertFalse("Remove Ads Pass must NOT emit PassActive Pro status", LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertFalse("Remove Ads Pass must NEVER grant Auto-Accept capability", LicenseManager.hasAnyAutoAcceptCapability())
        assertFalse("Remove Ads Pass must NOT unlock MIN_FARE", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertFalse("Remove Ads Pass must NOT unlock MAX_FARE", LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE))
        assertFalse("Remove Ads Pass must NOT unlock AUTO_CLICK", LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))
        assertFalse("Remove Ads Pass must NOT unlock TTS_VOICE", LicenseManager.isFeatureUnlocked(FeatureIds.TTS_VOICE))
        assertFalse("Remove Ads Pass must NOT unlock FLOATING_OVERLAY", LicenseManager.isFeatureUnlocked(FeatureIds.FLOATING_OVERLAY))

        // Case B: Backward-compatible legacy document format where activePassType == remove_ads_pass
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

        assertTrue("Legacy activePassType=remove_ads_pass must still hide ads", LicenseManager.isAdsRemoved())
        assertFalse("Legacy remove_ads_pass must NEVER grant Pro access", LicenseManager.isAccessGranted())
        assertFalse("Legacy remove_ads_pass must NEVER emit PassActive", LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertFalse("Legacy remove_ads_pass must NOT unlock filters", LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE))

        // Case C: Remove Ads via unlocked_features["remove_ads_pass"] only hides ads
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setFeatureLocksForTesting(unlocked = mapOf(PassTier.REMOVE_ADS.id to futureExpiry))
        assertTrue("unlocked_features[remove_ads_pass] must hide ads", LicenseManager.isAdsRemoved())
        assertFalse("unlocked_features[remove_ads_pass] must NOT grant Pro", LicenseManager.isAccessGranted())
        assertFalse("unlocked_features[remove_ads_pass] must NOT unlock filters", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
    }

    @Test
    fun monthlyProPassCombinedWithRemoveAdsPassEnablesBothProAndAdFree() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true,
            removeAdsExpiry = futureExpiry
        )
        LicenseManager.refreshAccessStatus(context)

        assertTrue("Pro Pass must be active", LicenseManager.isAccessGranted())
        assertTrue("MIN_FARE must be unlocked", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertTrue("Separate Remove Ads expiry must remove ads", LicenseManager.isAdsRemoved())
    }

    @Test
    fun tamperedRemoveAdsCacheTimestampIsRejectedByHmac() {
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = null,
            passExpiry = 0L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true,
            removeAdsExpiry = 0L
        )
        val prefs = context.getSharedPreferences(LicenseManager.LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(LicenseManager.KEY_CACHED_REMOVE_ADS_EXPIRY, futureExpiry).commit()

        val tampered = LicenseManager.getCachedLicensingData(context)
        assertEquals("Tampered removeAdsExpiry must be rejected and zeroed", 0L, tampered.removeAdsExpiry)
        LicenseManager.refreshAccessStatus(context)
        assertFalse("Tampered cache must never remove ads", LicenseManager.isAdsRemoved())
    }

    @Test
    fun featurePricingMatchesUpdatedHourlyRates() {
        val expectedRates = mapOf(
            FeatureIds.MIN_FARE to 10,
            FeatureIds.MAX_FARE to 10,
            FeatureIds.MAX_PICKUP_DISTANCE to 10,
            FeatureIds.MAX_DROP_DISTANCE to 10,
            FeatureIds.MIN_PER_KM_RATE to 15,
            FeatureIds.DESTINATION_BLACKLIST to 5,
            FeatureIds.TTS_VOICE to 10,
            FeatureIds.FLOATING_OVERLAY to 10,
            FeatureIds.AUTO_CLICK to 15
        )
        assertEquals(expectedRates.size, FeatureIds.ALL_FEATURES.size)
        expectedRates.forEach { (featureId, expectedPts) ->
            assertEquals(
                "Pricing mismatch for $featureId",
                expectedPts,
                FeatureIds.getPricing(featureId)?.pointsPerHour
            )
        }
    }

    @Test
    fun freeModeLocksAllFiltersEvenIfPreviouslyEnabledUnlessPurchasedOrProPassActive() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        // Simulate a logged-in user whose Firestore/RAM has all toggles true from an earlier Trial/Pass,
        // now expired into Free Mode (no active pass, no trial, empty unlocked_features)
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "autoAcceptEnabled" to true,
                "isMinFareEnabled" to true,
                "minFare" to 80.0,
                "isMaxFareEnabled" to true,
                "maxFare" to 500.0,
                "isMaxPickupDistanceEnabled" to true,
                "maxPickupDistance" to 2.0,
                "isMaxDropDistanceEnabled" to true,
                "maxDropDistance" to 12.0,
                "isMinPerKmRateEnabled" to true,
                "minPerKmRate" to 15.0,
                "isDestinationBlacklistEnabled" to true,
                "isVoiceAnnouncerEnabled" to true,
                "isFloatingOverlayEnabled" to true,
                "isAutoClickEnabled" to true
            )
        )

        // Free Mode with 0 purchased features: ALL filters & automation MUST stay locked
        assertFalse(LicenseManager.isAccessGranted())
        assertFalse(LicenseManager.hasAnyAutoAcceptCapability())
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isMaxDropDistanceEnabled(context))
        assertFalse(AppSettings.isMinPerKmRateEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertFalse(AppSettings.isAutoClickEnabled(context))

        // Attempting to toggle locked filters ON in Free Mode must also remain blocked
        AppSettings.setMinFareEnabled(context, true)
        assertFalse(AppSettings.isMinFareEnabled(context))

        // Purchasing MIN_PER_KM_RATE (15 Pts/Hr) unlocks ONLY MIN_PER_KM_RATE
        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_PER_KM_RATE to futureExpiry)
        )
        assertTrue(AppSettings.isMinPerKmRateEnabled(context))
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isMaxDropDistanceEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertTrue(LicenseManager.hasAnyAutoAcceptCapability())
        assertTrue(AppSettings.isAutoClickEnabled(context))

        // Activating Daily/Weekly/Monthly Pass unlocks all filters
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
        assertTrue(LicenseManager.isAccessGranted())
        assertTrue(AppSettings.isMinFareEnabled(context))
        assertTrue(AppSettings.isMaxFareEnabled(context))
        assertTrue(AppSettings.isMaxPickupDistanceEnabled(context))
        assertTrue(AppSettings.isMaxDropDistanceEnabled(context))
        assertTrue(AppSettings.isMinPerKmRateEnabled(context))
        assertTrue(AppSettings.isDestinationBlacklistEnabled(context))
        assertTrue(AppSettings.isVoiceAnnouncerEnabled(context))
        assertTrue(AppSettings.isFloatingOverlayEnabled(context))
        assertTrue(AppSettings.isAutoClickEnabled(context))

        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun legitimateRebootDoesNotTriggerFalseClockTamperingWhilePostRebootRollbackDoes() {
        val nowWall = System.currentTimeMillis()
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = nowWall + 86_400_000L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )

        // Prior session had higher elapsedRealtime (e.g. phone was up for 1 hour), then rebooted (elapsed reset)
        val currentElapsed = SystemClock.elapsedRealtime()
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = currentElapsed + 3_600_000L,
            wallClockMs = nowWall - 120_000L
        )

        assertFalse(
            "Legitimate reboot where elapsedRealtime resets and wall clock stayed accurate must NOT falsely flag tampering",
            LicenseManager.isClockTamperingDetected(context)
        )

        // If device rebooted AND user wound wall clock backwards behind lastServerTs: MUST still trigger
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = currentElapsed + 3_600_000L,
            wallClockMs = nowWall + 3_600_000L
        )
        assertTrue(
            "Rolling clock backwards across a reboot must still trigger tampering",
            LicenseManager.isClockTamperingDetected(context)
        )
    }

    @Test
    fun strict12DigitNumericUtrValidationRejectsInvalidInputs() = runTest {
        assertTrue(PaymentVerificationRepository.isValidUtr("123456789012"))
        assertFalse(PaymentVerificationRepository.isValidUtr("UTR123456789"))
        assertFalse(PaymentVerificationRepository.isValidUtr("12345678901"))
        assertFalse(PaymentVerificationRepository.isValidUtr("1234567890123"))

        val alphaRes = PassManager.verifyPaymentWithBackendDetailed(context, "UTR123456789")
        assertTrue("Alphanumeric UTR must be rejected as Invalid", alphaRes is PassManager.VerificationResult.Invalid)

        val shortRes = PassManager.verifyPaymentWithBackendDetailed(context, "12345678901")
        assertTrue("11-digit UTR must be rejected as Invalid", shortRes is PassManager.VerificationResult.Invalid)
    }
}

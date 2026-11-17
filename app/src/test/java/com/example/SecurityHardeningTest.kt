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
        // Expired trial toggles reset cleanly; toggle must not silently re-activate
        assertFalse(AppSettings.isMinPerKmRateEnabled(context))
        AppSettings.setMinPerKmRateEnabled(context, true)
        assertTrue(AppSettings.isMinPerKmRateEnabled(context))
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isMaxDropDistanceEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertTrue(LicenseManager.hasAnyAutoAcceptCapability())
        // Strict AUTO_CLICK check: unlocking a rate filter does NOT grant auto-click
        assertFalse(AppSettings.isAutoClickEnabled(context))

        // Unlocking AUTO_CLICK allows enabling auto-click
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_PER_KM_RATE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry
            )
        )
        AppSettings.setAutoClickEnabled(context, true)
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

        // Under Pro Pass, user can enable all automation filters
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxFareEnabled(context, true)
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setMaxDropDistanceEnabled(context, true)
        AppSettings.setMinPerKmRateEnabled(context, true)
        AppSettings.setDestinationBlacklistEnabled(context, true)
        AppSettings.setVoiceAnnouncerEnabled(context, true)
        AppSettings.setFloatingOverlayEnabled(context, true)
        AppSettings.setAutoClickEnabled(context, true)

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

    @Test
    fun unknownPassTypeAndLocalPassManagerCannotBypassProLicensingOrAutoAccept() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 86_400_000L

        // Local PassManager flag alone must NEVER grant Pro access or expiry
        PassManager.setPassStatus(context, true)
        assertFalse("PassManager fallback must not activate AppSettings.isPassActive", AppSettings.isPassActive(context))
        assertEquals("PassManager fallback must not leak expiry", 0L, AppSettings.getPassExpiryTimestamp(context))

        // Unknown / null activePassType in Firestore snapshot must NOT be treated as a Pro pass
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "activePassType" to "unknown_tier",
                "passExpiryTimestamp" to futureExpiry,
                "autoAcceptEnabled" to true
            )
        )
        assertFalse("Unknown pass type must NOT activate isPassActiveFlow", AppSettings.isPassActiveFlow.value)
        assertFalse("Unknown pass type must NOT activate AppSettings.isPassActive", AppSettings.isPassActive(context))
        assertEquals("Unknown pass type must yield 0L pass expiry", 0L, AppSettings.getPassExpiryTimestamp(context))
        assertFalse(
            "Auto-accept must stay disabled without any Pro pass or unlocked filter capability",
            AppSettings.isAutoAcceptEnabled(context)
        )

        // Even with Remove Ads pass only, Auto-Accept capability remains false
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "activePassType" to PassTier.REMOVE_ADS.id,
                "passExpiryTimestamp" to futureExpiry,
                "autoAcceptEnabled" to true
            )
        )
        assertFalse("Remove Ads pass must NOT unlock Pro pass state", AppSettings.isPassActive(context))
        assertFalse("Remove Ads pass must NOT grant auto-accept capability", AppSettings.isAutoAcceptEnabled(context))

        PassManager.setPassStatus(context, false)
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun offlineColdStartClockRollbackIsDetectedAndServerTimeIsClamped() {
        LicenseManager.resetStateForTesting(context)

        // Simulate cold start after process restart (serverTimeBaselineMs == 0L, no syncWall)
        // where device clock was rolled back 1 hour behind the persisted lastServerTimestamp
        val persistedServerTs = System.currentTimeMillis() + 3_600_000L
        val prefs = context.getSharedPreferences(LicenseManager.LICENSE_CACHE_PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(LicenseManager.KEY_LAST_SERVER_TIMESTAMP, persistedServerTs).commit()

        assertTrue(
            "Offline cold-start clock rollback must be detected immediately",
            LicenseManager.isClockTamperingDetected(context)
        )
        assertTrue(
            "getCurrentServerTimeMs must clamp to at least persisted lastServerTimestamp",
            LicenseManager.getCurrentServerTimeMs() >= persistedServerTs
        )
    }

    @Test
    fun unifiedHardwareIdMatchesBetweenDeviceUtilsAndHardwareIdManager() {
        val hwUtilsField = DeviceUtils::class.java.getDeclaredField("cachedHardwareId")
        hwUtilsField.isAccessible = true
        hwUtilsField.set(DeviceUtils, null)

        val hmIdField = HardwareIdManager::class.java.getDeclaredField("cachedHardwareId")
        hmIdField.isAccessible = true
        hmIdField.set(HardwareIdManager, null)

        val hmDetailsField = HardwareIdManager::class.java.getDeclaredField("cachedHardwareDetails")
        hmDetailsField.isAccessible = true
        hmDetailsField.set(HardwareIdManager, null)

        val fp1 = DeviceUtils.buildUnifiedHardwareFingerprint(context)
        val deviceHwId = DeviceUtils.getDeviceHardwareId(context)
        val permanentDetails = HardwareIdManager.getHardwareDetails(context)

        assertTrue(deviceHwId.startsWith("HW-"))
        assertTrue(permanentDetails.formattedId.startsWith("OM-"))
        assertEquals(fp1.sha256Hex.uppercase(java.util.Locale.US), permanentDetails.fullSha256)
        assertEquals("HW-" + fp1.sha256Hex.take(16).uppercase(java.util.Locale.US), deviceHwId)

        // Recomputing after clearing caches must yield exact same deterministic IDs
        hwUtilsField.set(DeviceUtils, null)
        hmIdField.set(HardwareIdManager, null)
        hmDetailsField.set(HardwareIdManager, null)

        assertEquals(deviceHwId, DeviceUtils.getDeviceHardwareId(context))
        assertEquals(permanentDetails.formattedId, HardwareIdManager.getPermanentHardwareId(context))
    }

    @Test
    fun `deterministic fallback custom user ID preserves ID across reinstall and honors legacy prefs`() {
        // 1. Clean slate
        DeviceUtils.resetCustomUserIdForTesting(context)
        assertNull(DeviceUtils.getSavedCustomUserIdOrNull(context))

        // 2. Deterministic derivation from hardware fingerprint
        val userId1 = DeviceUtils.getCustomUserId(context)
        assertTrue(userId1.startsWith("OM-USER-"))
        assertEquals(16, userId1.length) // "OM-USER-" (8) + 8 chars = 16

        val expectedSuffix = DeviceUtils.buildUnifiedHardwareFingerprint(context)
            .sha256Hex
            .take(8)
            .uppercase(java.util.Locale.US)
        assertEquals("OM-USER-$expectedSuffix", userId1)

        // 3. Clear in-memory cache and SharedPreferences (simulate app uninstall/reinstall on same device)
        DeviceUtils.resetCustomUserIdForTesting(context)
        val userIdAfterReinstall = DeviceUtils.getCustomUserId(context)
        assertEquals(
            "Reinstalling on the same device must deterministically produce the exact same custom user ID",
            userId1,
            userIdAfterReinstall
        )

        // 4. Test legacy migration: if SharedPreferences already holds a saved ID, it must be honored
        val legacyId = "OM-USER-LEGACY01"
        context.getSharedPreferences("device_security_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_user_id", legacyId)
            .commit()

        DeviceUtils.resetCustomUserIdForTesting(null) // clear in-memory cache only
        assertEquals(legacyId, DeviceUtils.getSavedCustomUserIdOrNull(context))
        assertEquals(legacyId, DeviceUtils.getCustomUserId(context))

        // Cleanup
        DeviceUtils.resetCustomUserIdForTesting(context)
    }

    @Test
    fun licenseManagerReferralAndPaymentUtrValidationRejectInvalidInputs() = runTest {
        val badCodeRes = LicenseManager.claimReferralViaActionQueue("bad-123")
        assertTrue("Malformed referral code must fail before hitting Firestore", badCodeRes.isFailure)
        assertTrue(badCodeRes.exceptionOrNull() is IllegalArgumentException)

        val refField = LicenseManager::class.java.getDeclaredField("_referralCode")
        refField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val refFlow = refField.get(LicenseManager) as kotlinx.coroutines.flow.MutableStateFlow<String?>
        val previousCode = refFlow.value
        try {
            refFlow.value = "OMAB12CD"
            val selfRes = LicenseManager.claimReferralViaActionQueue("omab12cd")
            assertTrue("Self-referral must fail locally before hitting Firestore", selfRes.isFailure)
            assertTrue(selfRes.exceptionOrNull() is IllegalArgumentException)
        } finally {
            refFlow.value = previousCode
        }

        val badUtrRes = LicenseManager.activatePassViaPayment(PassTier.DAILY, "12345ABC")
        assertTrue("Non-12-digit UTR must fail before hitting Firestore", badUtrRes.isFailure)
        assertTrue(badUtrRes.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun defaultFreemiumUserStartsLockedWithZeroPhantomTimers() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        assertFalse(LicenseManager.isAccessGranted())
        assertTrue(LicenseManager.unlockedFeatures.value.isEmpty())
        FeatureIds.ALL_FEATURES.forEach { feature ->
            assertEquals(
                "Default freemium user must have 0ms remaining on ${feature.id}",
                0L,
                LicenseManager.getFeatureRemainingTimeMs(feature.id)
            )
            assertFalse(
                "Feature ${feature.id} must be locked by default",
                LicenseManager.isFeatureUnlocked(feature.id)
            )
        }

        val state = AppSettings.settingsState.value
        assertFalse(state.isAutoAcceptEnabled)
        assertFalse(state.isMinFareEnabled)
        assertFalse(state.isMaxFareEnabled)
        assertFalse(state.isMaxPickupDistanceEnabled)
        assertFalse(state.isMaxDropDistanceEnabled)
        assertFalse(state.isMinPerKmRateEnabled)
        assertFalse(state.isDestinationBlacklistEnabled)
        assertFalse(state.isVoiceAnnouncerEnabled)
        assertFalse(state.isFloatingOverlayEnabled)
        assertFalse(state.isAutoClickEnabled)
        assertFalse(AppSettings.hasActiveEvaluationFilter(context))
    }

    @Test
    fun featureRemainingTimeZeroesOnTamperOrAdminLock() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to futureExpiry)
        )
        assertTrue(LicenseManager.getFeatureRemainingTimeMs(FeatureIds.MIN_FARE) > 0L)

        // Clock rollback tampering must immediately zero remaining time
        LicenseManager.setLastServerSyncForTesting(
            elapsedMs = SystemClock.elapsedRealtime() + 3_600_000L,
            wallClockMs = System.currentTimeMillis() + 3_600_000L
        )
        assertEquals(0L, LicenseManager.getFeatureRemainingTimeMs(FeatureIds.MIN_FARE))

        // Clear tampering prefs and re-verify Admin lock zeroes remaining time
        LicenseManager.resetStateForTesting(context)
        LicenseManager.forceReleaseLoadingState()
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to futureExpiry),
            locked = setOf(FeatureIds.MIN_FARE)
        )
        assertEquals(0L, LicenseManager.getFeatureRemainingTimeMs(FeatureIds.MIN_FARE))
    }

    @Test
    fun autoClickAloneWithoutEvaluationFilterCannotEnableAutoAccept() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.AUTO_CLICK to futureExpiry)
        )

        assertFalse(
            "AUTO_CLICK without any evaluation filter unlocked must NOT grant auto-accept capability",
            LicenseManager.hasAnyAutoAcceptCapability()
        )
        AppSettings.setAutoAcceptEnabled(context, true)
        assertFalse(
            "Master auto-accept must stay OFF until at least 1 evaluation filter or Pro Pass is active",
            AppSettings.isAutoAcceptEnabled(context)
        )
        assertFalse(AppSettings.settingsState.value.isAutoClickEnabled)
    }

    @Test
    fun lockedLocalPrefsTogglesCannotLeakIntoEffectiveSettingsState() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        // Simulate stale/tampered booleans written into user filter SharedPreferences while in Free Mode
        context.getSharedPreferences(AppSettings.USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("fkey_min_fare_enabled", true)
            .putBoolean("fkey_max_fare_enabled", true)
            .putBoolean("fkey_auto_accept_enabled", true)
            .putBoolean("fkey_auto_click_enabled", true)
            .commit()

        AppSettings.applyFirestoreSnapshotForTest(context, emptyMap())

        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
        assertFalse(AppSettings.isAutoClickEnabled(context))
        assertFalse(AppSettings.settingsState.value.isMinFareEnabled)
        assertFalse(AppSettings.settingsState.value.isMaxFareEnabled)
        assertFalse(AppSettings.settingsState.value.isAutoAcceptEnabled)
        assertFalse(AppSettings.settingsState.value.isAutoClickEnabled)
        assertFalse(AppSettings.hasActiveEvaluationFilter(context))
    }

    @Test
    fun firestoreBlacklistSyncMergesAndDeduplicatesBothBlockedLocationsAndBlacklistedDestinations() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.DESTINATION_BLACKLIST to futureExpiry)
        )

        // 1. Admin writes only blacklistedDestinations
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isDestinationBlacklistEnabled" to true,
                "blacklistedDestinations" to listOf("  Airport ", "Toll Gate", "airport")
            )
        )
        assertEquals(listOf("Airport", "Toll Gate"), AppSettings.getBlockedLocations(context))

        // 2. Both blockedLocations and blacklistedDestinations present -> merge & deduplicate cleanly
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isDestinationBlacklistEnabled" to true,
                "blockedLocations" to listOf("Highway Toll", "Railway Station"),
                "blacklistedDestinations" to listOf("highway toll", "Slum Area")
            )
        )
        assertEquals(
            listOf("Highway Toll", "Railway Station", "Slum Area"),
            AppSettings.getBlockedLocations(context)
        )
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun signOutViaUpdateUserAuthClearsLocalCustomizationFlagsForNextFirestoreSync() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.DESTINATION_BLACKLIST to futureExpiry
            )
        )

        // User customizes Min Fare & Blacklist locally (writes customized_* flags into USER_FILTER_PREFS_NAME)
        AppSettings.setMinFare(context, 180f)
        AppSettings.setBlockedLocations(context, listOf("LocalOnly"))

        val fPrefs = context.getSharedPreferences(AppSettings.USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
        assertTrue(fPrefs.all.keys.any { it.startsWith("customized_") })

        // Signing out via updateUserAuthFromFirebase(context, null) MUST clear all customized_* flags
        AppSettings.updateUserAuthFromFirebase(context, null)
        assertFalse(
            "All customized_* flags must be cleared on sign-out",
            fPrefs.all.keys.any { it.startsWith("customized_") }
        )

        // Next user / fresh Firestore rules now apply cleanly without being blocked by stale local flags
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.DESTINATION_BLACKLIST to futureExpiry
            )
        )
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "minFare" to 75.0,
                "blacklistedDestinations" to listOf("AdminZone")
            )
        )
        assertEquals(75f, AppSettings.getMinFare(context), 0.01f)
        assertEquals(listOf("AdminZone"), AppSettings.getBlockedLocations(context))
        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun globalAdminManagerCanExecuteActionsEvaluatesBanExpiryLiveWithoutTicker() {
        LicenseManager.resetStateForTesting(context)
        GlobalAdminManager.setBanStateForTesting(UserBanState.NotBanned)
        assertTrue(GlobalAdminManager.canExecuteActions(context))

        val now = LicenseManager.getCurrentServerTimeMs()
        // Active ban blocks execution immediately
        GlobalAdminManager.setBanStateForTesting(
            UserBanState.Banned(
                banUntilTimestamp = now + 120_000L,
                formattedDateTime = "Future",
                reason = "Violation"
            )
        )
        assertFalse(GlobalAdminManager.canExecuteActions(context))
        assertTrue(GlobalAdminManager.isBlocked.value)

        // Expired ban timestamp is lifted synchronously inside canExecuteActions() even if ticker hasn't fired
        GlobalAdminManager.setBanStateForTesting(
            UserBanState.Banned(
                banUntilTimestamp = now - 1_000L,
                formattedDateTime = "Past",
                reason = "Expired"
            )
        )
        assertTrue(GlobalAdminManager.canExecuteActions(context))
        assertTrue(GlobalAdminManager.banState.value is UserBanState.NotBanned)
        assertFalse(GlobalAdminManager.isBlocked.value)
    }

    @Test
    fun staleTrialRemnantsInUnlockedFeaturesMustNotUnlockFeaturesWhenTrialIsExpired() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        val now = LicenseManager.getCurrentServerTimeMs()
        val expiredTrialTime = now - 3_600_000L

        // Set expired trial
        LicenseManager.setLicensingForTesting(
            passTier = null,
            passExpiry = 0L,
            trialClaimed = expiredTrialTime - 172_800_000L,
            trialExpiry = expiredTrialTime,
            trialUsed = true,
            removeAdsExpiry = 0L
        )

        assertFalse(LicenseManager.isAccessGranted())
        assertFalse(LicenseManager.isPassActive)
        assertFalse(LicenseManager.isTrialActive)
        assertFalse(LicenseManager.isProMode())

        // Backend had populated all 11 features matching the trial expiry
        val all11Features = FeatureIds.ALL_FEATURES.map { it.id } + listOf(FeatureIds.FARE_FILTERS, FeatureIds.DISTANCE_FILTERS)
        val staleMap = all11Features.associateWith { expiredTrialTime }

        LicenseManager.setFeatureLocksForTesting(unlocked = staleMap)

        // All 11 features must be filtered out as expired trial remnants
        assertEquals(0, LicenseManager.unlockedFeatures.value.size)
        for (fId in all11Features) {
            assertFalse("Feature $fId must be locked when trial is expired!", LicenseManager.isFeatureUnlocked(fId))
        }

        // Toggles must be locked
        AppSettings.publishEffectiveSettings(context)
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isAutoClickEnabled(context))
    }

    @Test
    fun freeModeBannerAdsShownEvenWhenFiltersAreUnlockedWithPoints() {
        LicenseManager.resetStateForTesting(context)
        val now = LicenseManager.getCurrentServerTimeMs()

        // User is in Free Mode (no pass, no active trial, no remove ads)
        LicenseManager.setLicensingForTesting(
            passTier = null,
            passExpiry = 0L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false,
            removeAdsExpiry = 0L
        )

        assertFalse(LicenseManager.isProMode())
        assertFalse(LicenseManager.isRemoveAdsActive())
        assertTrue("In clean Free Mode, banner ads must be shown", LicenseManager.shouldShowBannerAds())

        // User unlocks 2 filters with Points for 1 hour
        val futureExpiry = now + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_FARE to futureExpiry,
                FeatureIds.MAX_FARE to futureExpiry
            )
        )

        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.MAX_FARE))
        assertFalse(LicenseManager.isProMode())
        assertFalse(LicenseManager.isRemoveAdsActive())

        // Unlocking individual filters with Points must NOT suppress banner ads!
        assertTrue(
            "Banner ads MUST reliably display in Free Mode even if filters are unlocked via Points!",
            LicenseManager.shouldShowBannerAds()
        )
    }

    @Test
    fun proModeAndRemoveAdsPassCorrectlyControlBannerAdsVisibility() {
        LicenseManager.resetStateForTesting(context)
        val now = LicenseManager.getCurrentServerTimeMs()

        // 1. Pro Pass Active (Daily Pass) hides banner ads
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.DAILY,
            passExpiry = now + 86_400_000L
        )
        assertTrue(LicenseManager.isPassActive)
        assertTrue(LicenseManager.isProMode())
        assertFalse("Pro Mode must hide banner ads", LicenseManager.shouldShowBannerAds())

        // 2. Active Free Trial hides banner ads
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            trialClaimed = now - 10_000L,
            trialExpiry = now + 172_800_000L,
            trialUsed = false
        )
        assertTrue(LicenseManager.isTrialActive)
        assertTrue(LicenseManager.isProMode())
        assertFalse("Active Free Trial must hide banner ads", LicenseManager.shouldShowBannerAds())

        // 3. Remove Ads Pass hides banner ads in Free Mode
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            removeAdsExpiry = now + 86_400_000L
        )
        assertFalse(LicenseManager.isProMode())
        assertTrue(LicenseManager.isRemoveAdsActive())
        assertFalse("Active Remove Ads pass must hide banner ads", LicenseManager.shouldShowBannerAds())
    }

    @Test
    fun expiredFeatureTogglesResetCleanlyInPublishEffectiveSettings() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // Pre-save enabled states in SharedPreferences
        val prefs = context.getSharedPreferences(AppSettings.USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("user_min_fare_enabled", true)
            .putBoolean("user_auto_click_enabled", true)
            .commit()

        // But user is in Free Mode with 0 unlocked features
        LicenseManager.setLicensingForTesting()
        LicenseManager.setFeatureLocksForTesting(emptyMap())

        AppSettings.init(context)
        AppSettings.publishEffectiveSettings(context)

        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isAutoClickEnabled(context))
        assertFalse("Persisted XML pref must be reset to false", prefs.getBoolean("user_min_fare_enabled", true))
        assertFalse("Persisted XML pref must be reset to false", prefs.getBoolean("user_auto_click_enabled", true))
    }
}


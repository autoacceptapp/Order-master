package com.example

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import com.example.data.PaymentVerificationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val hasAddPointsMethod = LicenseManager::class.java.declaredMethods.any { it.name == "addPoints" }
        assertFalse("Client addPoints method must not exist on LicenseManager", hasAddPointsMethod)
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
                "isAutoAcceptEnabled" to true,
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

        // Free Mode with 0 purchased features: ALL filters, voice & overlay MUST stay locked,
        // while standard Free Mode auto-accept (1-2s speed) remains available (300ms boost locked).
        assertFalse(LicenseManager.isAccessGranted())
        assertFalse(LicenseManager.hasAnyUnlockedFilter())
        assertTrue(LicenseManager.hasAnyAutoAcceptCapability())
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isMaxDropDistanceEnabled(context))
        assertFalse(AppSettings.isMinPerKmRateEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        assertFalse(AppSettings.isVoiceAnnouncerEnabled(context))
        assertFalse(AppSettings.isFloatingOverlayEnabled(context))
        assertTrue("Standard Free Mode auto-click remains available when enabled", AppSettings.isAutoClickEnabled(context))

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
        assertTrue(LicenseManager.hasAnyUnlockedFilter())
        assertTrue(LicenseManager.hasAnyAutoAcceptCapability())
        // 300ms Pro Boost feature remains locked until explicitly unlocked
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))

        // Unlocking AUTO_CLICK grants the 300ms speed boost
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(
                FeatureIds.MIN_PER_KM_RATE to futureExpiry,
                FeatureIds.AUTO_CLICK to futureExpiry
            )
        )
        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))
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
        assertFalse("PassManager must not expose a setPassStatus mutator", PassManager::class.java.declaredMethods.any { it.name == "setPassStatus" })
        assertFalse("PassManager fallback must not activate AppSettings.isPassActive", AppSettings.isPassActive(context))
        assertEquals("PassManager fallback must not leak expiry", 0L, AppSettings.getPassExpiryTimestamp(context))

        // Unknown / null activePassType in Firestore snapshot must NOT be treated as a Pro pass
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "activePassType" to "unknown_tier",
                "passExpiryTimestamp" to futureExpiry,
                "isAutoAcceptEnabled" to true
            )
        )
        assertFalse("Unknown pass type must NOT activate isPassActiveFlow", AppSettings.isPassActiveFlow.value)
        assertFalse("Unknown pass type must NOT activate AppSettings.isPassActive", AppSettings.isPassActive(context))
        assertEquals("Unknown pass type must yield 0L pass expiry", 0L, AppSettings.getPassExpiryTimestamp(context))
        assertFalse("Unknown pass type must NOT unlock Pro filters", LicenseManager.hasAnyUnlockedFilter())
        assertFalse("Unknown pass type must NOT unlock MIN_FARE filter", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))

        // Even with Remove Ads pass only, Pro pass state and all filters remain locked
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "activePassType" to PassTier.REMOVE_ADS.id,
                "passExpiryTimestamp" to futureExpiry,
                "isAutoAcceptEnabled" to true
            )
        )
        assertFalse("Remove Ads pass must NOT unlock Pro pass state", AppSettings.isPassActive(context))
        assertFalse("Remove Ads pass must NOT unlock any filter", LicenseManager.hasAnyUnlockedFilter())
        assertFalse("Remove Ads pass must NOT unlock MIN_FARE filter", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))

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
    fun `legacy custom user ID is strictly read-only migration hint and resolveUserKey avoids OM-USER generation`() {
        // 1. Clean slate: no legacy custom_user_id on disk
        DeviceUtils.resetCustomUserIdForTesting(context)
        assertNull(DeviceUtils.getSavedCustomUserIdOrNull(context))

        // 2. Unauthenticated resolveUserKey resolves deterministically to linkedHardwareId (not OM-USER-*)
        val expectedHwId = HardwareIdManager.getPermanentHardwareId(context)
        val resolvedKey = LicenseManager.resolveUserKey(context)
        assertEquals(expectedHwId, resolvedKey)
        assertFalse("resolveUserKey must not generate legacy OM-USER-* IDs", resolvedKey.startsWith("OM-USER-"))

        // 3. HardwareIdManager must NOT create duplicate hardware_id_prefs SharedPreferences
        val legacyHwPrefs = context.getSharedPreferences("hardware_id_prefs", Context.MODE_PRIVATE)
        assertTrue(
            "hardware_id_prefs must remain empty when HardwareIdManager delegates to DeviceUtils",
            legacyHwPrefs.all.isEmpty()
        )

        // 4. Read-only migration hint honors pre-existing custom_user_id on disk and clears cleanly
        val legacyId = "OM-USER-LEGACY01"
        context.getSharedPreferences("device_security_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_user_id", legacyId)
            .commit()

        assertEquals(legacyId, DeviceUtils.getSavedCustomUserIdOrNull(context))
        DeviceUtils.clearSavedCustomUserId(context)
        assertNull(DeviceUtils.getSavedCustomUserIdOrNull(context))
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
    fun autoClickBoostAloneDoesNotUnlockFiltersAndAdminLockBlocksAutoAccept() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.AUTO_CLICK to futureExpiry)
        )

        // AUTO_CLICK boost does NOT unlock any filter rule
        assertFalse(
            "AUTO_CLICK boost alone must NOT unlock evaluation filters",
            LicenseManager.hasAnyUnlockedFilter()
        )
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))

        // Standard Free Mode auto-accept works when enabled
        AppSettings.setAutoAcceptEnabled(context, true)
        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertTrue(AppSettings.isAutoClickEnabled(context))

        // Explicit Admin lock on AUTO_CLICK immediately blocks both Auto-Accept and Auto-Click
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.AUTO_CLICK to futureExpiry),
            locked = setOf(FeatureIds.AUTO_CLICK)
        )
        AppSettings.publishEffectiveSettings(context)
        assertFalse(LicenseManager.hasAnyAutoAcceptCapability())
        assertFalse(AppSettings.isAutoAcceptEnabled(context))
        assertFalse(AppSettings.isAutoClickEnabled(context))
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
    fun firestoreBlacklistSyncDeduplicatesAndSortsCanonicalBlockedLocations() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val futureExpiry = System.currentTimeMillis() + 3_600_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.DESTINATION_BLACKLIST to futureExpiry)
        )

        // 1. Canonical blockedLocations trims, deduplicates case-insensitively, and sorts
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isDestinationBlacklistEnabled" to true,
                "blockedLocations" to listOf("  Airport ", "Toll Gate", "airport")
            )
        )
        assertEquals(listOf("Airport", "Toll Gate"), AppSettings.getBlockedLocations(context))

        // 2. Subsequent canonical blockedLocations update replaces list cleanly
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isDestinationBlacklistEnabled" to true,
                "blockedLocations" to listOf("Highway Toll", "Railway Station", "highway toll", "Slum Area")
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
                "blockedLocations" to listOf("AdminZone")
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

        // 1. Pro Pass Active (Daily / Weekly / Monthly Pass) MUST STILL SHOW banner ads (Pro != Remove Ads)
        for (tier in listOf(PassTier.DAILY, PassTier.WEEKLY, PassTier.MONTHLY)) {
            LicenseManager.resetStateForTesting(context)
            LicenseManager.setLicensingForTesting(
                passTier = tier,
                passExpiry = now + 86_400_000L
            )
            assertTrue(LicenseManager.isPassActive)
            assertTrue(LicenseManager.isProMode())
            assertFalse("${tier.title} must NOT mark ads removed", LicenseManager.isRemoveAdsActive())
            assertTrue("${tier.title} must STILL show banner ads without remove_ads pass", LicenseManager.shouldShowBannerAds())
            assertTrue("${tier.title} must STILL show ads without remove_ads pass", LicenseManager.shouldShowAds())
        }

        // 2. Active Free Trial MUST STILL SHOW banner ads
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            trialClaimed = now - 10_000L,
            trialExpiry = now + 172_800_000L,
            trialUsed = false
        )
        assertTrue(LicenseManager.isTrialActive)
        assertTrue(LicenseManager.isProMode())
        assertFalse("Active Free Trial must NOT mark ads removed", LicenseManager.isRemoveAdsActive())
        assertTrue("Active Free Trial must STILL show banner ads", LicenseManager.shouldShowBannerAds())

        // 3. Remove Ads Pass hides banner ads in Free Mode (and alongside Pro Pass)
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            removeAdsExpiry = now + 86_400_000L
        )
        assertFalse(LicenseManager.isProMode())
        assertTrue(LicenseManager.isRemoveAdsActive())
        assertFalse("Active Remove Ads pass must hide banner ads", LicenseManager.shouldShowBannerAds())
        assertFalse("Active Remove Ads pass must hide ads", LicenseManager.shouldShowAds())
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

        // User is in Free Mode with 0 unlocked features
        LicenseManager.setLicensingForTesting()
        LicenseManager.setFeatureLocksForTesting(emptyMap())

        AppSettings.init(context)
        AppSettings.publishEffectiveSettings(context)

        // Locked filter toggles MUST be reset cleanly to false in both RAM and XML
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse("Persisted XML filter pref must be reset to false", prefs.getBoolean("user_min_fare_enabled", true))

        // Standard Free Mode auto-click stays intact unless Admin locks AUTO_CLICK
        assertTrue(AppSettings.isAutoClickEnabled(context))
        LicenseManager.setFeatureLocksForTesting(unlocked = emptyMap(), locked = setOf(FeatureIds.AUTO_CLICK))
        AppSettings.publishEffectiveSettings(context)
        assertFalse(AppSettings.isAutoClickEnabled(context))
        assertFalse("Admin-locked auto_click pref must be reset to false", prefs.getBoolean("user_auto_click_enabled", true))
    }

    @Test
    fun canonicalFirestoreFeatureKeysTakePrecedenceOverLegacyAliases() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val canonicalExpiry = System.currentTimeMillis() + 7_200_000L
        val legacyExpiry = System.currentTimeMillis() + 3_600_000L

        // When both canonical and legacy keys exist, canonical unlockedFeatures & lockedFeatures must win
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "unlockedFeatures" to mapOf(FeatureIds.MIN_FARE to canonicalExpiry),
                "unlocked_features" to mapOf(FeatureIds.MAX_FARE to legacyExpiry),
                "lockedFeatures" to listOf(FeatureIds.AUTO_CLICK),
                "locked_features" to listOf(FeatureIds.TTS_VOICE)
            )
        )

        assertEquals(mapOf(FeatureIds.MIN_FARE to canonicalExpiry), AppSettings.unlockedFeaturesFlow.value)
        assertEquals(setOf(FeatureIds.AUTO_CLICK), AppSettings.lockedFeaturesFlow.value)

        // Fallback to legacy aliases works cleanly when canonical keys are absent
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "unlocked_features" to mapOf(FeatureIds.MAX_FARE to legacyExpiry),
                "locked_features" to listOf(FeatureIds.TTS_VOICE)
            )
        )
        assertEquals(mapOf(FeatureIds.MAX_FARE to legacyExpiry), AppSettings.unlockedFeaturesFlow.value)
        assertEquals(setOf(FeatureIds.TTS_VOICE), AppSettings.lockedFeaturesFlow.value)

        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun githubUpdateManagerClearsCachedMetadataWhenInstalledVersionCatchesUp() {
        GitHubUpdateManager.clearCachedUpdate(context)
        GitHubUpdateManager.cacheUpdateAvailable(
            context,
            UpdateResult.UpdateAvailable(
                latestVersion = "v2.0.0",
                latestVersionCode = 50,
                releaseNotes = "Update notes",
                downloadUrl = "https://example.com/app-release.apk",
                apkFileName = "app-release.apk",
                assetSize = 1024L
            )
        )

        // While installed versionCode < cachedVersionCode, cached update is surfaced
        val available = GitHubUpdateManager.getCachedUpdateAvailable(
            context,
            currentVersionCode = 49,
            currentVersion = "1.9.0"
        )
        assertNotNull(available)
        assertEquals(50, available?.latestVersionCode)

        // Once installed versionCode >= cachedVersionCode, cached metadata is purged immediately
        val stale = GitHubUpdateManager.getCachedUpdateAvailable(
            context,
            currentVersionCode = 50,
            currentVersion = "2.0.0"
        )
        assertNull(stale)
        assertNull(
            GitHubUpdateManager.getCachedUpdateAvailable(
                context,
                currentVersionCode = 49,
                currentVersion = "1.9.0"
            )
        )
    }

    @Test
    fun recentEvaluationsFlowCapsInMemoryBufferAt25Items() {
        AppSettings.clearEvaluations()
        for (i in 1..35) {
            val offer = ParsedRideOffer(
                rawText = "Offer #$i ₹${100 + i}",
                totalFare = (100 + i).toDouble(),
                pickupDistanceKm = 1.0,
                dropDistanceKm = 3.0,
                hasAcceptButton = true
            )
            AppSettings.recordEvaluation(
                context,
                RideEvaluation(
                    offer = offer,
                    isAutoAcceptEnabled = true,
                    passesFare = true,
                    passesPickupDistance = true,
                    isAccepted = true,
                    decisionReason = "Accepted #$i"
                )
            )
        }
        // Wait briefly for Dispatchers.IO coroutine buffer updates
        val deadline = System.currentTimeMillis() + 3000L
        while (AppSettings.getRecentEvaluations().size < 25 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        Thread.sleep(150)
        assertEquals(25, AppSettings.getRecentEvaluations().size)
        AppSettings.clearEvaluations()
    }

    @Test
    fun appSettingsPreservesLocalTogglesAcrossLoadingAndFirestoreDefaultsOnlyClearingOnVerifiedExpired() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // Pre-persist local user filter preferences & toggles before entitlements finish loading
        val fPrefs = context.getSharedPreferences(AppSettings.USER_FILTER_PREFS_NAME, Context.MODE_PRIVATE)
        fPrefs.edit()
            .putBoolean("user_auto_accept_enabled", true)
            .putBoolean("user_min_fare_enabled", true)
            .putFloat("user_min_fare", 140f)
            .putBoolean("user_auto_click_enabled", true)
            .commit()

        // 1. AccessStatus is Loading: init() + publishEffectiveSettings() must NOT wipe local preferences
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.Loading)
        AppSettings.init(context)
        AppSettings.publishEffectiveSettings(context)
        assertTrue(
            "Loading state must not wipe user_min_fare_enabled from SharedPreferences",
            fPrefs.getBoolean("user_min_fare_enabled", false)
        )
        assertTrue(
            "Loading state must not wipe user_auto_click_enabled from SharedPreferences",
            fPrefs.getBoolean("user_auto_click_enabled", false)
        )

        // 2. Firestore snapshot arrives with default false values while still Loading: local preferences must be preserved
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isAutoAcceptEnabled" to false,
                "isMinFareEnabled" to false,
                "minFare" to 50.0,
                "isAutoClickEnabled" to false
            )
        )
        assertTrue(fPrefs.getBoolean("user_min_fare_enabled", false))

        // 3. Transition Loading -> PassActive: effective SettingsState must immediately expose preserved user toggles
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry
        )
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.PassActive)
        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertTrue(AppSettings.isMinFareEnabled(context))
        assertTrue(AppSettings.isAutoClickEnabled(context))
        assertTrue(AppSettings.settingsState.value.isMinFareEnabled)
        assertTrue(AppSettings.settingsState.value.isAutoClickEnabled)
        assertEquals(140f, AppSettings.getMinFare(context), 0.01f)

        // 4. Transition PassActive -> verified Expired: unentitled filter toggles are reset cleanly,
        // while Free Mode standard 1-2s auto-click remains intact (300ms Pro Boost locked).
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = System.currentTimeMillis() - 60_000L
        )
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.Expired)
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(fPrefs.getBoolean("user_min_fare_enabled", true))
        assertFalse("300ms Pro Boost must lock on pass expiry", LicenseManager.isFeatureUnlocked(FeatureIds.AUTO_CLICK))
        assertTrue("Free Mode standard 1-2s auto-click stays active on pass expiry", AppSettings.isAutoClickEnabled(context))
    }

    @Test
    fun authoritativeUnlockedFeaturesFilterStrictlyExcludesDeadTrialAndPassEntries() {
        LicenseManager.resetStateForTesting(context)
        val now = LicenseManager.getCurrentServerTimeMs()
        val deadTrialExpiry = now + 30_000L // edge case: server marked trialUsed=true with un-zeroed future trialExpiry
        val activePointUnlockExpiry = now + 3_600_000L

        val filtered = LicenseManager.filterAuthoritativeUnlockedFeatures(
            rawMap = mapOf(
                FeatureIds.MIN_FARE to deadTrialExpiry,
                FeatureIds.MAX_FARE to (now - 1000L),
                FeatureIds.AUTO_CLICK to activePointUnlockExpiry,
                FeatureIds.TTS_VOICE to -1L
            ),
            now = now,
            passActive = false,
            trialActive = false,
            trialUsed = true,
            trialExpiry = deadTrialExpiry,
            passExpiry = 0L
        )

        assertFalse("Expired entry must be excluded", filtered.containsKey(FeatureIds.MAX_FARE))
        assertFalse("Entry bounded by dead trial must be excluded", filtered.containsKey(FeatureIds.MIN_FARE))
        assertEquals(activePointUnlockExpiry, filtered[FeatureIds.AUTO_CLICK])
        assertEquals(-1L, filtered[FeatureIds.TTS_VOICE])
    }

    @Test
    fun deviceUtilsNormalizeSha256HandlesColonLowercaseAndRejectsMalformed() {
        val canonicalHex = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90"
        val colonLower = canonicalHex.lowercase().chunked(2).joinToString(":")
        val whitespaceMixed = "  ${canonicalHex.lowercase().substring(0, 32)} \n ${canonicalHex.substring(32)}  "

        assertEquals(canonicalHex, DeviceUtils.normalizeSha256(canonicalHex))
        assertEquals(canonicalHex, DeviceUtils.normalizeSha256(colonLower))
        assertEquals(canonicalHex, DeviceUtils.normalizeSha256(whitespaceMixed))

        // Malformed inputs must fail closed to ""
        assertEquals("", DeviceUtils.normalizeSha256(null))
        assertEquals("", DeviceUtils.normalizeSha256(""))
        assertEquals("", DeviceUtils.normalizeSha256("   "))
        assertEquals("", DeviceUtils.normalizeSha256("sha256_test"))
        assertEquals("", DeviceUtils.normalizeSha256(canonicalHex.dropLast(1))) // 63 chars
        assertEquals("", DeviceUtils.normalizeSha256(canonicalHex + "A")) // 65 chars
        assertEquals("", DeviceUtils.normalizeSha256("G" + canonicalHex.drop(1))) // non-hex

        val runtimeSig = DeviceUtils.getApkSignatureSha256(context)
        assertTrue(
            "Unit test APK signature must be valid 64-char uppercase hex, got: $runtimeSig",
            Regex("^[0-9A-F]{64}$").matches(runtimeSig)
        )

        // buildActionQueuePayload normalizes colon-separated lowercase SHA-256 and clears malformed values
        val normalizedPayload = LicenseManager.buildActionQueuePayload(
            firebaseUid = "uid_sig_1",
            userEmail = "sig@example.com",
            hardwareId = "HW-SIG-1",
            deviceIntegrityOk = true,
            appSignatureSha256 = colonLower,
            actionType = "CLAIM_TRIAL",
            payload = emptyMap()
        )
        assertEquals(canonicalHex, normalizedPayload["appSignatureSha256"])

        val malformedPayload = LicenseManager.buildActionQueuePayload(
            firebaseUid = "uid_sig_1",
            userEmail = "sig@example.com",
            hardwareId = "HW-SIG-1",
            deviceIntegrityOk = true,
            appSignatureSha256 = "not-a-valid-sha256",
            actionType = "CLAIM_TRIAL",
            payload = emptyMap()
        )
        assertEquals("", malformedPayload["appSignatureSha256"])
    }

    @Test
    fun signatureRejectionCodesFailFastWithoutTimeoutInActionQueueAndPaymentRepo() {
        val untrustedOutcome = LicenseManager.evaluateActionQueueSnapshot(
            actionType = "PURCHASE_PASS",
            status = "REJECTED",
            errorCode = "UNTRUSTED_APP_SIGNATURE"
        )
        assertTrue(untrustedOutcome is LicenseManager.ActionQueueOutcome.Failed)
        val untrustedMsg = (untrustedOutcome as LicenseManager.ActionQueueOutcome.Failed).errorMessage
        assertTrue(
            "Expected friendly official APK guidance, got: $untrustedMsg",
            untrustedMsg.contains("signature verification failed", ignoreCase = true) &&
                untrustedMsg.contains("official", ignoreCase = true)
        )

        val missingConfigOutcome = LicenseManager.evaluateActionQueueSnapshot(
            actionType = "ACTIVATE_PASS_PAYMENT",
            status = "FAILED",
            errorMessage = "SECURITY_CONFIG_MISSING_APP_SHA256"
        )
        assertTrue(missingConfigOutcome is LicenseManager.ActionQueueOutcome.Failed)
        val missingConfigMsg = (missingConfigOutcome as LicenseManager.ActionQueueOutcome.Failed).errorMessage
        assertTrue(
            "Expected friendly server config guidance, got: $missingConfigMsg",
            missingConfigMsg.contains("not configured", ignoreCase = true) &&
                missingConfigMsg.contains("Admin", ignoreCase = true)
        )

        // PaymentVerificationRepository classifies both as terminal (cancels active listeners immediately)
        val repoUntrusted = com.example.data.PaymentVerificationRepository.classifyVerificationRejection(
            rawError = "UNTRUSTED_APP_SIGNATURE"
        )
        assertTrue(repoUntrusted.isTerminal)
        assertFalse(repoUntrusted.canRetry)
        assertTrue(repoUntrusted.message.contains("official", ignoreCase = true))

        val repoMissingCfg = com.example.data.PaymentVerificationRepository.classifyVerificationRejection(
            rawError = null,
            errorCode = "SECURITY_CONFIG_MISSING_APP_SHA256"
        )
        assertTrue(repoMissingCfg.isTerminal)
        assertTrue(repoMissingCfg.canRetry)
        assertTrue(repoMissingCfg.message.contains("not configured", ignoreCase = true))
    }

    @Test
    fun canonicalSchemaVersion2ParsingPrefersCanonicalKeysAndFallsBackToLegacy() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        LicenseManager.forceReleaseLoadingState()

        val now = System.currentTimeMillis()
        val canonicalPassExp = now + 86_400_000L * 7
        val legacyPassExp = now + 86_400_000L
        val canonicalFeatureExp = now + 7_200_000L
        val legacyFeatureExp = now + 3_600_000L
        val canonicalRemoveAdsExp = now + 86_400_000L * 30
        val legacyRemoveAdsExp = now + 86_400_000L * 5

        // 1. AppSettings: canonical schemaVersion=2 keys win over legacy keys when both exist
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "schemaVersion" to 2,
                "activePassType" to "weekly",
                "passExpiryTimestamp" to canonicalPassExp,
                "pass_expiry_date" to legacyPassExp,
                "unlockedFeatures" to mapOf(FeatureIds.MIN_FARE to canonicalFeatureExp),
                "featureExpiries" to mapOf(FeatureIds.MAX_FARE to legacyFeatureExp),
                "lockedFeatures" to listOf(FeatureIds.AUTO_CLICK),
                "locked_features" to listOf(FeatureIds.TTS_VOICE),
                "blacklistedDestinations" to listOf("Airport", "airport", " Railway Station "),
                "blockedLocations" to listOf("Legacy Place")
            )
        )
        assertEquals(canonicalPassExp, AppSettings.passExpiryTimestampFlow.value)
        assertEquals(mapOf(FeatureIds.MIN_FARE to canonicalFeatureExp), AppSettings.unlockedFeaturesFlow.value)
        assertEquals(setOf(FeatureIds.AUTO_CLICK), AppSettings.lockedFeaturesFlow.value)
        assertEquals(listOf("Airport", "Railway Station"), AppSettings.getBlockedLocations(context))

        // 2. AppSettings: graceful fallback to legacy keys for unmigrated pre-v2 documents
        AppSettings.resetBusinessStateForTest(context)
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "activePassType" to "daily",
                "pass_expiry_date" to legacyPassExp,
                "featureExpiries" to mapOf(FeatureIds.MAX_FARE to legacyFeatureExp),
                "locked_features" to listOf(FeatureIds.TTS_VOICE),
                "blockedLocations" to listOf("Old Bus Stand", "Airport")
            )
        )
        assertEquals(legacyPassExp, AppSettings.passExpiryTimestampFlow.value)
        assertEquals(mapOf(FeatureIds.MAX_FARE to legacyFeatureExp), AppSettings.unlockedFeaturesFlow.value)
        assertEquals(setOf(FeatureIds.TTS_VOICE), AppSettings.lockedFeaturesFlow.value)
        assertEquals(listOf("Airport", "Old Bus Stand"), AppSettings.getBlockedLocations(context))

        // 3. LicenseManager.parseUserProfileSnapshotMap: canonical v2 precedence
        val parsedV2 = LicenseManager.parseUserProfileSnapshotMap(
            mapOf(
                "schemaVersion" to 2,
                "uid" to "uid_v2",
                "firebaseUid" to "uid_v2",
                "userEmail" to "canonical@example.com",
                "email" to "legacy@example.com",
                "linkedHardwareId" to "HW-CANONICAL",
                "hardwareId" to "HW-LEGACY",
                "points" to 10,
                "pointsBalance" to 250,
                "activePassType" to "monthly",
                "passExpiryTimestamp" to canonicalPassExp,
                "passExpiry" to legacyPassExp,
                "removeAdsExpiryTimestamp" to canonicalRemoveAdsExp,
                "removeAdsExpiryMs" to legacyRemoveAdsExp,
                "unlockedFeatures" to mapOf(FeatureIds.MIN_FARE to canonicalFeatureExp),
                "featureExpiries" to mapOf(FeatureIds.MAX_FARE to legacyFeatureExp),
                "lockedFeatures" to listOf(FeatureIds.AUTO_CLICK),
                "locked_features" to listOf(FeatureIds.TTS_VOICE),
                "blacklistedDestinations" to listOf("Sector 18", "Noida"),
                "blockedLocations" to listOf("Old Zone"),
                "isBanned" to true,
                "banReason" to "Tampering detected"
            )
        )
        assertEquals(2, parsedV2.schemaVersion)
        assertEquals("uid_v2", parsedV2.firebaseUid)
        assertEquals("canonical@example.com", parsedV2.userEmail)
        assertEquals("HW-CANONICAL", parsedV2.linkedHardwareId)
        assertEquals(250L, parsedV2.pointsBalance)
        assertEquals(250, parsedV2.points)
        assertTrue(parsedV2.hasPointsField)
        assertEquals(canonicalPassExp, parsedV2.passExpiryTimestamp)
        assertEquals(canonicalRemoveAdsExp, parsedV2.removeAdsExpiryTimestamp)
        assertEquals(mapOf(FeatureIds.MIN_FARE to canonicalFeatureExp), parsedV2.unlockedFeatures)
        assertEquals(setOf(FeatureIds.AUTO_CLICK), parsedV2.lockedFeatures)
        assertEquals(listOf("Noida", "Sector 18"), parsedV2.blacklistedDestinations)
        assertTrue(parsedV2.isBanned)

        // Canonical isBanned=true without future banUntil resolves to permanent ban (Long.MAX_VALUE)
        val permanentBanUntil = LicenseManager.resolveEffectiveBanUntilMs(parsedV2, now)
        assertEquals(Long.MAX_VALUE, permanentBanUntil)
        GlobalAdminManager.syncBanStateFromLicenseManager(permanentBanUntil, parsedV2.banReason)
        val banState = GlobalAdminManager.banState.value
        assertTrue(banState is UserBanState.Banned)
        assertEquals("Permanent", (banState as UserBanState.Banned).formattedDateTime)
        GlobalAdminManager.syncBanStateFromLicenseManager(null, null)

        // 4. LicenseManager.parseUserProfileSnapshotMap: legacy pre-v2 fallback (points fallback when pointsBalance absent)
        val parsedLegacy = LicenseManager.parseUserProfileSnapshotMap(
            mapOf(
                "uid" to "uid_legacy",
                "email" to "legacy@example.com",
                "deviceId" to "DEV-LEGACY",
                "points" to 85,
                "passExpiry" to legacyPassExp,
                "removeAdsExpiryMs" to legacyRemoveAdsExp,
                "featureExpiries" to mapOf(FeatureIds.MAX_FARE to legacyFeatureExp),
                "locked_features" to listOf(FeatureIds.TTS_VOICE),
                "blockedLocations" to listOf("Legacy Drop"),
                "banUntilTimestamp" to (now + 60_000L)
            )
        )
        assertEquals(1, parsedLegacy.schemaVersion)
        assertEquals("uid_legacy", parsedLegacy.firebaseUid)
        assertEquals("legacy@example.com", parsedLegacy.userEmail)
        assertEquals("DEV-LEGACY", parsedLegacy.linkedHardwareId)
        assertEquals(85L, parsedLegacy.pointsBalance)
        assertEquals(85, parsedLegacy.points)
        assertEquals(legacyPassExp, parsedLegacy.passExpiryTimestamp)
        assertEquals(legacyRemoveAdsExp, parsedLegacy.removeAdsExpiryTimestamp)
        assertEquals(mapOf(FeatureIds.MAX_FARE to legacyFeatureExp), parsedLegacy.unlockedFeatures)
        assertEquals(setOf(FeatureIds.TTS_VOICE), parsedLegacy.lockedFeatures)
        assertEquals(listOf("Legacy Drop"), parsedLegacy.blacklistedDestinations)
        assertEquals(now + 60_000L, LicenseManager.resolveEffectiveBanUntilMs(parsedLegacy, now))

        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun profilePayloadsEmitOnlyCanonicalSchemaVersion2Keys() {
        val forbiddenLegacyKeys = setOf(
            "email", "hardwareId", "deviceId", "userId",
            "blacklistedDestinations", "blockedLocations"
        )

        val initPayload = LicenseManager.buildInitProfilePayload(
            firebaseUid = "uid_clean_01",
            userEmail = "driver@example.com",
            hardwareId = "HW-CLEAN-01",
            legacyCustomId = "OM-USER-OLD1"
        )
        assertEquals(
            setOf("firebaseUid", "userEmail", "linkedHardwareId", "legacyCustomUserId"),
            initPayload.keys
        )
        assertTrue(initPayload.keys.intersect(forbiddenLegacyKeys).isEmpty())

        val updatePayload = LicenseManager.computeNeededProfileUpdates(
            snapshotFirebaseUid = null,
            snapshotHardwareId = null,
            snapshotEmail = null,
            expectedFirebaseUid = "uid_clean_01",
            currentHardwareId = "HW-CLEAN-01",
            authEmail = "driver@example.com"
        )
        assertEquals(
            setOf("firebaseUid", "userEmail", "linkedHardwareId"),
            updatePayload.keys
        )
        assertTrue(updatePayload.keys.intersect(forbiddenLegacyKeys).isEmpty())

        val validSig = "A".repeat(64)
        val initActionDoc = LicenseManager.buildActionQueuePayload(
            firebaseUid = "uid_clean_01",
            userEmail = "driver@example.com",
            hardwareId = "HW-CLEAN-01",
            deviceIntegrityOk = true,
            appSignatureSha256 = validSig,
            actionType = "INIT_PROFILE",
            payload = initPayload
        )
        assertTrue(initActionDoc.keys.intersect(forbiddenLegacyKeys).isEmpty())
        assertEquals(validSig, initActionDoc["appSignatureSha256"])

        val updateActionDoc = LicenseManager.buildActionQueuePayload(
            firebaseUid = "uid_clean_01",
            userEmail = "driver@example.com",
            hardwareId = "HW-CLEAN-01",
            deviceIntegrityOk = true,
            appSignatureSha256 = validSig,
            actionType = "UPDATE_PROFILE",
            payload = updatePayload
        )
        assertTrue(updateActionDoc.keys.intersect(forbiddenLegacyKeys).isEmpty())
        assertEquals(validSig, updateActionDoc["appSignatureSha256"])
    }

    @Test
    fun actionRateLimitCooldownEnforces13500MsAnchoredToTerminalCompletionAndServerLastActionTime() {
        LicenseManager.resetStateForTesting(context)
        assertEquals(13_500L, LicenseManager.ACTION_RATE_LIMIT_COOLDOWN_MS)

        // 1. PENDING status must NOT prematurely start the completion cooldown
        val t0 = 1_000_000L
        val pendingOutcome = LicenseManager.evaluateActionQueueSnapshot(
            actionType = "PURCHASE_PASS",
            status = "PENDING",
            nowElapsed = t0
        )
        assertEquals(LicenseManager.ActionQueueOutcome.Pending, pendingOutcome)
        assertEquals(0L, LicenseManager.getLastActionCompletedAtMsForTesting())
        assertTrue(LicenseManager.checkActionSubmissionAllowed(nowElapsed = t0 + 1_000L).isSuccess)

        // 2. Terminal COMPLETED, REJECTED, and FAILED each anchor cooldown to completion time for 13_500L ms
        listOf("COMPLETED", "REJECTED", "FAILED").forEachIndexed { idx, terminalStatus ->
            LicenseManager.resetStateForTesting(context)
            val completionElapsed = 2_000_000L + (idx * 50_000L)
            LicenseManager.evaluateActionQueueSnapshot(
                actionType = "PURCHASE_PASS",
                status = terminalStatus,
                errorMessage = if (terminalStatus == "COMPLETED") null else "Server rejected",
                nowElapsed = completionElapsed
            )
            assertEquals(completionElapsed, LicenseManager.getLastActionCompletedAtMsForTesting())

            // Blocked at +12_000ms (still inside 13.5s safety buffer) and +13_499ms
            assertEquals(1_500L, LicenseManager.computeActionCooldownRemainingMs(completionElapsed + 12_000L))
            assertEquals(1L, LicenseManager.computeActionCooldownRemainingMs(completionElapsed + 13_499L))
            val blockedResult = LicenseManager.checkActionSubmissionAllowed(completionElapsed + 12_500L)
            assertTrue("Status $terminalStatus must block premature follow-up action", blockedResult.isFailure)
            assertTrue(
                "Expected friendly cooldown message, got: ${blockedResult.exceptionOrNull()?.message}",
                blockedResult.exceptionOrNull()?.message?.contains("Please wait a few seconds before trying again", ignoreCase = true) == true
            )

            // Permitted cleanly once 13_500L ms has elapsed since terminal completion
            assertEquals(0L, LicenseManager.computeActionCooldownRemainingMs(completionElapsed + 13_500L))
            assertTrue(LicenseManager.checkActionSubmissionAllowed(completionElapsed + 13_500L).isSuccess)
        }

        // 3. In-flight lock prevents duplicate concurrent action submissions with friendly message
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setActionInFlightForTesting(true)
        val inFlightResult = LicenseManager.checkActionSubmissionAllowed(nowElapsed = 5_000_000L)
        assertTrue(inFlightResult.isFailure)
        assertEquals(LicenseManager.ACTION_COOLDOWN_FRIENDLY_MESSAGE, inFlightResult.exceptionOrNull()?.message)
        LicenseManager.setActionInFlightForTesting(false)
        assertTrue(LicenseManager.checkActionSubmissionAllowed(nowElapsed = 5_000_000L).isSuccess)

        // 4. Server users/{uid}.lastActionTime enforces the 13_500L ms window
        LicenseManager.resetStateForTesting(context)
        val nowServer = 1_900_000_000_000L
        val nowElapsed = 6_000_000L
        LicenseManager.updateLastActionCooldownFromServerTimestamp(
            lastActionServerMs = nowServer - 12_500L,
            nowServerMs = nowServer,
            nowElapsed = nowElapsed
        )
        assertEquals(1_000L, LicenseManager.computeActionCooldownRemainingMs(nowElapsed))
        assertTrue(LicenseManager.checkActionSubmissionAllowed(nowElapsed).isFailure)
        assertEquals(0L, LicenseManager.computeActionCooldownRemainingMs(nowElapsed + 1_000L))
        assertTrue(LicenseManager.checkActionSubmissionAllowed(nowElapsed + 1_000L).isSuccess)
    }

    @Test
    fun pointsBalanceAndPointsResolveIdenticallyAcrossLicenseManagerAndAppSettings() {
        AppSettings.init(context)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)

        // Case 1: Both pointsBalance and points exist -> pointsBalance wins identically in both
        val bothDoc: Map<String, Any?> = mapOf(
            "pointsBalance" to 420L,
            "points" to 90L
        )
        AppSettings.applyFirestoreSnapshotForTest(context, bothDoc)
        val parsedBoth = LicenseManager.parseUserProfileSnapshotMap(bothDoc)
        assertEquals(420L, LicenseManager.resolvePointsBalanceFromMap(bothDoc))
        assertEquals(420L, parsedBoth.pointsBalance)
        assertEquals(420, parsedBoth.points)
        assertEquals(420L, AppSettings.pointsBalanceFlow.value)

        // Case 2: Only points exists -> falls back to points identically in both
        val pointsOnlyDoc: Map<String, Any?> = mapOf(
            "points" to 175
        )
        AppSettings.applyFirestoreSnapshotForTest(context, pointsOnlyDoc)
        val parsedPointsOnly = LicenseManager.parseUserProfileSnapshotMap(pointsOnlyDoc)
        assertEquals(175L, LicenseManager.resolvePointsBalanceFromMap(pointsOnlyDoc))
        assertEquals(175L, parsedPointsOnly.pointsBalance)
        assertEquals(175, parsedPointsOnly.points)
        assertEquals(175L, AppSettings.pointsBalanceFlow.value)

        // Case 3: Neither field exists -> defaults to 0L identically in both
        val emptyDoc: Map<String, Any?> = mapOf("activePassType" to "none")
        AppSettings.applyFirestoreSnapshotForTest(context, emptyDoc)
        val parsedEmpty = LicenseManager.parseUserProfileSnapshotMap(emptyDoc)
        assertEquals(0L, LicenseManager.resolvePointsBalanceFromMap(emptyDoc))
        assertEquals(0L, parsedEmpty.pointsBalance)
        assertEquals(0, parsedEmpty.points)
        assertEquals(0L, AppSettings.pointsBalanceFlow.value)

        AppSettings.resetBusinessStateForTest(context)
    }

    @Test
    fun passTierFromIdRoundTripsBothCanonicalWireIdsAndStoredCodes() {
        val expectedPairs = listOf(
            Triple(PassTier.DAILY, "daily", "daily_pass"),
            Triple(PassTier.WEEKLY, "weekly", "weekly_pass"),
            Triple(PassTier.MONTHLY, "monthly", "monthly_pass"),
            Triple(PassTier.REMOVE_ADS, "remove_ads", "remove_ads_pass")
        )

        expectedPairs.forEach { (tier, canonicalWireId, storedCode) ->
            assertEquals(canonicalWireId, tier.canonicalId)
            assertEquals(storedCode, tier.id)

            // Exact canonical wire ID & stored code round-trips
            assertEquals(tier, PassTier.fromId(canonicalWireId))
            assertEquals(tier, PassTier.fromId(storedCode))
            assertEquals(tier, PassTier.fromId(tier.canonicalId))
            assertEquals(tier, PassTier.fromId(tier.id))

            // Case-insensitive & whitespace-trimmed inputs
            assertEquals(tier, PassTier.fromId("  ${canonicalWireId.uppercase()}  "))
            assertEquals(tier, PassTier.fromId("  ${storedCode.uppercase()}  "))
        }

        // Non-pass / invalid values strictly return null
        listOf(null, "", "  ", "none", "NONE", "null", "free", "invalid_tier").forEach { invalid ->
            assertNull("Expected null for '$invalid'", PassTier.fromId(invalid))
        }
    }

    @Test
    fun unlockMultipleFeaturesParsesCanonicalUnlockedFeaturesAndFallsBackToLegacyUnlockedExpiries() = kotlinx.coroutines.runBlocking {
        LicenseManager.resetStateForTesting(context)
        val now = LicenseManager.getCurrentServerTimeMs()
        val canonicalMinFareExp = now + 3_600_000L
        val canonicalAutoClickExp = now + 7_200_000L
        val legacyDifferentExp = now + 1_800_000L

        // Case 1: Canonical unlockedFeatures is present (alongside legacy unlockedExpiries) -> canonical wins with 0 refreshes
        var refreshCalls = 0
        val canonicalResult = LicenseManager.applyCompletedMultiFeatureUnlockSnapshot(
            actionData = mapOf(
                "newPointsBalance" to 250L,
                "unlockedFeatures" to mapOf(
                    FeatureIds.MIN_FARE to canonicalMinFareExp,
                    FeatureIds.AUTO_CLICK to canonicalAutoClickExp
                ),
                "unlockedExpiries" to mapOf(
                    FeatureIds.MIN_FARE to legacyDifferentExp,
                    FeatureIds.AUTO_CLICK to legacyDifferentExp
                )
            ),
            requestedFeatureIds = listOf(FeatureIds.MIN_FARE, FeatureIds.AUTO_CLICK),
            onMissingFeaturesRefresh = { refreshCalls++ }
        )

        assertTrue("Canonical unlockedFeatures must succeed immediately", canonicalResult.isSuccess)
        assertEquals("Zero extra server refreshes needed when canonical unlockedFeatures covers all features", 0, refreshCalls)
        assertEquals(250, LicenseManager.pointsBalance.value)
        assertEquals(
            mapOf(
                FeatureIds.MIN_FARE to canonicalMinFareExp,
                FeatureIds.AUTO_CLICK to canonicalAutoClickExp
            ),
            canonicalResult.getOrNull()
        )
        assertEquals(canonicalMinFareExp, LicenseManager.unlockedFeatures.value[FeatureIds.MIN_FARE])
        assertEquals(canonicalAutoClickExp, LicenseManager.unlockedFeatures.value[FeatureIds.AUTO_CLICK])

        // Case 2: Canonical unlockedFeatures absent -> gracefully falls back to legacy unlockedExpiries with 0 refreshes
        LicenseManager.resetStateForTesting(context)
        val legacyMaxFareExp = now + 5_400_000L
        val legacyTtsExp = now + 5_400_000L
        var legacyRefreshCalls = 0
        val legacyResult = LicenseManager.applyCompletedMultiFeatureUnlockSnapshot(
            actionData = mapOf(
                "newPointsBalance" to 190L,
                "unlockedExpiries" to mapOf(
                    FeatureIds.MAX_FARE to legacyMaxFareExp,
                    FeatureIds.TTS_VOICE to legacyTtsExp
                )
            ),
            requestedFeatureIds = listOf(FeatureIds.MAX_FARE, FeatureIds.TTS_VOICE),
            onMissingFeaturesRefresh = { legacyRefreshCalls++ }
        )

        assertTrue("Legacy unlockedExpiries fallback must succeed immediately", legacyResult.isSuccess)
        assertEquals(0, legacyRefreshCalls)
        assertEquals(190, LicenseManager.pointsBalance.value)
        assertEquals(legacyMaxFareExp, LicenseManager.unlockedFeatures.value[FeatureIds.MAX_FARE])
        assertEquals(legacyTtsExp, LicenseManager.unlockedFeatures.value[FeatureIds.TTS_VOICE])

        // Case 3: Missing feature in action doc triggers fallback profile refresh callback
        LicenseManager.resetStateForTesting(context)
        var missingRefreshCalls = 0
        val partialResult = LicenseManager.applyCompletedMultiFeatureUnlockSnapshot(
            actionData = mapOf(
                "unlockedFeatures" to mapOf(FeatureIds.MIN_FARE to canonicalMinFareExp)
            ),
            requestedFeatureIds = listOf(FeatureIds.MIN_FARE, FeatureIds.MAX_DROP_DISTANCE),
            onMissingFeaturesRefresh = { missingRefreshCalls++ }
        )
        assertEquals(1, missingRefreshCalls)
        assertTrue(partialResult.isSuccess)
        assertEquals(canonicalMinFareExp, partialResult.getOrNull()?.get(FeatureIds.MIN_FARE))
    }

    @Test
    fun appSettingsSyncsCanonicalIsAutoClickEnabledFirstAndUpdateProfileEmitsCanonicalKey() {
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry
        )

        // 1. Canonical isAutoClickEnabled present without legacy isAutoAcceptEnabled -> syncs true
        assertEquals(true, AppSettings.resolveCanonicalAutoClickFromMap(mapOf("isAutoClickEnabled" to true)))
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf("isAutoClickEnabled" to true)
        )
        assertTrue(AppSettings.isAutoClickEnabled(context))
        assertTrue(AppSettings.isAutoAcceptEnabled(context))

        // 2. Both isAutoClickEnabled (false) and legacy isAutoAcceptEnabled (true) present -> canonical wins (false)
        assertEquals(
            false,
            AppSettings.resolveCanonicalAutoClickFromMap(
                mapOf(
                    "isAutoClickEnabled" to false,
                    "isAutoAcceptEnabled" to true
                )
            )
        )
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf(
                "isAutoClickEnabled" to false,
                "isAutoAcceptEnabled" to true
            )
        )
        assertFalse("Canonical isAutoClickEnabled=false must override legacy isAutoAcceptEnabled=true", AppSettings.isAutoClickEnabled(context))
        assertFalse("Canonical isAutoClickEnabled=false must override legacy isAutoAcceptEnabled=true for auto-accept", AppSettings.isAutoAcceptEnabled(context))

        // 3. Canonical isAutoClickEnabled absent -> falls back to legacy isAutoAcceptEnabled=true
        assertEquals(true, AppSettings.resolveCanonicalAutoClickFromMap(mapOf("isAutoAcceptEnabled" to true)))
        AppSettings.applyFirestoreSnapshotForTest(
            context,
            mapOf("isAutoAcceptEnabled" to true)
        )
        assertTrue("Fallback to legacy isAutoAcceptEnabled=true when canonical key is absent", AppSettings.isAutoClickEnabled(context))
        assertTrue("Fallback to legacy isAutoAcceptEnabled=true for auto-accept when canonical key is absent", AppSettings.isAutoAcceptEnabled(context))

        // 4. UPDATE_PROFILE payloads emit canonical isAutoClickEnabled and NEVER obsolete isAutoAcceptEnabled
        val uid = "uid_step10b_autoclick"
        val hwId = "HW-STEP10B-AC"
        val email = "captain@example.com"
        val sig = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"

        val migrationUpdates = LicenseManager.computeNeededProfileUpdates(
            snapshotFirebaseUid = uid,
            snapshotHardwareId = hwId,
            snapshotEmail = email,
            expectedFirebaseUid = uid,
            currentHardwareId = hwId,
            authEmail = email,
            snapshotAutoClickEnabled = null,
            desiredAutoClickEnabled = true
        )
        assertEquals(true, migrationUpdates["isAutoClickEnabled"])
        assertFalse("UPDATE_PROFILE must never emit obsolete isAutoAcceptEnabled", migrationUpdates.containsKey("isAutoAcceptEnabled"))

        // Already-canonical doc produces zero UPDATE_PROFILE writes (no write loop)
        val noUpdates = LicenseManager.computeNeededProfileUpdates(
            snapshotFirebaseUid = uid,
            snapshotHardwareId = hwId,
            snapshotEmail = email,
            expectedFirebaseUid = uid,
            currentHardwareId = hwId,
            authEmail = email,
            snapshotAutoClickEnabled = true,
            desiredAutoClickEnabled = null
        )
        assertTrue("Canonical user doc must not trigger unnecessary UPDATE_PROFILE writes", noUpdates.isEmpty())

        // Even if legacy isAutoAcceptEnabled is passed into buildActionQueuePayload for UPDATE_PROFILE,
        // it is translated to canonical isAutoClickEnabled and obsolete keys are stripped
        val translatedEnvelope = LicenseManager.buildActionQueuePayload(
            firebaseUid = uid,
            userEmail = email,
            hardwareId = hwId,
            deviceIntegrityOk = true,
            appSignatureSha256 = sig,
            actionType = "UPDATE_PROFILE",
            payload = mapOf("isAutoAcceptEnabled" to true)
        )
        assertEquals(true, translatedEnvelope["isAutoClickEnabled"])
        assertFalse(translatedEnvelope.containsKey("isAutoAcceptEnabled"))
        assertFalse(translatedEnvelope.containsKey("autoAcceptEnabled"))

        // When both are passed on UPDATE_PROFILE, explicit canonical isAutoClickEnabled wins
        val explicitCanonicalEnvelope = LicenseManager.buildActionQueuePayload(
            firebaseUid = uid,
            userEmail = email,
            hardwareId = hwId,
            deviceIntegrityOk = true,
            appSignatureSha256 = sig,
            actionType = "UPDATE_PROFILE",
            payload = mapOf(
                "isAutoClickEnabled" to false,
                "isAutoAcceptEnabled" to true
            )
        )
        assertEquals(false, explicitCanonicalEnvelope["isAutoClickEnabled"])
        assertFalse(explicitCanonicalEnvelope.containsKey("isAutoAcceptEnabled"))

        AppSettings.resetBusinessStateForTest(context)
    }
}




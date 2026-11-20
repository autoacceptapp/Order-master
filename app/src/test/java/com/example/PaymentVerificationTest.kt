package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PaymentVerificationRepository
import com.example.data.PaymentVerificationState
import com.example.data.repository.AppConfigRepository
import com.google.firebase.firestore.ListenerRegistration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.test.runTest

/**
 * Unit tests for PaymentVerificationRepository, PassManager and validation logic.
 */
@RunWith(RobolectricTestRunner::class)
class PaymentVerificationTest {

    @After
    fun tearDown() {
        AppConfigRepository.applyAppConfigFromMap(null)
        PassPricingConfig.resetDefaultsForTesting()
        FeatureIds.resetRatesForTesting()
    }

    @Test
    fun testStrict12DigitUtrValidation() {
        // Valid 12-digit numeric UTRs
        assertTrue(PaymentVerificationRepository.isValidUtr("426719823451"))
        assertTrue(PaymentVerificationRepository.isValidUtr("123456789012"))
        assertTrue(PaymentVerificationRepository.isValidUtr("000000000000"))

        // Invalid: Less than 12 digits
        assertFalse(PaymentVerificationRepository.isValidUtr("42671982345"))
        assertFalse(PaymentVerificationRepository.isValidUtr("123"))
        assertFalse(PaymentVerificationRepository.isValidUtr(""))

        // Invalid: More than 12 digits
        assertFalse(PaymentVerificationRepository.isValidUtr("4267198234510"))

        // Invalid: Contains non-digits (letters or symbols)
        assertFalse(PaymentVerificationRepository.isValidUtr("42671982345A"))
        assertFalse(PaymentVerificationRepository.isValidUtr("4267-1982-3451"))
        assertFalse(PaymentVerificationRepository.isValidUtr("4267 1982 3451"))
        assertFalse(PaymentVerificationRepository.isValidUtr("UTR4267198234"))
    }

    @Test
    fun testAmountToPassTierMapping() {
        // Exact matches only: ₹9, ₹49, ₹179, ₹199
        assertEquals(PassTier.DAILY, PaymentVerificationRepository.mapAmountToPassTier(9L))
        assertEquals(PassTier.WEEKLY, PaymentVerificationRepository.mapAmountToPassTier(49L))
        assertEquals(PassTier.MONTHLY, PaymentVerificationRepository.mapAmountToPassTier(179L))
        assertEquals(PassTier.REMOVE_ADS, PaymentVerificationRepository.mapAmountToPassTier(199L))

        assertEquals(PassTier.DAILY, PassTier.fromAmountInInr(9L))
        assertEquals(PassTier.WEEKLY, PassTier.fromAmountInInr(49L))
        assertEquals(PassTier.MONTHLY, PassTier.fromAmountInInr(179L))
        assertEquals(PassTier.REMOVE_ADS, PassTier.fromAmountInInr(199L))

        // Arbitrary/non-exact amounts must return null (no range-based false tier mapping)
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(0L))
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(20L))
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(50L))
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(100L))
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(200L))
        assertNull(PaymentVerificationRepository.mapAmountToPassTier(500L))
        assertNull(PassPricingConfig.mapAmountToPassTier(50L))
        assertNull(PassTier.fromAmountInInr(200L))
    }

    @Test
    fun testNullTierSelectionIsRejectedWithoutSilentDailyFallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PassManager.PREFS_UTR_RATE_LIMIT, Context.MODE_PRIVATE).edit().clear().commit()

        val repo = PaymentVerificationRepository()
        repo.verifyPayment(context, "426719823451", selectedTier = null)
        val state = repo.verificationState.value
        assertTrue("Expected Error state when selectedTier is null, got $state", state is PaymentVerificationState.Error)
        val err = state as PaymentVerificationState.Error
        assertEquals("Please select the Pass plan you paid for before submitting your UTR.", err.message)
        assertTrue(err.canRetry)
        assertEquals("426719823451", err.utr)

        val detailed = repo.verifyUtrDetailed(context, "426719823451", preferredTier = null)
        assertTrue("Expected VerificationResult.Invalid when preferredTier is null, got $detailed", detailed is PassManager.VerificationResult.Invalid)
    }

    @Test
    fun testRemoveAdsPassTierPropertiesAndAdSuppression() {
        val tier = PassTier.fromId("remove_ads_pass")
        assertNotNull(tier)
        assertEquals(PassTier.REMOVE_ADS, tier)
        assertEquals(199, tier!!.priceInInr)
        assertEquals(28L * 24L * 3600L * 1000L, tier.durationMs)
        assertEquals("Remove Ads Pass", tier.title)

        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)

        // Without pass, ads should NOT be removed
        assertFalse(LicenseManager.isAdsRemoved())

        // Cache Remove Ads pass
        val futureExpiry = System.currentTimeMillis() + 86400000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.REMOVE_ADS,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )
        LicenseManager.refreshAccessStatus(context)

        // With active Remove Ads pass, ads MUST be suppressed
        assertTrue(LicenseManager.isAdsRemoved())
    }

    @Test
    fun testClipboardUtrExtractionFromSms() {
        // Common bank SMS texts
        val sms1 = "Sent Rs. 49.00 to ordermaster@okhdfcbank on 23-09-2026. UPI Ref: 426719823451. Check balance:..."
        val match1 = Regex("\\b\\d{12}\\b").find(sms1)?.value
        assertEquals("426719823451", match1)

        val sms2 = "Dear Customer, INR 179.00 debited from A/c XX1234 for VPA ordermaster@okhdfcbank. UTR no: 987654321098."
        val match2 = Regex("\\b\\d{12}\\b").find(sms2)?.value
        assertEquals("987654321098", match2)

        // Raw pasted input with whitespace
        val rawInput = " 4267 1982 3451 "
        val cleaned = rawInput.filter { it.isDigit() }.take(12)
        assertEquals("426719823451", cleaned)
        assertTrue(PaymentVerificationRepository.isValidUtr(cleaned))
    }

    @Test
    fun testRapidoAccessibilityServiceHierarchy() {
        // Confirm RapidoAccessibilityService is a valid, direct AccessibilityService implementation
        val rapidoClass = RapidoAccessibilityService::class.java
        assertTrue(android.accessibilityservice.AccessibilityService::class.java.isAssignableFrom(rapidoClass))
        assertEquals("com.rapido.rider", RapidoAccessibilityService.TARGET_PACKAGE)
    }

    @Test
    fun testPassManagerDefaultStateIsFalse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear prefs to guarantee fresh/reinstall state
        context.getSharedPreferences("PassPrefs", Context.MODE_PRIVATE).edit().clear().commit()

        // 2. Default state MUST be false
        assertFalse(PassManager.isPassActive(context))
    }

    @Test
    fun testPassManagerReadOnlyAndSetPassStatusNeutralized() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)
        assertFalse(PassManager.isPassActive(context))
        assertFalse(PassManager.isPassActiveFlow.value)
        assertFalse(PassManager::class.java.declaredMethods.any { it.name == "setPassStatus" })

        // Authoritative Pro Pass in LicenseManager activates read-only flow
        val futureExpiry = System.currentTimeMillis() + 86_400_000L
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        LicenseManager.refreshAccessStatus(context)
        assertTrue(PassManager.isPassActive(context))
        assertTrue(PassManager.isPassActiveFlow.value)

        LicenseManager.resetStateForTesting(context)
    }

    @Test
    fun testServerSideExpiryLogicRule() {
        val currentTime = System.currentTimeMillis()

        // Condition 1: currentTime < passExpiryDate && paymentStatus == "SUCCESS" -> Pass Active
        val futureExpiry = currentTime + 86400000L
        val statusSuccess = "SUCCESS"
        val isActive = currentTime < futureExpiry && statusSuccess.equals("SUCCESS", ignoreCase = true)
        assertTrue(isActive)

        // Condition 2: currentTime > passExpiryDate -> Pass Expired
        val pastExpiry = currentTime - 1000L
        val isExpired = !(currentTime < pastExpiry && statusSuccess.equals("SUCCESS", ignoreCase = true))
        assertTrue(isExpired)

        // Condition 3: paymentStatus != "SUCCESS" -> Inactive
        val statusFailed = "FAILED"
        val isFailedActive = currentTime < futureExpiry && statusFailed.equals("SUCCESS", ignoreCase = true)
        assertFalse(isFailedActive)
    }

    @Test
    fun testPaymentVerificationRepositoryAndPassManagerSupportPassTier() {
        val repo = PaymentVerificationRepository.defaultInstance
        assertNotNull("PaymentVerificationRepository defaultInstance should exist", repo)

        // Verify verifyPayment method exists on PaymentVerificationRepository with PassTier parameter
        val verifyPaymentMethods = PaymentVerificationRepository::class.java.declaredMethods.filter { it.name == "verifyPayment" }
        assertTrue("verifyPayment must be declared on PaymentVerificationRepository", verifyPaymentMethods.isNotEmpty())
        val hasTierParamOnRepo = verifyPaymentMethods.any { method ->
            method.parameterTypes.contains(PassTier::class.java)
        }
        assertTrue("PaymentVerificationRepository.verifyPayment must accept PassTier parameter", hasTierParamOnRepo)

        // Verify verifyPaymentWithBackendDetailed method exists on PassManager and delegates to PaymentVerificationRepository
        val verifyBackendDetailedMethods = PassManager::class.java.declaredMethods.filter { it.name == "verifyPaymentWithBackendDetailed" }
        assertTrue("verifyPaymentWithBackendDetailed must be declared on PassManager", verifyBackendDetailedMethods.isNotEmpty())
        assertTrue(
            "Legacy PassManager.verifyPaymentWithBackend must be removed so PaymentVerificationRepository is the single source of truth",
            PassManager::class.java.declaredMethods.none { it.name == "verifyPaymentWithBackend" }
        )
    }

    @Test
    fun testUtrRateLimitingBlocksFourthAttemptWithinOneMinute() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PassManager.PREFS_UTR_RATE_LIMIT, Context.MODE_PRIVATE).edit().clear().commit()

        // Rapid attempts 1, 2, and 3 must be permitted locally
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context))
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context))
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context))

        // 4th rapid attempt must be blocked locally by rate limiter
        assertFalse(PassManager.checkAndRecordUtrRateLimit(context))

        // When verifyPaymentWithBackendDetailed is invoked on 4th attempt, it immediately returns rate limit error without invoking Firestore
        val result = PassManager.verifyPaymentWithBackendDetailed(context, "123456789012")
        assertTrue("Result must be VerificationResult.Error", result is PassManager.VerificationResult.Error)
        assertEquals("Too many attempts. Please try again after 1 minute.", (result as PassManager.VerificationResult.Error).message)
    }

    @Test
    fun testUtrRateLimitingAllowsAttemptsAfterOneMinuteWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(PassManager.PREFS_UTR_RATE_LIMIT, Context.MODE_PRIVATE).edit().clear().commit()

        val t0 = 1_000_000L
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context, t0))
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context, t0 + 10_000L))
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context, t0 + 20_000L))
        // 4th within same minute is blocked
        assertFalse(PassManager.checkAndRecordUtrRateLimit(context, t0 + 30_000L))

        // After 61 seconds from t0, older timestamp drops out, allowing new attempt
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context, t0 + 61_000L))
    }

    @Test
    fun testUtrRateLimitingHandlesCorruptedPreferencesGracefully() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(PassManager.PREFS_UTR_RATE_LIMIT, Context.MODE_PRIVATE)
        prefs.edit().putString(PassManager.KEY_UTR_ATTEMPT_TIMESTAMPS, "corrupted,non_numeric,data!!!").commit()

        // Should not crash, recovers gracefully and allows valid verification
        assertTrue(PassManager.checkAndRecordUtrRateLimit(context))
    }

    @Test
    fun testLicenseManagerFastUiBootPopulatesPassActiveFromCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)

        val now = System.currentTimeMillis()
        val futureExpiry = now + 86400000L // 24 hours in future

        // 1. Save authoritative licensing data to cache
        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = PassTier.DAILY,
            passExpiry = futureExpiry,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = false
        )

        // 2. Verify cache reads back correctly
        val cached = LicenseManager.getCachedLicensingData(context)
        assertEquals(PassTier.DAILY, cached.passTier)
        assertEquals(futureExpiry, cached.passExpiry)

        // 3. Trigger fast UI boot refresh while hasLoadedLicensing is false
        LicenseManager.refreshAccessStatus(context)

        // 4. Assert UI state immediately reflects PassActive without waiting for network
        val status = LicenseManager.accessStatus.value
        assertTrue("Status must be PassActive on fast UI boot but was $status", status is AccessStatus.PassActive)
        assertEquals(PassTier.DAILY, (status as AccessStatus.PassActive).passTier)
        assertTrue(status.remainingTimeMs > 0)
    }

    @Test
    fun testLicenseManagerFastUiBootPopulatesTrialActiveFromCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        LicenseManager.resetStateForTesting(context)

        val now = System.currentTimeMillis()
        val trialExpiry = now + 3600000L // 1 hour in future

        LicenseManager.saveAuthoritativeLicensingCache(
            context = context,
            passTier = null,
            passExpiry = 0L,
            trialClaimed = now - 1000L,
            trialExpiry = trialExpiry,
            trialUsed = true
        )

        val cached = LicenseManager.getCachedLicensingData(context)
        assertEquals(trialExpiry, cached.trialExpiry)
        assertEquals(now - 1000L, cached.trialClaimed)
        assertTrue("isFreeTrialAllowed must be true", DeviceUtils.isFreeTrialAllowed(context))

        LicenseManager.refreshAccessStatus(context)
        val status = LicenseManager.accessStatus.value
        assertTrue("Status must be TrialActive on fast UI boot but was $status", status is AccessStatus.TrialActive)
        assertEquals(trialExpiry, (status as AccessStatus.TrialActive).expiryTimestamp)
    }

    @Test
    fun testLicenseManagerEnforcesStrictTwoHourOfflineGraceForUnpaidAndTrialUsers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(48))
            LicenseManager.resetStateForTesting(context)

            val now = System.currentTimeMillis()
            val futureTrialExpiry = now + 12L * 3600L * 1000L // Active trial for next 12 hours

            // Active trial (no paid Pro Pass) in cache
            LicenseManager.saveAuthoritativeLicensingCache(
                context = context,
                passTier = null,
                passExpiry = 0L,
                trialClaimed = now - 10_000L,
                trialExpiry = futureTrialExpiry,
                trialUsed = true
            )

            assertEquals(2, LicenseManager.getEffectiveOfflineGraceHours(context))
            assertEquals(2, AppSettings.getEffectiveOfflineGraceHours(context))

            // Simulate offline for 3 hours (> strict 2-hour unpaid/trial limit)
            val threeHoursAgo = now - (3L * 3600L * 1000L)
            LicenseManager.setLastServerSyncForTesting(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - (3L * 3600L * 1000L),
                wallClockMs = threeHoursAgo
            )

            assertTrue("Unpaid/trial offline grace period must expire after 2 hours", LicenseManager.isOfflineGracePeriodExpired(context))

            // Refresh access status
            LicenseManager.refreshAccessStatus(context)

            // Zero-Trust Security: Free/trial user MUST be Expired after 2 hours offline
            val status = LicenseManager.accessStatus.value
            assertTrue("Status must be Expired due to 2-hour offline limit", status is AccessStatus.Expired)
            assertTrue((status as AccessStatus.Expired).message.contains("2-hour offline limit"))
        } finally {
            LicenseManager.resetStateForTesting(context)
        }
    }

    @Test
    fun testPaidPassOfflineGraceExtendsTo24HoursAndCapsAtPassExpiry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofHours(48))
            LicenseManager.resetStateForTesting(context)
            AppConfigRepository.applyAppConfigFromMap(null) // default 24h paid pass grace

            val now = System.currentTimeMillis()
            val tenDaysExpiry = now + (10L * 24L * 3600L * 1000L)

            LicenseManager.saveAuthoritativeLicensingCache(
                context = context,
                passTier = PassTier.MONTHLY,
                passExpiry = tenDaysExpiry,
                trialClaimed = 0L,
                trialExpiry = 0L,
                trialUsed = false
            )

            assertEquals(24, LicenseManager.getEffectiveOfflineGraceHours(context))
            assertEquals(24, AppSettings.getEffectiveOfflineGraceHours(context))

            // 3 hours offline: MUST remain active for paid pass driver
            LicenseManager.setLastServerSyncForTesting(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - (3L * 3600L * 1000L),
                wallClockMs = now - (3L * 3600L * 1000L)
            )
            assertFalse("Paid pass driver offline 3h must NOT expire under 24h grace", LicenseManager.isOfflineGracePeriodExpired(context))
            LicenseManager.refreshAccessStatus(context)
            assertTrue("Paid pass driver offline 3h must remain PassActive", LicenseManager.accessStatus.value is AccessStatus.PassActive)

            // 20 hours offline: MUST still remain active under 24h grace
            LicenseManager.setLastServerSyncForTesting(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - (20L * 3600L * 1000L),
                wallClockMs = now - (20L * 3600L * 1000L)
            )
            assertFalse("Paid pass driver offline 20h must NOT expire under 24h grace", LicenseManager.isOfflineGracePeriodExpired(context))

            // 25 hours offline: MUST expire (> 24h grace)
            LicenseManager.setLastServerSyncForTesting(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - (25L * 3600L * 1000L),
                wallClockMs = now - (25L * 3600L * 1000L)
            )
            assertTrue("Paid pass driver offline 25h must expire after 24h grace", LicenseManager.isOfflineGracePeriodExpired(context))
            LicenseManager.refreshAccessStatus(context)
            val expiredStatus = LicenseManager.accessStatus.value
            assertTrue("Expected Expired status after 25h offline", expiredStatus is AccessStatus.Expired)
            assertTrue((expiredStatus as AccessStatus.Expired).message.contains("24-hour offline limit"))

            // Pure formula check: min(passExpiryTimestamp, lastCloudSyncAt + paidPassOfflineGraceHours * 3_600_000L)
            val syncAt = 1_700_000_000_000L
            val sixHourExpiry = syncAt + (6L * 3_600_000L)
            val deadlineCappedByExpiry = LicenseManager.computeOfflineGraceDeadlineMs(
                lastCloudSyncAtMs = syncAt,
                activePaidPassExpiryMs = sixHourExpiry,
                nowMs = syncAt + (2L * 3_600_000L),
                paidPassOfflineGraceHours = 24
            )
            assertEquals("Offline grace must never exceed passExpiryTimestamp", sixHourExpiry, deadlineCappedByExpiry)

            // Remote paidPassOfflineGraceHours override & clamping (2..72)
            AppConfigRepository.applyAppConfigFromMap(mapOf("paidPassOfflineGraceHours" to 12L))
            assertEquals(12, LicenseManager.getEffectiveOfflineGraceHours(context))
            val deadline12h = LicenseManager.computeOfflineGraceDeadlineMs(
                lastCloudSyncAtMs = syncAt,
                activePaidPassExpiryMs = syncAt + (100L * 3_600_000L),
                nowMs = syncAt + (1L * 3_600_000L)
            )
            assertEquals(syncAt + (12L * 3_600_000L), deadline12h)

            // Clamped ceiling (200 -> 72h)
            AppConfigRepository.applyAppConfigFromMap(mapOf("paidPassOfflineGraceHours" to 200L))
            assertEquals(72, LicenseManager.getEffectiveOfflineGraceHours(context))
            val deadline72h = LicenseManager.computeOfflineGraceDeadlineMs(
                lastCloudSyncAtMs = syncAt,
                activePaidPassExpiryMs = syncAt + (200L * 3_600_000L),
                nowMs = syncAt + (1L * 3_600_000L)
            )
            assertEquals(syncAt + (72L * 3_600_000L), deadline72h)
        } finally {
            LicenseManager.resetStateForTesting(context)
        }
    }

    @Test
    fun testRewardedAdManagerConfigurationAndSafety() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals(AdConfig.rewardedAdUnitId, RewardedAdManager.REWARDED_AD_UNIT_ID)
        assertEquals("ca-app-pub-3940256099942544/5224354917", RewardedAdManager.TEST_AD_UNIT_ID)
        RewardedAdManager.loadAd(context)
        assertFalse(RewardedAdManager.isAdLoaded())

        // Verify Remove Ads Pass properties
        assertEquals(199, PassTier.REMOVE_ADS.priceInInr)
        assertEquals(28, PassTier.REMOVE_ADS.durationDays)
        assertEquals("remove_ads_pass", PassTier.REMOVE_ADS.id)
        assertEquals(PassManager.isAdsRemoved(), LicenseManager.isAdsRemoved())
    }

    @Test
    fun testLicenseManagerForceReleaseLoadingState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            LicenseManager.resetStateForTesting(context)
            assertEquals(AccessStatus.Loading, LicenseManager.accessStatus.value)

            // Force release loading lock
            LicenseManager.forceReleaseLoadingState()

            // Status must transition out of Loading
            val status = LicenseManager.accessStatus.value
            assertTrue("Status must not be Loading after forceReleaseLoadingState but was $status", status !is AccessStatus.Loading)
            assertTrue("Status should be Expired for fresh testing state", status is AccessStatus.Expired)
        } finally {
            LicenseManager.resetStateForTesting(context)
        }
    }

    @Test
    fun testRewardedAdManagerNoFillReleaseHandlingAndVersionCode() {
        try {
            RewardedAdManager.setMockLoadedForTesting(false)
            assertFalse(RewardedAdManager.isNoFillState.value)
            assertEquals(RewardedAdManager.LOADING_USER_MESSAGE, RewardedAdManager.getUnavailableMessage())

            // In release builds (!isDebugBuild), ERROR_CODE_NO_FILL (3) must stop auto-retries and surface friendly message
            assertFalse(
                RewardedAdManager.shouldRetryAfterFailure(
                    com.google.android.gms.ads.AdRequest.ERROR_CODE_NO_FILL,
                    isDebugBuild = false
                )
            )
            RewardedAdManager.simulateLoadFailureForTesting(
                com.google.android.gms.ads.AdRequest.ERROR_CODE_NO_FILL,
                isDebugBuild = false
            )
            assertTrue(RewardedAdManager.isNoFillState.value)
            assertFalse(RewardedAdManager.isLoadingState.value)
            assertEquals(RewardedAdManager.NO_FILL_USER_MESSAGE, RewardedAdManager.lastErrorMessage.value)
            assertEquals(RewardedAdManager.NO_FILL_USER_MESSAGE, RewardedAdManager.getUnavailableMessage())

            // Default local BuildConfig.APP_VERSION_CODE must be >= 1 and aligned below GitHub Actions run numbers
            assertTrue(BuildConfig.APP_VERSION_CODE >= 1)
        } finally {
            RewardedAdManager.setMockLoadedForTesting(false)
        }
    }

    @Test
    fun testUtrSnapshotEvaluationHandlesApprovedWithIsUsedWithoutDeadlock() {
        val currentUser = "user_uid_123"

        // Admin approves pending_verifications/{utr} -> status="APPROVED", isUsed=true, blank claimedBy
        assertEquals(
            PaymentVerificationRepository.UtrSnapshotDecision.APPROVED_ALREADY_APPLIED,
            PaymentVerificationRepository.evaluateUtrSnapshot(
                status = "APPROVED",
                isUsed = true,
                claimedBy = "",
                docOwnerId = currentUser,
                currentUserId = currentUser
            )
        )

        // Approved with explicit claimedBy matching currentUser
        assertEquals(
            PaymentVerificationRepository.UtrSnapshotDecision.APPROVED_ALREADY_APPLIED,
            PaymentVerificationRepository.evaluateUtrSnapshot(
                status = "approved",
                isUsed = true,
                claimedBy = currentUser,
                docOwnerId = currentUser,
                currentUserId = currentUser
            )
        )

        // VERIFIED + isUsed=true for current user -> already applied (not an error)
        assertEquals(
            PaymentVerificationRepository.UtrSnapshotDecision.APPROVED_ALREADY_APPLIED,
            PaymentVerificationRepository.evaluateUtrSnapshot(
                status = "VERIFIED",
                isUsed = true,
                claimedBy = currentUser,
                docOwnerId = currentUser,
                currentUserId = currentUser
            )
        )

        // Verified + isUsed=false -> ready for ACTIVATE_PASS_PAYMENT claim transaction
        assertEquals(
            PaymentVerificationRepository.UtrSnapshotDecision.VERIFIED_READY_TO_CLAIM,
            PaymentVerificationRepository.evaluateUtrSnapshot(
                status = "Verified",
                isUsed = false,
                claimedBy = null,
                docOwnerId = null,
                currentUserId = currentUser
            )
        )

        // Claimed by a DIFFERENT user MUST still return ALREADY_CLAIMED_BY_OTHER
        assertEquals(
            PaymentVerificationRepository.UtrSnapshotDecision.ALREADY_CLAIMED_BY_OTHER,
            PaymentVerificationRepository.evaluateUtrSnapshot(
                status = "APPROVED",
                isUsed = true,
                claimedBy = "other_user_999",
                docOwnerId = "other_user_999",
                currentUserId = currentUser
            )
        )
    }

    @Test
    fun testPendingVerificationPayloadMatchesFirestoreRulesAllowlist() {
        val unifiedAllowedKeys = setOf(
            "deviceHardwareId", "hardwareId", "passName", "passTier", "passType",
            "amount", "paymentAmount", "utr", "status", "isUsed", "userId",
            "firebaseUid", "uid", "userEmail", "email", "timestamp", "createdAt", "submittedAt", "notes"
        )
        val utr = "426719823451"
        val authUid = "uid_1"
        val payload = PaymentVerificationRepository.buildPendingVerificationPayload(
            utr = utr,
            firebaseUid = authUid,
            tier = PassTier.MONTHLY,
            deviceHardwareId = "hw_test_1234"
        )

        // Core owner & state invariants of isValidPendingVerificationCreate()
        assertEquals(authUid, payload["uid"])
        assertEquals(authUid, payload["firebaseUid"])
        assertFalse("Payload must not duplicate userId alongside canonical uid/firebaseUid", payload.containsKey("userId"))
        assertEquals("PENDING", payload["status"])
        assertEquals(false, payload["isUsed"])
        assertEquals(utr, payload["utr"])
        assertTrue((payload["utr"] as String).matches(Regex("^[0-9]{12}$")))
        assertTrue(payload["amount"] is Int)
        assertEquals("hw_test_1234", payload["deviceHardwareId"])
        assertEquals(PassTier.MONTHLY.title, payload["passName"])
        assertEquals(PassTier.MONTHLY.id, payload["passTier"])
        assertEquals(com.google.firebase.firestore.FieldValue.serverTimestamp(), payload["createdAt"])
        assertTrue(
            "Unexpected keys outside unified firestore.rules allowlist: ${payload.keys - unifiedAllowedKeys}",
            unifiedAllowedKeys.containsAll(payload.keys)
        )
        assertFalse(payload.containsKey("userEmail"))
        assertFalse(payload.containsKey("linkedHardwareId"))
    }

    @Test
    fun testRemoveAdsExpiryResolvesRemoveAdsExpiryInsteadOfActiveProPass() {
        val now = 1_800_000_000_000L
        val proExpiry = now + 86_400_000L
        val stackedRemoveAdsExpiry = now + (56L * 86_400_000L)

        // When user already holds an active Pro Pass and claims REMOVE_ADS, expiry must come from
        // serverRemoveAdsExpiry / removeAdsExpiryMs rather than activePassInfo or default +28d
        val resolvedStacked = PaymentVerificationRepository.resolveActivatedExpiryTimestamp(
            tier = PassTier.REMOVE_ADS,
            serverTier = PassTier.DAILY, // Pro pass remains primary activePassType
            serverExpiry = proExpiry,
            activePassExpiry = proExpiry,
            removeAdsExpiry = stackedRemoveAdsExpiry,
            now = now
        )
        assertEquals(stackedRemoveAdsExpiry, resolvedStacked)

        // When serverTier == REMOVE_ADS directly, serverExpiry wins
        val serverRemoveAds = now + (30L * 86_400_000L)
        val resolvedDirect = PaymentVerificationRepository.resolveActivatedExpiryTimestamp(
            tier = PassTier.REMOVE_ADS,
            serverTier = PassTier.REMOVE_ADS,
            serverExpiry = serverRemoveAds,
            activePassExpiry = 0L,
            removeAdsExpiry = 0L,
            now = now
        )
        assertEquals(serverRemoveAds, resolvedDirect)

        // When neither serverExpiry nor removeAdsExpiry is > now, safely falls back to now + durationMs
        val resolvedFallback = PaymentVerificationRepository.resolveActivatedExpiryTimestamp(
            tier = PassTier.REMOVE_ADS,
            serverTier = null,
            serverExpiry = 0L,
            activePassExpiry = proExpiry,
            removeAdsExpiry = 0L,
            now = now
        )
        assertEquals(now + PassTier.REMOVE_ADS.durationMs, resolvedFallback)
    }

    @Test
    fun testActionQueueBasePayloadEmitsOnlyCanonicalFirebaseUidWithoutUserId() {
        val payload = LicenseManager.buildActionQueuePayload(
            firebaseUid = "uid_canonical_99",
            userEmail = "captain@example.com",
            hardwareId = "hw_id_abc",
            deviceIntegrityOk = true,
            appSignatureSha256 = "sha256_test",
            actionType = "ACTIVATE_PASS_PAYMENT",
            payload = mapOf(
                "userId" to "should_be_stripped",
                "passTier" to PassTier.REMOVE_ADS.id,
                "utr" to "123456789012"
            )
        )

        assertEquals("uid_canonical_99", payload["uid"])
        assertEquals("uid_canonical_99", payload["firebaseUid"])
        assertFalse("Action queue payload must not include duplicate userId", payload.containsKey("userId"))
        assertTrue(payload.keys.containsAll(setOf("uid", "firebaseUid", "actionType", "status", "createdAt")))
        assertEquals(com.google.firebase.firestore.FieldValue.serverTimestamp(), payload["createdAt"])
        assertTrue(
            "Unexpected keys outside unified firestore.rules allowlist: ${payload.keys - LicenseManager.PENDING_ACTION_ALLOWED_KEYS}",
            LicenseManager.PENDING_ACTION_ALLOWED_KEYS.containsAll(payload.keys)
        )
    }

    @Test
    fun testAllClientPendingActionsSatisfyUnifiedFirestoreRulesInvariants() {
        val authUid = "auth_uid_unified_01"
        val userEmail = "driver@example.com"
        val hwId = "OM-TEST-HW-0001"
        val sig = "abcdef0123456789"
        val serverTs = com.google.firebase.firestore.FieldValue.serverTimestamp()

        val forbiddenPrivilegedKeys = setOf(
            "payload", "data", "email", "isAdmin", "role",
            "points", "pointsBalance", "newPointsBalance", "pointsAdded", "pointsDeducted",
            "activePassType", "passExpiryTimestamp", "verifiedUtr",
            "processedBy", "processedAt", "completedAt", "failedAt", "errorMessage",
            "resolvedUserId", "banUntil", "banUntilTimestamp", "isBanned", "hardwareBanned",
            "isPaymentVerified", "locked_features", "lockedFeatures",
            "unlocked_features", "unlockedFeatures", "featureExpiries",
            "trialExpiry", "freeTrialExpiry", "trialExpiresAt", "trialClaimed", "trialUsed",
            "hasUsedFreeTrial", "removeAdsExpiryTimestamp", "removeAdsExpiryMs",
            "remove_ads_expiry_timestamp", "trialStartedAt", "hasClaimedReferral",
            "referredBy", "lastSecurityViolation", "lastActionTime", "lastPendingActionAt", "lastActionAt"
        )

        fun assertPassesUnifiedPendingActionRules(doc: Map<String, Any>) {
            // 1. Canonical owner check
            assertEquals("uid must equal auth.uid", authUid, doc["uid"])
            assertEquals("firebaseUid must equal auth.uid", authUid, doc["firebaseUid"])
            assertFalse("userId must not be emitted", doc.containsKey("userId"))

            // 2. Status & actionType allowlist
            assertEquals("PENDING", doc["status"])
            val actionType = doc["actionType"] as? String
            assertNotNull(actionType)
            assertTrue("actionType '$actionType' not allowlisted", actionType in LicenseManager.PENDING_ACTION_ALLOWED_TYPES)
            assertFalse("ADD_POINTS must never be allowlisted", actionType == "ADD_POINTS")

            // 3. No reward amount override on REWARD_AD_WATCHED
            if (actionType == "REWARD_AD_WATCHED") {
                assertFalse("REWARD_AD_WATCHED must never include amount", doc.containsKey("amount"))
            }

            // 4. Server timestamp createdAt (immune to client clock skew)
            assertEquals("createdAt must use FieldValue.serverTimestamp()", serverTs, doc["createdAt"])

            // 5. Flat canonical schema allowlist & privileged field blocklist
            assertTrue(
                "Disallowed keys in $actionType: ${doc.keys - LicenseManager.PENDING_ACTION_ALLOWED_KEYS}",
                LicenseManager.PENDING_ACTION_ALLOWED_KEYS.containsAll(doc.keys)
            )
            assertTrue(
                "Forbidden privileged/nested keys in $actionType: ${doc.keys.intersect(forbiddenPrivilegedKeys)}",
                doc.keys.intersect(forbiddenPrivilegedKeys).isEmpty()
            )

            // 6. Flat canonical parameter types (hasValidPendingActionFields)
            doc["userEmail"]?.let { assertTrue(it is String) }
            doc["passTier"]?.let { assertTrue(it is String) }
            doc["utr"]?.let { assertTrue(it is String) }
            doc["referralCode"]?.let { assertTrue(it is String) }
            doc["hardwareId"]?.let { assertTrue(it is String) }
            doc["linkedHardwareId"]?.let { assertTrue(it is String) }
            doc["featureId"]?.let { assertTrue(it is String) }
            doc["featureIds"]?.let { assertTrue(it is List<*>) }
            doc["hours"]?.let { assertTrue(it is Int || it is Long || it is Double || it is Float) }
        }

        val actionCases = listOf(
            "INIT_PROFILE" to LicenseManager.buildInitProfilePayload(authUid, userEmail, hwId, "OM-USER-OLD1"),
            "UPDATE_PROFILE" to LicenseManager.computeNeededProfileUpdates("", "", "", authUid, hwId, userEmail),
            "MIGRATE_PROFILE" to mapOf("legacyCustomUserId" to "OM-USER-OLD1", "oldHardwareId" to "HW-OLD"),
            "CLAIM_REFERRAL" to mapOf("referralCode" to "OMAB12CD"),
            "PURCHASE_PASS" to LicenseManager.buildPurchasePassPayload(PassTier.DAILY),
            "PURCHASE_PASS" to LicenseManager.buildPurchasePassPayload(PassTier.WEEKLY),
            "PURCHASE_PASS" to LicenseManager.buildPurchasePassPayload(PassTier.MONTHLY),
            "PURCHASE_PASS" to LicenseManager.buildPurchasePassPayload(PassTier.REMOVE_ADS),
            "ACTIVATE_PASS_PAYMENT" to LicenseManager.buildActivatePassPaymentPayload(PassTier.WEEKLY, "123456789012"),
            "UNLOCK_FEATURE" to LicenseManager.buildUnlockFeaturePayload(FeatureIds.MIN_FARE, 2),
            "UNLOCK_MULTIPLE_FEATURES" to LicenseManager.buildUnlockMultipleFeaturesPayload(
                listOf(FeatureIds.MIN_FARE, FeatureIds.AUTO_CLICK),
                3
            ),
            "CLAIM_TRIAL" to emptyMap(),
            "REWARD_AD_WATCHED" to emptyMap()
        )

        actionCases.forEach { (actionType, payload) ->
            val built = LicenseManager.buildActionQueuePayload(
                firebaseUid = authUid,
                userEmail = userEmail,
                hardwareId = hwId,
                deviceIntegrityOk = true,
                appSignatureSha256 = sig,
                actionType = actionType,
                payload = payload
            )
            assertPassesUnifiedPendingActionRules(built)
        }

        // Defense-in-depth: privileged/nested keys and REWARD_AD_WATCHED amount are stripped automatically
        val sanitizedReward = LicenseManager.buildActionQueuePayload(
            firebaseUid = authUid,
            userEmail = userEmail,
            hardwareId = hwId,
            deviceIntegrityOk = true,
            appSignatureSha256 = sig,
            actionType = "REWARD_AD_WATCHED",
            payload = mapOf(
                "amount" to 9999,
                "email" to "spoof@example.com",
                "payload" to mapOf("nested" to true),
                "data" to mapOf("points" to 5000),
                "points" to 5000,
                "activePassType" to "monthly",
                "verifiedUtr" to "123456789012",
                "isAdmin" to true
            )
        )
        assertPassesUnifiedPendingActionRules(sanitizedReward)
        assertFalse("ADD_POINTS must not be in PENDING_ACTION_ALLOWED_TYPES", "ADD_POINTS" in LicenseManager.PENDING_ACTION_ALLOWED_TYPES)
    }

    @Test
    fun testDynamicPricingOverridesAndFallbackDefaults() {
        try {
            PassPricingConfig.applyOverrides(
                dailyPriceInr = 15,
                weeklyPriceInr = 69,
                monthlyPriceInr = 199,
                removeAdsPriceInr = 249,
                dailyPoints = 150,
                weeklyPoints = 650
            )
            assertEquals(15, PassTier.DAILY.effectivePriceInInr())
            assertEquals(69, PassTier.WEEKLY.effectivePriceInInr())
            assertEquals(199, PassTier.MONTHLY.effectivePriceInInr())
            assertEquals(249, PassTier.REMOVE_ADS.effectivePriceInInr())
            assertEquals(150, PassTier.DAILY.effectivePointsCost())
            assertEquals(650, PassTier.WEEKLY.effectivePointsCost())
            // Non-overridden points fall back cleanly to defaults
            assertEquals(PassPricingConfig.MONTHLY_POINTS, PassTier.MONTHLY.effectivePointsCost())
            assertEquals(PassTier.WEEKLY, PassPricingConfig.mapAmountToPassTier(69L))
            assertNull(PassPricingConfig.mapAmountToPassTier(49L))

            FeatureIds.applyRateOverrides(mapOf(FeatureIds.MIN_FARE to 20))
            assertEquals(20, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
            assertEquals(10, FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_FARE))
        } finally {
            PassPricingConfig.resetDefaultsForTesting()
            FeatureIds.resetRatesForTesting()
        }

        // Defaults restored cleanly
        assertEquals(9, PassTier.DAILY.effectivePriceInInr())
        assertEquals(10, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
    }

    @Test
    fun testPaymentNotReceivedYetIsNonTerminalAndKeepsListenerAlive() {
        val expectedMsg =
            "UTR submitted. Waiting for bank SMS confirmation — your pass will activate automatically once received."

        // LicenseManager formats PAYMENT_NOT_RECEIVED_YET cleanly
        val formatted = LicenseManager.formatActionQueueErrorMessage(
            actionType = "ACTIVATE_PASS_PAYMENT",
            rawError = "PAYMENT_NOT_RECEIVED_YET"
        )
        assertEquals(expectedMsg, formatted)

        // Both raw server code and pre-formatted message classify as non-terminal pending
        listOf(
            PaymentVerificationRepository.classifyVerificationRejection("PAYMENT_NOT_RECEIVED_YET"),
            PaymentVerificationRepository.classifyVerificationRejection(
                rawError = null,
                errorCode = "PAYMENT_NOT_RECEIVED_YET"
            ),
            PaymentVerificationRepository.classifyVerificationRejection(formatted)
        ).forEach { decision ->
            assertFalse("PAYMENT_NOT_RECEIVED_YET must be non-terminal", decision.isTerminal)
            assertTrue("PAYMENT_NOT_RECEIVED_YET must keep isStillPending = true", decision.isStillPending)
            assertTrue("PAYMENT_NOT_RECEIVED_YET must keep canRetry = true", decision.canRetry)
            assertFalse("PAYMENT_NOT_RECEIVED_YET must not mark alreadyClaimed", decision.isAlreadyClaimed)
            assertEquals(expectedMsg, decision.message)
        }

        // Listener on pending_verifications/{utr} stays attached and state transitions to Pending
        val repo = PaymentVerificationRepository()
        var removeCount = 0
        val fakeRegistration = ListenerRegistration { removeCount++ }
        val utr = "426719823451"
        repo.attachListenerForTesting(utr, fakeRegistration)
        assertTrue(repo.hasActiveListenerForTesting())

        val decision = PaymentVerificationRepository.classifyVerificationRejection("PAYMENT_NOT_RECEIVED_YET")
        repo.applyRejectionDecision(utr, decision)

        assertEquals("Active pending_verifications listener must NOT be cancelled", 0, removeCount)
        assertTrue("Listener must remain active for auto-approval", repo.hasActiveListenerForTesting())
        val state = repo.verificationState.value
        assertTrue("State must remain Pending, got $state", state is PaymentVerificationState.Pending)
        val pending = state as PaymentVerificationState.Pending
        assertEquals(utr, pending.utr)
        assertEquals(expectedMsg, pending.message)

        // Clean up
        repo.cancelPendingListener()
        assertEquals(1, removeCount)
    }

    @Test
    fun testUtrAlreadyUsedAndAmountMismatchAreTerminalAndDetachListener() {
        val utr = "426719823452"

        // 1. UTR_ALREADY_USED
        val formattedUsed = LicenseManager.formatActionQueueErrorMessage(
            actionType = "ACTIVATE_PASS_PAYMENT",
            rawError = "UTR_ALREADY_USED"
        )
        assertEquals("UTR already claimed by another user.", formattedUsed)

        val usedDecision = PaymentVerificationRepository.classifyVerificationRejection("UTR_ALREADY_USED")
        assertTrue("UTR_ALREADY_USED must be terminal", usedDecision.isTerminal)
        assertFalse("UTR_ALREADY_USED must not allow retry", usedDecision.canRetry)
        assertTrue("UTR_ALREADY_USED must set isAlreadyClaimed = true", usedDecision.isAlreadyClaimed)
        assertFalse("UTR_ALREADY_USED must not be isStillPending", usedDecision.isStillPending)
        assertEquals("UTR already claimed by another user.", usedDecision.message)

        val repo = PaymentVerificationRepository()
        var usedRemoved = 0
        repo.attachListenerForTesting(utr, ListenerRegistration { usedRemoved++ })
        repo.applyRejectionDecision(utr, usedDecision)
        assertEquals("Listener must detach immediately on UTR_ALREADY_USED", 1, usedRemoved)
        assertFalse(repo.hasActiveListenerForTesting())
        val usedState = repo.verificationState.value
        assertTrue("State must be Error on UTR_ALREADY_USED", usedState is PaymentVerificationState.Error)
        assertFalse((usedState as PaymentVerificationState.Error).canRetry)
        assertEquals("UTR already claimed by another user.", usedState.message)

        // 2. AMOUNT_MISMATCH (both bare code and Admin detailed format)
        val expectedMismatchMsg =
            "Payment amount does not match the selected Pass plan. Please select the exact plan you paid for and try again."
        assertEquals(
            expectedMismatchMsg,
            LicenseManager.formatActionQueueErrorMessage(
                actionType = "ACTIVATE_PASS_PAYMENT",
                rawError = "AMOUNT_MISMATCH: expected 49, got 9"
            )
        )

        listOf(
            PaymentVerificationRepository.classifyVerificationRejection("AMOUNT_MISMATCH: expected 49, got 9"),
            PaymentVerificationRepository.classifyVerificationRejection("AMOUNT_MISMATCH"),
            PaymentVerificationRepository.classifyVerificationRejection(expectedMismatchMsg)
        ).forEach { mismatchDecision ->
            assertTrue("AMOUNT_MISMATCH must be terminal", mismatchDecision.isTerminal)
            assertTrue("AMOUNT_MISMATCH must allow retry with correct plan", mismatchDecision.canRetry)
            assertFalse("AMOUNT_MISMATCH must not be isStillPending", mismatchDecision.isStillPending)
            assertFalse("AMOUNT_MISMATCH must not set isAlreadyClaimed", mismatchDecision.isAlreadyClaimed)
            assertEquals(expectedMismatchMsg, mismatchDecision.message)
        }

        var mismatchRemoved = 0
        repo.attachListenerForTesting(utr, ListenerRegistration { mismatchRemoved++ })
        val mismatchDecision = PaymentVerificationRepository.classifyVerificationRejection(
            "AMOUNT_MISMATCH: expected 49, got 9"
        )
        repo.applyRejectionDecision(utr, mismatchDecision)
        assertEquals("Listener must detach immediately on AMOUNT_MISMATCH", 1, mismatchRemoved)
        assertFalse(repo.hasActiveListenerForTesting())
        val mismatchState = repo.verificationState.value
        assertTrue("State must be Error on AMOUNT_MISMATCH", mismatchState is PaymentVerificationState.Error)
        assertTrue((mismatchState as PaymentVerificationState.Error).canRetry)
        assertEquals(expectedMismatchMsg, mismatchState.message)
    }

    @Test
    fun testAllClientFirestorePayloadBuildersEmitZeroLegacyAliasKeys() {
        val forbiddenLegacyKeys = setOf(
            "email",
            "hardwareId",
            "deviceId",
            "blockedLocations",
            "points",
            "userId",
            "payload",
            "data"
        )

        val uid = "uid_step8b_hygiene"
        val userEmail = "captain.hygiene@example.com"
        val hwId = "HW-HYGIENE-8B"
        val sig = "aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99:aa:bb:cc:dd:ee:ff:00:11:22:33:44:55:66:77:88:99"

        fun assertNoLegacyKeys(label: String, map: Map<String, Any>) {
            val found = map.keys.intersect(forbiddenLegacyKeys)
            assertTrue("$label emitted forbidden legacy keys: $found", found.isEmpty())
        }

        // 1. Profile payload builders
        assertNoLegacyKeys(
            "buildInitProfilePayload",
            LicenseManager.buildInitProfilePayload(uid, userEmail, hwId, legacyCustomId = "OM_OLD")
        )
        assertNoLegacyKeys(
            "computeNeededProfileUpdates",
            LicenseManager.computeNeededProfileUpdates(
                snapshotFirebaseUid = null,
                snapshotHardwareId = "OLD_HW",
                snapshotEmail = "old@example.com",
                expectedFirebaseUid = uid,
                currentHardwareId = hwId,
                authEmail = userEmail
            )
        )

        // 2. Pass & feature payload builders
        PassTier.entries.forEach { tier ->
            assertNoLegacyKeys("buildPurchasePassPayload($tier)", LicenseManager.buildPurchasePassPayload(tier))
            assertNoLegacyKeys(
                "buildActivatePassPaymentPayload($tier)",
                LicenseManager.buildActivatePassPaymentPayload(tier, "426719823451")
            )
            assertNoLegacyKeys(
                "buildPendingVerificationPayload($tier)",
                PaymentVerificationRepository.buildPendingVerificationPayload(
                    utr = "426719823451",
                    firebaseUid = uid,
                    tier = tier,
                    deviceHardwareId = hwId
                )
            )
        }
        assertNoLegacyKeys(
            "buildUnlockFeaturePayload",
            LicenseManager.buildUnlockFeaturePayload(FeatureIds.MIN_FARE, 2)
        )
        assertNoLegacyKeys(
            "buildUnlockMultipleFeaturesPayload",
            LicenseManager.buildUnlockMultipleFeaturesPayload(listOf(FeatureIds.MIN_FARE, FeatureIds.MAX_FARE), 1)
        )

        // 3. Action queue envelope builder (including hostile legacy key injection defense)
        val hostileLegacyPayload = mapOf<String, Any>(
            "email" to "legacy@example.com",
            "hardwareId" to "LEGACY_HW",
            "deviceId" to "LEGACY_DEV",
            "blockedLocations" to listOf("Airport"),
            "points" to 9999,
            "userId" to "legacy_uid",
            "payload" to mapOf("nested" to true),
            "data" to mapOf("nested" to true),
            "passTier" to "weekly",
            "utr" to "426719823451"
        )
        LicenseManager.PENDING_ACTION_ALLOWED_TYPES.forEach { actionType ->
            val built = LicenseManager.buildActionQueuePayload(
                firebaseUid = uid,
                userEmail = userEmail,
                hardwareId = hwId,
                deviceIntegrityOk = true,
                appSignatureSha256 = sig,
                actionType = actionType,
                payload = hostileLegacyPayload
            )
            assertNoLegacyKeys("buildActionQueuePayload($actionType)", built)
            assertEquals(uid, built["uid"])
            assertEquals(uid, built["firebaseUid"])
            assertEquals(userEmail, built["userEmail"])
            assertEquals(hwId, built["linkedHardwareId"])
        }
    }

    @Test
    fun testApprovedSnapshotResolutionHandlesAutoAdjustedTierAndNormalApproval() {
        // Case 1: Admin auto-matched paid ₹49 from requested Daily Pass to Weekly Pass (String field)
        val autoAdjusted = PaymentVerificationRepository.resolveApprovedSnapshot(
            amount = 49L,
            resolvedPassTierId = "weekly_pass",
            docPassTierId = "daily_pass",
            autoAdjustedFromRaw = "daily_pass",
            requestedTier = PassTier.DAILY,
            serverTier = PassTier.WEEKLY
        )
        assertNotNull(autoAdjusted)
        assertEquals(PassTier.WEEKLY, autoAdjusted!!.passTier)
        assertEquals(PassTier.DAILY, autoAdjusted.autoAdjustedFromRequestedTier)
        assertTrue(autoAdjusted.message.contains("Auto-matched from Daily Pass to Weekly Pass"))

        val successState = PaymentVerificationState.Success(
            utr = "426719823451",
            amount = 49L,
            passTier = autoAdjusted.passTier,
            expiryTimestamp = System.currentTimeMillis() + 86_400_000L,
            isImmediate = true,
            message = autoAdjusted.message,
            resolvedPassTier = autoAdjusted.passTier,
            autoAdjustedFromRequestedTier = autoAdjusted.autoAdjustedFromRequestedTier
        )
        assertTrue(successState.wasAutoAdjusted)
        assertEquals(PassTier.WEEKLY, successState.resolvedPassTier)
        assertEquals(PassTier.DAILY, successState.autoAdjustedFromRequestedTier)

        // Case 2: Admin sends boolean autoAdjustedFromRequestedTier = true
        val autoAdjustedBool = PaymentVerificationRepository.resolveApprovedSnapshot(
            amount = 179L,
            resolvedPassTierId = "monthly",
            docPassTierId = "weekly",
            autoAdjustedFromRaw = true,
            requestedTier = PassTier.WEEKLY,
            serverTier = PassTier.MONTHLY
        )
        assertNotNull(autoAdjustedBool)
        assertEquals(PassTier.MONTHLY, autoAdjustedBool!!.passTier)
        assertEquals(PassTier.WEEKLY, autoAdjustedBool.autoAdjustedFromRequestedTier)

        // Case 3: Normal exact match approval (no auto-adjustment)
        val normalApproval = PaymentVerificationRepository.resolveApprovedSnapshot(
            amount = 49L,
            resolvedPassTierId = "weekly_pass",
            docPassTierId = "weekly_pass",
            autoAdjustedFromRaw = null,
            requestedTier = PassTier.WEEKLY,
            serverTier = PassTier.WEEKLY
        )
        assertNotNull(normalApproval)
        assertEquals(PassTier.WEEKLY, normalApproval!!.passTier)
        assertNull(normalApproval.autoAdjustedFromRequestedTier)
        assertFalse(normalApproval.message.contains("Auto-matched"))
    }
}




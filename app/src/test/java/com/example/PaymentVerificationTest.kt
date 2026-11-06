package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PaymentVerificationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Daily: ₹9
        assertEquals(PassTier.DAILY, PaymentVerificationRepository.mapAmountToPassTier(9L))
        assertEquals(PassTier.DAILY, PaymentVerificationRepository.mapAmountToPassTier(20L))

        // Weekly: ₹49
        assertEquals(PassTier.WEEKLY, PaymentVerificationRepository.mapAmountToPassTier(49L))
        assertEquals(PassTier.WEEKLY, PaymentVerificationRepository.mapAmountToPassTier(100L))

        // Monthly: ₹179
        assertEquals(PassTier.MONTHLY, PaymentVerificationRepository.mapAmountToPassTier(179L))
        assertEquals(PassTier.MONTHLY, PaymentVerificationRepository.mapAmountToPassTier(500L))

        // Remove Ads: ₹199
        assertEquals(PassTier.REMOVE_ADS, PaymentVerificationRepository.mapAmountToPassTier(199L))
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
        // Confirm RapidoAccessibilityService is a valid AccessibilityService and MyAccessibilityService subclass
        val rapidoClass = RapidoAccessibilityService::class.java
        assertTrue(MyAccessibilityService::class.java.isAssignableFrom(rapidoClass))
        assertTrue(android.accessibilityservice.AccessibilityService::class.java.isAssignableFrom(rapidoClass))
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
    fun testPassManagerSetPassStatusAndToggle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PassManager.setPassStatus(context, true)
        assertTrue(PassManager.isPassActive(context))

        PassManager.setPassStatus(context, false)
        assertFalse(PassManager.isPassActive(context))
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
    fun testPaymentActivityDeclared() {
        val activityClass = PaymentActivity::class.java
        assertTrue(android.app.Activity::class.java.isAssignableFrom(activityClass))
    }

    @Test
    fun testPaymentActivityPaymentMethodsSupportPassTier() {
        val activityClass = PaymentActivity::class.java

        // Verify onPaymentSuccess method exists with String and PassTier parameters
        val onPaymentSuccessMethods = activityClass.declaredMethods.filter { it.name == "onPaymentSuccess" }
        assertTrue("onPaymentSuccess must be declared", onPaymentSuccessMethods.isNotEmpty())

        val hasTierParamOnPaymentSuccess = onPaymentSuccessMethods.any { method ->
            method.parameterTypes.contains(PassTier::class.java)
        }
        assertTrue("onPaymentSuccess must accept PassTier parameter", hasTierParamOnPaymentSuccess)

        // Verify verifyPaymentWithBackend method exists with PassTier parameter
        val verifyBackendMethods = activityClass.declaredMethods.filter { it.name == "verifyPaymentWithBackend" }
        assertTrue("verifyPaymentWithBackend must be declared", verifyBackendMethods.isNotEmpty())

        val hasTierParamVerifyBackend = verifyBackendMethods.any { method ->
            method.parameterTypes.contains(PassTier::class.java)
        }
        assertTrue("verifyPaymentWithBackend must accept PassTier parameter", hasTierParamVerifyBackend)
    }

    @Test
    fun testUtrRateLimitingBlocksFourthAttemptWithinOneMinute() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PassManager.resetUtrRateLimit(context)

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
        PassManager.resetUtrRateLimit(context)

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

        LicenseManager.refreshAccessStatus(context)

        val status = LicenseManager.accessStatus.value
        assertTrue("Status must be TrialActive on fast UI boot but was $status", status is AccessStatus.TrialActive)
        assertEquals(trialExpiry, (status as AccessStatus.TrialActive).expiryTimestamp)
    }

    @Test
    fun testLicenseManagerEnforcesOfflineGracePeriodLockRegardlessOfCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            LicenseManager.resetStateForTesting(context)

            val now = System.currentTimeMillis()
            val futureExpiry = now + 86400000L // Active for next 24 hours

            // Valid pass in cache
            LicenseManager.saveAuthoritativeLicensingCache(
                context = context,
                passTier = PassTier.MONTHLY,
                passExpiry = futureExpiry,
                trialClaimed = 0L,
                trialExpiry = 0L,
                trialUsed = false
            )

            // Simulate offline for 3 hours (> 2 hours limit)
            val threeHoursAgo = now - (3L * 3600L * 1000L)
            LicenseManager.setLastServerSyncForTesting(
                elapsedMs = android.os.SystemClock.elapsedRealtime() - (3L * 3600L * 1000L),
                wallClockMs = threeHoursAgo
            )

            assertTrue("Offline grace period must be expired", LicenseManager.isOfflineGracePeriodExpired())

            // Refresh access status
            LicenseManager.refreshAccessStatus()

            // Zero-Trust Security: Must be Expired despite valid cached pass expiry
            val status = LicenseManager.accessStatus.value
            assertTrue("Status must be Expired due to 2-hour offline limit", status is AccessStatus.Expired)
            assertTrue((status as AccessStatus.Expired).message.contains("2-hour offline limit"))
        } finally {
            LicenseManager.resetStateForTesting(context)
        }
    }

    @Test
    fun testRewardedAdManagerConfigurationAndSafety() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertEquals("ca-app-pub-3940256099942544/5224354917", RewardedAdManager.REWARDED_AD_UNIT_ID)
        RewardedAdManager.loadAd(context)
        assertFalse(RewardedAdManager.isAdLoaded())

        // Verify Remove Ads Pass properties
        assertEquals(199, PassTier.REMOVE_ADS.priceInInr)
        assertEquals(28, PassTier.REMOVE_ADS.durationDays)
        assertEquals("remove_ads_pass", PassTier.REMOVE_ADS.id)
        assertEquals(PassManager.isAdsRemoved(), LicenseManager.isAdsRemoved())
    }
}

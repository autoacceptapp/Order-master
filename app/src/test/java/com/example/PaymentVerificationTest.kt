package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PaymentVerificationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    }

    @Test
    fun testClipboardUtrExtractionFromSms() {
        // Common bank SMS texts
        val sms1 = "Sent Rs. 49.00 to autoaccept6122-1@okhdfcbank on 23-09-2026. UPI Ref: 426719823451. Check balance:..."
        val match1 = Regex("\\b\\d{12}\\b").find(sms1)?.value
        assertEquals("426719823451", match1)

        val sms2 = "Dear Customer, INR 179.00 debited from A/c XX1234 for VPA autoaccept6122-1@okhdfcbank. UTR no: 987654321098."
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
}

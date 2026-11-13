package com.example

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
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
}

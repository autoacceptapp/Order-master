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

/**
 * End-to-end verification test suite proving all 4 core Order Master product rules:
 * 1. Free Mode (Expired Trial / Expired Pass / Unpaid): All filters locked by default,
 *    stale Pro/Trial filter values ignored, incoming offers bypassed & auto-accepted in 1-2s.
 * 2. Free Mode with Point-Unlocked Filters: ONLY actively unlocked filters (`expiry > now`)
 *    become editable and enforced; locked filters stay ignored; expired unlocks immediately re-lock.
 * 3. Pro Mode (Active Free Trial OR Paid Daily/Weekly/Monthly Pass): All filters unlocked
 *    in UI and strictly enforced before Accept.
 * 4. Ads Removal strictly requires a separate `remove_ads` pass: Pro Passes and Free Trial
 *    NEVER hide ads; ONLY an active `remove_ads` pass hides ads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BusinessRulesAuditTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GlobalAdminManager.setBanStateForTesting(UserBanState.NotBanned)
        LicenseManager.resetStateForTesting(context)
        AppSettings.init(context)
        AppSettings.resetBusinessStateForTest(context)
    }

    @After
    fun tearDown() {
        GlobalAdminManager.setBanStateForTesting(UserBanState.NotBanned)
        LicenseManager.resetStateForTesting(context)
        AppSettings.resetBusinessStateForTest(context)
    }

    private fun buildOffer(
        fare: Double = 35.0,
        pickupKm: Double = 6.5,
        dropKm: Double = 18.0,
        dropLocation: String = "Airport Terminal 2"
    ): ParsedRideOffer {
        return ParsedRideOffer(
            rawText = "Auto\n₹${fare.toInt()}\n${pickupKm} km Pickup\n${dropKm} km $dropLocation\nAccept",
            totalFare = fare,
            fareBreakdown = listOf(fare),
            pickupDistanceKm = pickupKm,
            dropDistanceKm = dropKm,
            pickupText = "Station Road",
            dropText = dropLocation,
            hasAcceptButton = true,
            targetOffer = TargetRideOffer(
                fare = fare,
                pickupDistance = pickupKm,
                pickupLocation = "Station Road",
                dropDistance = dropKm,
                dropLocation = dropLocation
            )
        )
    }

    // =========================================================================================
    // RULE 1: Free Mode — All Filters Locked by Default, Stale Values Ignored, 1-2s Auto-Accept
    // =========================================================================================

    @Test
    fun rule1_freeModeLocksAllFiltersIgnoresStaleProValuesAndAutoAcceptsWithin1To2Seconds() {
        val now = LicenseManager.getCurrentServerTimeMs()

        // Step A: User previously had an active Monthly Pro Pass and configured strict filters
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = now + 86_400_000L
        )
        assertTrue(LicenseManager.isProMode())

        AppSettings.setAutoAcceptEnabled(context, true)
        AppSettings.setMinFare(context, 300f)
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxFare(context, 400f)
        AppSettings.setMaxFareEnabled(context, true)
        AppSettings.setMaxPickupDistance(context, 1.0f)
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setMaxDropDistance(context, 2.0f)
        AppSettings.setMaxDropDistanceEnabled(context, true)
        AppSettings.setMinPerKmRate(context, 40f)
        AppSettings.setMinPerKmRateEnabled(context, true)
        AppSettings.setBlockedLocations(context, listOf("Airport"))
        AppSettings.setDestinationBlacklistEnabled(context, true)

        // Confirm the bad offer is strictly REJECTED while Pro Pass is active
        val badOffer = buildOffer(
            fare = 35.0, // violates stale minFare=300
            pickupKm = 6.5, // violates stale maxPickup=1.0
            dropKm = 18.0, // violates stale maxDrop=2.0 & minPerKmRate=40
            dropLocation = "Airport Terminal 2" // violates stale blacklist=["Airport"]
        )
        val proEval = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, badOffer)
        assertFalse("Pro Mode must strictly reject offer violating enabled filters", proEval.isAccepted)

        // Step B: Pro Pass expires -> transitions to Free Mode (Trial also used/expired)
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = now - 60_000L,
            trialClaimed = now - 300_000_000L,
            trialExpiry = now - 100_000_000L,
            trialUsed = true
        )
        assertFalse(LicenseManager.isProMode())
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.Expired)

        // Every single filter control must be strictly locked & disabled in Free Mode
        for (filterId in FeatureIds.FILTER_FEATURE_IDS) {
            assertFalse("Filter $filterId must be locked in Free Mode", LicenseManager.isFeatureUnlocked(filterId))
        }
        assertFalse(LicenseManager.hasAnyUnlockedFilter())
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isMaxDropDistanceEnabled(context))
        assertFalse(AppSettings.isMinPerKmRateEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))
        assertFalse(AppSettings.hasActiveEvaluationFilter(context))

        // Attempting to modify locked filters in Free Mode UI must be blocked
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setDestinationBlacklistEnabled(context, true)
        assertFalse(AppSettings.isMinFareEnabled(context))
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))

        // Master Auto-Accept & Standard Auto-Click remain active in Free Mode
        assertTrue(LicenseManager.hasAnyAutoAcceptCapability())
        assertTrue(AppSettings.isAutoAcceptEnabled(context))
        assertTrue(AppSettings.isAutoClickEnabled(context))

        // Step C: Stale Pro filter thresholds MUST be completely bypassed -> offer is ACCEPTED
        val freeEval = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, badOffer)
        assertTrue(
            "Free Mode with 0 unlocked filters must bypass all stale Pro filters and accept incoming offer",
            freeEval.isAccepted
        )

        // Step D: Free Mode auto-accept delay MUST stay strictly within 1–2 seconds (1000..2000ms)
        val delayFromDefault500 = RapidoAccessibilityService.computeEffectiveAcceptDelayMs(
            configuredDelayMs = 500L,
            isProMode = false,
            isAutoClickSpeedUnlocked = false
        )
        val delayFrom1000 = RapidoAccessibilityService.computeEffectiveAcceptDelayMs(
            configuredDelayMs = 1000L,
            isProMode = false,
            isAutoClickSpeedUnlocked = false
        )
        val delayFrom1650 = RapidoAccessibilityService.computeEffectiveAcceptDelayMs(
            configuredDelayMs = 1650L,
            isProMode = false,
            isAutoClickSpeedUnlocked = false
        )
        val delayFrom4500 = RapidoAccessibilityService.computeEffectiveAcceptDelayMs(
            configuredDelayMs = 4500L,
            isProMode = false,
            isAutoClickSpeedUnlocked = false
        )
        assertTrue("Default Free Mode delay ($delayFromDefault500 ms) must be within 1000..2000ms", delayFromDefault500 in 1000L..2000L)
        assertEquals(1000L, delayFrom1000)
        assertEquals(1650L, delayFrom1650)
        assertEquals(2000L, delayFrom4500)

        // Pro Mode still allows ultra-fast sub-second 300ms-500ms acceptance
        val proDelay = RapidoAccessibilityService.computeEffectiveAcceptDelayMs(
            configuredDelayMs = 500L,
            isProMode = true,
            isAutoClickSpeedUnlocked = true
        )
        assertEquals(500L, proDelay)
    }

    @Test
    fun rule1_freshUnpaidFreeModeUserCanToggleMasterAutoAcceptDirectly() {
        LicenseManager.setLicensingForTesting(
            passTier = null,
            passExpiry = 0L,
            trialClaimed = 0L,
            trialExpiry = 0L,
            trialUsed = true
        )
        assertTrue(LicenseManager.accessStatus.value is AccessStatus.Expired)

        // Fresh user toggles Master Auto-Accept ON in Free Mode
        AppSettings.setAutoAcceptEnabled(context, true)
        assertTrue("Master Auto-Accept must turn ON in Free Mode", AppSettings.isAutoAcceptEnabled(context))
        assertTrue("Standard Free Mode Auto-Click must auto-activate with Master Switch", AppSettings.isAutoClickEnabled(context))

        val offer = buildOffer(fare = 42.0, pickupKm = 4.2, dropKm = 9.0, dropLocation = "Suburb")
        val evaluation = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, offer)
        assertTrue("Unfiltered Free Mode must accept valid incoming Rapido offer", evaluation.isAccepted)
    }

    // =========================================================================================
    // RULE 2: Free Mode with Point-Unlocked Filters — Only Active Point-Unlocked Filters Apply
    // =========================================================================================

    @Test
    fun rule2_freeModeEnforcesOnlyActivePointUnlockedFiltersAndReLocksImmediatelyOnExpiry() {
        val now = LicenseManager.getCurrentServerTimeMs()
        val futureExpiry = now + 3_600_000L

        // Start in Free Mode with Master Auto-Accept enabled
        LicenseManager.setLicensingForTesting(
            passTier = null,
            passExpiry = 0L,
            trialClaimed = now - 200_000_000L,
            trialExpiry = now - 50_000_000L,
            trialUsed = true
        )
        AppSettings.setAutoAcceptEnabled(context, true)

        // Unlock ONLY MIN_FARE with Points for 1 hour
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to futureExpiry)
        )
        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.MAX_PICKUP_DISTANCE))
        assertFalse(LicenseManager.isFeatureUnlocked(FeatureIds.DESTINATION_BLACKLIST))

        // User can edit & enable MIN_FARE, while MAX_PICKUP_DISTANCE & BLACKLIST stay locked
        AppSettings.setMinFare(context, 100f)
        AppSettings.setMinFareEnabled(context, true)
        AppSettings.setMaxPickupDistance(context, 1.0f)
        AppSettings.setMaxPickupDistanceEnabled(context, true)
        AppSettings.setBlockedLocations(context, listOf("Airport"))
        AppSettings.setDestinationBlacklistEnabled(context, true)

        assertTrue(AppSettings.isMinFareEnabled(context))
        assertEquals(100f, AppSettings.getMinFare(context), 0.01f)
        assertFalse(AppSettings.isMaxPickupDistanceEnabled(context))
        assertFalse(AppSettings.isDestinationBlacklistEnabled(context))

        // Offer below Min Fare (₹60 < ₹100) MUST be rejected by active point-unlocked MIN_FARE
        val lowFareOffer = buildOffer(fare = 60.0, pickupKm = 0.5, dropKm = 2.0, dropLocation = "City Center")
        val lowFareEval = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, lowFareOffer)
        assertFalse("Active point-unlocked MIN_FARE must reject ₹60 < ₹100", lowFareEval.isAccepted)

        // Offer meeting Min Fare (₹150 >= ₹100) MUST be ACCEPTED even with 9km pickup & "Airport" drop
        // because MAX_PICKUP_DISTANCE and DESTINATION_BLACKLIST remain locked!
        val highFareFarAirportOffer = buildOffer(fare = 150.0, pickupKm = 9.0, dropKm = 20.0, dropLocation = "Airport Terminal 1")
        val highFareEval = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, highFareFarAirportOffer)
        assertTrue("Still-locked pickup & blacklist filters must be ignored when only MIN_FARE is unlocked", highFareEval.isAccepted)

        // Now simulate MIN_FARE point-unlock expiry (expiry <= now)
        val expiredTime = LicenseManager.getCurrentServerTimeMs() - 1_000L
        LicenseManager.setFeatureLocksForTesting(
            unlocked = mapOf(FeatureIds.MIN_FARE to expiredTime)
        )
        LicenseManager.refreshAccessStatus(context)

        // Expired point-unlock MUST immediately revert to locked in UI state and stop filtering in service!
        assertFalse("Expired point-unlocked MIN_FARE must immediately lock", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertFalse("Expired point-unlocked MIN_FARE must immediately disable in AppSettings", AppSettings.isMinFareEnabled(context))
        assertFalse("Expired point-unlocked MIN_FARE must immediately disable in SettingsState", AppSettings.settingsState.value.isMinFareEnabled)

        val lowFareAfterExpiryEval = RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, lowFareOffer)
        assertTrue(
            "Once MIN_FARE point unlock expires, ₹60 offer must no longer be filtered in Free Mode",
            lowFareAfterExpiryEval.isAccepted
        )
    }

    // =========================================================================================
    // RULE 3: Pro Mode (Active Trial OR Daily/Weekly/Monthly Pass) — All Filters Unlocked & Enforced
    // =========================================================================================

    @Test
    fun rule3_activeTrialAndAllPaidProPassesUnlockAndStrictlyEnforceAllSixFilters() {
        val now = LicenseManager.getCurrentServerTimeMs()
        val futureExpiry = now + 86_400_000L

        val proScenarios: List<Pair<String, () -> Unit>> = listOf(
            "Active Free Trial" to {
                LicenseManager.setLicensingForTesting(
                    trialClaimed = now - 10_000L,
                    trialExpiry = futureExpiry,
                    trialUsed = false
                )
            },
            "Paid Daily Pass" to {
                LicenseManager.setLicensingForTesting(passTier = PassTier.DAILY, passExpiry = futureExpiry)
            },
            "Paid Weekly Pass" to {
                LicenseManager.setLicensingForTesting(passTier = PassTier.WEEKLY, passExpiry = futureExpiry)
            },
            "Paid Monthly Pass" to {
                LicenseManager.setLicensingForTesting(passTier = PassTier.MONTHLY, passExpiry = futureExpiry)
            }
        )

        for ((label, activateMode) in proScenarios) {
            LicenseManager.resetStateForTesting(context)
            AppSettings.resetBusinessStateForTest(context)
            activateMode()

            assertTrue("$label must activate Pro Mode", LicenseManager.isProMode())
            assertTrue("$label must grant access", LicenseManager.isAccessGranted())

            // All 6 filters must be unlocked
            for (filterId in FeatureIds.FILTER_FEATURE_IDS) {
                assertTrue("$label must unlock filter $filterId", LicenseManager.isFeatureUnlocked(filterId))
            }

            // Enable and configure all 6 filters
            AppSettings.setAutoAcceptEnabled(context, true)
            AppSettings.setMinFare(context, 80f)
            AppSettings.setMinFareEnabled(context, true)
            AppSettings.setMaxFare(context, 300f)
            AppSettings.setMaxFareEnabled(context, true)
            AppSettings.setMaxPickupDistance(context, 2.0f)
            AppSettings.setMaxPickupDistanceEnabled(context, true)
            AppSettings.setMaxDropDistance(context, 10.0f)
            AppSettings.setMaxDropDistanceEnabled(context, true)
            AppSettings.setMinPerKmRate(context, 15f)
            AppSettings.setMinPerKmRateEnabled(context, true)
            AppSettings.setBlockedLocations(context, listOf("Airport"))
            AppSettings.setDestinationBlacklistEnabled(context, true)

            // 1. Valid offer passing all 6 filters -> ACCEPTED
            val idealOffer = buildOffer(fare = 150.0, pickupKm = 1.5, dropKm = 6.5, dropLocation = "MG Road")
            assertTrue("$label: ideal offer must pass all 6 filters", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, idealOffer).isAccepted)

            // 2. Violates Min Fare (₹60 < ₹80) -> REJECTED
            assertFalse("$label: must enforce Min Fare", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 60.0, pickupKm = 1.0, dropKm = 2.0, dropLocation = "MG Road")).isAccepted)

            // 3. Violates Max Fare (₹350 > ₹300) -> REJECTED
            assertFalse("$label: must enforce Max Fare", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 350.0, pickupKm = 1.0, dropKm = 5.0, dropLocation = "MG Road")).isAccepted)

            // 4. Violates Max Pickup Distance (3.5km > 2.0km) -> REJECTED
            assertFalse("$label: must enforce Max Pickup Distance", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 150.0, pickupKm = 3.5, dropKm = 4.0, dropLocation = "MG Road")).isAccepted)

            // 5. Violates Max Drop Distance (12.0km > 10.0km) -> REJECTED
            assertFalse("$label: must enforce Max Drop Distance", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 250.0, pickupKm = 1.0, dropKm = 12.0, dropLocation = "MG Road")).isAccepted)

            // 6. Violates Min Per-Km Rate (₹100 / (2km + 8km) = ₹10/km < ₹15/km) -> REJECTED
            assertFalse("$label: must enforce Min Per-Km Rate", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 100.0, pickupKm = 2.0, dropKm = 8.0, dropLocation = "MG Road")).isAccepted)

            // 7. Violates Destination Blacklist ("Airport") -> REJECTED
            assertFalse("$label: must enforce Destination Blacklist", RapidoAccessibilityService.evaluateOfferWithEffectiveLicensing(context, buildOffer(fare = 150.0, pickupKm = 1.0, dropKm = 5.0, dropLocation = "Airport Gate 3")).isAccepted)
        }
    }

    // =========================================================================================
    // RULE 4: Ads Removal Strictly Requires Separate `remove_ads` Pass (Pro Pass Does NOT Hide Ads)
    // =========================================================================================

    @Test
    fun rule4_adsRemainEnabledInFreeModeTrialAndAllProPassesUntilRemoveAdsPassIsActive() {
        val now = LicenseManager.getCurrentServerTimeMs()
        val futureExpiry = now + 86_400_000L

        // 1. Free Mode -> Ads ENABLED
        LicenseManager.setLicensingForTesting()
        assertFalse(LicenseManager.isAdsRemoved())
        assertTrue("Free Mode must show ads", LicenseManager.shouldShowAds())
        assertTrue("Free Mode must show banner ads", LicenseManager.shouldShowBannerAds())

        // 2. Active Free Trial -> Ads STILL ENABLED
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            trialClaimed = now - 5_000L,
            trialExpiry = futureExpiry,
            trialUsed = false
        )
        assertTrue(LicenseManager.isTrialActive)
        assertTrue(LicenseManager.isProMode())
        assertFalse("Free Trial must NOT remove ads", LicenseManager.isAdsRemoved())
        assertTrue("Free Trial must keep ads enabled", LicenseManager.shouldShowAds())
        assertTrue("Free Trial must keep banner ads enabled", LicenseManager.shouldShowBannerAds())

        // 3. Active Paid Pro Passes (Daily, Weekly, Monthly) -> Ads STILL ENABLED
        for (tier in listOf(PassTier.DAILY, PassTier.WEEKLY, PassTier.MONTHLY)) {
            LicenseManager.resetStateForTesting(context)
            LicenseManager.setLicensingForTesting(passTier = tier, passExpiry = futureExpiry)
            assertTrue(LicenseManager.isPassActive)
            assertTrue(LicenseManager.isProMode())
            assertFalse("${tier.title} must NOT remove ads", LicenseManager.isAdsRemoved())
            assertTrue("${tier.title} must keep ads enabled", LicenseManager.shouldShowAds())
            assertTrue("${tier.title} must keep banner ads enabled", LicenseManager.shouldShowBannerAds())
        }

        // 4. Separate `remove_ads` Pass in Free Mode -> Ads REMOVED, Pro filters STILL LOCKED
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(removeAdsExpiry = futureExpiry)
        assertFalse(LicenseManager.isProMode())
        assertTrue(LicenseManager.isAdsRemoved())
        assertFalse("Active remove_ads pass must hide ads", LicenseManager.shouldShowAds())
        assertFalse("Active remove_ads pass must hide banner ads", LicenseManager.shouldShowBannerAds())
        assertFalse("remove_ads pass must NOT unlock filters", LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))

        // 5. Combined Monthly Pro Pass + active `remove_ads` Pass -> Pro filters UNLOCKED AND Ads REMOVED
        LicenseManager.resetStateForTesting(context)
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry,
            removeAdsExpiry = futureExpiry
        )
        assertTrue(LicenseManager.isProMode())
        assertTrue(LicenseManager.isFeatureUnlocked(FeatureIds.MIN_FARE))
        assertTrue(LicenseManager.isAdsRemoved())
        assertFalse("Monthly Pro + active remove_ads pass must hide ads", LicenseManager.shouldShowAds())
        assertFalse("Monthly Pro + active remove_ads pass must hide banner ads", LicenseManager.shouldShowBannerAds())

        // 6. When `remove_ads` Pass expires while Monthly Pro Pass is STILL active -> Ads RE-APPEAR immediately!
        LicenseManager.setLicensingForTesting(
            passTier = PassTier.MONTHLY,
            passExpiry = futureExpiry,
            removeAdsExpiry = now - 5_000L
        )
        assertTrue("Monthly Pro Pass should still be active", LicenseManager.isProMode())
        assertFalse("Expired remove_ads pass must revert isAdsRemoved to false", LicenseManager.isAdsRemoved())
        assertTrue("Expired remove_ads pass must re-enable ads even while Monthly Pro Pass is active", LicenseManager.shouldShowAds())
        assertTrue("Expired remove_ads pass must re-enable banner ads even while Monthly Pro Pass is active", LicenseManager.shouldShowBannerAds())
    }
}

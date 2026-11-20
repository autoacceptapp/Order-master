package com.example

import com.example.data.repository.AppConfig
import com.example.data.repository.AppConfigRepository
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppConfigTest {

    @After
    fun tearDown() {
        AppConfigRepository.paymentConfigTestListenerFactory = null
        AppConfigRepository.applyPaymentConfigFromMap(null)
        AppConfigRepository.applyAppConfigFromMap(null)
    }

    @Test
    fun testAppConfigDefaultValues() {
        val defaultConfig = AppConfig()
        assertEquals(1, defaultConfig.minSupportedVersionCode)
        assertEquals(1, defaultConfig.latestVersionCode)
        assertEquals("", defaultConfig.dynamicAppDownloadUrl)
    }

    @Test
    fun testAppConfigCustomValues() {
        val config = AppConfig(
            minSupportedVersionCode = 20,
            latestVersionCode = 25,
            dynamicAppDownloadUrl = "https://ordermaster.app/download/v25.apk"
        )
        assertEquals(20, config.minSupportedVersionCode)
        assertEquals(25, config.latestVersionCode)
        assertEquals("https://ordermaster.app/download/v25.apk", config.dynamicAppDownloadUrl)
    }

    @Test
    fun testForceUpdateEvaluationRule() {
        // App version code is 10
        val currentInstalledVersionCode = 10

        // Case 1: Min supported is 5 -> Force update NOT required
        val configSupported = AppConfig(minSupportedVersionCode = 5, latestVersionCode = 12)
        assertFalse(currentInstalledVersionCode < configSupported.minSupportedVersionCode)

        // Case 2: Min supported is 10 -> Force update NOT required (exactly on supported version)
        val configExact = AppConfig(minSupportedVersionCode = 10, latestVersionCode = 12)
        assertFalse(currentInstalledVersionCode < configExact.minSupportedVersionCode)

        // Case 3: Min supported is 15 -> Force update IS required (client version is too old)
        val configDeprecated = AppConfig(minSupportedVersionCode = 15, latestVersionCode = 20)
        assertTrue(currentInstalledVersionCode < configDeprecated.minSupportedVersionCode)
    }

    @Test
    fun testAppConfigRepositoryConstants() {
        assertEquals("admin_settings", AppConfigRepository.COLL_ADMIN_SETTINGS)
        assertEquals("app_config", AppConfigRepository.DOC_APP_CONFIG)
        assertEquals("payment_config", AppConfigRepository.DOC_PAYMENT_CONFIG)
    }

    @Test
    fun testObserveAppConfigWithoutFirebaseDoesNotThrow() = kotlinx.coroutines.test.runTest {
        val flow = AppConfigRepository.observeAppConfig()
        val firstItem = flow.first()
        assertEquals(1, firstItem.minSupportedVersionCode)
        assertEquals(1, firstItem.latestVersionCode)
        assertEquals("", firstItem.dynamicAppDownloadUrl)
    }

    @Test
    fun testPaymentConfigDefaultsAndNullSnapshotFallback() = kotlinx.coroutines.test.runTest {
        val appliedNull = AppConfigRepository.applyPaymentConfigFromSnapshot(null)
        assertEquals(PassPricingConfig.DAILY_PRICE_INR, appliedNull.dailyPriceInr)
        assertEquals(PassPricingConfig.WEEKLY_PRICE_INR, appliedNull.weeklyPriceInr)
        assertEquals(PassPricingConfig.MONTHLY_PRICE_INR, appliedNull.monthlyPriceInr)
        assertEquals(PassPricingConfig.REMOVE_ADS_PRICE_INR, appliedNull.removeAdsPriceInr)
        assertEquals(PassPricingConfig.DAILY_POINTS, appliedNull.dailyPoints)
        assertEquals(listOf(9, 49, 179, 199), appliedNull.validPassAmountsInr)

        val paymentItem = AppConfigRepository.observePaymentConfig().first()
        assertEquals(PassPricingConfig.DAILY_PRICE_INR, paymentItem.dailyPriceInr)
        assertEquals(PassPricingConfig.REMOVE_ADS_PRICE_INR, paymentItem.removeAdsPriceInr)
    }

    @Test
    fun testCanonicalPaymentConfigSchemaParsingAndPropagation() {
        val canonicalDoc: Map<String, Any?> = mapOf(
            "adminUpiId" to "admin@upi",
            "adminPayeeName" to "Order Master Admin",
            "dailyPoints" to 120L,
            "weeklyPoints" to 600L,
            "monthlyPoints" to 1800L,
            "removeAdsPoints" to 2000L,
            "dailyPriceInr" to 15L,
            "weeklyPriceInr" to 59L,
            "monthlyPriceInr" to 199L,
            "removeAdsPriceInr" to 249L,
            "validPassAmountsInr" to listOf(15L, 59L, 199L, 249L),
            "featureHourlyRates" to mapOf(
                FeatureIds.MIN_FARE to 18L,
                FeatureIds.AUTO_CLICK to 25L,
                FeatureIds.DESTINATION_BLACKLIST to 8
            )
        )

        val applied = AppConfigRepository.applyPaymentConfigFromMap(canonicalDoc)

        // Verify parsed PaymentConfig state
        assertEquals("admin@upi", applied.adminUpiId)
        assertEquals("Order Master Admin", applied.adminPayeeName)
        assertEquals(120, applied.dailyPoints)
        assertEquals(600, applied.weeklyPoints)
        assertEquals(1800, applied.monthlyPoints)
        assertEquals(2000, applied.removeAdsPoints)
        assertEquals(15, applied.dailyPriceInr)
        assertEquals(59, applied.weeklyPriceInr)
        assertEquals(199, applied.monthlyPriceInr)
        assertEquals(249, applied.removeAdsPriceInr)
        assertEquals(listOf(15, 59, 199, 249), applied.validPassAmountsInr)
        assertEquals(18, applied.featureHourlyRates[FeatureIds.MIN_FARE])
        assertEquals(25, applied.featureHourlyRates[FeatureIds.AUTO_CLICK])
        assertEquals(8, applied.featureHourlyRates[FeatureIds.DESTINATION_BLACKLIST])

        // Verify live StateFlow & PassTier / PassPricingConfig / FeatureIds propagation
        assertEquals(applied, AppConfigRepository.currentPaymentConfig.value)
        assertEquals(120, PassTier.DAILY.effectivePointsCost())
        assertEquals(600, PassTier.WEEKLY.effectivePointsCost())
        assertEquals(1800, PassTier.MONTHLY.effectivePointsCost())
        assertEquals(2000, PassTier.REMOVE_ADS.effectivePointsCost())

        assertEquals(15, PassTier.DAILY.effectivePriceInInr())
        assertEquals(59, PassTier.WEEKLY.effectivePriceInInr())
        assertEquals(199, PassTier.MONTHLY.effectivePriceInInr())
        assertEquals(249, PassTier.REMOVE_ADS.effectivePriceInInr())

        assertEquals(PassTier.DAILY, PassPricingConfig.mapAmountToPassTier(15L))
        assertEquals(PassTier.WEEKLY, PassPricingConfig.mapAmountToPassTier(59L))
        assertEquals(PassTier.MONTHLY, PassPricingConfig.mapAmountToPassTier(199L))
        assertEquals(PassTier.REMOVE_ADS, PassPricingConfig.mapAmountToPassTier(249L))
        assertNull(PassPricingConfig.mapAmountToPassTier(49L))
        assertTrue(PassPricingConfig.isExactPassAmount(249L))
        assertFalse(PassPricingConfig.isExactPassAmount(49L))
        assertEquals(listOf(15, 59, 199, 249), PassPricingConfig.effectiveValidPassAmountsInr)

        assertEquals(18, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
        assertEquals(25, FeatureIds.getEffectivePointsPerHour(FeatureIds.AUTO_CLICK))
        assertEquals(8, FeatureIds.getEffectivePointsPerHour(FeatureIds.DESTINATION_BLACKLIST))
        // Unspecified feature falls back cleanly to its default rate
        assertEquals(10, FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_FARE))
    }

    @Test
    fun testLegacyTopLevelAndFeatureRatesFallbackWhenCanonicalMissing() {
        val legacyDoc: Map<String, Any?> = mapOf(
            "dailyPointsCost" to 110L,
            "weeklyPointsCost" to 520L,
            "monthlyPointsCost" to 1600L,
            "removeAdsPointsCost" to 1750L,
            "passPricesInr" to mapOf(
                "daily" to 11L,
                "weekly_pass" to 55L,
                "monthly" to 189L,
                "remove_ads_pass" to 219L
            ),
            "featureRates" to mapOf(
                FeatureIds.MIN_FARE to 14L,
                FeatureIds.TTS_VOICE to 12L
            )
        )

        val applied = AppConfigRepository.applyPaymentConfigFromMap(legacyDoc)

        assertEquals(110, applied.dailyPoints)
        assertEquals(520, applied.weeklyPoints)
        assertEquals(1600, applied.monthlyPoints)
        assertEquals(1750, applied.removeAdsPoints)

        assertEquals(11, applied.dailyPriceInr)
        assertEquals(55, applied.weeklyPriceInr)
        assertEquals(189, applied.monthlyPriceInr)
        assertEquals(219, applied.removeAdsPriceInr)
        assertEquals(listOf(11, 55, 189, 219), applied.validPassAmountsInr)

        assertEquals(14, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
        assertEquals(12, FeatureIds.getEffectivePointsPerHour(FeatureIds.TTS_VOICE))
        assertEquals(110, PassTier.DAILY.effectivePointsCost())
        assertEquals(219, PassTier.REMOVE_ADS.effectivePriceInInr())
    }

    @Test
    fun testLegacyMapFallbacksPassPointsCostPriceInInrAndFeaturePrices() {
        val legacyMapOnlyDoc: Map<String, Any?> = mapOf(
            "passPointsCost" to mapOf(
                "daily" to 130L,
                "weekly_pass" to 540L,
                "monthly_pass" to 1650L,
                "remove_ads" to 1850L
            ),
            "priceInInr" to mapOf(
                "daily_pass" to 12L,
                "weekly" to 52L,
                "monthly_pass" to 185L,
                "remove_ads" to 209L
            ),
            "featurePricesPtsPerHr" to mapOf(
                FeatureIds.MAX_FARE to 16L
            ),
            "featurePrices" to mapOf(
                FeatureIds.MAX_FARE to 99L,
                FeatureIds.FLOATING_OVERLAY to 13L
            )
        )

        val applied = AppConfigRepository.applyPaymentConfigFromMap(legacyMapOnlyDoc)

        assertEquals(130, applied.dailyPoints)
        assertEquals(540, applied.weeklyPoints)
        assertEquals(1650, applied.monthlyPoints)
        assertEquals(1850, applied.removeAdsPoints)

        assertEquals(12, applied.dailyPriceInr)
        assertEquals(52, applied.weeklyPriceInr)
        assertEquals(185, applied.monthlyPriceInr)
        assertEquals(209, applied.removeAdsPriceInr)

        // featurePricesPtsPerHr takes precedence over featurePrices
        assertEquals(16, FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_FARE))
        // featurePrices still works when higher-precedence maps do not define the feature
        assertEquals(13, FeatureIds.getEffectivePointsPerHour(FeatureIds.FLOATING_OVERLAY))
    }

    @Test
    fun testCanonicalKeysTakePrecedenceOverLegacyKeysWhenBothPresent() {
        val mixedDoc: Map<String, Any?> = mapOf(
            // Canonical keys (MUST WIN)
            "dailyPoints" to 140L,
            "weeklyPoints" to 640L,
            "monthlyPoints" to 1900L,
            "removeAdsPoints" to 2100L,
            "dailyPriceInr" to 19L,
            "weeklyPriceInr" to 69L,
            "monthlyPriceInr" to 209L,
            "removeAdsPriceInr" to 259L,
            "featureHourlyRates" to mapOf(
                FeatureIds.MIN_FARE to 22L,
                FeatureIds.AUTO_CLICK to 30L
            ),
            // Legacy keys (MUST BE OVERRIDDEN BY CANONICAL)
            "dailyPointsCost" to 999L,
            "weeklyPointsCost" to 999L,
            "monthlyPointsCost" to 999L,
            "removeAdsPointsCost" to 999L,
            "passPointsCost" to mapOf(
                "daily" to 888L,
                "weekly" to 888L,
                "monthly" to 888L,
                "remove_ads" to 888L
            ),
            "passPricesInr" to mapOf(
                "daily" to 777L,
                "weekly" to 777L,
                "monthly" to 777L,
                "remove_ads" to 777L
            ),
            "priceInInr" to mapOf(
                "daily" to 666L,
                "weekly" to 666L,
                "monthly" to 666L,
                "remove_ads" to 666L
            ),
            "featureRates" to mapOf(
                FeatureIds.MIN_FARE to 99L,
                FeatureIds.MAX_PICKUP_DISTANCE to 17L
            ),
            "featurePricesPtsPerHr" to mapOf(
                FeatureIds.AUTO_CLICK to 88L
            ),
            "featurePrices" to mapOf(
                FeatureIds.MIN_FARE to 77L
            )
        )

        val applied = AppConfigRepository.applyPaymentConfigFromMap(mixedDoc)

        assertEquals(140, applied.dailyPoints)
        assertEquals(640, applied.weeklyPoints)
        assertEquals(1900, applied.monthlyPoints)
        assertEquals(2100, applied.removeAdsPoints)

        assertEquals(19, applied.dailyPriceInr)
        assertEquals(69, applied.weeklyPriceInr)
        assertEquals(209, applied.monthlyPriceInr)
        assertEquals(259, applied.removeAdsPriceInr)

        assertEquals(22, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
        assertEquals(30, FeatureIds.getEffectivePointsPerHour(FeatureIds.AUTO_CLICK))
        // Non-overlapping legacy feature key remains available during transition
        assertEquals(17, FeatureIds.getEffectivePointsPerHour(FeatureIds.MAX_PICKUP_DISTANCE))
    }

    @Test
    fun testInvalidOrNonPositiveValuesFallBackSafely() {
        val invalidDoc: Map<String, Any?> = mapOf(
            "dailyPoints" to 0L,
            "weeklyPoints" to -50L,
            "dailyPriceInr" to -9L,
            "weeklyPriceInr" to "not_a_number",
            "validPassAmountsInr" to listOf(-1L, 0L),
            "featureHourlyRates" to mapOf(
                FeatureIds.MIN_FARE to 0L,
                FeatureIds.AUTO_CLICK to -5L
            )
        )

        val applied = AppConfigRepository.applyPaymentConfigFromMap(invalidDoc)

        assertEquals(PassPricingConfig.DAILY_POINTS, applied.dailyPoints)
        assertEquals(PassPricingConfig.WEEKLY_POINTS, applied.weeklyPoints)
        assertEquals(PassPricingConfig.DAILY_PRICE_INR, applied.dailyPriceInr)
        assertEquals(PassPricingConfig.WEEKLY_PRICE_INR, applied.weeklyPriceInr)
        assertEquals(listOf(9, 49, 179, 199), applied.validPassAmountsInr)
        assertEquals(10, FeatureIds.getEffectivePointsPerHour(FeatureIds.MIN_FARE))
        assertEquals(15, FeatureIds.getEffectivePointsPerHour(FeatureIds.AUTO_CLICK))
    }

    @Test
    fun testAdminSettingsAuthGatingMatchesUnifiedFirestoreRules() {
        // Step 6A: Public pre-login get is allowed for BOTH app_config and payment_config
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_APP_CONFIG, null))
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_APP_CONFIG, ""))
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_PAYMENT_CONFIG, null))
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_PAYMENT_CONFIG, "  "))

        // security_config, server_clock, and all other admin_settings strictly require authentication
        assertFalse(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SECURITY_CONFIG, null))
        assertFalse(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SECURITY_CONFIG, ""))
        assertFalse(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SERVER_CLOCK, null))
        assertFalse(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SERVER_CLOCK, "  "))
        assertFalse(AppConfigRepository.canReadAdminSetting("other_admin_setting", null))

        // Authenticated user (non-blank UID) can read all admin_settings docs
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_PAYMENT_CONFIG, "firebase_uid_123"))
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SECURITY_CONFIG, "firebase_uid_123"))
        assertTrue(AppConfigRepository.canReadAdminSetting(AppConfigRepository.DOC_SERVER_CLOCK, "firebase_uid_123"))
    }

    @Test
    fun testObservePaymentConfigStartsListeningImmediatelyPreLoginWithoutAuth() = kotlinx.coroutines.test.runTest {
        var listenerAttached = false
        var listenerRemoved = false

        // Simulate unauthenticated startup (no FirebaseApp / FirebaseAuth.currentUser == null)
        AppConfigRepository.paymentConfigTestListenerFactory = { onSnapshotData ->
            listenerAttached = true
            onSnapshotData(
                mapOf(
                    "adminUpiId" to "live@upi",
                    "adminPayeeName" to "Order Master Live",
                    "dailyPriceInr" to 15L,
                    "weeklyPriceInr" to 59L,
                    "monthlyPriceInr" to 199L,
                    "removeAdsPriceInr" to 249L,
                    "dailyPoints" to 120L,
                    "weeklyPoints" to 600L,
                    "monthlyPoints" to 1800L,
                    "removeAdsPoints" to 2000L
                )
            )
            com.google.firebase.firestore.ListenerRegistration {
                listenerRemoved = true
            }
        }

        val liveConfig = AppConfigRepository.observePaymentConfig().first { it.dailyPriceInr == 15 }

        assertTrue("observePaymentConfig() must attach listener immediately pre-login without requiring FirebaseAuth.currentUser", listenerAttached)
        assertTrue("Listener must detach cleanly when Flow collection completes", listenerRemoved)
        assertEquals("live@upi", liveConfig.adminUpiId)
        assertEquals("Order Master Live", liveConfig.adminPayeeName)
        assertEquals(15, liveConfig.dailyPriceInr)
        assertEquals(59, liveConfig.weeklyPriceInr)
        assertEquals(199, liveConfig.monthlyPriceInr)
        assertEquals(249, liveConfig.removeAdsPriceInr)
        // Live prices are propagated immediately for the login/store preview screen
        assertEquals(15, PassTier.DAILY.effectivePriceInInr())
        assertEquals(59, PassTier.WEEKLY.effectivePriceInInr())
        assertEquals(199, PassTier.MONTHLY.effectivePriceInInr())
        assertEquals(249, PassTier.REMOVE_ADS.effectivePriceInInr())
    }

    @Test
    fun testAppConfigRemoteFieldSettingsDefaultsOverridesAndClamping() {
        // Default values
        val defaults = AppConfigRepository.applyAppConfigFromMap(null)
        assertEquals(24, defaults.paidPassOfflineGraceHours)
        assertEquals(24, AppConfigRepository.effectivePaidPassOfflineGraceHours())
        assertEquals("com.rapido.rider", defaults.rapidoTargetPackage)
        assertEquals("com.rapido.rider", AppConfigRepository.effectiveRapidoTargetPackage())
        assertEquals(AppConfigRepository.DEFAULT_RAPIDO_ACCEPT_KEYWORDS, defaults.rapidoAcceptButtonKeywords)
        assertFalse(defaults.rapidoAutoExpandMoreOrders)
        assertFalse(AppConfigRepository.isRapidoAutoExpandMoreOrdersEnabled())

        // Valid remote overrides (List accept keywords + custom package + 48h grace + auto-expand true)
        val overridden = AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "minSupportedVersionCode" to 3L,
                "latestVersionCode" to 5L,
                "dynamicAppDownloadUrl" to "https://example.com/app.apk",
                "paidPassOfflineGraceHours" to 48L,
                "rapidoTargetPackage" to "com.rapido.captain.beta",
                "rapidoAcceptButtonKeywords" to listOf("Accept Now", "Grab Ride", "Accept Now"),
                "rapidoAutoExpandMoreOrders" to true
            )
        )
        assertEquals(48, overridden.paidPassOfflineGraceHours)
        assertEquals(48, AppConfigRepository.effectivePaidPassOfflineGraceHours())
        assertEquals("com.rapido.captain.beta", overridden.rapidoTargetPackage)
        assertEquals("com.rapido.captain.beta", AppConfigRepository.effectiveRapidoTargetPackage())
        assertEquals(listOf("Accept Now", "Grab Ride"), overridden.rapidoAcceptButtonKeywords)
        assertTrue(overridden.rapidoAutoExpandMoreOrders)
        assertTrue(AppConfigRepository.isRapidoAutoExpandMoreOrdersEnabled())

        // Lower clamp (0 / 1 -> 2) & comma-separated String keywords & invalid package fallback
        val clampedLow = AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "paidPassOfflineGraceHours" to 1L,
                "rapidoTargetPackage" to "invalid_no_dot_pkg",
                "rapidoAcceptButtonKeywords" to "Tap Accept, Take Order, स्वीकार",
                "rapidoAutoExpandMoreOrders" to "false"
            )
        )
        assertEquals(2, clampedLow.paidPassOfflineGraceHours)
        assertEquals("com.rapido.rider", clampedLow.rapidoTargetPackage)
        assertEquals(listOf("Tap Accept", "Take Order", "स्वीकार"), clampedLow.rapidoAcceptButtonKeywords)
        assertFalse(clampedLow.rapidoAutoExpandMoreOrders)

        // Upper clamp (120 -> 72) & empty keywords fallback to defaults
        val clampedHigh = AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "paidPassOfflineGraceHours" to 120L,
                "rapidoAcceptButtonKeywords" to emptyList<String>()
            )
        )
        assertEquals(72, clampedHigh.paidPassOfflineGraceHours)
        assertEquals(AppConfigRepository.DEFAULT_RAPIDO_ACCEPT_KEYWORDS, clampedHigh.rapidoAcceptButtonKeywords)
    }

    @Test
    fun testAppConfigRemoteRegexCompilationAndMalformedFallback() {
        // Valid remote regexes compile cleanly
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoFareRegex" to """(?:₹|INR)\s*(\d+(?:\.\d{1,2})?)""",
                "rapidoDistanceRegex" to """(\d+(?:\.\d{1,2})?)\s*(km|m)"""
            )
        )
        val fareRegex = AppConfigRepository.effectiveRapidoFareRegex()
        val distRegex = AppConfigRepository.effectiveRapidoDistanceRegex()
        assertTrue("Valid remote fare regex should compile", fareRegex != null)
        assertTrue("Valid remote distance regex should compile", distRegex != null)
        assertEquals("75", fareRegex!!.find("Fare INR 75")?.groupValues?.get(1))
        assertEquals("2.4", distRegex!!.find("Drop 2.4 km")?.groupValues?.get(1))

        // Malformed regex patterns must NOT crash and must return null for safe built-in fallback
        AppConfigRepository.applyAppConfigFromMap(
            mapOf(
                "rapidoFareRegex" to "[unclosed_group(",
                "rapidoDistanceRegex" to "(?invalid_regex"
            )
        )
        assertNull("Malformed remote fare regex must fall back to null", AppConfigRepository.effectiveRapidoFareRegex())
        assertNull("Malformed remote distance regex must fall back to null", AppConfigRepository.effectiveRapidoDistanceRegex())
    }
}




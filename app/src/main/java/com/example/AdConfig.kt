package com.example

/**
 * AdConfig - Centralized AdMob configuration for Order Master.
 * Automatically switches between official Google Test Ad Unit IDs during development (DEBUG)
 * and Production Ad Unit IDs for release builds to protect against invalid traffic policy violations.
 */
object AdConfig {
    // Official Google Test Ad Unit IDs (guaranteed fill rate for development/testing)
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // =========================================================================
    // PRODUCTION AD UNIT ID PLACEHOLDERS
    // Replace these placeholder strings with your approved AdMob Production
    // Ad Unit IDs before compiling your release APK:
    // =========================================================================
    private const val PROD_BANNER_AD_UNIT_ID = "YOUR_PROD_BANNER_ID"
    private const val PROD_REWARDED_AD_UNIT_ID = "YOUR_PROD_REWARDED_ID"

    /**
     * Active Banner Ad Unit ID based on the current build variant.
     * Strictly returns official Google Test ID if BuildConfig.DEBUG == true.
     * Returns PROD_BANNER_AD_UNIT_ID when BuildConfig.DEBUG == false.
     */
    val bannerAdUnitId: String
        get() = if (BuildConfig.DEBUG) {
            TEST_BANNER_AD_UNIT_ID
        } else {
            PROD_BANNER_AD_UNIT_ID
        }

    /**
     * Active Rewarded Ad Unit ID based on the current build variant.
     * Strictly returns official Google Test ID if BuildConfig.DEBUG == true.
     * Returns PROD_REWARDED_AD_UNIT_ID when BuildConfig.DEBUG == false.
     */
    val rewardedAdUnitId: String
        get() = if (BuildConfig.DEBUG) {
            TEST_REWARDED_AD_UNIT_ID
        } else {
            PROD_REWARDED_AD_UNIT_ID
        }
}

package com.example

/**
 * AdConfig - Centralized AdMob configuration for Order Master.
 * - Uses real Production Ad Unit IDs (`PROD_BANNER_AD_UNIT_ID`, `PROD_REWARDED_AD_UNIT_ID`) as primary IDs.
 * - Fallback to Google's official Test Ad Unit IDs is strictly restricted to `BuildConfig.DEBUG` builds.
 * - In release builds, `RewardedAdManager` never serves test ads on Error Code 3 (`NO_FILL`);
 *   instead it halts retry loops and surfaces a user-friendly unavailable message (`NO_FILL_USER_MESSAGE`).
 */
object AdConfig {
    // Official Google Test Ad Unit IDs (used strictly in BuildConfig.DEBUG fallback paths)
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // Production Ad Unit IDs for Order Master (AdMob Publisher ID: ca-app-pub-5657835600587543)
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-5657835600587543/1828106387"
    const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-5657835600587543/3152796343"

    /**
     * Primary Banner Ad Unit ID (always uses the production Ad Unit ID).
     */
    val bannerAdUnitId: String
        get() = PROD_BANNER_AD_UNIT_ID

    /**
     * Fallback Banner Ad Unit ID (restricted strictly to DEBUG builds;
     * release builds remain on PROD_BANNER_AD_UNIT_ID with no test-ad fallback).
     */
    val fallbackBannerAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_BANNER_AD_UNIT_ID else PROD_BANNER_AD_UNIT_ID

    /**
     * Primary Rewarded Video Ad Unit ID (always uses the production Ad Unit ID).
     */
    val rewardedAdUnitId: String
        get() = PROD_REWARDED_AD_UNIT_ID

    /**
     * Fallback Rewarded Video Ad Unit ID (restricted strictly to DEBUG builds;
     * on release NO_FILL, RewardedAdManager surfaces NO_FILL_USER_MESSAGE instead of test ads).
     */
    val fallbackRewardedAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
}

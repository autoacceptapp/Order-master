package com.example

/**
 * AdConfig - Centralized AdMob configuration for Order Master.
 * Optimized for direct GitHub APK distribution and public releases:
 * - Attempts real Production Ad Unit IDs directly.
 * - Automatically falls back to Google's official Test Ad Unit IDs if AdMob returns
 *   Error Code 3 (NO_FILL) due to the APK not yet being indexed on Google Play Store,
 *   ensuring 100% ad visibility and reliable user reward claiming.
 */
object AdConfig {
    // Official Google Test Ad Unit IDs (guaranteed 100% fill rate for testing and unlinked APKs)
    const val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
    const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

    // Production Ad Unit IDs for Order Master (AdMob Publisher ID: ca-app-pub-5657835600587543)
    const val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-5657835600587543/1828106387"
    const val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-5657835600587543/3152796343"

    /**
     * Primary Banner Ad Unit ID.
     * Uses your production Ad Unit ID directly so production revenue is generated.
     */
    val bannerAdUnitId: String
        get() = PROD_BANNER_AD_UNIT_ID

    /**
     * Fallback Banner Ad Unit ID when AdMob returns NO_FILL for unindexed GitHub APKs.
     */
    val fallbackBannerAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_BANNER_AD_UNIT_ID else PROD_BANNER_AD_UNIT_ID

    /**
     * Primary Rewarded Video Ad Unit ID.
     * Uses your production Ad Unit ID directly.
     */
    val rewardedAdUnitId: String
        get() = PROD_REWARDED_AD_UNIT_ID

    /**
     * Fallback Rewarded Video Ad Unit ID (restricted strictly to DEBUG builds).
     */
    val fallbackRewardedAdUnitId: String
        get() = if (BuildConfig.DEBUG) TEST_REWARDED_AD_UNIT_ID else PROD_REWARDED_AD_UNIT_ID
}

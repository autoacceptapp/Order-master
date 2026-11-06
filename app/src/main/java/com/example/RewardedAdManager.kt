package com.example

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * RewardedAdManager - Singleton manager for loading and showing AdMob Rewarded Video Ads.
 * Strictly uses Google's official Test Ad Unit ID during development for guaranteed 100% fill rate.
 * Rewards users with Points via the Zero-Trust Action Queue.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"

    // Google's official Test Ad Unit ID for Rewarded Video Ads
    const val TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"
    const val REWARDED_AD_UNIT_ID = TEST_AD_UNIT_ID

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var isLoading = false

    /**
     * Checks if a rewarded ad is currently cached and ready to be shown.
     */
    fun isAdLoaded(): Boolean = rewardedAd != null

    /**
     * Checks if an ad request is currently in progress.
     */
    fun isLoading(): Boolean = isLoading

    /**
     * Pre-caches a Rewarded Ad using the AdMob SDK on background worker threads.
     * Safe to call multiple times; will not re-trigger if an ad is already loaded or in-flight.
     */
    fun loadAd(context: Context) {
        if (isLoading || rewardedAd != null) {
            Log.d(TAG, "RewardedAd already loaded or loading in progress.")
            return
        }

        if (isUnitTestEnvironment()) {
            Log.d(TAG, "Skipping RewardedAd.load in unit test environment.")
            return
        }

        isLoading = true
        try {
            val adRequest = AdRequest.Builder().build()
            Log.d(TAG, "Requesting RewardedAd with official Google Test Unit ID: $REWARDED_AD_UNIT_ID")

            RewardedAd.load(
                context.applicationContext,
                REWARDED_AD_UNIT_ID,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        isLoading = false
                        Log.i(TAG, "RewardedAd loaded successfully with adUnitId: $REWARDED_AD_UNIT_ID")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        rewardedAd = null
                        isLoading = false
                        Log.e(
                            TAG,
                            "Ad Failed to load. Error Code: ${adError.code}, Message: ${adError.message}, Domain: ${adError.domain}"
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            isLoading = false
            Log.e(TAG, "Error initiating RewardedAd load for adUnitId $REWARDED_AD_UNIT_ID: ${t.message}", t)
        }
    }

    /**
     * Shows the loaded Rewarded Ad in the provided [activity].
     * If the ad is not yet loaded, informs the user with a Toast and triggers a background load.
     * Invokes [onRewardEarned] when user completes watching and earns reward.
     * Automatically requests pre-caching of the next ad immediately after reward or dismissal.
     */
    fun showAd(activity: Activity, onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            Log.w(TAG, "Rewarded ad is not ready yet. Showing toast and triggering background load.")
            Toast.makeText(activity, "Ad is still loading, please try again", Toast.LENGTH_SHORT).show()
            loadAd(activity)
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Rewarded ad dismissed by user.")
                rewardedAd = null
                loadAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(
                    TAG,
                    "Rewarded ad failed to show: code=${adError.code}, message='${adError.message}', domain='${adError.domain}'"
                )
                rewardedAd = null
                loadAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Rewarded ad showing fullscreen.")
            }
        }

        try {
            ad.show(activity, OnUserEarnedRewardListener { rewardItem ->
                Log.i(TAG, "User earned reward: amount=${rewardItem.amount}, type=${rewardItem.type}")
                onRewardEarned()
                rewardedAd = null
                loadAd(activity)
            })
        } catch (t: Throwable) {
            Log.e(TAG, "Exception showing RewardedAd: ${t.message}", t)
            rewardedAd = null
            loadAd(activity)
        }
    }

    private fun isUnitTestEnvironment(): Boolean {
        return Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true ||
            runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
    }

    /**
     * Testing hook to set/clear cached ad state in unit tests without invoking native SDK.
     */
    internal fun setMockLoadedForTesting(isLoaded: Boolean) {
        if (!isLoaded) {
            rewardedAd = null
            isLoading = false
        }
    }
}

package com.example

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.OnUserEarnedRewardListener
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback

/**
 * RewardedAdManager - Singleton manager for loading and showing AdMob Rewarded Video Ads.
 * Rewards users with Points via the Zero-Trust Action Queue.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"
    const val REWARDED_AD_UNIT_ID = "ca-app-pub-5657835600587543/4664462217"

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
     * Pre-caches a Rewarded Ad using the AdMob SDK.
     * Safe to call multiple times; will not re-trigger if an ad is already loaded or in-flight.
     */
    fun loadAd(context: Context) {
        if (isLoading || rewardedAd != null) {
            Log.d(TAG, "RewardedAd already loaded or loading in progress.")
            return
        }

        isLoading = true
        try {
            val adRequest = AdRequest.Builder().build()
            RewardedAd.load(
                context.applicationContext,
                REWARDED_AD_UNIT_ID,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        isLoading = false
                        Log.i(TAG, "RewardedAd loaded successfully.")
                    }

                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        rewardedAd = null
                        isLoading = false
                        Log.w(
                            TAG,
                            "RewardedAd failed to load: ${loadAdError.message} (code: ${loadAdError.code})"
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            isLoading = false
            Log.e(TAG, "Error initiating RewardedAd load: ${t.message}", t)
        }
    }

    /**
     * Shows the loaded Rewarded Ad in the provided [activity].
     * Invokes [onRewardEarned] when user completes watching and earns reward.
     * Automatically requests pre-caching of the next ad immediately after reward or dismissal.
     */
    fun showAd(activity: Activity, onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            Log.w(TAG, "Rewarded ad is not ready. Triggering background load.")
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
                Log.e(TAG, "Rewarded ad failed to show: ${adError.message} (code: ${adError.code})")
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

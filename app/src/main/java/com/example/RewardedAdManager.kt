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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RewardedAdManager - Singleton manager for loading and showing AdMob Rewarded Video Ads.
 * Production Ad Unit IDs are attempted first; in DEBUG builds, falls back to official test ads.
 * Handles ERROR_CODE_NO_FILL gracefully in release builds without infinite loading/retry loops.
 */
object RewardedAdManager {
    private const val TAG = "RewardedAdManager"
    const val NO_FILL_USER_MESSAGE = "No ad available right now, try again in a moment"
    const val LOADING_USER_MESSAGE = "Loading video ad... Please try again in a moment."

    // Delegated to centralized AdConfig (switches between official test ID in DEBUG and production in RELEASE)
    val TEST_AD_UNIT_ID: String
        get() = AdConfig.TEST_REWARDED_AD_UNIT_ID

    val REWARDED_AD_UNIT_ID: String
        get() = AdConfig.rewardedAdUnitId

    @Volatile
    private var rewardedAd: RewardedAd? = null

    @Volatile
    private var isLoading = false

    @Volatile
    private var isLoadedAdFallback = false

    private val _isLoadingState = MutableStateFlow(false)
    val isLoadingState: StateFlow<Boolean> = _isLoadingState.asStateFlow()

    private val _isNoFillState = MutableStateFlow(false)
    val isNoFillState: StateFlow<Boolean> = _isNoFillState.asStateFlow()

    private val _lastErrorMessage = MutableStateFlow<String?>(null)
    val lastErrorMessage: StateFlow<String?> = _lastErrorMessage.asStateFlow()

    /**
     * Checks if a rewarded ad is currently cached and ready to be shown.
     */
    fun isAdLoaded(): Boolean = rewardedAd != null

    /**
     * Checks if an ad request is currently in progress.
     */
    fun isLoading(): Boolean = isLoading

    fun getUnavailableMessage(): String {
        return if (_isNoFillState.value && !isLoading) {
            _lastErrorMessage.value ?: NO_FILL_USER_MESSAGE
        } else {
            LOADING_USER_MESSAGE
        }
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private var retryCount = 0
    private const val MAX_RETRIES = 3

    /**
     * Pre-caches a Rewarded Ad using the AdMob SDK on background worker threads.
     * Safe to call multiple times; will not re-trigger if an ad is already loaded or in-flight.
     * First attempts production ad unit; in DEBUG builds, falls back to the official test unit.
     */
    fun loadAd(context: Context) {
        retryCount = 0
        loadWithAdUnit(context.applicationContext, REWARDED_AD_UNIT_ID, isFallback = false)
    }

    private fun loadWithAdUnit(appContext: Context, adUnitToLoad: String, isFallback: Boolean) {
        if (isLoading || rewardedAd != null) {
            Log.d(TAG, "RewardedAd already loaded or loading in progress.")
            return
        }

        if (isUnitTestEnvironment()) {
            Log.d(TAG, "Skipping RewardedAd.load in unit test environment.")
            return
        }

        isLoading = true
        _isLoadingState.value = true
        _isNoFillState.value = false
        _lastErrorMessage.value = null
        try {
            val adRequest = AdRequest.Builder().build()
            Log.d(TAG, "Requesting RewardedAd with adUnitId: $adUnitToLoad (isFallback=$isFallback)")

            RewardedAd.load(
                appContext,
                adUnitToLoad,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        rewardedAd = ad
                        isLoadedAdFallback = isFallback
                        isLoading = false
                        _isLoadingState.value = false
                        _isNoFillState.value = false
                        _lastErrorMessage.value = null
                        retryCount = 0
                        Log.i(TAG, "RewardedAd loaded successfully with adUnitId: $adUnitToLoad (isFallback=$isFallback)")
                    }

                    override fun onAdFailedToLoad(adError: LoadAdError) {
                        rewardedAd = null
                        isLoadedAdFallback = false
                        isLoading = false
                        _isLoadingState.value = false
                        Log.w(
                            TAG,
                            "RewardedAd notice for $adUnitToLoad: ${adError.message} (Code: ${adError.code}, Domain: ${adError.domain})."
                        )
                        // Strictly allow test ad fallback ONLY in debug builds
                        if (BuildConfig.DEBUG && !isFallback && adUnitToLoad != AdConfig.fallbackRewardedAdUnitId) {
                            Log.i(TAG, "Debug build: Production RewardedAd failed to load. Falling back to test ad unit...")
                            mainHandler.post {
                                loadWithAdUnit(appContext, AdConfig.fallbackRewardedAdUnitId, isFallback = true)
                            }
                            return
                        }

                        if (!shouldRetryAfterFailure(adError.code, BuildConfig.DEBUG)) {
                            _isNoFillState.value = true
                            _lastErrorMessage.value = NO_FILL_USER_MESSAGE
                            return
                        }

                        if (retryCount < MAX_RETRIES) {
                            retryCount++
                            val delayMs = 5000L * retryCount
                            Log.d(TAG, "Scheduling RewardedAd auto-retry in ${delayMs / 1000}s (attempt $retryCount of $MAX_RETRIES)...")
                            mainHandler.postDelayed({
                                loadWithAdUnit(appContext, adUnitToLoad, isFallback)
                            }, delayMs)
                        } else if (adError.code == AdRequest.ERROR_CODE_NO_FILL) {
                            _isNoFillState.value = true
                            _lastErrorMessage.value = NO_FILL_USER_MESSAGE
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            isLoading = false
            _isLoadingState.value = false
            Log.w(TAG, "Notice initiating RewardedAd load for adUnitId $adUnitToLoad: ${t.message}")
        }
    }

    internal fun shouldRetryAfterFailure(errorCode: Int, isDebugBuild: Boolean): Boolean {
        return !(errorCode == AdRequest.ERROR_CODE_NO_FILL && !isDebugBuild)
    }

    /**
     * Shows the loaded Rewarded Ad in the provided [activity].
     * If the ad is not yet loaded, informs the user with a clear Toast and triggers a background load.
     */
    fun showAd(activity: Activity, onRewardEarned: () -> Unit) {
        val ad = rewardedAd
        if (ad == null || (!BuildConfig.DEBUG && isLoadedAdFallback)) {
            Log.w(TAG, "Rewarded ad unavailable for production reward payout. Reloading production ad.")
            rewardedAd = null
            isLoadedAdFallback = false
            Toast.makeText(activity, getUnavailableMessage(), Toast.LENGTH_SHORT).show()
            loadAd(activity)
            return
        }

        val currentAdIsFallback = isLoadedAdFallback
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Rewarded ad dismissed by user.")
                rewardedAd = null
                isLoadedAdFallback = false
                loadAd(activity)
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(
                    TAG,
                    "Rewarded ad failed to show: code=${adError.code}, message='${adError.message}', domain='${adError.domain}'"
                )
                rewardedAd = null
                isLoadedAdFallback = false
                loadAd(activity)
            }

            override fun onAdShowedFullScreenContent() {
                Log.d(TAG, "Rewarded ad showing fullscreen.")
            }
        }

        try {
            ad.show(activity, OnUserEarnedRewardListener { rewardItem ->
                Log.i(TAG, "User earned reward: amount=${rewardItem.amount}, type=${rewardItem.type}")
                if (BuildConfig.DEBUG || !currentAdIsFallback) {
                    onRewardEarned()
                } else {
                    Log.w(TAG, "Blocked reward grant from non-production ad in release build.")
                }
                rewardedAd = null
                isLoadedAdFallback = false
                loadAd(activity)
            })
        } catch (t: Throwable) {
            Log.e(TAG, "Exception showing RewardedAd: ${t.message}", t)
            rewardedAd = null
            isLoadedAdFallback = false
            loadAd(activity)
        }
    }

    private fun isUnitTestEnvironment(): Boolean {
        return BuildConfig.DEBUG && (
            Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true ||
                runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
        )
    }

    internal fun simulateLoadFailureForTesting(errorCode: Int, isDebugBuild: Boolean = false) {
        rewardedAd = null
        isLoadedAdFallback = false
        isLoading = false
        _isLoadingState.value = false
        if (!shouldRetryAfterFailure(errorCode, isDebugBuild) || errorCode == AdRequest.ERROR_CODE_NO_FILL) {
            _isNoFillState.value = true
            _lastErrorMessage.value = NO_FILL_USER_MESSAGE
        }
    }

    /**
     * Testing hook to set/clear cached ad state in unit tests without invoking native SDK.
     */
    internal fun setMockLoadedForTesting(isLoaded: Boolean) {
        if (!isLoaded) {
            rewardedAd = null
            isLoading = false
            isLoadedAdFallback = false
            _isLoadingState.value = false
            _isNoFillState.value = false
            _lastErrorMessage.value = null
            retryCount = 0
        }
    }
}

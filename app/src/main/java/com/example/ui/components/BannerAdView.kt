package com.example.ui.components

import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError

// Google's official Test Ad Unit ID for standard Banner Ads (320x50)
const val ADMOB_BANNER_TEST_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
const val ADMOB_BANNER_AD_UNIT_ID = ADMOB_BANNER_TEST_AD_UNIT_ID

/**
 * BannerAdView
 *
 * Renders a Google Mobile Ads (AdMob) Banner Ad using AndroidView in Jetpack Compose.
 * Strictly uses Google's official Test Ad Unit ID during development for guaranteed 100% fill rate.
 * Safely manages lifecycle cleanup with destroy() on disposal.
 */
@Composable
fun BannerAdView(
    modifier: Modifier = Modifier,
    adUnitId: String = ADMOB_BANNER_TEST_AD_UNIT_ID
) {
    val isInPreview = LocalInspectionMode.current
    val isTestEnv = remember {
        Build.FINGERPRINT?.contains("robolectric", ignoreCase = true) == true ||
            runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
    }

    if (isInPreview || isTestEnv) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .testTag("banner_ad_container"),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Google Mobile Ads: Banner Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    var adViewRef by remember { mutableStateOf<AdView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            try {
                adViewRef?.destroy()
                adViewRef = null
            } catch (e: Exception) {
                Log.w("BannerAdView", "Error destroying AdView: ${e.message}")
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("banner_ad_container"),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier
                .wrapContentSize()
                .testTag("banner_ad_view"),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER)
                    this.adUnitId = adUnitId
                    adListener = object : AdListener() {
                        override fun onAdLoaded() {
                            Log.d("BannerAdView", "AdMob Banner Ad loaded successfully.")
                        }

                        override fun onAdFailedToLoad(error: LoadAdError) {
                            Log.e(
                                "BannerAdView",
                                "AdMob Banner Ad failed to load: code=${error.code}, message='${error.message}', domain='${error.domain}'"
                            )
                        }

                        override fun onAdOpened() {
                            Log.d("BannerAdView", "AdMob Banner Ad opened.")
                        }

                        override fun onAdClosed() {
                            Log.d("BannerAdView", "AdMob Banner Ad closed.")
                        }
                    }
                    try {
                        val adRequest = AdRequest.Builder().build()
                        loadAd(adRequest)
                    } catch (e: Exception) {
                        Log.e("BannerAdView", "Error requesting AdMob Banner: ${e.message}", e)
                    }
                    adViewRef = this
                }
            }
        )
    }
}

package com.oxygenupdater.ui.common

import android.app.Activity
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.WindowInsets
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.util.fastCoerceAtLeast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.oxygenupdater.BuildConfig
import com.oxygenupdater.ui.main.NavType
import com.oxygenupdater.utils.logDebug

/**
 * Lays out an [AdView]. Responsible for calculating the appropriate size, creating the [AdView]
 * with the correct configuration, and finally loading the ad itself.
 *
 * [AdView.pause], [AdView.resume], and [AdView.destroy] are called when necessary, via Compose
 * [LifecycleResumeEffect] and [DisposableEffect].
 *
 * @param activity AdMob recommends an [Activity] context: https://developers.google.com/admob/android/mediation#initialize_your_ad_object_with_an_activity_instance
 * @param addNavBarPadding flag to control if we should add [navigationBarsPadding]
 */
@Composable
fun ColumnScope.BannerAd(
    activity: Activity,
    navType: NavType,
    addNavBarPadding: Boolean,
): Unit = if (LocalInspectionMode.current) {
    Text("AdView", Modifier.align(Alignment.CenterHorizontally))
} else {
    var loaded by rememberState(false)

    val density = LocalDensity.current.density
    val adSize = remember(navType) {
        val adWidthPx = if (SDK_INT >= VERSION_CODES.R) {
            val metrics = activity.windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars())

            metrics.bounds.width() - (insets.right + insets.left)
        } else activity.resources.displayMetrics.widthPixels

        /**
         * Note: keep in sync with
         * [androidx.compose.material3.tokens.NavigationRailCollapsedTokens.NarrowContainerWidth]
         * */
        val sideRailWidth = if (navType == NavType.SideRail) 80 + 1 else 0
        val adWidth = (adWidthPx / density.fastCoerceAtLeast(1f)).toInt() - sideRailWidth

        /**
         * TODO(ads): [AdView.setAdSize] can only be called once per [AdView], which means we can't
         *  update its size when [navType] changes. Figure out how to discard the old one and reload.
         */
        AdSize.getLargeAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    val adView = remember {
        AdView(activity).apply {
            // Both of these can only be set once per AdView
            adUnitId = BuildConfig.AD_BANNER_MAIN_ID
            setAdSize(adSize).also { logDebug(TAG, "Requested size: $adSize") }

            adListener = object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    loaded = false
                    logDebug(TAG, "Banner ad failed to load: $error")
                }

                override fun onAdLoaded() {
                    loaded = true
                    val adapterResponseInfo = responseInfo?.loadedAdapterResponseInfo
                    val adapterClassName = adapterResponseInfo?.adapterClassName
                    val adSourceName = adapterResponseInfo?.adSourceName
                    val latencyMillis = adapterResponseInfo?.latencyMillis
                    logDebug(TAG, "$adapterClassName: LOADED $adSourceName $adSize ($latencyMillis ms)")
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        logDebug(TAG, "Loading")
        adView.loadAd(buildAdRequest())
    }

    AndroidView(
        factory = { adView },
        // We draw the activity edge-to-edge, so nav bar padding should be applied only if ad loaded
        modifier = (if (addNavBarPadding && loaded) Modifier.navigationBarsPadding() else Modifier)
            .align(Alignment.CenterHorizontally)
            .wrapContentSize()
    )

    // Pause and resume the AdView when the lifecycle is paused and resumed
    LifecycleResumeEffect(adView) {
        adView.resume()
        onPauseOrDispose { adView.pause() }
    }

    DisposableEffect(Unit) {
        // Destroy the AdView to prevent memory leaks when the screen is disposed
        onDispose { adView.destroy() }
    }
}

/**
 * @param collapsibleBanner Default `false`. Controls whether we use Google's new collapsible
 *        banner ad format: https://developers.google.com/admob/android/banner/collapsible.
 *        **Note**: while collapsible banner ads have higher eCPMs, they have terrible UX,
 *        and so it shouldn't be enabled again unless Google improves on this front. Firstly,
 *        the button to collapse it is sometimes either very small, or in some cases it's not
 *        there at all (we've received screenshots over email). It also has a bug that has
 *        existed at least since https://github.com/oxygen-updater/oxygen-updater/commit/79a7eb9f0cc4969bfad4234f8681c6fd1c922425.
 *        Back then (Aug 16, 2024), this format was marked 'experimental', but this bug still
 *        exists even though it's not experimental anymore. By memory, the culprit ads usually
 *        were TikTok or Temu that were full-height without collapsible buttons. Not sure.
 *        This entire doc-comment is kept so that the next time we feel tempted to turn it on
 *        again, we're reminded of everything wrong with the format. (especially user experience!)
 */
inline fun buildAdRequest(
    collapsibleBanner: Boolean = false,
) = AdRequest.Builder().apply {
    // https://developers.google.com/admob/android/banner/collapsible
    if (collapsibleBanner) addNetworkExtrasBundle(AdMobAdapter::class.java, Bundle().apply {
        putString("collapsible", "bottom")
    })
}.build()

private const val TAG = "BannerAd"

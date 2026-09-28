package com.sora.mockgps.monetization.ads

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

@Composable
fun AdaptiveBanner(
    adUnitId: String,
    visible: Boolean,
    loadToken: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current
    if (!visible) return
    val adSize = remember(configuration.screenWidthDp) {
        val activity = context as? Activity
        if (activity != null) {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, configuration.screenWidthDp)
        } else {
            AdSize.BANNER
        }
    }
    var adView by remember { mutableStateOf<AdView?>(null) }
    DisposableEffect(lifecycleOwner, adView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> adView?.pause()
                Lifecycle.Event.ON_RESUME -> adView?.resume()
                Lifecycle.Event.ON_DESTROY -> adView?.destroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    Box(modifier = modifier.fillMaxWidth().wrapContentHeight()) {
        key(loadToken, adUnitId, adSize) {
            AndroidView(
                factory = { viewContext ->
                    AdView(viewContext).apply {
                        setAdSize(adSize)
                        this.adUnitId = adUnitId
                        loadAd(AdRequest.Builder().build())
                        adView = this
                    }
                },
                update = { view -> adView = view },
                onRelease = { view ->
                    view.destroy()
                    if (adView === view) adView = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

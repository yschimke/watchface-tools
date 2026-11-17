package ee.schimke.watchface.push

import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.HttpPushApi
import ee.schimke.watchface.push.data.PreviewArt
import ee.schimke.watchface.push.data.PushApi
import ee.schimke.watchface.push.link.DataStoreTokenStore
import ee.schimke.watchface.push.link.LinkController
import ee.schimke.watchface.push.link.TokenStore
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.RealWatchFacePusher
import ee.schimke.watchface.push.push.WatchFacePusher
import ee.schimke.watchface.push.push.WffSupport
import okhttp3.OkHttpClient

/** Hand-wired dependencies. [baseUrl] and [fake] come from BuildConfig, overridable in debug. */
class AppGraph(context: Context, baseUrl: String, fake: Boolean) {
  val tokens: TokenStore = DataStoreTokenStore(context)
  val api: PushApi
  val pusher: WatchFacePusher =
    RealWatchFacePusher(context, WffSupport.maxVersionForSdk(Build.VERSION.SDK_INT))
  val installFlow: InstallFlow
  val linkController: LinkController
  val imageLoader: ImageLoader

  init {
    val builder = OkHttpClient.Builder()
    val effectiveBase =
      if (fake) {
        val host = "fake.watchface.invalid"
        builder.addInterceptor(FakePushServer(host, previewPng = PreviewArt::render))
        "https://$host"
      } else baseUrl
    val client = builder.build()
    api = HttpPushApi(effectiveBase, client)
    installFlow = InstallFlow(api, pusher, context.cacheDir, Build.VERSION.SDK_INT)
    linkController =
      LinkController(api, tokens, deviceName = Build.MODEL, wearOsApi = Build.VERSION.SDK_INT)
    imageLoader =
      ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .build()
  }
}

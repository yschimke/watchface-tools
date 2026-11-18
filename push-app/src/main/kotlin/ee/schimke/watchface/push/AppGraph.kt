package ee.schimke.watchface.push

import android.content.Context
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import ee.schimke.watchface.push.data.CatalogServers
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.PreviewArt
import ee.schimke.watchface.push.data.PushApi
import ee.schimke.watchface.push.data.SwitchingPushApi
import ee.schimke.watchface.push.link.DataStoreServerStore
import ee.schimke.watchface.push.link.DataStoreTokenStore
import ee.schimke.watchface.push.link.LinkController
import ee.schimke.watchface.push.link.ServerStore
import ee.schimke.watchface.push.link.TokenStore
import ee.schimke.watchface.push.push.InstallFlow
import ee.schimke.watchface.push.push.RealWatchFacePusher
import ee.schimke.watchface.push.push.WatchFacePusher
import ee.schimke.watchface.push.push.WffSupport
import okhttp3.OkHttpClient

/**
 * Hand-wired dependencies. [defaultServer] and [knownServers] come from BuildConfig. With [fake]
 * (debug builds) the in-process fake server is the default and the real servers stay selectable.
 */
class AppGraph(
  context: Context,
  defaultServer: String,
  knownServers: List<String>,
  fake: Boolean,
  val allowInsecureServers: Boolean,
  /** Debug only: extra designs for the fake's catalog (see [FakePushServer]). */
  extraCatalogUrl: String? = null,
  /** Debug only: false makes the fake wait for [approveFakeLink] instead of approving itself. */
  fakeAutoApprove: Boolean = true,
) {
  /** Diagnostics to logcat under [LOG_TAG]; the emulator workflow asserts on these lines. */
  val log: (String) -> Unit = { Log.i(LOG_TAG, it) }
  val tokens: TokenStore = DataStoreTokenStore(context)
  val serverPresets: List<String> =
    if (fake) CatalogServers.presets(FAKE_SERVER, listOf(defaultServer) + knownServers, true)
    else CatalogServers.presets(defaultServer, knownServers, allowInsecureServers)
  val servers: ServerStore = DataStoreServerStore(context, serverPresets.first())
  private val switching: SwitchingPushApi
  val api: PushApi
  val pusher: WatchFacePusher =
    RealWatchFacePusher(context, WffSupport.maxVersionForSdk(Build.VERSION.SDK_INT))
  val installFlow: InstallFlow
  val linkController: LinkController
  val imageLoader: ImageLoader

  /** The in-process server when [fake]; null otherwise. */
  var fakeServer: FakePushServer? = null
    private set

  /** Stands in for the user confirming the code on the site (debug tests only). */
  fun approveFakeLink() {
    fakeServer?.approvePending()
  }

  init {
    val builder = OkHttpClient.Builder()
    if (fake) {
      val host = FAKE_SERVER.substringAfter("://")
      val server =
        FakePushServer(
          host,
          previewPng = PreviewArt::render,
          extraCatalogUrl = extraCatalogUrl,
          autoApprove = fakeAutoApprove,
        )
      fakeServer = server
      builder.addInterceptor(server)
    }
    val client = builder.build()
    switching = SwitchingPushApi(serverPresets.first(), client)
    api = switching
    installFlow = InstallFlow(api, pusher, context.cacheDir, Build.VERSION.SDK_INT, log)
    linkController =
      LinkController(api, tokens, deviceName = Build.MODEL, wearOsApi = Build.VERSION.SDK_INT)
    imageLoader =
      ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
        .build()
  }

  fun switchServer(server: String) = switching.switchTo(server)

  companion object {
    const val FAKE_SERVER = "https://fake.watchface.invalid"
    const val LOG_TAG = "FacesPush"
  }
}

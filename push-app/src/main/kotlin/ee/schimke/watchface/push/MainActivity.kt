package ee.schimke.watchface.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.setSingletonImageLoaderFactory
import ee.schimke.watchface.push.ui.PushApp

class MainActivity : ComponentActivity() {
  private var approveReceiver: BroadcastReceiver? = null

  override fun onDestroy() {
    approveReceiver?.let(::unregisterReceiver)
    super.onDestroy()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Debug builds can be pointed elsewhere without rebuilding; base_url becomes the selection:
    //   adb shell am start -n <pkg>/.MainActivity --es base_url https://host --ez fake false
    // For automated tests (.github/workflows/push-app-emulator.yml) debug builds also take
    //   --es fake_extra_catalog <url>  more designs for the fake's catalog (a real packaged face)
    //   --es device_token <token>      link to base_url with this token, skipping the device code
    //   --ez fake_auto_approve false   the fake waits for a FAKE_APPROVE broadcast to link:
    //     adb shell am broadcast -a ee.schimke.watchface.push.FAKE_APPROVE -p <pkg>
    val fake =
      if (BuildConfig.DEBUG && intent.hasExtra("fake")) intent.getBooleanExtra("fake", true)
      else BuildConfig.DEFAULT_FAKE_BACKEND
    val graph =
      AppGraph(
        applicationContext,
        defaultServer = BuildConfig.DEFAULT_BASE_URL,
        knownServers = BuildConfig.KNOWN_SERVERS.split(',').filter(String::isNotBlank),
        fake = fake,
        allowInsecureServers = BuildConfig.DEBUG,
        extraCatalogUrl =
          if (BuildConfig.DEBUG) intent.getStringExtra("fake_extra_catalog") else null,
        fakeAutoApprove = !BuildConfig.DEBUG || intent.getBooleanExtra("fake_auto_approve", true),
      )
    if (BuildConfig.DEBUG && graph.fakeServer != null) {
      val receiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context, intent: Intent) {
            graph.log("fake approve requested")
            graph.approveFakeLink()
          }
        }
      // Exported so `adb shell am broadcast` reaches it; debug builds with the fake only.
      registerReceiver(receiver, IntentFilter(FAKE_APPROVE), RECEIVER_EXPORTED)
      approveReceiver = receiver
    }
    setContent {
      setSingletonImageLoaderFactory { graph.imageLoader }
      val vm: AppViewModel =
        viewModel(
          factory =
            object : ViewModelProvider.Factory {
              @Suppress("UNCHECKED_CAST")
              override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(
                    graph.api,
                    graph.tokens,
                    graph.pusher,
                    graph.installFlow,
                    graph.linkController,
                    servers = graph.servers,
                    switchServer = graph::switchServer,
                    serverPresets = graph.serverPresets,
                    allowInsecureServers = graph.allowInsecureServers,
                    log = graph.log,
                  )
                  .also { vm ->
                    if (BuildConfig.DEBUG) {
                      val baseUrl = intent.getStringExtra("base_url")
                      baseUrl?.let(vm::selectServer)
                      intent.getStringExtra("device_token")?.let { token ->
                        if (baseUrl != null) vm.importToken(token, baseUrl)
                      }
                    }
                  } as T
            },
        )
      PushApp(vm)
    }
  }
}

/** Debug builds: approves the fake server's pending device code (see `fake_auto_approve`). */
const val FAKE_APPROVE = "ee.schimke.watchface.push.FAKE_APPROVE"

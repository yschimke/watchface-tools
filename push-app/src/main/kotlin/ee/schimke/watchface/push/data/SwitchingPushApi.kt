package ee.schimke.watchface.push.data

import java.io.File
import okhttp3.OkHttpClient

/**
 * A [PushApi] whose server can change while the app runs. Every call goes to the server current
 * when it starts; callers cancel work in flight before switching (AppViewModel does).
 */
class SwitchingPushApi(initial: String, private val client: OkHttpClient) : PushApi {
  @Volatile private var delegate: PushApi = HttpPushApi(initial, client)

  @Volatile
  var server: String = initial
    private set

  fun switchTo(server: String) {
    if (server == this.server) return
    delegate = HttpPushApi(server, client)
    this.server = server
  }

  override suspend fun createDeviceCode(request: DeviceCodeRequest) =
    delegate.createDeviceCode(request)

  override suspend fun poll(code: String, pollSecret: String) = delegate.poll(code, pollSecret)

  override suspend fun listDesigns(deviceToken: String) = delegate.listDesigns(deviceToken)

  override suspend fun downloadApk(deviceToken: String, design: Design, target: File) =
    delegate.downloadApk(deviceToken, design, target)

  override suspend fun reportInstall(deviceToken: String, event: InstallEvent) =
    delegate.reportInstall(deviceToken, event)
}

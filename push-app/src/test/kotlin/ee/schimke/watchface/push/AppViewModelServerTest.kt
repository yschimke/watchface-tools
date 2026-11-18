package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.SwitchingPushApi
import ee.schimke.watchface.push.link.InMemoryServerStore
import ee.schimke.watchface.push.link.InMemoryTokenStore
import ee.schimke.watchface.push.link.LinkController
import ee.schimke.watchface.push.link.LinkedAccount
import ee.schimke.watchface.push.push.InstallFlow
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient

/** The session follows the chosen catalog server, and a token is only used with its own server. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelServerTest {
  private val home = "https://$FAKE_HOST"
  private val other = "https://other.example"
  private val server = FakePushServer(FAKE_HOST)
  private val api = SwitchingPushApi(home, OkHttpClient.Builder().addInterceptor(server).build())
  private val switched = mutableListOf<String>()
  private val tokens = InMemoryTokenStore(LinkedAccount(server.deviceToken, "demo", home))
  private val servers = InMemoryServerStore(home)

  @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

  @AfterTest fun reset() = Dispatchers.resetMain()

  private fun vm(): AppViewModel {
    val pusher = FakePusher()
    return AppViewModel(
      api,
      tokens,
      pusher,
      InstallFlow(api, pusher, Files.createTempDirectory("cache").toFile(), 36),
      LinkController(api, tokens, "Test", 36),
      servers = servers,
      switchServer = {
        switched += it
        api.switchTo(it)
      },
      serverPresets = listOf(home, other),
    )
  }

  @Test
  fun linkedOnlyOnTheServerThatIssuedTheToken() {
    val vm = vm()
    assertEquals(home, vm.server.value)
    assertIs<Session.Linked>(vm.session.value)

    assertTrue(vm.selectServer("other.example/"))
    assertEquals(other, vm.server.value)
    assertEquals(other, api.server)
    assertIs<Session.Unlinked>(vm.session.value)

    // Back to the first server: its token was kept, so the watch is linked there again.
    assertTrue(vm.selectServer(home))
    assertIs<Session.Linked>(vm.session.value)
    assertEquals(listOf(home, other, home), switched)
  }

  @Test
  fun refusesAnUnusableServerAndStaysPut() {
    val vm = vm()
    assertFalse(vm.selectServer("http://insecure.example"))
    assertFalse(vm.selectServer("not a server"))
    assertEquals(home, vm.server.value)
    assertIs<Session.Linked>(vm.session.value)
  }
}

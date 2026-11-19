package ee.schimke.watchface.push

import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody

/** The session follows the chosen catalog server, and a token is only used with its own server. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelServerTest {
  private val home = "https://$FAKE_HOST"
  private val other = "https://other.example"
  private val server = FakePushServer(FAKE_HOST)
  /**
   * Every request's URL and Authorization header, whichever server it went to. Copy-on-write: the
   * test reads it while OkHttp threads append (unlinking starts an anonymous listing).
   */
  private val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String?>>()
  /** While set, listings fail with 503, as a server or network outage does. */
  @Volatile private var listingDown = false
  private val api =
    SwitchingPushApi(
      home,
      OkHttpClient.Builder()
        .addInterceptor { chain ->
          val request = chain.request()
          sent += request.url.toString() to request.header("Authorization")
          if (listingDown && request.url.encodedPath.endsWith("/designs"))
            okhttp3.Response.Builder()
              .request(request)
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(503)
              .message("down")
              .body("".toResponseBody())
              .build()
          else if (request.url.host == FAKE_HOST) chain.proceed(request)
          else
            okhttp3.Response.Builder()
              .request(request)
              .protocol(okhttp3.Protocol.HTTP_1_1)
              .code(404)
              .message("other")
              .body("".toResponseBody())
              .build()
        }
        .addInterceptor(server)
        .build(),
    )
  private val pusher = FakePusher()
  private val switched = mutableListOf<String>()
  private var tokens = InMemoryTokenStore(LinkedAccount(server.deviceToken, "demo", home))
  private val servers = InMemoryServerStore(home)

  /** Drives the ViewModel's delays (link polling); IO still runs on real threads. */
  private val scheduler = kotlinx.coroutines.test.TestCoroutineScheduler()

  @BeforeTest fun main() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

  @AfterTest fun reset() = Dispatchers.resetMain()

  private fun vm(): AppViewModel {
    return AppViewModel(
      api,
      tokens,
      pusher,
      InstallFlow(
        api,
        pusher,
        Files.createTempDirectory("cache").toFile(),
        ee.schimke.watchface.push.data.DeviceInfo(36),
      ),
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
    // No token for this server: the watch browses its public faces instead of prompting.
    assertIs<Session.Browsing>(vm.session.value)

    // Back to the first server: its token was kept, so the watch is linked there again.
    assertTrue(vm.selectServer(home))
    assertIs<Session.Linked>(vm.session.value)
    assertEquals(listOf(home, other, home), switched)
  }

  @Test
  fun reselectingTheCurrentServerKeepsItsListing(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      val vm = vm()
      // Picked again while the first public listing may still be in flight.
      assertTrue(vm.selectServer(home))
      awaitReady(vm)
      assertEquals(listOf(home), switched)
    }

  @Test
  fun refusesAnUnusableServerAndStaysPut() {
    val vm = vm()
    assertFalse(vm.selectServer("http://insecure.example"))
    assertFalse(vm.selectServer("not a server"))
    assertEquals(home, vm.server.value)
    assertIs<Session.Linked>(vm.session.value)
  }

  @Test
  fun anInstallInFlightNeverSendsItsTokenToTheNextServer(): Unit =
    kotlinx.coroutines.runBlocking {
      val vm = vm()
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.designs.value !is DesignsState.Ready) kotlinx.coroutines.delay(10)
      }
      val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
      pusher.gate = gate
      vm.installDesign(server.designs.first(), confirmReplace = true, activate = false)
      // The APK is downloaded; the push is now held at the gate.
      kotlinx.coroutines.withTimeout(5_000) {
        while (sent.none { it.first.substringBefore('?').endsWith("/apk") }) kotlinx.coroutines
          .delay(10)
      }
      kotlinx.coroutines.delay(100)
      // Mid-install: unlink and move to another server, then let the push finish.
      vm.unlink()
      assertTrue(vm.selectServer(other))
      gate.complete(Unit)
      kotlinx.coroutines.delay(300)

      val leaked = sent.filter { !it.first.startsWith(home) && it.second != null }
      assertTrue(leaked.isEmpty(), "token sent to another server: $leaked")
      assertTrue(server.installs.isEmpty(), "the cancelled install must not report")
      assertIs<InstallUi.Idle>(vm.install.value)
    }

  private fun awaitReady(vm: AppViewModel): DesignsState.Ready =
    kotlinx.coroutines.runBlocking {
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.designs.value !is DesignsState.Ready) kotlinx.coroutines.delay(10)
      }
      vm.designs.value as DesignsState.Ready
    }

  @Test
  fun anUnlinkedWatchOpensOnThePublicFaces(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      val vm = vm()
      assertIs<Session.Browsing>(vm.session.value)
      val ready = awaitReady(vm)
      assertEquals(server.publicIds, ready.designs.map { it.id }.toSet())

      vm.installDesign(ready.designs.first { it.id == "aurora" }, false, activate = false)
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.install.value !is InstallUi.Finished) kotlinx.coroutines.delay(10)
      }
      assertIs<ee.schimke.watchface.push.push.InstallResult.Success>(
        (vm.install.value as InstallUi.Finished).result,
      )
      assertTrue(sent.all { it.second == null }, "no token while browsing: $sent")
    }

  @Test
  fun unlinkingGoesBackToThePublicFaces(): Unit =
    kotlinx.coroutines.runBlocking {
      val vm = vm()
      assertTrue(awaitReady(vm).designs.any { it.id !in server.publicIds })
      vm.unlink()
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.session.value !is Session.Browsing) kotlinx.coroutines.delay(10)
      }
      kotlinx.coroutines.withTimeout(5_000) {
        while (
          (vm.designs.value as? DesignsState.Ready)?.designs?.map { it.id }?.toSet() !=
            server.publicIds
        ) kotlinx.coroutines.delay(10)
      }
    }

  @Test
  fun linkedListingAddsTheAccountsOwnDesigns() {
    val ready = awaitReady(vm())
    assertEquals(server.designs.map { it.id }, ready.designs.map { it.id })
    assertTrue(ready.designs.any { it.id !in server.publicIds })
  }

  @Test
  fun anonymousRefusalOfAPrivateFaceKeepsTheSession(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      val vm = vm()
      awaitReady(vm)
      vm.installDesign(server.designs.first { it.id == "pulse" }, false, activate = false)
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.install.value !is InstallUi.Finished) kotlinx.coroutines.delay(10)
      }
      val result = (vm.install.value as InstallUi.Finished).result
      assertIs<ee.schimke.watchface.push.push.InstallResult.DownloadFailed>(result)
      assertTrue(result.anonymous)
      assertIs<Session.Browsing>(vm.session.value)
    }

  @Test
  fun leavingTheSignInScreenForgetsItsCode(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      val vm = vm()
      vm.startLink()
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.link.value !is ee.schimke.watchface.push.link.LinkState.Waiting) {
          kotlinx.coroutines.delay(10)
        }
      }
      vm.cancelLink()
      assertIs<ee.schimke.watchface.push.link.LinkState.Requesting>(vm.link.value)
    }

  @Test
  fun leavingTheSignInScreenCancelsItsLinkAttempt(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      val vm = vm()
      vm.startLink()
      kotlinx.coroutines.withTimeout(5_000) {
        while (vm.link.value !is ee.schimke.watchface.push.link.LinkState.Waiting) {
          kotlinx.coroutines.delay(10)
        }
      }
      vm.cancelLink()
      // The fake approves on the third poll, one second apart: run well past that.
      repeat(8) {
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        kotlinx.coroutines.delay(100) // real time, for a poll's IO to come back
      }
      assertIs<Session.Browsing>(vm.session.value)
      assertEquals(null, tokens.account.first())
      assertFalse(server.linked, "no poll may reach approval while browsing")
    }

  @Test
  fun aFaceThatFinishesBuildingBecomesInstallableOnItsOwn(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      server.startBuild("comet", "Comet")
      val vm = vm()
      val first = awaitReady(vm)
      assertFalse(first.designs.single { it.id == "comet" }.isInstallable)

      val poller =
        kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
          vm.pollWhileBuilding()
        }
      try {
        server.finishBuild("comet")
        val listings = sent.count { isListing(it.first) }
        scheduler.advanceTimeBy(AppViewModel.BUILD_POLL_MILLIS + 1)
        kotlinx.coroutines.withTimeout(5_000) {
          while (
            (vm.designs.value as? DesignsState.Ready)
              ?.designs
              ?.single { it.id == "comet" }
              ?.isInstallable != true
          ) kotlinx.coroutines.delay(10)
        }
        assertEquals(listings + 1, sent.count { isListing(it.first) })

        // Nothing is building now: further ticks do not re-list.
        scheduler.advanceTimeBy(3 * AppViewModel.BUILD_POLL_MILLIS)
        kotlinx.coroutines.delay(200)
        assertEquals(listings + 1, sent.count { isListing(it.first) })
      } finally {
        poller.cancel()
        // Nothing of this ViewModel may resume on Main after the test resets it.
        vm.viewModelScope.cancel()
      }
    }

  @Test
  fun aFailedBuildPollKeepsTheListAndTheNextTickRecovers(): Unit =
    kotlinx.coroutines.runBlocking {
      tokens = InMemoryTokenStore()
      server.startBuild("comet", "Comet")
      val vm = vm()
      awaitReady(vm)
      val poller =
        kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(scheduler)).launch {
          vm.pollWhileBuilding()
        }
      try {
        listingDown = true
        val listings = sent.count { isListing(it.first) }
        scheduler.advanceTimeBy(AppViewModel.BUILD_POLL_MILLIS + 1)
        kotlinx.coroutines.withTimeout(5_000) {
          while (sent.count { isListing(it.first) } == listings) {
            kotlinx.coroutines.delay(10)
          }
        }
        kotlinx.coroutines.delay(200)
        val kept = assertIs<DesignsState.Ready>(vm.designs.value, "a failed poll keeps the list")
        assertTrue(kept.designs.any { it.id == "comet" })

        listingDown = false
        server.finishBuild("comet")
        scheduler.advanceTimeBy(AppViewModel.BUILD_POLL_MILLIS + 1)
        kotlinx.coroutines.withTimeout(5_000) {
          while (
            (vm.designs.value as? DesignsState.Ready)
              ?.designs
              ?.single { it.id == "comet" }
              ?.isInstallable != true
          ) kotlinx.coroutines.delay(10)
        }
      } finally {
        poller.cancel()
        vm.viewModelScope.cancel()
      }
    }

  private fun isListing(url: String) = url.substringBefore('?').endsWith("/designs")
}

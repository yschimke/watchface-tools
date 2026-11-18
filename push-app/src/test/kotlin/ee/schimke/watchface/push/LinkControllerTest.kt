package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.Design
import ee.schimke.watchface.push.data.DeviceCode
import ee.schimke.watchface.push.data.DeviceCodeRequest
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.InstallEvent
import ee.schimke.watchface.push.data.PollResponse
import ee.schimke.watchface.push.data.PushApi
import ee.schimke.watchface.push.link.InMemoryTokenStore
import ee.schimke.watchface.push.link.LinkController
import ee.schimke.watchface.push.link.LinkState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

class LinkControllerTest {
  @Test
  fun approvedAfterPollingStoresTokenAndEmitsLinked() = runTest {
    val (server, api) = fakeServerApi(FakePushServer(FAKE_HOST, approveAfterPolls = 2))
    val tokens = InMemoryTokenStore()
    val states =
      LinkController(api, tokens, "Test", 36, now = { currentTime })
        .link("https://$FAKE_HOST")
        .toList()

    assertEquals(LinkState.Requesting, states[0])
    val waiting = assertIs<LinkState.Waiting>(states[1])
    assertEquals("AB7K-92QX", waiting.code)
    assertEquals("https://$FAKE_HOST/link?code=AB7K-92QX", waiting.verificationUrl)
    assertEquals(LinkState.Linked("demo@example.com"), states.last())
    assertEquals(server.deviceToken, tokens.account.first()?.deviceToken)
    assertEquals("https://$FAKE_HOST", tokens.account.first()?.server)
  }

  private class StubApi(val polls: ArrayDeque<() -> PollResponse>, val expires: Int = 600) :
    PushApi {
    override suspend fun createDeviceCode(request: DeviceCodeRequest) =
      DeviceCode("CODE", "secret", "https://x/link?code=CODE", expires, pollIntervalSeconds = 5)

    override suspend fun poll(code: String, pollSecret: String) = polls.removeFirst()()

    override suspend fun listDesigns(deviceToken: String): List<Design> = error("unused")

    override suspend fun downloadApk(deviceToken: String, design: Design, target: File): String =
      error("unused")

    override suspend fun reportInstall(deviceToken: String, event: InstallEvent) = Unit
  }

  @Test
  fun deniedEndsWithoutStoringToken() = runTest {
    val tokens = InMemoryTokenStore()
    val api = StubApi(ArrayDeque(listOf({ PollResponse("pending") }, { PollResponse("denied") })))
    val states =
      LinkController(api, tokens, "Test", 36, now = { currentTime })
        .link("https://$FAKE_HOST")
        .toList()
    assertEquals(LinkState.Denied, states.last())
    assertEquals(null, tokens.account.first())
  }

  @Test
  fun transientNetworkErrorKeepsPolling() = runTest {
    val tokens = InMemoryTokenStore()
    val api =
      StubApi(
        ArrayDeque(
          listOf(
            { throw ApiException(0, "offline") },
            { PollResponse("approved", "tok", null) },
          ),
        ),
      )
    val states =
      LinkController(api, tokens, "Test", 36, now = { currentTime })
        .link("https://$FAKE_HOST")
        .toList()
    assertEquals(LinkState.Linked(null), states.last())
    assertEquals("tok", tokens.account.first()?.deviceToken)
  }

  @Test
  fun neverApprovedExpiresAtDeadline() = runTest {
    val api = StubApi(ArrayDeque(List(100) { { PollResponse("pending") } }), expires = 12)
    val states =
      LinkController(api, InMemoryTokenStore(), "Test", 36, now = { currentTime })
        .link("https://$FAKE_HOST")
        .toList()
    assertEquals(LinkState.Expired, states.last())
  }

  @Test
  fun goneCodeExpiresImmediately() = runTest {
    val api = StubApi(ArrayDeque(listOf({ throw ApiException(410, "gone") })))
    val states =
      LinkController(api, InMemoryTokenStore(), "Test", 36, now = { currentTime })
        .link("https://$FAKE_HOST")
        .toList()
    assertEquals(LinkState.Expired, states.last())
  }
}

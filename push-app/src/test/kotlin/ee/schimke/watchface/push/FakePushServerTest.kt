package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.DefaultJson
import ee.schimke.watchface.push.data.DesignList
import ee.schimke.watchface.push.data.FakePushServer
import ee.schimke.watchface.push.data.HttpPushApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

/** The fake follows the server's access rules: public faces are open, private ones need a link. */
class FakePushServerTest {
  private val pair = fakeServerApi()
  private val server = pair.first
  private val api = pair.second

  @Test
  fun anonymousCallersSeeOnlyPublicDesigns() = runTest {
    assertEquals(server.publicDesigns, api.listDesigns(null))
    assertEquals(server.designs, api.listDesigns(server.deviceToken))
  }

  @Test
  fun publicApksDownloadAnonymouslyAndPrivateOnesNeedTheToken() = runTest {
    val file = File.createTempFile("fake", ".apk")
    val aurora = server.designs.first { it.id == "aurora" }
    assertEquals(aurora.sha256, api.downloadApk(null, aurora, file))

    val pulse = server.designs.first { it.id == "pulse" }
    val refused = assertFailsWith<ApiException> { api.downloadApk(null, pulse, file) }
    assertEquals(401, refused.status)
    assertEquals(pulse.sha256, api.downloadApk(server.deviceToken, pulse, file))
    file.delete()
  }

  @Test
  fun aWrongTokenIsRefusedEvenForPublicDesigns() = runTest {
    assertEquals(401, assertFailsWith<ApiException> { api.listDesigns("revoked") }.status)
  }

  @Test
  fun extraCatalogDesignsAreListedForEveryone() = runTest {
    val extra = MockWebServer().apply { start() }
    val real = FakeDesigns.sample.copy(id = "real", title = "Real face")
    val body = DefaultJson.encodeToString(DesignList.serializer(), DesignList(listOf(real)))
    repeat(2) { extra.enqueue(MockResponse.Builder().body(body).build()) }
    val fake = FakePushServer(FAKE_HOST, extraCatalogUrl = extra.url("/extra.json").toString())
    val withExtra =
      HttpPushApi("https://$FAKE_HOST", OkHttpClient.Builder().addInterceptor(fake).build())

    assertEquals(fake.publicDesigns + real, withExtra.listDesigns(null))
    assertEquals(fake.designs + real, withExtra.listDesigns(fake.deviceToken))
    extra.close()
  }
}

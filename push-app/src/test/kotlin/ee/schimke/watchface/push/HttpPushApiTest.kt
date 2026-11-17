package ee.schimke.watchface.push

import ee.schimke.watchface.push.data.ApiException
import ee.schimke.watchface.push.data.DeviceCodeRequest
import ee.schimke.watchface.push.data.HttpPushApi
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class HttpPushApiTest {
  private val server = MockWebServer().apply { start() }
  private val api = HttpPushApi(server.url("/").toString(), OkHttpClient())

  @Test
  fun endpointsAndBearerMatchTheContract() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """{"code":"C","pollSecret":"s","verificationUrl":"https://x/link?code=C","expiresInSeconds":60}""",
        )
        .build(),
    )
    server.enqueue(MockResponse.Builder().body("""{"status":"pending","extra":1}""").build())
    server.enqueue(
      MockResponse.Builder()
        .body(
          """{"designs":[{"id":"a","title":"A","previewUrl":"p","version":2,"wffVersion":4,"packageName":"ee.schimke.watchface.push.watchfacepush.a","apkUrl":"u","sha256":"00","validationToken":"t"}]}""",
        )
        .build(),
    )

    val code = api.createDeviceCode(DeviceCodeRequest("Watch", 36))
    assertEquals(5, code.pollIntervalSeconds)
    api.poll("C", "s")
    val designs = api.listDesigns("tok")

    assertEquals("a", designs.single().id)
    val first = server.takeRequest()
    assertEquals(
      "POST /api/watchface-push/v1/device-codes",
      "${first.method} ${first.url.encodedPath}",
    )
    assertNull(first.headers["Authorization"])
    val second = server.takeRequest()
    assertEquals("/api/watchface-push/v1/device-codes/C/poll", second.url.encodedPath)
    assertEquals("""{"pollSecret":"s"}""", second.body?.utf8())
    val third = server.takeRequest()
    assertEquals("Bearer tok", third.headers["Authorization"])
  }

  @Test
  fun unauthorizedIsSurfaced() = runTest {
    server.enqueue(MockResponse.Builder().code(401).build())
    val e = assertFailsWith<ApiException> { api.listDesigns("old") }
    assertEquals(true, e.isUnauthorized)
  }

  @Test
  fun deviceTokenIsNotSentToAnotherHost() = runTest {
    val cdn = MockWebServer().apply { start() }
    cdn.enqueue(MockResponse.Builder().body("bytes").build())
    val design =
      FakeDesigns.sample.copy(
        apkUrl = cdn.url("/a.apk").toString().replace("localhost", "127.0.0.1"),
      )
    val file = File.createTempFile("download", ".apk")
    val sha = api.downloadApk("secret", design, file)
    assertNull(cdn.takeRequest().headers["Authorization"])
    assertEquals("bytes", file.readText())
    assertEquals("277089d91c0bdf4f2e6862ba7e4a07605119431f5d13f726dd352b06f1b206a9", sha)
    file.delete()
  }
}

object FakeDesigns {
  val sample = ee.schimke.watchface.push.data.FakePushServer(FAKE_HOST).designs.first()
}

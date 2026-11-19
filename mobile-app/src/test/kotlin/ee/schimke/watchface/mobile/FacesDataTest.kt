package ee.schimke.watchface.mobile

import ee.schimke.watchface.mobile.data.FaceCatalog
import ee.schimke.watchface.mobile.data.FaceRenderer
import ee.schimke.watchface.mobile.data.FacesException
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient

class FacesDataTest {
  private val server = MockWebServer().apply { start() }
  private val base = server.url("/").toString()
  private val document = byteArrayOf(1, 2, 3, 4)
  private val sha =
    MessageDigest.getInstance("SHA-256").digest(document).joinToString("") { "%02x".format(it) }

  @Test
  fun theCatalogListsPublicFacesAndIgnoresWhatItDoesntRead() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """
          |{"designs":[{"id":"aurora","title":"Aurora","previewUrl":"https://x/a.png",
          |"version":3,"wffVersion":4,"apkUrl":"https://x/a.apk","sha256":"00"}]}
          """
            .trimMargin(),
        )
        .build(),
    )
    val faces = FaceCatalog(base, OkHttpClient()).faces()
    assertEquals(listOf("aurora"), faces.map { it.id })
    assertEquals("/api/watchface-push/v1/designs", server.takeRequest().url.encodedPath)
  }

  @Test
  fun theRendererAsksForATickingFaceWithoutSecondsAndChecksItsHash() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body("""{"sha256":"$sha","blob":"/designs/v1/blobs/$sha.rc","bindings":{}}""")
        .build(),
    )
    server.enqueue(MockResponse.Builder().body(okio.Buffer().write(document)).build())
    val face = FaceRenderer(base, OkHttpClient()).render("https://ui.coo.ee", "catalog:a/b", 4_000)
    assertContentEquals(document, face.bytes)
    assertEquals(sha, face.sha256)

    val resolve = server.takeRequest()
    assertEquals("/api/cards/resolve", resolve.url.encodedPath)
    val card = Json.parseToJsonElement(resolve.body!!.utf8()).jsonObject.getValue("card").jsonObject
    assertEquals("watchface", card.getValue("type").jsonPrimitive.content)
    assertEquals("catalog:a/b", card.getValue("face").jsonPrimitive.content)
    assertEquals(FaceRenderer.MAX_PX, card.getValue("size").jsonPrimitive.int)
    assertEquals(false, card.getValue("sweep").jsonPrimitive.boolean)
    assertEquals(false, card.getValue("seconds").jsonPrimitive.boolean)
    assertEquals("/designs/v1/blobs/$sha.rc", server.takeRequest().url.encodedPath)
  }

  @Test
  fun aDocumentThatDoesntMatchItsHashIsRefused() = runTest {
    server.enqueue(
      MockResponse.Builder().body("""{"sha256":"$sha","blob":"/designs/v1/blobs/x.rc"}""").build(),
    )
    server.enqueue(MockResponse.Builder().body("not the document").build())
    assertFailsWith<FacesException> {
      FaceRenderer(base, OkHttpClient()).render("https://ui.coo.ee", "aurora", 300)
    }
  }

  @Test
  fun aBlobOnAnotherHostIsNeverFetched() = runTest {
    server.enqueue(
      MockResponse.Builder()
        .body("""{"sha256":"$sha","blob":"https://elsewhere.example/x.rc"}""")
        .build(),
    )
    assertFailsWith<FacesException> {
      FaceRenderer(base, OkHttpClient()).render("https://ui.coo.ee", "aurora", 300)
    }
    assertEquals(1, server.requestCount)
  }

  @Test
  fun aRendererErrorIsReported() = runTest {
    server.enqueue(MockResponse.Builder().code(404).body("no such face").build())
    val error =
      assertFailsWith<FacesException> {
        FaceRenderer(base, OkHttpClient()).render("https://ui.coo.ee", "nope", 300)
      }
    assertEquals(404, error.status)
    assertTrue(server.requestCount == 1)
  }
}

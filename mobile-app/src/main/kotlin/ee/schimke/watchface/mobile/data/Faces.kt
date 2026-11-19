package ee.schimke.watchface.mobile.data

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A published face, as a Watch Face Push site lists it (watchface-tools `docs/PUSH_API.md`). */
@Serializable
data class Face(
  val id: String,
  val title: String,
  val description: String? = null,
  val previewUrl: String,
  val version: Long,
)

@Serializable private data class FaceList(val designs: List<Face> = emptyList())

/** Thrown for answers the app can't use. [status] is the HTTP status, 0 for I/O. */
class FacesException(val status: Int, message: String, cause: Throwable? = null) :
  IOException(message, cause)

/** The public faces a Watch Face Push site lists: what the watch app shows before linking. */
class FaceCatalog(server: String, private val client: OkHttpClient) {
  private val base = server.trimEnd('/').toHttpUrl()

  suspend fun faces(): List<Face> {
    val url = base.newBuilder().addEncodedPathSegments(DESIGNS_PATH).build()
    val body = client.fetch(Request.Builder().url(url).build())
    return runCatching { json.decodeFromString(FaceList.serializer(), body.decodeToString()) }
      .getOrElse { throw FacesException(200, "Unreadable face list", it) }
      .designs
  }

  companion object {
    const val DESIGNS_PATH = "api/watchface-push/v1/designs"
  }
}

/** A face compiled to Remote Compose for one size: its `.rc` bytes and their SHA-256. */
class RenderedFace(val sha256: String, val bytes: ByteArray)

/**
 * Compiles a published face to a Remote Compose document, on a design server
 * (homeassistant-remotecompose's `design-server`, `WatchFaces`): Watch Face Format to `.rc` needs
 * Skia and the translator, which run on a server, not on the phone.
 *
 * The face is asked for without its second hand and with no sweep: a launcher draws a widget as of
 * its last push and the app pushes once a minute
 * ([ee.schimke.watchface.mobile.widget.FaceWidgetTicker]), so a second hand would stand still.
 */
class FaceRenderer(renderer: String, private val client: OkHttpClient) {
  private val base = renderer.trimEnd('/').toHttpUrl()

  suspend fun render(server: String, faceId: String, sizePx: Int): RenderedFace {
    val request = buildJsonObject {
      put(
        "card",
        buildJsonObject {
          put("type", "watchface")
          put("face", faceId)
          put("server", server)
          put("size", sizePx.coerceIn(MIN_PX, MAX_PX))
          put("sweep", false)
          put("seconds", false)
        },
      )
    }
    val resolveUrl = base.newBuilder().addEncodedPathSegments("api/cards/resolve").build()
    val match =
      client
        .fetch(
          Request.Builder()
            .url(resolveUrl)
            .post(request.toString().toRequestBody("application/json".toMediaType()))
            .build(),
        )
        .decodeToString()
        .let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: throw FacesException(200, "Unreadable answer from the renderer")
    val sha = (match["sha256"] as? JsonPrimitive)?.contentOrNull?.takeIf(SHA256::matches)
    val blob = match["blob"]?.jsonPrimitive?.contentOrNull
    if (sha == null || blob == null || !blob.startsWith("/")) {
      throw FacesException(200, "The renderer returned no document")
    }
    // The blob is on the renderer's own host: never follow it anywhere else.
    val bytes = client.fetch(Request.Builder().url(base.resolve(blob)!!).build())
    if (sha256(bytes) != sha) throw FacesException(200, "The document doesn't match its hash")
    return RenderedFace(sha, bytes)
  }

  companion object {
    const val MIN_PX = 64
    const val MAX_PX = 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
  }
}

private val json = Json { ignoreUnknownKeys = true }

private const val MAX_BYTES = 4L * 1024 * 1024

/** The body of a 2xx answer, at most [MAX_BYTES]; anything else is a [FacesException]. */
private suspend fun OkHttpClient.fetch(request: Request): ByteArray =
  withContext(Dispatchers.IO) {
    try {
      newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw FacesException(response.code, "HTTP ${response.code} from ${request.url.host}")
        }
        val length = response.body.contentLength()
        if (length > MAX_BYTES) throw FacesException(response.code, "Answer too large")
        val source = response.body.source()
        // Buffers at most one byte past the cap, however the body is framed.
        if (source.request(MAX_BYTES + 1)) throw FacesException(response.code, "Answer too large")
        source.buffer.readByteArray()
      }
    } catch (e: FacesException) {
      throw e
    } catch (e: IOException) {
      throw FacesException(0, "Network error: ${e.message}", e)
    }
  }

internal fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

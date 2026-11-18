package ee.schimke.watchface.push.data

import ee.schimke.watchface.push.PushConstants
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Thrown for server answers the client cannot use. [status] is the HTTP status, 0 for I/O. */
class ApiException(val status: Int, message: String, cause: Throwable? = null) :
  IOException(message, cause) {
  val isUnauthorized: Boolean
    get() = status == 401
}

/** Client side of `docs/PUSH_API.md`. */
interface PushApi {
  suspend fun createDeviceCode(request: DeviceCodeRequest): DeviceCode

  suspend fun poll(code: String, pollSecret: String): PollResponse

  /**
   * The designs this watch may install. With no [deviceToken] (not linked) the server lists its
   * public designs; with one, the account's own published designs as well.
   */
  suspend fun listDesigns(deviceToken: String?): List<Design>

  /**
   * Streams the design's APK to [target]; returns the lowercase hex SHA-256 of the bytes written.
   * Public designs download without a [deviceToken].
   */
  suspend fun downloadApk(deviceToken: String?, design: Design, target: File): String

  /** Install telemetry; needs a linked watch, so it is skipped without a [deviceToken]. */
  suspend fun reportInstall(deviceToken: String?, event: InstallEvent)
}

class HttpPushApi(
  baseUrl: String,
  private val client: OkHttpClient,
  private val json: Json = DefaultJson,
) : PushApi {
  private val base = baseUrl.trimEnd('/').toHttpUrl()
  private val jsonType = "application/json".toMediaType()

  private fun url(path: String) = base.newBuilder().addEncodedPathSegments(path.trim('/')).build()

  private fun Request.Builder.bearer(token: String?) = apply {
    if (token != null) header("Authorization", "Bearer $token")
  }

  private suspend fun execute(request: Request): okhttp3.Response =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      try {
        client.newCall(request).execute()
      } catch (e: IOException) {
        throw ApiException(0, "Network error: ${e.message}", e)
      }
    }

  /** Runs the request and reads the whole body on IO; body reads hit the socket. */
  private suspend inline fun <reified T> call(request: Request): T {
    val (code, body) =
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
          client.newCall(request).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
          throw ApiException(0, "Network error: ${e.message}", e)
        }
      }
    if (code !in 200..299) throw ApiException(code, "HTTP $code")
    return try {
      json.decodeFromString<T>(body)
    } catch (e: Exception) {
      throw ApiException(code, "Unreadable response: ${e.message}", e)
    }
  }

  override suspend fun createDeviceCode(request: DeviceCodeRequest): DeviceCode =
    call(
      Request.Builder()
        .url(url("${PushConstants.API_PREFIX}/device-codes"))
        .post(json.encodeToString(request).toRequestBody(jsonType))
        .build(),
    )

  override suspend fun poll(code: String, pollSecret: String): PollResponse =
    call(
      Request.Builder()
        .url(url("${PushConstants.API_PREFIX}/device-codes/$code/poll"))
        .post(json.encodeToString(PollRequest(pollSecret)).toRequestBody(jsonType))
        .build(),
    )

  override suspend fun listDesigns(deviceToken: String?): List<Design> =
    call<DesignList>(
        Request.Builder()
          .url(url("${PushConstants.API_PREFIX}/designs"))
          .bearer(deviceToken)
          .get()
          .build(),
      )
      .designs

  override suspend fun downloadApk(deviceToken: String?, design: Design, target: File): String {
    val apkUrl = design.apkUrl.toHttpUrl()
    // Only send the device token to our own server, never to a CDN the apkUrl may point at.
    val token = if (apkUrl.host == base.host) deviceToken else null
    val request = Request.Builder().url(apkUrl).bearer(token).get().build()
    return execute(request).use { response ->
      if (!response.isSuccessful) throw ApiException(response.code, "HTTP ${response.code}")
      val digest = MessageDigest.getInstance("SHA-256")
      try {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
          target.outputStream().use { out ->
            response.body.byteStream().use { input ->
              val buffer = ByteArray(16 * 1024)
              while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
              }
            }
          }
        }
      } catch (e: IOException) {
        throw ApiException(0, "Download failed: ${e.message}", e)
      }
      digest.digest().joinToString("") { "%02x".format(it) }
    }
  }

  override suspend fun reportInstall(deviceToken: String?, event: InstallEvent) {
    if (deviceToken == null) return
    // Best effort telemetry: never fail an install because of it.
    runCatching {
      execute(
          Request.Builder()
            .url(url("${PushConstants.API_PREFIX}/installs"))
            .bearer(deviceToken)
            .post(json.encodeToString(event).toRequestBody(jsonType))
            .build(),
        )
        .close()
    }
  }
}

val DefaultJson = Json {
  ignoreUnknownKeys = true
  encodeDefaults = false
}

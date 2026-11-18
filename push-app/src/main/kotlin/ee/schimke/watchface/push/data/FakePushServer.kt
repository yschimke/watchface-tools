package ee.schimke.watchface.push.data

import ee.schimke.watchface.push.PushConstants
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * In-process implementation of `docs/PUSH_API.md` as an OkHttp [Interceptor], so debug builds and
 * tests run end to end without a server. Install it on the [okhttp3.OkHttpClient] used by
 * [HttpPushApi] (and by Coil for previews). The device-code is approved after [approveAfterPolls]
 * polls, standing in for the user confirming on the site.
 *
 * The "APKs" are deterministic placeholder bytes whose SHA-256 matches the design, so the whole
 * download/verify path runs; a real watch will reject them at the push API (no valid token).
 *
 * Like the real server, anonymous callers see the [publicIds] designs and may download them; a
 * linked watch also sees the account's own (private) designs. With [extraCatalogUrl] the listing
 * also carries the public designs of the [DesignList] JSON found there: the emulator test serves
 * packager output that way, so a real, validated face goes through the fake catalog.
 */
class FakePushServer(
  private val baseHost: String,
  private val approveAfterPolls: Int = 2,
  private val previewPng: (title: String) -> ByteArray = { ByteArray(0) },
  private val extraCatalogUrl: String? = null,
  /** False: never approve on its own; a test calls [approvePending] instead. */
  private val autoApprove: Boolean = true,
  private val json: Json = DefaultJson,
) : Interceptor {
  val deviceToken = "fake-device-token"
  val installs = mutableListOf<InstallEvent>()
  private val polls = AtomicInteger()
  @Volatile var linked = false
  @Volatile var tamperApk = false
  @Volatile private var approvedByTest = false

  /** Approves the device code being polled (a new code needs approving again). */
  fun approvePending() {
    approvedByTest = true
  }

  val designs: List<Design> =
    listOf(
      fakeDesign("aurora", "Aurora", 3, 2, "Analog face with a soft gradient sky."),
      fakeDesign("pulse", "Pulse", 1, 4, "Bold digital time with heart-rate ring."),
      fakeDesign("future", "Next Gen", 2, 5, "Needs a newer Watch Face Format than Wear OS 6."),
    )

  /** Designs anyone may list and download; `pulse` belongs to the linked demo account. */
  val publicIds: Set<String> = setOf("aurora", "future")

  val publicDesigns: List<Design>
    get() = designs.filter { it.id in publicIds }

  private fun apkBytes(id: String, version: Long) = "fake-apk:$id:$version".repeat(64).toByteArray()

  private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  private fun fakeDesign(id: String, title: String, version: Long, wff: Int, description: String) =
    Design(
      id = id,
      title = title,
      description = description,
      previewUrl = "https://$baseHost/previews/$id.png",
      version = version,
      versionName = "1.$version",
      wffVersion = wff,
      packageName = "${PushConstants.PACKAGE_PREFIX}$id",
      apkUrl = "https://$baseHost${PushConstants.API_PREFIX}/designs/$id/apk",
      sha256 = sha(apkBytes(id, version)),
      sizeBytes = apkBytes(id, version).size.toLong(),
      validationToken = "fake-validation-token-$id",
    )

  override fun intercept(chain: Interceptor.Chain): Response {
    val request = chain.request()
    if (request.url.host != baseHost) return chain.proceed(request)
    val path = request.url.encodedPath
    val api = PushConstants.API_PREFIX

    fun respond(code: Int, body: ByteArray = ByteArray(0), type: String = "text/plain") =
      Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("fake")
        .body(body.toResponseBody(type.toMediaType()))
        .build()

    fun ok(body: String) = respond(200, body.toByteArray(), "application/json")

    return when {
      request.method == "GET" && path.startsWith("/previews/") -> {
        val design = designs.firstOrNull { path == "/previews/${it.id}.png" }
        if (design == null) respond(404) else respond(200, previewPng(design.title), "image/png")
      }
      request.method == "POST" && path == "$api/device-codes" -> {
        polls.set(0)
        approvedByTest = false
        ok(
          json.encodeToString(
            DeviceCode(
              code = "AB7K-92QX",
              pollSecret = "fake-poll-secret",
              verificationUrl = "https://$baseHost/link?code=AB7K-92QX",
              expiresInSeconds = 600,
              pollIntervalSeconds = 1,
            ),
          ),
        )
      }
      request.method == "POST" && path.startsWith("$api/device-codes/") && path.endsWith("/poll") ->
        if (
          polls.incrementAndGet().let { autoApprove && it > approveAfterPolls } || approvedByTest
        ) {
          linked = true
          ok(json.encodeToString(PollResponse("approved", deviceToken, "demo@example.com")))
        } else ok(json.encodeToString(PollResponse("pending")))
      request.header("Authorization").let { it != null && it != "Bearer $deviceToken" } ->
        respond(401)
      request.method == "GET" && path == "$api/designs" -> {
        val anonymous = request.header("Authorization") == null
        val listed = if (anonymous) publicDesigns else designs
        ok(json.encodeToString(DesignList(listed + extraDesigns(chain))))
      }
      request.method == "GET" && path.startsWith("$api/designs/") && path.endsWith("/apk") -> {
        val design = designs.firstOrNull { path == "$api/designs/${it.id}/apk" }
        val anonymous = request.header("Authorization") == null
        if (design == null) respond(404)
        else if (anonymous && design.id !in publicIds) respond(401)
        else {
          val bytes = apkBytes(design.id, design.version)
          if (tamperApk) bytes[0] = (bytes[0] + 1).toByte()
          respond(200, bytes, "application/vnd.android.package-archive")
        }
      }
      request.header("Authorization") == null -> respond(401)
      request.method == "POST" && path == "$api/installs" -> {
        val buffer = okio.Buffer()
        request.body?.writeTo(buffer)
        installs += json.decodeFromString<InstallEvent>(buffer.readUtf8())
        respond(204)
      }
      else -> respond(404)
    }
  }

  /** The designs at [extraCatalogUrl], fetched for real (it is not on [baseHost]). */
  private fun extraDesigns(chain: Interceptor.Chain): List<Design> {
    val url = extraCatalogUrl ?: return emptyList()
    return try {
      chain.proceed(okhttp3.Request.Builder().url(url).build()).use { response ->
        if (!response.isSuccessful) emptyList()
        else json.decodeFromString<DesignList>(response.body.string()).designs
      }
    } catch (e: java.io.IOException) {
      emptyList()
    }
  }
}

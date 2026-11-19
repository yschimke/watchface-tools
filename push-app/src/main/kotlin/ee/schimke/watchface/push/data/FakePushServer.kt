package ee.schimke.watchface.push.data

import ee.schimke.watchface.push.PushConstants
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
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
      // Catalog faces come after the designs, as on the real server.
      fakeCatalogFace(
        CatalogInfo("wear-os-samples-watchfaces", "Wear OS samples"),
        previewId = "SimpleAnalog",
        title = "Simple Analog",
        face = "c_wear_os_samples_watchfaces_simpleanalog_0a1b2c3d4e",
      ),
    )

  /** Faces anyone may list and download; `pulse` belongs to the linked demo account. */
  val publicIds: Set<String> = setOf("aurora", "future", CATALOG_FACE_ID)

  val publicDesigns: List<Design>
    get() = designs.filter { it.id in publicIds }

  /** Faces the server is building ([startBuild]), listed to everyone after [designs]. */
  private val builds = java.util.concurrent.ConcurrentHashMap<String, Design>()

  /** Every design this server lists, downloads and previews, with the [builds] in progress. */
  private val all: List<Design>
    get() = designs + builds.values.sortedBy { it.id }

  /**
   * Lists a new public face [id] that the server is still building: no APK, token or checksum, just
   * [build]. [finishBuild] makes it installable, as a real server's build completing does.
   */
  fun startBuild(
    id: String,
    title: String,
    build: BuildStatus = BuildStatus(BuildStatus.BUILDING, stage = "packaging", version = 1),
  ): Design =
    fakeDesign(id, title, version = 1, wff = 1, description = "Being built on the server.")
      .copy(version = 0, apkUrl = null, sha256 = null, validationToken = null, build = build)
      .also { builds[id] = it }

  /** Completes [startBuild]'s build: version 1, downloadable, `build` state `built`. */
  fun finishBuild(id: String): Design {
    val pending = checkNotNull(builds[id]) { "no build of $id" }
    return fakeDesign(id, pending.title, version = 1, wff = 1, description = "Built.")
      .copy(build = BuildStatus(BuildStatus.BUILT, version = 1))
      .also { builds[id] = it }
  }

  private fun apkBytes(id: String, version: Long) = "fake-apk:$id:$version".repeat(64).toByteArray()

  private fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  /** A face from a public catalog: an id with a `/`, server-built URLs, attribution. */
  private fun fakeCatalogFace(
    catalog: CatalogInfo,
    previewId: String,
    title: String,
    face: String,
  ) =
    fakeDesign(
        "catalog:${catalog.system}/$previewId",
        title,
        version = 1,
        wff = 1,
        description = "From the ${catalog.title} catalog.",
        face = face,
        urlPath = "${PushConstants.API_PREFIX}/catalog-faces/${catalog.system}/$previewId",
      )
      .copy(
        source = Design.SOURCE_CATALOG,
        catalog = catalog,
        attribution =
          Attribution(
            repoUrl = "https://github.com/android/wear-os-samples",
            sourceUrl = "https://github.com/android/wear-os-samples/tree/main/WatchFaceFormat",
            license = "Apache-2.0",
          ),
        visibility = "public",
      )

  private fun fakeDesign(
    id: String,
    title: String,
    version: Long,
    wff: Int,
    description: String,
    face: String = id,
    urlPath: String = "${PushConstants.API_PREFIX}/designs/$id",
  ) =
    Design(
      id = id,
      title = title,
      description = description,
      previewUrl =
        if (face == id) "https://$baseHost/previews/$id.png"
        else "https://$baseHost$urlPath/preview",
      version = version,
      versionName = "1.$version",
      wffVersion = wff,
      packageName = "${PushConstants.PACKAGE_PREFIX}$face",
      apkUrl = "https://$baseHost$urlPath/apk?v=$version",
      sha256 = sha(apkBytes(id, version)),
      sizeBytes = apkBytes(id, version).size.toLong(),
      validationToken = "fake-validation-token-$face",
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
      request.method == "GET" && all.any { path == it.previewUrl?.toHttpUrl()?.encodedPath } -> {
        val design = all.firstOrNull { path == it.previewUrl?.toHttpUrl()?.encodedPath }
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
        // Like the real server: faces still building are listed only to apps that ask.
        val building =
          if (request.url.queryParameter("includePending") == "1") builds.values.sortedBy { it.id }
          else builds.values.filter { it.isInstallable }.sortedBy { it.id }
        ok(json.encodeToString(DesignList(listed + building + extraDesigns(chain))))
      }
      request.method == "GET" && path.endsWith("/apk") -> {
        // Matched on the listed apkUrl, never rebuilt from the id (catalog ids contain `/`).
        val design = all.firstOrNull { path == it.apkUrl?.toHttpUrl()?.encodedPath }
        val anonymous = request.header("Authorization") == null
        if (design == null) respond(404)
        else if (anonymous && design.id !in publicIds && !builds.containsKey(design.id))
          respond(401)
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

  companion object {
    /** The fake's catalog face, listed anonymously like the server's preview.coo.ee catalogs. */
    const val CATALOG_FACE_ID = "catalog:wear-os-samples-watchfaces/SimpleAnalog"
  }
}

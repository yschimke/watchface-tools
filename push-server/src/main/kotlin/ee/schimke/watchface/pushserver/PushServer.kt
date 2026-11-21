package ee.schimke.watchface.pushserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import ee.schimke.watchface.packager.AndroidSdk
import ee.schimke.watchface.packager.PackOutcome
import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagerOptions
import ee.schimke.watchface.packager.PackagerReport
import ee.schimke.watchface.packager.SigningConfig
import ee.schimke.watchface.packager.WatchFacePackager
import ee.schimke.watchface.packager.WffEstimate
import ee.schimke.watchface.wff.WffXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** Abstraction over [WatchFacePackager] so unit tests can exercise HTTP routing without an SDK. */
interface WatchFacePackagerEngine {
  fun sdkAvailable(): Boolean

  fun estimate(bundle: Map<String, ByteArray>): PackagerReport = WffEstimate.report(bundle)

  fun validate(bundle: Map<String, ByteArray>, options: PackagerOptions): PackagerReport

  fun packWithReport(bundle: Map<String, ByteArray>, options: PackagerOptions): PackOutcome
}

class DefaultWatchFacePackagerEngine(
  private val sdkLocator: () -> AndroidSdk? = { AndroidSdk.locate() },
) : WatchFacePackagerEngine {
  override fun sdkAvailable(): Boolean = sdkLocator() != null

  override fun validate(bundle: Map<String, ByteArray>, options: PackagerOptions): PackagerReport =
    WatchFacePackager(sdk = sdkLocator()).validate(bundle, options)

  override fun packWithReport(
    bundle: Map<String, ByteArray>,
    options: PackagerOptions,
  ): PackOutcome = WatchFacePackager(sdk = sdkLocator()).packWithReport(bundle, options)
}

/**
 * Standalone HTTP server wrapping `:wff-packager` (`validate`, `pack`, `estimate`).
 *
 * Isolates Google's `validator-push` (and its bundled `xercesImpl`), the Android SDK (`aapt2`,
 * `zipalign`, `android.jar`), and `WFF_SIGNING_*` marketplace secrets into an independently
 * deployable container.
 */
class PushServer(
  host: String = "0.0.0.0",
  port: Int = 8080,
  private val signing: PushServerSigning = PushServerSigning.fromEnvironment(),
  private val debugKeyDirectory: File = SigningConfig.defaultDebugKeyDirectory(),
  private val engine: WatchFacePackagerEngine = DefaultWatchFacePackagerEngine(),
  private val version: String = PushServer::class.java.`package`?.implementationVersion ?: "dev",
  private val maxBodyBytes: Int = DEFAULT_MAX_BODY_BYTES,
  private val maxUncompressedBytes: Int = DEFAULT_MAX_UNCOMPRESSED_BYTES,
  private val maxZipEntries: Int = DEFAULT_MAX_ZIP_ENTRIES,
  concurrency: Int = DEFAULT_CONCURRENCY,
) : Closeable {
  private val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
  private val server: HttpServer =
    HttpServer.create(InetSocketAddress(host, port), 0).apply {
      this.executor = this@PushServer.executor
      createContext("/healthz") { handleHealthz(it) }
      createContext("/readyz") { handleReadyz(it) }
      createContext("/v1/estimate") { handleEstimate(it) }
      createContext("/v1/validate") { handleValidate(it) }
      createContext("/v1/pack") { handlePack(it) }
    }

  val boundPort: Int
    get() = server.address.port

  fun start() {
    server.start()
  }

  override fun close() {
    server.stop(2)
    executor.shutdownNow()
  }

  private fun handleHealthz(exchange: HttpExchange) {
    if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
      sendJsonError(exchange, 405, "method_not_allowed", "use GET")
      return
    }
    sendBytes(
      exchange,
      200,
      "text/plain; charset=utf-8",
      "ok\n".toByteArray(StandardCharsets.UTF_8),
    )
  }

  private fun handleReadyz(exchange: HttpExchange) {
    if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
      sendJsonError(exchange, 405, "method_not_allowed", "use GET")
      return
    }
    val sdkOk = engine.sdkAvailable()
    val signingConfigured = signing is PushServerSigning.Configured
    val certSha = (signing as? PushServerSigning.Configured)?.certificateSha256
    val signingError =
      when (signing) {
        is PushServerSigning.Configured -> null
        is PushServerSigning.Unconfigured -> signing.reason
        is PushServerSigning.Invalid -> signing.reason
      }
    val status = if (sdkOk) 200 else 503
    val body = buildString {
      append('{')
      append("\"ready\":").append(sdkOk).append(',')
      append("\"sdkAvailable\":").append(sdkOk).append(',')
      append("\"signingConfigured\":").append(signingConfigured).append(',')
      append("\"signerCertificateSha256\":").append(jsonStringOrNull(certSha)).append(',')
      append("\"signingError\":").append(jsonStringOrNull(signingError)).append(',')
      append("\"version\":").append(jsonString(version))
      append("}\n")
    }
    sendBytes(
      exchange,
      status,
      "application/json; charset=utf-8",
      body.toByteArray(StandardCharsets.UTF_8),
    )
  }

  private fun handleEstimate(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") {
      sendJsonError(exchange, 405, "method_not_allowed", "use POST")
      return
    }
    val bundle = readBundleOrRespondError(exchange) ?: return
    try {
      val report = engine.estimate(bundle)
      sendBytes(
        exchange,
        200,
        "application/json; charset=utf-8",
        (report.toJson() + "\n").toByteArray(StandardCharsets.UTF_8),
      )
    } catch (e: Exception) {
      sendJsonError(exchange, 500, "estimate_failed", e.message ?: "estimate failed")
    }
  }

  private fun handleValidate(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") {
      sendJsonError(exchange, 405, "method_not_allowed", "use POST")
      return
    }
    if (!engine.sdkAvailable()) {
      sendJsonError(
        exchange,
        503,
        "android_sdk_missing",
        "Android SDK is not available on this push-server: ${AndroidSdk.INSTALL_HINT}",
      )
      return
    }
    val params = parseQuery(exchange.requestURI.rawQuery)
    val versionCode =
      parseOptionalInt(params["versionCode"], "versionCode", default = 1)
        ?: return sendJsonError(exchange, 400, "bad_request", "versionCode must be an integer")
    val bundle = readBundleOrRespondError(exchange) ?: return
    val marketplace =
      params["pushMarketplace"]?.takeIf { it.isNotBlank() } ?: inferredMarketplace(bundle)
    val target =
      try {
        resolveTarget(params, marketplace, bundle)
      } catch (e: IllegalArgumentException) {
        sendJsonError(exchange, 400, "bad_request", e.message ?: "invalid target")
        return
      }
    val options =
      PackagerOptions(
        target = target,
        versionCode = versionCode,
        signing = SigningConfig.GeneratedDebugKey(debugKeyDirectory),
      )
    try {
      val report = engine.validate(bundle, options)
      sendBytes(
        exchange,
        200,
        "application/json; charset=utf-8",
        (report.toJson() + "\n").toByteArray(StandardCharsets.UTF_8),
      )
    } catch (e: Exception) {
      sendJsonError(exchange, 500, "validate_failed", e.message ?: "validation failed")
    }
  }

  private fun handlePack(exchange: HttpExchange) {
    if (exchange.requestMethod != "POST") {
      sendJsonError(exchange, 405, "method_not_allowed", "use POST")
      return
    }
    if (!engine.sdkAvailable()) {
      sendJsonError(
        exchange,
        503,
        "android_sdk_missing",
        "Android SDK is not available on this push-server: ${AndroidSdk.INSTALL_HINT}",
      )
      return
    }
    val params = parseQuery(exchange.requestURI.rawQuery)
    val versionCode =
      parseOptionalInt(params["versionCode"], "versionCode", default = 1)
        ?: return sendJsonError(exchange, 400, "bad_request", "versionCode must be an integer")
    val previousVersionCode =
      if ("previousVersionCode" in params && !params["previousVersionCode"].isNullOrBlank()) {
        params["previousVersionCode"]?.toIntOrNull()
          ?: return sendJsonError(
            exchange,
            400,
            "bad_request",
            "previousVersionCode must be an integer",
          )
      } else null
    val signWithEnv =
      when (params["signWithEnv"]?.lowercase()) {
        null,
        "",
        "false" -> false
        "true" -> true
        else ->
          return sendJsonError(exchange, 400, "bad_request", "signWithEnv must be true or false")
      }
    val (signingConfig, expectedCert) =
      if (signWithEnv) {
        when (signing) {
          is PushServerSigning.Configured ->
            signing.signingConfig to signing.expectedCertificateSha256
          is PushServerSigning.Unconfigured -> {
            sendJsonError(exchange, 503, "signing_key_unavailable", signing.reason)
            return
          }
          is PushServerSigning.Invalid -> {
            sendJsonError(exchange, 503, "signing_key_unavailable", signing.reason)
            return
          }
        }
      } else {
        SigningConfig.GeneratedDebugKey(debugKeyDirectory) to null
      }

    val bundle = readBundleOrRespondError(exchange) ?: return
    val marketplace = params["pushMarketplace"]?.takeIf { it.isNotBlank() }
    val target =
      try {
        resolveTarget(params, marketplace, bundle)
      } catch (e: IllegalArgumentException) {
        sendJsonError(exchange, 400, "bad_request", e.message ?: "invalid target")
        return
      }

    val options =
      PackagerOptions(
        target = target,
        versionCode = versionCode,
        signing = signingConfig,
        previousVersionCode = previousVersionCode,
        expectedCertificateSha256 = expectedCert,
      )
    try {
      val outcome = engine.packWithReport(bundle, options)
      val packaged = outcome.packaged
      if (packaged == null) {
        val diagJson = outcome.diagnostics.joinToString(",") { jsonString(it.toString()) }
        val body = "{\"diagnostics\":[$diagJson],\"report\":${outcome.report.toJson()}}\n"
        sendBytes(
          exchange,
          422,
          "application/json; charset=utf-8",
          body.toByteArray(StandardCharsets.UTF_8),
        )
        return
      }
      exchange.responseHeaders.set("X-Package-Name", packaged.packageName)
      exchange.responseHeaders.set("X-Version-Code", packaged.versionCode.toString())
      exchange.responseHeaders.set("X-Wff-Version", packaged.wffVersion.number.toString())
      exchange.responseHeaders.set("X-Signer-Certificate-Sha256", packaged.signerCertificateSha256)
      packaged.validationToken?.let { exchange.responseHeaders.set("X-Validation-Token", it) }
      sendBytes(exchange, 200, "application/vnd.android.package-archive", packaged.apk)
    } catch (e: Exception) {
      sendJsonError(exchange, 500, "pack_failed", e.message ?: "packaging failed")
    }
  }

  private fun readBundleOrRespondError(exchange: HttpExchange): Map<String, ByteArray>? {
    val zipBytes =
      try {
        readBounded(exchange.requestBody, maxBodyBytes)
      } catch (e: PayloadTooLargeException) {
        sendJsonError(exchange, 413, "payload_too_large", e.message ?: "payload too large")
        return null
      } catch (e: Exception) {
        sendJsonError(exchange, 400, "bad_request", "cannot read request body: ${e.message}")
        return null
      }
    if (zipBytes.isEmpty()) {
      sendJsonError(exchange, 400, "bad_request", "request body must be a ZIP of the WFF bundle")
      return null
    }
    return try {
      readZipBundle(zipBytes, maxUncompressedBytes, maxZipEntries)
    } catch (e: PayloadTooLargeException) {
      sendJsonError(
        exchange,
        413,
        "payload_too_large",
        e.message ?: "uncompressed bundle too large",
      )
      null
    } catch (e: IllegalArgumentException) {
      sendJsonError(exchange, 400, "bad_request", e.message ?: "invalid bundle ZIP")
      null
    } catch (e: java.io.IOException) {
      sendJsonError(
        exchange,
        400,
        "bad_request",
        "invalid bundle ZIP: ${e.message ?: e.javaClass.simpleName}",
      )
      null
    }
  }

  private fun resolveTarget(
    params: Map<String, String>,
    marketplace: String?,
    bundle: Map<String, ByteArray>,
  ): PackageTarget =
    if (marketplace != null) {
      val face =
        params["faceName"]?.takeIf { it.isNotBlank() }
          ?: defaultFaceName(bundle)
          ?: throw IllegalArgumentException("faceName is required (bundle has no package)")
      PackageTarget.WatchFacePush(marketplace, face)
    } else {
      PackageTarget.Sideload(params["package"]?.takeIf { it.isNotBlank() })
    }

  private fun sendJsonError(exchange: HttpExchange, status: Int, code: String, error: String) {
    val body = "{\"code\":${jsonString(code)},\"error\":${jsonString(error)}}\n"
    sendBytes(
      exchange,
      status,
      "application/json; charset=utf-8",
      body.toByteArray(StandardCharsets.UTF_8),
    )
  }

  private fun sendBytes(
    exchange: HttpExchange,
    status: Int,
    contentType: String,
    body: ByteArray,
  ) {
    exchange.responseHeaders.set("Content-Type", contentType)
    if (exchange.requestMethod == "HEAD") {
      exchange.sendResponseHeaders(status, -1)
      exchange.close()
      return
    }
    exchange.sendResponseHeaders(status, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
  }

  companion object {
    const val DEFAULT_MAX_BODY_BYTES: Int = 32 * 1024 * 1024
    const val DEFAULT_MAX_UNCOMPRESSED_BYTES: Int = 64 * 1024 * 1024
    const val DEFAULT_MAX_ZIP_ENTRIES: Int = 1000
    const val DEFAULT_CONCURRENCY: Int = 2

    internal fun parseQuery(rawQuery: String?): Map<String, String> {
      if (rawQuery.isNullOrBlank()) return emptyMap()
      val out = LinkedHashMap<String, String>()
      for (part in rawQuery.split('&')) {
        if (part.isEmpty()) continue
        val idx = part.indexOf('=')
        val key =
          URLDecoder.decode(if (idx >= 0) part.substring(0, idx) else part, StandardCharsets.UTF_8)
        val value =
          if (idx >= 0) URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8) else ""
        out[key] = value
      }
      return out
    }

    internal fun readZipBundle(
      zipBytes: ByteArray,
      maxUncompressedBytes: Int = DEFAULT_MAX_UNCOMPRESSED_BYTES,
      maxEntries: Int = DEFAULT_MAX_ZIP_ENTRIES,
    ): Map<String, ByteArray> {
      val files = LinkedHashMap<String, ByteArray>()
      var totalEntries = 0
      var totalBytes = 0
      ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
          if (++totalEntries > maxEntries) {
            throw PayloadTooLargeException("ZIP bundle exceeds $maxEntries entries")
          }
          if (entry.isDirectory) {
            if (zis.read() != -1) {
              throw IllegalArgumentException(
                "ZIP directory entry must not contain data: ${entry.name}",
              )
            }
          } else {
            val name = normalizeZipPath(entry.name)
            val data = readBounded(zis, maxUncompressedBytes - totalBytes)
            totalBytes += data.size
            files[name] = data
          }
          zis.closeEntry()
          entry = zis.nextEntry
        }
      }
      require(files.isNotEmpty()) { "ZIP bundle is empty" }
      return files.toSortedMap()
    }

    private fun normalizeZipPath(raw: String): String {
      val normalized = raw.replace('\\', '/').trimStart('/')
      require(normalized.isNotEmpty()) { "ZIP entry has empty path" }
      require(!raw.startsWith("/") && !raw.startsWith("\\")) {
        "ZIP entry path must be relative: $raw"
      }
      val segments = normalized.split('/')
      require(segments.none { it.isEmpty() || it == "." || it == ".." }) {
        "ZIP entry path escapes bundle: $raw"
      }
      return normalized
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
      val out = ByteArrayOutputStream()
      val buf = ByteArray(16 * 1024)
      var total = 0
      while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) {
          throw PayloadTooLargeException("payload exceeds maximum size of $maxBytes bytes")
        }
        out.write(buf, 0, n)
      }
      return out.toByteArray()
    }

    private fun parseOptionalInt(value: String?, name: String, default: Int): Int? =
      if (value.isNullOrBlank()) default else value.toIntOrNull()

    private fun inferredMarketplace(bundle: Map<String, ByteArray>): String? =
      bundlePackage(bundle)?.substringBefore(".watchfacepush.", "")?.takeIf(String::isNotEmpty)

    private fun bundlePackage(bundle: Map<String, ByteArray>): String? =
      bundle["AndroidManifest.xml"]
        ?.let { WffXmlParser.parseOrNull(it.decodeToString()) }
        ?.root
        ?.attr("package")

    private fun defaultFaceName(bundle: Map<String, ByteArray>): String? =
      bundlePackage(bundle)?.substringAfterLast('.')?.replace(Regex("[^A-Za-z0-9_]"), "_")

    internal fun jsonStringOrNull(value: String?): String =
      if (value == null) "null" else jsonString(value)

    internal fun jsonString(value: String): String =
      buildString(value.length + 2) {
        append('"')
        for (c in value) {
          when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
          }
        }
        append('"')
      }
  }
}

internal class PayloadTooLargeException(message: String) : RuntimeException(message)

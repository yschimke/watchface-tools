package ee.schimke.watchface.pushserver

import ee.schimke.watchface.packager.DiagnosticSeverity
import ee.schimke.watchface.packager.PackOutcome
import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagedWatchFace
import ee.schimke.watchface.packager.PackagerOptions
import ee.schimke.watchface.packager.PackagerReport
import ee.schimke.watchface.packager.PackagingDiagnostic
import ee.schimke.watchface.packager.SigningConfig
import ee.schimke.watchface.wff.WffVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PushServerTest {
  private val client: HttpClient = HttpClient.newHttpClient()

  @Test
  fun healthzAndReadyzReportSdkAndSigningState() {
    val engine = FakePackagerEngine(sdkReady = true)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("none"),
        engine = engine,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"

        val health =
          client.send(
            HttpRequest.newBuilder(URI("$base/healthz")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(200, health.statusCode())
        assertEquals("ok\n", health.body())

        val ready =
          client.send(
            HttpRequest.newBuilder(URI("$base/readyz")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(200, ready.statusCode())
        assertTrue(ready.body().contains("\"ready\":true"))
        assertTrue(ready.body().contains("\"sdkAvailable\":true"))
        assertTrue(ready.body().contains("\"signingConfigured\":false"))
      }

    val noSdk = FakePackagerEngine(sdkReady = false)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("none"),
        engine = noSdk,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"
        val ready =
          client.send(
            HttpRequest.newBuilder(URI("$base/readyz")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(503, ready.statusCode())
        assertTrue(ready.body().contains("\"ready\":false"))
      }
  }

  @Test
  fun estimateReturnsJsonReportWithoutSdk() {
    val engine = FakePackagerEngine(sdkReady = false)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("none"),
        engine = engine,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"
        val zip = zipOf(minimalBundle())
        val resp =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/estimate"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(200, resp.statusCode())
        assertTrue(resp.body().contains("\"schemaVersion\":1"))
        assertTrue(resp.body().contains("\"mode\":\"estimate\""))
      }
  }

  @Test
  fun validatePassesOptionsAndReturns200EvenWhenReportFails() {
    val engine = FakePackagerEngine(sdkReady = true, validatePassed = false)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("none"),
        engine = engine,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"
        val zip = zipOf(minimalBundle())
        val resp =
          client.send(
            HttpRequest.newBuilder(
                URI(
                  "$base/v1/validate?pushMarketplace=ee.schimke.watchface.push&faceName=demo&versionCode=7",
                ),
              )
              .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(200, resp.statusCode())
        assertTrue(resp.body().contains("\"passed\":false"))
        val opts = engine.lastValidateOptions!!
        assertEquals(7, opts.versionCode)
        val target = assertIs<PackageTarget.WatchFacePush>(opts.target)
        assertEquals("ee.schimke.watchface.push", target.marketplacePackage)
        assertEquals("demo", target.faceName)
      }
  }

  @Test
  fun packReturnsApkAndHeadersForPushAndSideload() {
    val keystore = generateTestKeystore()
    val signing =
      PushServerSigning.fromEnvironment(
        mapOf(
          PushServerSigning.ENV_KEYSTORE_B64 to
            Base64.getEncoder().encodeToString(keystore.readBytes()),
          PushServerSigning.ENV_KEYSTORE_PASSWORD to "changeit",
        ),
      )
    val configured = assertIs<PushServerSigning.Configured>(signing)
    assertEquals(64, configured.certificateSha256.length)

    val fakeApk = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x01, 0x02)
    val engine = FakePackagerEngine(sdkReady = true, packApk = fakeApk, validationToken = "tok-123")
    PushServer(host = "127.0.0.1", port = 0, signing = configured, engine = engine).use { server ->
      server.start()
      val base = "http://127.0.0.1:${server.boundPort}"
      val zip = zipOf(minimalBundle())

      // 1. Watch Face Push signed build
      val pushResp =
        client.send(
          HttpRequest.newBuilder(
              URI(
                "$base/v1/pack?pushMarketplace=ee.schimke.watchface.push&faceName=sunset&versionCode=3&signWithEnv=true",
              ),
            )
            .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )
      assertEquals(200, pushResp.statusCode())
      assertContentEquals(fakeApk, pushResp.body())
      assertEquals("tok-123", pushResp.headers().firstValue("X-Validation-Token").orElse(null))
      assertEquals(
        "ee.schimke.watchface.push.watchfacepush.sunset",
        pushResp.headers().firstValue("X-Package-Name").orElse(null),
      )
      assertEquals("3", pushResp.headers().firstValue("X-Version-Code").orElse(null))
      assertIs<SigningConfig.InMemoryKeystore>(engine.lastPackOptions!!.signing)

      // 2. Sideload debug build
      val debugResp =
        client.send(
          HttpRequest.newBuilder(URI("$base/v1/pack?package=com.example.preview&versionCode=1"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
            .build(),
          HttpResponse.BodyHandlers.ofByteArray(),
        )
      assertEquals(200, debugResp.statusCode())
      assertIs<SigningConfig.GeneratedDebugKey>(engine.lastPackOptions!!.signing)
    }
  }

  @Test
  fun packReturns422OnPackagingFailureAnd503WhenSigningMissing() {
    val engine = FakePackagerEngine(sdkReady = true, packFail = true)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("missing key"),
        engine = engine,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"
        val zip = zipOf(minimalBundle())

        val missingKey =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/pack?signWithEnv=true"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(503, missingKey.statusCode())
        assertTrue(missingKey.body().contains("signing_key_unavailable"))

        val failed =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/pack?package=com.example.fail"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(zip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(422, failed.statusCode())
        assertTrue(failed.body().contains("bad face"))
        assertTrue(failed.body().contains("\"report\":"))
      }
  }

  @Test
  fun zipRejectsPathTraversalAndOversizedBody() {
    val engine = FakePackagerEngine(sdkReady = true)
    PushServer(
        host = "127.0.0.1",
        port = 0,
        signing = PushServerSigning.Unconfigured("none"),
        engine = engine,
        maxBodyBytes = 1024,
      )
      .use { server ->
        server.start()
        val base = "http://127.0.0.1:${server.boundPort}"

        val traversalZip = zipOf(mapOf("../escape.txt" to "nope".encodeToByteArray()))
        val traversalResp =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/estimate"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(traversalZip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(400, traversalResp.statusCode())

        val tooBig = ByteArray(2048) { 1 }
        val bigResp =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/estimate"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(tooBig))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(413, bigResp.statusCode())

        val validZip = zipOf(minimalBundle())
        val truncatedZip = validZip.copyOf(validZip.size / 2)
        val truncatedResp =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/estimate"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(truncatedZip))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(400, truncatedResp.statusCode())
        assertTrue(truncatedResp.body().contains("invalid bundle ZIP"))

        val dirWithPayload = zipOf(mapOf("res/" to "bomb".encodeToByteArray()))
        val dirResp =
          client.send(
            HttpRequest.newBuilder(URI("$base/v1/estimate"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(dirWithPayload))
              .build(),
            HttpResponse.BodyHandlers.ofString(),
          )
        assertEquals(400, dirResp.statusCode())
        assertTrue(dirResp.body().contains("directory entry must not contain data"))
      }
  }

  @Test
  fun signingRejectsMismatchedCertificatePinAndReadsFileSecret() {
    val keystore = generateTestKeystore()
    val passFile =
      Files.createTempFile("wff-pass", ".txt").toFile().apply {
        writeText("changeit\n")
        deleteOnExit()
      }
    val ok =
      PushServerSigning.fromEnvironment(
        mapOf(
          PushServerSigning.ENV_KEYSTORE_PATH to keystore.absolutePath,
          "${PushServerSigning.ENV_KEYSTORE_PASSWORD}_FILE" to passFile.absolutePath,
        ),
      )
    assertIs<PushServerSigning.Configured>(ok)

    val badPin =
      PushServerSigning.fromEnvironment(
        mapOf(
          PushServerSigning.ENV_KEYSTORE_PATH to keystore.absolutePath,
          PushServerSigning.ENV_KEYSTORE_PASSWORD to "changeit",
          PushServerSigning.ENV_CERT_SHA256 to "0".repeat(64),
        ),
      )
    assertIs<PushServerSigning.Invalid>(badPin)
  }

  private fun minimalBundle(): Map<String, ByteArray> =
    mapOf(
      "AndroidManifest.xml" to
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="com.example.minimal">
            <application android:label="Minimal">
                <property android:name="com.google.wear.watchface.format.version" android:value="4" />
            </application>
        </manifest>
        """
          .trimIndent()
          .encodeToByteArray(),
      "res/raw/watchface.xml" to
        """
        <WatchFace width="450" height="450">
            <Scene backgroundColor="#ff000000" />
        </WatchFace>
        """
          .trimIndent()
          .encodeToByteArray(),
    )

  private fun zipOf(files: Map<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zos ->
      for ((path, bytes) in files) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(bytes)
        zos.closeEntry()
      }
    }
    return out.toByteArray()
  }

  private fun generateTestKeystore(): File {
    val file = Files.createTempFile("wff-test-ks", ".p12").toFile()
    file.delete()
    file.deleteOnExit()
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val proc =
      ProcessBuilder(
          keytool,
          "-genkeypair",
          "-keystore",
          file.absolutePath,
          "-storetype",
          "PKCS12",
          "-storepass",
          "changeit",
          "-keypass",
          "changeit",
          "-alias",
          "test",
          "-keyalg",
          "RSA",
          "-keysize",
          "2048",
          "-validity",
          "365",
          "-dname",
          "CN=Test",
        )
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    assertEquals(0, proc.waitFor(), "keytool failed: $output")
    return file
  }

  private class FakePackagerEngine(
    private val sdkReady: Boolean = true,
    private val validatePassed: Boolean = true,
    private val packApk: ByteArray = byteArrayOf(1, 2, 3),
    private val validationToken: String? = null,
    private val packFail: Boolean = false,
  ) : WatchFacePackagerEngine {
    var lastValidateOptions: PackagerOptions? = null
    var lastPackOptions: PackagerOptions? = null

    override fun sdkAvailable(): Boolean = sdkReady

    override fun validate(
      bundle: Map<String, ByteArray>,
      options: PackagerOptions,
    ): PackagerReport {
      lastValidateOptions = options
      return PackagerReport(
        wffVersion = 4,
        minimumWffVersion = 1,
        checks =
          listOf(
            ee.schimke.watchface.packager.ReportCheck(
              id = "wff-core",
              title = "WFF schema",
              status =
                if (validatePassed) ee.schimke.watchface.packager.CheckStatus.PASSED
                else ee.schimke.watchface.packager.CheckStatus.FAILED,
              findings = emptyList(),
            ),
          ),
      )
    }

    override fun packWithReport(
      bundle: Map<String, ByteArray>,
      options: PackagerOptions,
    ): PackOutcome {
      lastPackOptions = options
      val report = validate(bundle, options)
      if (packFail) {
        return PackOutcome(
          packaged = null,
          report = report,
          diagnostics = listOf(PackagingDiagnostic(DiagnosticSeverity.ERROR, "test", "bad face")),
        )
      }
      val pkg =
        when (val t = options.target) {
          is PackageTarget.WatchFacePush -> "${t.marketplacePackage}.watchfacepush.${t.faceName}"
          is PackageTarget.Sideload -> t.packageName ?: "com.example.sideload"
        }
      return PackOutcome(
        packaged =
          PackagedWatchFace(
            apk = packApk,
            packageName = pkg,
            versionCode = options.versionCode,
            wffVersion = WffVersion.V4,
            memoryFootprint = null,
            validationToken = validationToken,
            diagnostics = emptyList(),
            signerCertificateSha256 = "a".repeat(64),
          ),
        report = report,
        diagnostics = emptyList(),
      )
    }
  }
}

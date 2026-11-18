package ee.schimke.watchface.packager

import com.android.apksig.ApkVerifier
import ee.schimke.watchface.apk.JcaApkSigner
import ee.schimke.watchface.apk.WffApk
import ee.schimke.watchface.apk.WffApkBuilder
import ee.schimke.watchface.apk.WffApkOptions
import ee.schimke.watchface.apk.buildBlocking
import ee.schimke.watchface.apk.generateDebugApkSignerBlocking
import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The pure-Kotlin APK ([WffApkBuilder], the path that runs in the browser) held against every tool
 * `wff-packager` trusts: apksig, Google's Watch Face Push, WFF and memory-footprint validators, and
 * (with an Android SDK) aapt2's own reading of the same APK next to the aapt2-built one.
 */
class PureApkTest {
  @TempDir lateinit var tmp: File

  private fun pure(
    name: String,
    versionCode: Int = 42,
    signer: ee.schimke.watchface.apk.ApkSigner = generateDebugApkSignerBlocking(),
  ): WffApk =
    WffApkBuilder.buildBlocking(
      TestSupport.sampleBundle(name),
      WffApkOptions(
        PackageTarget.WatchFacePush("com.example.market", name.lowercase()),
        versionCode,
      ),
      signer,
    )

  private fun write(name: String, bytes: ByteArray) = File(tmp, name).also { it.writeBytes(bytes) }

  private fun verify(apk: File, minSdk: Int): ApkVerifier.Result =
    ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(minSdk).build().verify()

  private fun ApkVerifier.Result.describe(): String =
    "errors=$errors warnings=$warnings v2=${v2SchemeSigners.map { it.errors }} v3=${v3SchemeSigners.map { it.errors }} " +
      "v1=$isVerifiedUsingV1Scheme v2ok=$isVerifiedUsingV2Scheme v3ok=$isVerifiedUsingV3Scheme"

  @Test
  fun everySampleVerifiesWithApksigUnderBothSchemes() {
    for (name in TestSupport.sampleNames) {
      val out = pure(name)
      val result = verify(write("$name.apk", out.apk), WffBundleLayout.minSdkFor(out.wffVersion))
      assertTrue(result.isVerified, "$name: ${result.describe()}")
      assertTrue(result.isVerifiedUsingV3Scheme, "$name v3")
      assertEquals(emptyList(), result.errors.map { it.toString() }, name)
      assertEquals(
        out.signerCertificateSha256,
        java.security.MessageDigest.getInstance("SHA-256")
          .digest(result.signerCertificates.single().encoded)
          .joinToString("") { "%02x".format(it) },
      )
    }
  }

  @Test
  fun theV2BlockAloneVerifiesToo() {
    for (name in TestSupport.sampleNames) {
      val out =
        WffApkBuilder.buildBlocking(
          TestSupport.sampleBundle(name),
          WffApkOptions(
            PackageTarget.WatchFacePush("com.example.market", name.lowercase()),
            1,
            signV3 = false,
          ),
          generateDebugApkSignerBlocking(),
        )
      val result = verify(write("v2-$name.apk", out.apk), WffBundleLayout.minSdkFor(out.wffVersion))
      assertTrue(result.isVerified && result.isVerifiedUsingV2Scheme, "$name: ${result.describe()}")
      assertTrue(!result.isVerifiedUsingV3Scheme)
    }
  }

  @Test
  fun signsWithAnRsaKeystoreKeyToo() {
    val ks = File(tmp, "rsa.p12")
    run(
      "test",
      File(System.getProperty("java.home"), "bin/keytool").path,
      "-genkeypair",
      "-keystore",
      ks.path,
      "-storetype",
      "PKCS12",
      "-storepass",
      "secret1",
      "-keypass",
      "secret1",
      "-alias",
      "rel",
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "30",
      "-dname",
      "CN=release",
    )
    val key = loadSigningKey(SigningConfig.Keystore(ks, "secret1", "rel"))
    val out = pure("SimpleDigital", signer = JcaApkSigner(key.key, key.chain))
    val result = verify(write("rsa.apk", out.apk), WffBundleLayout.minSdkFor(out.wffVersion))
    assertTrue(result.isVerified, result.describe())
    assertTrue(result.isVerifiedUsingV3Scheme, result.describe())
    assertEquals(certificateSha256(key.chain.first()), out.signerCertificateSha256)
  }

  @Test
  fun everySampleIsAcceptedByGooglesValidatorsAndGetsAToken() {
    val validators = Validators.google()
    for (name in TestSupport.sampleNames) {
      val out = pure(name)
      val file = write("$name.apk", out.apk)
      val push = validators.push.validate(file, "com.example.market")
      assertEquals(emptyList(), push.failures.map { it.toString() }, "$name push validator")
      assertTrue(!push.validationToken.isNullOrBlank(), "$name: no validation token")
      val xml =
        ZipFile(file).use { z ->
          z.getInputStream(z.getEntry("res/raw/watchface.xml")).readBytes().decodeToString()
        }
      assertEquals(emptyList(), validators.format.validate(xml, out.wffVersion), "$name WFF XSD")
      val memory = validators.memory.evaluate(file, out.wffVersion)
      assertEquals(null, memory.limitFailure, name)
    }
  }

  @Test
  fun sameStructuralRulesAsTheAapt2Packager() {
    val entries =
      ZipFile(write("s.apk", pure("SimpleDigital").apk)).use { z ->
        z.entries().toList().map { it.name }
      }
    assertEquals(emptyList(), PushRules.checkApkEntries(entries))
  }

  // ---- against aapt2 -----------------------------------------------------------------------

  private fun aapt2(sdk: AndroidSdk, vararg args: String): String {
    val p = ProcessBuilder(sdk.aapt2.path, *args).redirectErrorStream(true).start()
    return p.inputStream.bufferedReader().readText().also { p.waitFor() }
  }

  /** What legitimately differs: aapt2 stamps compile/platform versions and source line numbers. */
  private fun normalise(dump: String): List<String> =
    dump
      .lines()
      .filterNot { Regex("A: (platformBuildVersion|[^ ]*compileSdkVersion)").containsMatchIn(it) }
      .map {
        it
          .replace(Regex(" \\(line=\\d+\\)"), "")
          .replace(
            Regex(
              " (compileSdkVersion|compileSdkVersionCodename|platformBuildVersionName|platformBuildVersionCode)='[^']*'",
            ),
            "",
          )
      }
      .filter { it.isNotBlank() }

  @Test
  fun aapt2ReadsThePureApkLikeItsOwn() {
    val sdk = TestSupport.requireSdk()
    val packager = WatchFacePackager(sdk, TestSupport.fakeValidators())
    for (name in TestSupport.sampleNames) {
      val bundle = TestSupport.sampleBundle(name)
      val theirs =
        packager.pack(
          bundle,
          TestSupport.pushOptions(versionCode = 42, keyDir = tmp, face = name.lowercase()),
        )
      val ours = pure(name)
      val a = write("aapt2-$name.apk", theirs.apk)
      val b = write("pure-$name.apk", ours.apk)

      assertEquals(theirs.packageName, ours.packageName)
      for (what in listOf("AndroidManifest.xml", "res/xml/watch_face_info.xml")) {
        assertEquals(
          normalise(aapt2(sdk, "dump", "xmltree", a.path, "--file", what)),
          normalise(aapt2(sdk, "dump", "xmltree", b.path, "--file", what)),
          "$name xmltree $what",
        )
      }
      assertEquals(
        normalise(aapt2(sdk, "dump", "resources", a.path)),
        normalise(aapt2(sdk, "dump", "resources", b.path)),
        "$name resource table",
      )
      assertEquals(
        normalise(aapt2(sdk, "dump", "badging", a.path)),
        normalise(aapt2(sdk, "dump", "badging", b.path)),
        "$name badging",
      )
      // The same files, byte for byte where nothing is recompiled.
      ZipFile(a).use { za ->
        ZipFile(b).use { zb ->
          // aapt2 re-encodes PNGs (crunching), so only the raw XML is byte-identical.
          for (path in listOf("res/raw/watchface.xml")) {
            val ea = za.getEntry(path)
            assertNotNull(ea, "$name aapt2 has $path")
            assertEquals(
              za.getInputStream(ea).readBytes().toList(),
              zb.getInputStream(zb.getEntry(path)).readBytes().toList(),
              "$name $path",
            )
          }
          assertEquals(
            za.entries().toList().map { it.name }.toSortedSet(),
            zb.entries().toList().map { it.name }.toSortedSet(),
            "$name entry sets",
          )
        }
      }
      assertTrue(WffBundleLayout.minSdkFor(ours.wffVersion) >= PushRules.MIN_SDK)
    }
  }

  /**
   * An APK built in a browser (Kotlin/Wasm + WebCrypto) held to the same bar. The wasm test
   * `publishesTheBrowserBuiltApkForExternalChecks` leaves its bytes in `__wffApkHex`; point
   * `WFF_BROWSER_APK` at them written to a file (marketplace `com.example.market`). Skipped without
   * it.
   */
  @Test
  fun anApkBuiltInTheBrowserPassesGooglesPushValidatorToo() {
    val path = System.getenv("WFF_BROWSER_APK")
    org.junit.jupiter.api.Assumptions.assumeTrue(path != null) { "WFF_BROWSER_APK is not set" }
    val file = File(path!!)
    val result = verify(file, 34)
    assertTrue(result.isVerified && result.isVerifiedUsingV3Scheme, result.describe())
    val push = Validators.google().push.validate(file, "com.example.market")
    assertEquals(emptyList(), push.failures.map { it.toString() })
    assertTrue(!push.validationToken.isNullOrBlank(), "no validation token")
  }
}

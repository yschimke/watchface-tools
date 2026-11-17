package ee.schimke.watchface.packager

import com.android.apksig.ApkVerifier
import ee.schimke.watchface.packager.TestSupport.pushOptions
import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** End-to-end packaging with the real aapt2/zipalign/apksig. Skipped without an Android SDK. */
class PackagerTest {
  @TempDir lateinit var tmp: File

  private fun entries(apk: ByteArray): List<String> {
    val f = File(tmp, "e-${apk.contentHashCode()}.apk").also { it.writeBytes(apk) }
    return ZipFile(f).use { z -> z.entries().toList().map { it.name } }
  }

  private fun badging(apk: ByteArray): String {
    val sdk = TestSupport.requireSdk()
    val f = File(tmp, "b-${apk.contentHashCode()}.apk").also { it.writeBytes(apk) }
    val p =
      ProcessBuilder(sdk.aapt2.path, "dump", "badging", f.path).redirectErrorStream(true).start()
    return p.inputStream.bufferedReader().readText().also { p.waitFor() }
  }

  @Test
  fun everySampleBuildsAsAPushApkWithAnOfficialToken() {
    val sdk = TestSupport.requireSdk()
    val packager = WatchFacePackager(sdk, Validators.google())
    for (name in TestSupport.sampleNames) {
      val bundle = TestSupport.sampleBundle(name)
      val out =
        packager.pack(bundle, pushOptions(versionCode = 42, keyDir = tmp, face = name.lowercase()))
      val pkg = "com.example.market.watchfacepush.${name.lowercase()}"
      assertEquals(pkg, out.packageName, name)
      assertEquals(42, out.versionCode)
      assertTrue(!out.validationToken.isNullOrBlank(), "$name: official validator gave no token")
      assertNotNull(out.memoryFootprint, name)

      val names = entries(out.apk)
      assertTrue("AndroidManifest.xml" in names && "resources.arsc" in names, "$name $names")
      assertTrue("res/raw/watchface.xml" in names, "$name $names")
      assertEquals(emptyList(), PushRules.checkApkEntries(names), "$name $names")
      assertEquals(
        emptyList(),
        names.filterNot {
          it == "AndroidManifest.xml" ||
            it == "resources.arsc" ||
            it.startsWith("res/") ||
            it.startsWith("META-INF/")
        },
      )

      val badging = badging(out.apk)
      assertTrue("package: name='$pkg' versionCode='42'" in badging, "$name\n$badging")
      val minSdk = WffBundleLayout.minSdkFor(out.wffVersion)
      assertTrue(minSdk >= PushRules.MIN_SDK)
      assertTrue("\nminSdkVersion:'$minSdk'" in badging, badging)
      assertTrue(
        "property: name='${WffBundleLayout.WFF_VERSION_PROPERTY}' value='${out.wffVersion.number}'" in
          badging,
        badging,
      )
      assertTrue("uses-feature: name='android.hardware.type.watch'" in badging, badging)
      assertTrue("application-debuggable" !in badging)

      val verified =
        ApkVerifier.Builder(File(tmp, "e-${out.apk.contentHashCode()}.apk")).build().verify()
      assertTrue(verified.isVerified, "$name: ${verified.errors}")
      assertTrue(
        verified.isVerifiedUsingV2Scheme || verified.isVerifiedUsingV3Scheme,
        "$name v1=${verified.isVerifiedUsingV1Scheme} v2=${verified.isVerifiedUsingV2Scheme} v3=${verified.isVerifiedUsingV3Scheme} v31=${verified.isVerifiedUsingV31Scheme} warnings=${verified.warnings}",
      )
    }
  }

  @Test
  fun sideloadKeepsAPlainPackageAndGetsNoToken() {
    val sdk = TestSupport.requireSdk()
    val out =
      WatchFacePackager(sdk, Validators.google())
        .pack(
          TestSupport.sampleBundle("SimpleDigital"),
          PackagerOptions(PackageTarget.Sideload(), 3, SigningConfig.GeneratedDebugKey(tmp)),
        )
    assertEquals("ee.schimke.sample.simpledigital", out.packageName)
    assertNull(out.validationToken)
    assertTrue(
      out.diagnostics.any { it.source == "signing" && it.severity == DiagnosticSeverity.WARNING },
    )
  }

  @Test
  fun signsWithTheCallerSuppliedKeystore() {
    val sdk = TestSupport.requireSdk()
    val ks = File(tmp, "release.p12")
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
    val out =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .pack(
          TestSupport.sampleBundle("SimpleAnalog"),
          PackagerOptions(
            PackageTarget.Sideload(),
            1,
            SigningConfig.Keystore(ks, "secret1", "rel"),
          ),
        )
    assertTrue(out.diagnostics.none { it.source == "signing" })
    val apk = File(tmp, "rel.apk").also { it.writeBytes(out.apk) }
    val result = ApkVerifier.Builder(apk).build().verify()
    assertTrue(result.isVerified)
    val signerCert = result.signerCertificates.single()
    val keystore = java.security.KeyStore.getInstance(ks, "secret1".toCharArray())
    val expected = keystore.getCertificate("rel")
    assertEquals(fingerprint(expected.encoded), fingerprint(signerCert.encoded))
    assertTrue(
      !File(tmp, "debug.p12").exists(),
      "debug key must not be generated when a keystore is given",
    )
  }

  @Test
  fun debugKeyIsGeneratedOnceAndReused() {
    val sdk = TestSupport.requireSdk()
    val packager = WatchFacePackager(sdk, TestSupport.fakeValidators())
    fun cert(): String {
      val out = packager.pack(TestSupport.sampleBundle("SimpleAnalog"), pushOptions(keyDir = tmp))
      val apk = File(tmp, "d.apk").also { it.writeBytes(out.apk) }
      return fingerprint(
        ApkVerifier.Builder(apk).build().verify().signerCertificates.single().encoded,
      )
    }
    assertEquals(cert(), cert())
    assertTrue(File(tmp, "debug.p12").isFile)
  }

  @Test
  fun tokenComesFromTheValidatorAndIsNeverInventedOnFailure() {
    val sdk = TestSupport.requireSdk()
    val bundle = TestSupport.sampleBundle("SimpleAnalog")
    val ok =
      WatchFacePackager(sdk, TestSupport.fakeValidators("TOKEN-123"))
        .pack(bundle, pushOptions(keyDir = tmp))
    assertEquals("TOKEN-123", ok.validationToken)

    val failing =
      TestSupport.fakeValidators(
        failures =
          listOf(
            PackagingDiagnostic(DiagnosticSeverity.ERROR, "push-validator", "APK_SIGNATURE: nope"),
          ),
      )
    val e =
      assertFailsWith<PackagingException> {
        WatchFacePackager(sdk, failing).pack(bundle, pushOptions(keyDir = tmp))
      }
    assertTrue("APK_SIGNATURE: nope" in e.message!!)

    val noToken = TestSupport.fakeValidators(token = "")
    val e2 =
      assertFailsWith<PackagingException> {
        WatchFacePackager(sdk, noToken).pack(bundle, pushOptions(keyDir = tmp))
      }
    assertTrue("no token" in e2.message!!)
  }

  @Test
  fun officialValidatorRejectsAnApkOutsideTheMarketplacePackage() {
    val sdk = TestSupport.requireSdk()
    // Build a sideload APK (plain package), then ask the real push validator about it.
    val out =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .pack(
          TestSupport.sampleBundle("SimpleAnalog"),
          PackagerOptions(PackageTarget.Sideload(), 1, SigningConfig.GeneratedDebugKey(tmp)),
        )
    val apk = File(tmp, "plain.apk").also { it.writeBytes(out.apk) }
    val result = GooglePushValidator.validate(apk, "com.example.market")
    assertNull(result.validationToken)
    assertTrue(result.failures.isNotEmpty(), "plain package must fail the push validator")
  }

  private fun fingerprint(b: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}

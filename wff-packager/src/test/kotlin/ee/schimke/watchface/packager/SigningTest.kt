package ee.schimke.watchface.packager

import com.android.apksig.ApkVerifier
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** A shared marketplace key from a secret, and the certificate pin. */
class SigningTest {
  @TempDir lateinit var tmp: File

  private val password = "s3cret-Pa55"

  private fun keystore(name: String, alias: String = "market"): File {
    val ks = File(tmp, "$name.p12")
    run(
      "test",
      File(System.getProperty("java.home"), "bin/keytool").path,
      "-genkeypair",
      "-keystore",
      ks.path,
      "-storetype",
      "PKCS12",
      "-storepass",
      password,
      "-keypass",
      password,
      "-alias",
      alias,
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "30",
      "-dname",
      "CN=$name",
    )
    return ks
  }

  private fun env(ks: File, vararg extra: Pair<String, String>): Map<String, String> =
    mapOf(
      SigningConfig.ENV_KEYSTORE_B64 to Base64.getEncoder().encodeToString(ks.readBytes()),
      SigningConfig.ENV_KEYSTORE_PASSWORD to password,
    ) + extra

  private fun fileCert(ks: File): String =
    certificateSha256(loadSigningKey(SigningConfig.Keystore(ks, password)).chain.first())

  @Test
  fun fromEnvironmentIsNullWhenUnset() {
    assertNull(SigningConfig.fromEnvironment(emptyMap()))
    assertNull(SigningConfig.fromEnvironment(mapOf(SigningConfig.ENV_KEYSTORE_B64 to "  ")))
  }

  @Test
  fun envKeystoreLoadsTheSameKeyAsTheFile() {
    val ks = keystore("market")
    val config = SigningConfig.fromEnvironment(env(ks))!!
    val key = loadSigningKey(config)
    assertFalse(key.debug)
    assertEquals(fileCert(ks), certificateSha256(key.chain.first()))
  }

  @Test
  fun mimeWrappedBase64IsAccepted() {
    val ks = keystore("market")
    val wrapped = Base64.getMimeEncoder().encodeToString(ks.readBytes())
    assertTrue('\n' in wrapped || '\r' in wrapped)
    val config = SigningConfig.fromEnvironment(env(ks, SigningConfig.ENV_KEYSTORE_B64 to wrapped))!!
    assertEquals(fileCert(ks), certificateSha256(loadSigningKey(config).chain.first()))
  }

  @Test
  fun errorsNeverContainSecrets() {
    val ks = keystore("market")
    val b64 = Base64.getEncoder().encodeToString(ks.readBytes())
    val missing =
      assertFailsWith<IllegalArgumentException> {
        SigningConfig.fromEnvironment(mapOf(SigningConfig.ENV_KEYSTORE_B64 to b64))
      }
    assertFalse(b64 in missing.message!!)

    val garbage =
      assertFailsWith<IllegalArgumentException> {
        SigningConfig.fromEnvironment(env(ks, SigningConfig.ENV_KEYSTORE_B64 to "not*base64!"))
      }
    assertFalse("not*base64!" in garbage.message!!)

    val wrongPassword =
      assertFailsWith<PackagingException> {
        loadSigningKey(
          SigningConfig.fromEnvironment(env(ks, SigningConfig.ENV_KEYSTORE_PASSWORD to "nope"))!!,
        )
      }
    assertEquals("signing", wrongPassword.diagnostics.single().source)

    val config = SigningConfig.fromEnvironment(env(ks))!!
    assertFalse(password in config.toString())
    assertFalse(password in SigningConfig.Keystore(ks, password).toString())
  }

  @Test
  fun pinAcceptsTheRightCertificateInAnyNotation() {
    val ks = keystore("market")
    val key = loadSigningKey(SigningConfig.fromEnvironment(env(ks))!!)
    val hex = certificateSha256(key.chain.first())
    checkPinnedCertificate(key, hex)
    checkPinnedCertificate(key, hex.uppercase().chunked(2).joinToString(":"))
    checkPinnedCertificate(key, null)
  }

  @Test
  fun pinRefusesAnotherKey() {
    val market = keystore("market")
    val other = keystore("other")
    val key = loadSigningKey(SigningConfig.fromEnvironment(env(other))!!)
    val e = assertFailsWith<PackagingException> { checkPinnedCertificate(key, fileCert(market)) }
    assertEquals("signing", e.diagnostics.single().source)
    assertTrue("does not match" in e.message!!)
    assertTrue(fileCert(market) in e.message!!)

    val bad = assertFailsWith<PackagingException> { checkPinnedCertificate(key, "abc") }
    assertTrue("64 hex digits" in bad.message!!)
  }

  @Test
  fun packSignsWithTheSharedKeyAndReportsItsFingerprint() {
    val sdk = TestSupport.requireSdk()
    val ks = keystore("market")
    val pin = fileCert(ks)
    val packager = WatchFacePackager(sdk, TestSupport.fakeValidators())
    fun pack(face: String, keystoreFile: File) =
      packager.pack(
        TestSupport.sampleBundle("SimpleAnalog"),
        PackagerOptions(
          PackageTarget.WatchFacePush("ee.schimke.watchface.push", face),
          1,
          SigningConfig.fromEnvironment(env(keystoreFile))!!,
          expectedCertificateSha256 = pin,
        ),
      )

    // Two different faces, one marketplace key.
    for (face in listOf("alpha", "beta")) {
      val out = pack(face, ks)
      assertEquals(pin, out.signerCertificateSha256)
      assertTrue(out.diagnostics.none { it.source == "signing" }, "no debug-key warning")
      val apk = File(tmp, "$face.apk").also { it.writeBytes(out.apk) }
      val verified = ApkVerifier.Builder(apk).build().verify()
      assertTrue(verified.isVerified)
      assertEquals(pin, certificateSha256(verified.signerCertificates.single()))
    }

    assertFailsWith<PackagingException> { pack("gamma", keystore("other")) }
  }
}

package ee.schimke.watchface.pushserver

import ee.schimke.watchface.packager.SigningConfig
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Marketplace signing key configuration loaded once at startup from environment variables.
 *
 * Supports the same variables as `wff-packager` plus `WFF_SIGNING_KEYSTORE_PATH` and Docker-secret
 * `*_FILE` variants so an operator can mount secrets as files or pass them directly:
 * - `WFF_SIGNING_KEYSTORE_PATH`: path to a PKCS12 keystore file, OR
 * - `WFF_SIGNING_KEYSTORE_B64` (or `*_FILE`): base64-encoded PKCS12 keystore
 * - `WFF_SIGNING_KEYSTORE_PASSWORD` (or `*_FILE`): store password (required when keystore is set)
 * - `WFF_SIGNING_KEY_ALIAS` (or `*_FILE`): optional key alias (defaults to the only key entry)
 * - `WFF_SIGNING_KEY_PASSWORD` (or `*_FILE`): optional key password (defaults to store password)
 * - `WFF_SIGNING_CERT_SHA256` (or `*_FILE`): optional pinned certificate SHA-256 hex fingerprint
 */
sealed interface PushServerSigning {
  class Configured(
    val signingConfig: SigningConfig.InMemoryKeystore,
    val expectedCertificateSha256: String?,
    val certificateSha256: String,
  ) : PushServerSigning

  /** No signing key was configured; debug builds and validation still work. */
  data class Unconfigured(val reason: String) : PushServerSigning

  /** A signing key was configured, but could not be opened or failed its certificate pin. */
  data class Invalid(val reason: String) : PushServerSigning

  companion object {
    const val ENV_KEYSTORE_PATH = "WFF_SIGNING_KEYSTORE_PATH"
    const val ENV_KEYSTORE_B64 = SigningConfig.ENV_KEYSTORE_B64
    const val ENV_KEYSTORE_PASSWORD = SigningConfig.ENV_KEYSTORE_PASSWORD
    const val ENV_KEY_ALIAS = SigningConfig.ENV_KEY_ALIAS
    const val ENV_KEY_PASSWORD = SigningConfig.ENV_KEY_PASSWORD
    const val ENV_CERT_SHA256 = SigningConfig.ENV_CERT_SHA256

    fun fromEnvironment(env: Map<String, String> = System.getenv()): PushServerSigning {
      val path = readEnv(ENV_KEYSTORE_PATH, env)?.trim()?.takeIf { it.isNotEmpty() }
      val b64 = readSecret(ENV_KEYSTORE_B64, env)?.trim()?.takeIf { it.isNotEmpty() }
      if (path == null && b64 == null) {
        return Unconfigured(
          "no marketplace signing key configured: set $ENV_KEYSTORE_PATH or $ENV_KEYSTORE_B64, " +
            "and $ENV_KEYSTORE_PASSWORD",
        )
      }
      val storePassword =
        readSecret(ENV_KEYSTORE_PASSWORD, env)
          ?: return Invalid("signing keystore is set but $ENV_KEYSTORE_PASSWORD is not")
      val keystoreBytes =
        if (path != null) {
          val file = File(path)
          if (!file.isFile) {
            return Invalid("$ENV_KEYSTORE_PATH does not point to a readable file: $path")
          }
          try {
            file.readBytes()
          } catch (e: Exception) {
            return Invalid("cannot read $ENV_KEYSTORE_PATH: ${e.message}")
          }
        } else {
          try {
            Base64.getMimeDecoder().decode(b64!!)
          } catch (_: IllegalArgumentException) {
            return Invalid("$ENV_KEYSTORE_B64 is not valid base64")
          }
        }
      if (keystoreBytes.isEmpty()) {
        return Invalid("signing keystore is empty")
      }
      val keyAlias = readSecret(ENV_KEY_ALIAS, env)?.trim()?.takeIf { it.isNotEmpty() }
      val keyPassword =
        readSecret(ENV_KEY_PASSWORD, env)?.takeIf { it.isNotEmpty() } ?: storePassword
      val expectedCert = readSecret(ENV_CERT_SHA256, env)?.trim()?.takeIf { it.isNotEmpty() }

      val certSha256 =
        try {
          val ks = KeyStore.getInstance("PKCS12")
          ks.load(keystoreBytes.inputStream(), storePassword.toCharArray())
          val alias =
            keyAlias
              ?: ks.aliases().toList().singleOrNull { ks.isKeyEntry(it) }
              ?: return Invalid(
                "signing keystore must have one key entry, or set $ENV_KEY_ALIAS",
              )
          if (!ks.isKeyEntry(alias)) {
            return Invalid("key alias '$alias' not found in signing keystore")
          }
          // Verify the key password opens the private key at startup rather than on first request.
          ks.getKey(alias, keyPassword.toCharArray())
            ?: return Invalid("key alias '$alias' has no private key")
          val cert =
            ks.getCertificate(alias) as? X509Certificate
              ?: return Invalid("key alias '$alias' has no X.509 certificate")
          sha256Hex(cert.encoded)
        } catch (e: Exception) {
          return Invalid("cannot open signing keystore: ${e.message}")
        }

      if (expectedCert != null) {
        val normalized = normalizeSha256(expectedCert)
        if (normalized == null) {
          return Invalid("$ENV_CERT_SHA256 must be 64 hex digits (colons allowed)")
        }
        if (normalized != certSha256) {
          return Invalid(
            "signing certificate SHA-256 $certSha256 does not match pinned $normalized",
          )
        }
      }

      return Configured(
        signingConfig =
          SigningConfig.InMemoryKeystore(
            bytes = keystoreBytes,
            storePassword = storePassword,
            keyAlias = keyAlias,
            keyPassword = keyPassword,
          ),
        expectedCertificateSha256 = expectedCert,
        certificateSha256 = certSha256,
      )
    }

    private fun readEnv(name: String, env: Map<String, String>): String? = env[name]

    /**
     * Reads `name` from [env], or falls back to `<name>_FILE` (trimming trailing newlines, the
     * convention for Docker secrets).
     */
    private fun readSecret(name: String, env: Map<String, String>): String? {
      env[name]
        ?.takeIf { it.isNotEmpty() }
        ?.let {
          return it
        }
      val filePath = env["${name}_FILE"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
      val file = File(filePath)
      if (!file.isFile) return null
      return file.readText().trimEnd('\r', '\n')
    }

    private fun sha256Hex(bytes: ByteArray): String =
      MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun normalizeSha256(value: String): String? =
      value
        .filterNot { it == ':' || it.isWhitespace() }
        .lowercase()
        .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
  }
}

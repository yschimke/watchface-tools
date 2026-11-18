package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffVersion
import java.io.File

/** Where the signing key comes from. */
public sealed interface SigningConfig {
  /** A caller-supplied keystore (PKCS12 or JKS). */
  public class Keystore(
    public val file: File,
    public val storePassword: String,
    public val keyAlias: String? = null,
    public val keyPassword: String = storePassword,
  ) : SigningConfig {
    override fun toString(): String = "Keystore(file=$file, keyAlias=$keyAlias)"
  }

  /**
   * A keystore held in memory, typically decoded from a secret (see [fromEnvironment]), so a server
   * can sign every APK with one long-lived key without writing it to disk.
   */
  public class InMemoryKeystore(
    bytes: ByteArray,
    public val storePassword: String,
    public val keyAlias: String? = null,
    public val keyPassword: String = storePassword,
    /** `PKCS12` (the default, what `keytool` writes) or `JKS`. */
    public val storeType: String = "PKCS12",
  ) : SigningConfig {
    private val data = bytes.copyOf()

    public val bytes: ByteArray
      get() = data.copyOf()

    /** Never prints the key material or passwords. */
    override fun toString(): String =
      "InMemoryKeystore(type=$storeType, keyAlias=$keyAlias, ${data.size} bytes)"
  }

  /**
   * A debug key generated on first use under [directory] (default `$WFF_PACKAGER_HOME` or
   * `~/.config/wff-packager`). It is per installation, never part of the repository, and APKs
   * signed with it can't be updated by APKs signed with another key.
   */
  public class GeneratedDebugKey(public val directory: File = defaultDebugKeyDirectory()) :
    SigningConfig

  public companion object {
    /** Base64 of the keystore file (`base64 -w0 release.p12`). */
    public const val ENV_KEYSTORE_B64: String = "WFF_SIGNING_KEYSTORE_B64"
    public const val ENV_KEYSTORE_PASSWORD: String = "WFF_SIGNING_KEYSTORE_PASSWORD"
    /** Optional; defaults to the keystore's only key entry. */
    public const val ENV_KEY_ALIAS: String = "WFF_SIGNING_KEY_ALIAS"
    /** Optional; defaults to the store password. */
    public const val ENV_KEY_PASSWORD: String = "WFF_SIGNING_KEY_PASSWORD"
    /** Optional pin for [PackagerOptions.expectedCertificateSha256]. */
    public const val ENV_CERT_SHA256: String = "WFF_SIGNING_CERT_SHA256"

    /**
     * The signing key from environment variables (or any map with the same names), or null when
     * [ENV_KEYSTORE_B64] is unset or blank. Throws [IllegalArgumentException] if the keystore is
     * set but the password is missing or the base64 is malformed; the message never contains a
     * secret value.
     */
    public fun fromEnvironment(env: Map<String, String> = System.getenv()): InMemoryKeystore? {
      val b64 = env[ENV_KEYSTORE_B64]?.takeIf(String::isNotBlank) ?: return null
      val password =
        env[ENV_KEYSTORE_PASSWORD]
          ?: throw IllegalArgumentException(
            "$ENV_KEYSTORE_B64 is set but $ENV_KEYSTORE_PASSWORD is not",
          )
      val bytes =
        try {
          java.util.Base64.getMimeDecoder().decode(b64.trim())
        } catch (_: IllegalArgumentException) {
          throw IllegalArgumentException("$ENV_KEYSTORE_B64 is not valid base64")
        }
      require(bytes.isNotEmpty()) { "$ENV_KEYSTORE_B64 decodes to nothing" }
      return InMemoryKeystore(
        bytes,
        password,
        env[ENV_KEY_ALIAS]?.takeIf(String::isNotBlank),
        env[ENV_KEY_PASSWORD]?.takeIf(String::isNotEmpty) ?: password,
      )
    }

    /** The pinned certificate fingerprint from [ENV_CERT_SHA256], or null. */
    public fun expectedCertificateFromEnvironment(
      env: Map<String, String> = System.getenv(),
    ): String? = env[ENV_CERT_SHA256]?.takeIf(String::isNotBlank)?.trim()

    public fun defaultDebugKeyDirectory(): File =
      System.getenv("WFF_PACKAGER_HOME")?.takeIf(String::isNotBlank)?.let(::File)
        ?: File(System.getProperty("user.home"), ".config/wff-packager")
  }
}

public class PackagerOptions(
  public val target: PackageTarget,
  /** Caller-managed, must increase with every published version of the same face. */
  public val versionCode: Int,
  public val signing: SigningConfig = SigningConfig.GeneratedDebugKey(),
  /** If set, [versionCode] must be strictly greater than this (the last published code). */
  public val previousVersionCode: Int? = null,
  /** Override the `versionName`; null keeps the bundle manifest's. */
  public val versionName: String? = null,
  /**
   * SHA-256 of the signing certificate the APK must be signed with (hex, colons and case ignored).
   * Packaging fails before signing if the key's certificate differs, so a configuration slip can't
   * ship an APK that won't update the installed one. Null skips the check.
   */
  public val expectedCertificateSha256: String? = null,
)

public class MemoryFootprintReport(
  public val totalBytes: Long,
  public val maxActiveBytes: Long,
  public val maxAmbientBytes: Long,
) {
  override fun toString(): String =
    "MemoryFootprint(total=$totalBytes, active=$maxActiveBytes, ambient=$maxAmbientBytes)"
}

public class PackagedWatchFace(
  public val apk: ByteArray,
  public val packageName: String,
  public val versionCode: Int,
  public val wffVersion: WffVersion,
  public val memoryFootprint: MemoryFootprintReport?,
  /** Only ever set from Google's official Watch Face Push validator. */
  public val validationToken: String?,
  public val diagnostics: List<PackagingDiagnostic>,
  /** Lower-case hex SHA-256 of the signing certificate, as `apksigner` and Play report it. */
  public val signerCertificateSha256: String,
)

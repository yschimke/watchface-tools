package ee.schimke.watchface.apk

/** The signature schemes the APK signing block accepts here: SHA-256 with ECDSA P-256 or RSA. */
public enum class SignatureAlgorithm(
  /** The signature algorithm id the APK Signature Scheme puts on the wire. */
  public val id: Int,
) {
  /** `RSASSA-PKCS1-v1_5` with SHA-256 (any modulus size the platform accepts, 2048 and up). */
  RSA_PKCS1_SHA256(0x0103),
  /** ECDSA over P-256 with SHA-256; the signature is DER (`SEQUENCE { r, s }`). */
  ECDSA_SHA256(0x0201),
}

/**
 * The one platform seam in APK packaging: something that holds a private key and signs with it.
 * Everything else, including hashing, is pure Kotlin. A JVM signer wraps `java.security`
 * ([JcaApkSigner]); a browser signer wraps WebCrypto, which is asynchronous, so [sign] suspends.
 *
 * An implementation is responsible for [certificates] describing the key that [sign] uses: the
 * packager takes the public key from the leaf certificate and cannot check the pair.
 */
public interface ApkSigner {
  public val algorithm: SignatureAlgorithm

  /** The signing certificate chain, DER-encoded X.509, signer's own certificate first. */
  public val certificates: List<ByteArray>

  /**
   * Signs [data] with SHA-256 and returns the signature as [algorithm] defines it (DER for ECDSA,
   * the raw PKCS#1 v1.5 value for RSA).
   */
  public suspend fun sign(data: ByteArray): ByteArray
}

/** Lower-case hex SHA-256 of the signing certificate, as `apksigner` and Play print it. */
public val ApkSigner.certificateSha256: String
  get() = Sha256.digest(certificates.first()).toHex()

/** A signer for a key held by [sign], with its certificate chain. */
internal class FunctionApkSigner(
  override val algorithm: SignatureAlgorithm,
  override val certificates: List<ByteArray>,
  private val signFunction: suspend (ByteArray) -> ByteArray,
) : ApkSigner {
  init {
    require(certificates.isNotEmpty()) { "a signer needs at least its own certificate" }
  }

  override suspend fun sign(data: ByteArray): ByteArray = signFunction(data)
}

/** A freshly generated ECDSA P-256 key pair: its `SubjectPublicKeyInfo` and a DER signer. */
internal class GeneratedKey(val spki: ByteArray, val sign: suspend (ByteArray) -> ByteArray)

internal expect suspend fun generateEcdsaP256(): GeneratedKey

/**
 * A signer for a newly generated ECDSA P-256 key behind a self-signed certificate, for sideloading
 * and previews. The key lives only as long as the returned signer, so every call yields a different
 * signing identity: an APK signed with one cannot be updated by an APK signed with another. Never
 * use it for anything published; pass a signer for a long-lived key instead.
 */
public suspend fun generateDebugApkSigner(commonName: String = "wff-apk debug key"): ApkSigner {
  val key = generateEcdsaP256()
  val certificate =
    SelfSignedCertificate.create(key.spki, SignatureAlgorithm.ECDSA_SHA256, commonName, key.sign)
  return FunctionApkSigner(SignatureAlgorithm.ECDSA_SHA256, listOf(certificate), key.sign)
}

/** A minimal X.509 v3 self-signed certificate, the kind Android accepts as an APK signer. */
internal object SelfSignedCertificate {
  suspend fun create(
    spki: ByteArray,
    algorithm: SignatureAlgorithm,
    commonName: String,
    sign: suspend (ByteArray) -> ByteArray,
  ): ByteArray {
    val sigAlg =
      when (algorithm) {
        SignatureAlgorithm.ECDSA_SHA256 -> Der.sequence(Der.oid("1.2.840.10045.4.3.2"))
        SignatureAlgorithm.RSA_PKCS1_SHA256 ->
          Der.sequence(Der.oid("1.2.840.113549.1.1.11"), Der.nullValue())
      }
    val name = Der.sequence(Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8(commonName))))
    // Android does not check validity when installing; keep it long and inside UTCTime's range.
    val validity = Der.sequence(Der.utcTime("200101000000Z"), Der.utcTime("491231235959Z"))
    // A serial that follows the key, so two different keys never share an issuer/serial pair.
    val serial =
      Sha256.digest(spki).copyOfRange(0, 8).also { it[0] = (it[0].toInt() and 0x7f).toByte() }
    val tbs =
      Der.sequence(
        Der.tlv(0xa0, Der.integer(2)),
        Der.integer(serial),
        sigAlg,
        name,
        validity,
        name,
        spki,
      )
    return Der.sequence(tbs, sigAlg, Der.bitString(sign(tbs)))
  }
}
